package app.selah.desktop

import io.ktor.server.response.respondRedirect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import app.selah.shared.cleanListName
import app.selah.shared.forOldRemotes
import app.selah.shared.requiredPermission

import app.selah.shared.ClientMessage
import app.selah.shared.DEFAULT_PORT
import app.selah.shared.ServerMessage
import app.selah.shared.WS_PATH
import app.selah.shared.decodeClientMessage
import app.selah.shared.encode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticResources
import io.ktor.server.routing.routing
import app.selah.shared.ProtocolJson
import app.selah.shared.DiscoveryReply
import io.ktor.http.ContentType
import io.ktor.server.response.respondText
import io.ktor.server.response.respondFile
import io.ktor.server.routing.get
import io.ktor.utils.io.readAvailable
import java.io.File
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveChannel
import io.ktor.server.routing.post
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.close
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.time.Duration.Companion.seconds

/** Servidor WebSocket para los controles remotos (celulares y tablets en la misma red). */
class RemoteServer(
    private val commands: Commands,
    private val library: Library,
    val port: Int = DEFAULT_PORT,
    /** Clave para controlar (null = no se pide). */
    private val pin: kotlinx.coroutines.flow.StateFlow<String?> = kotlinx.coroutines.flow.MutableStateFlow(null),
    /** "¿Qué usa tu iglesia?" (las pruebas: todo encendido). */
    private val church: kotlinx.coroutines.flow.StateFlow<ChurchFeatures> = kotlinx.coroutines.flow.MutableStateFlow(ChurchFeatures.ALL),
    /** Partituras y secuencias (null = no hay). */
    private val music: MusicLibrary? = null,
    /** Celulares aprobados y su papel (null = sin papeles: la clave controla todo, como antes). */
    private val team: TeamStore? = null,
) {
    /** Papel de quien llama por HTTP: con la clave, operador; con el código de un celular aprobado, su papel. */
    private fun httpRole(pinHeader: String?, deviceHeader: String?): app.selah.shared.Role = when {
        !guard.required -> app.selah.shared.Role.OPERATOR
        pinHeader != null && pinHeader == pin.value -> app.selah.shared.Role.OPERATOR
        else -> deviceHeader?.takeIf(TeamStore::validId)?.let { team?.member(TeamStore.hash(it))?.role } ?: app.selah.shared.Role.VIEWER
    }
    private fun io.ktor.server.application.ApplicationCall.role() = httpRole(request.headers["X-Proyecta-Pin"], request.headers[DEVICE_HEADER])
    private val musicCatalog: kotlinx.coroutines.flow.Flow<MusicCatalog> = music?.catalog ?: kotlinx.coroutines.flow.flowOf(MusicCatalog())
    private val guard = PinGuard(pin)
    private val presenter get() = commands.presenter
    private val historyEntries: kotlinx.coroutines.flow.Flow<Any?> = commands.history?.entries ?: kotlinx.coroutines.flow.flowOf(null)
    private val servicesConfig: kotlinx.coroutines.flow.Flow<app.selah.shared.ServicesConfig> =
        commands.services?.config ?: kotlinx.coroutines.flow.flowOf(app.selah.shared.ServicesConfig())

    /** Celulares de la congregación conectados ahora a la página de la letra. */
    private val viewers = java.util.concurrent.atomic.AtomicInteger(0)
    val congregationViewers: Int get() = viewers.get()
    /** Nombre de red del equipo: sirve para revisar el encabezado Host (no es el que se muestra). */
    private val pcName: String = runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrDefault("Selah")
    /** Nombre que se muestra en los celulares ("Conectado a …"). */
    private val displayName: String = computerName()
    private var engine: EmbeddedServer<*, *>? = null

    fun start() {
        engine = embeddedServer(CIO, port = port, host = "0.0.0.0") { module() }.start(wait = false)
    }

    fun stop() {
        engine?.stop(gracePeriodMillis = 200, timeoutMillis = 1000)
        engine = null
    }

    private fun Application.module() {
        install(WebSockets) {
            pingPeriod = 15.seconds
            // Nadie manda mensajes grandes: uno gigante solo serviría para llenar la memoria.
            maxFrameSize = NetGuard.MAX_FRAME_BYTES
            timeout = 30.seconds
        }
        routing {
            // Nombre del PC, para que la página muestre "Conectado a <nombre>".
            get("/info") {
                call.respondText(
                    ProtocolJson.encodeToString(DiscoveryReply.serializer(), DiscoveryReply(displayName, port)),
                    ContentType.Application.Json,
                )
            }
            // Imagen de una diapositiva de presentación (ya convertida), para la vista de escenario y el
            // control web: GET /diapositiva?nombre=x.pdf&n=0[&mini=1]. Solo lectura y solo de esa carpeta.
            get("/diapositiva") {
                val name = call.request.queryParameters["nombre"].orEmpty()
                val n = call.request.queryParameters["n"]?.toIntOrNull()
                val mini = call.request.queryParameters["mini"] == "1"
                val file = n?.takeIf { library.presentations.file(name) != null }?.let {
                    if (mini) library.presentations.thumb(name, it) else library.presentations.page(name, it)
                }
                if (file == null) call.respondText("No encontrada", status = HttpStatusCode.NotFound)
                else call.respondFile(file)
            }
            // Partituras (solo lectura): GET /partitura/paginas?nombre=x.pdf → {"paginas":3}; GET /partitura?nombre=x.pdf&n=0 → imagen.
            get("/partitura/paginas") {
                val name = call.request.queryParameters["nombre"].orEmpty()
                val pages = music?.takeIf { church.value.scores }?.scorePages
                if (team != null && !call.role().can(app.selah.shared.Permission.SCORES)) { call.respondText("Las partituras son para los músicos del equipo", status = HttpStatusCode.Forbidden); return@get }
                if (pages?.file(name) == null) { call.respondText("No encontrada", status = HttpStatusCode.NotFound); return@get }
                val count = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { pages.pages(name).size } }
                count.onSuccess { call.respondText("{\"paginas\":$it}", ContentType.Application.Json) }
                    .onFailure { call.respondText(it.message ?: "No pude abrir la partitura", status = HttpStatusCode.UnprocessableEntity) }
            }
            get("/partitura") {
                val name = call.request.queryParameters["nombre"].orEmpty()
                val n = call.request.queryParameters["n"]?.toIntOrNull()
                val pages = music?.takeIf { church.value.scores }?.scorePages
                if (team != null && !call.role().can(app.selah.shared.Permission.SCORES)) { call.respondText("Las partituras son para los músicos del equipo", status = HttpStatusCode.Forbidden); return@get }
                // pages() espera si la partitura se está convirtiendo en este momento.
                val file = if (pages != null && n != null && pages.file(name) != null)
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { pages.pages(name).getOrNull(n) }.getOrNull() }
                else null
                if (file == null) call.respondText("No encontrada", status = HttpStatusCode.NotFound) else call.respondFile(file)
            }
            // Subir un anuncio (foto o video) desde el celular: POST /anuncios?nombre=foto.jpg con la clave en X-Proyecta-Pin.
            post("/anuncios") {
                val address = call.request.local.remoteAddress
                if (!NetGuard.trusted(call.request.headers["Host"], call.request.headers["Origin"], pcName)) {
                    call.respondText("No permitido", status = HttpStatusCode.Forbidden)
                    return@post
                }
                val byDevice = team != null && call.request.headers[DEVICE_HEADER] != null && call.role().can(app.selah.shared.Permission.UPLOAD)
                if (guard.required && !byDevice) {
                    val attempt = call.request.headers["X-Proyecta-Pin"].orEmpty()
                    if (guard.check(address, attempt) != PinGuard.Result.Ok) {
                        call.respondText("Falta la clave o es incorrecta", status = HttpStatusCode.Unauthorized)
                        return@post
                    }
                }
                val name = uploadName(call.request.queryParameters["nombre"].orEmpty(), System.currentTimeMillis())
                if (name == null) {
                    call.respondText("Solo imágenes o videos", status = HttpStatusCode.BadRequest)
                    return@post
                }
                val declared = call.request.headers["Content-Length"]?.toLongOrNull()
                if (declared != null && declared > MAX_UPLOAD) {
                    call.respondText("El archivo es muy grande (máximo ${MAX_UPLOAD / 1_000_000} MB)", status = HttpStatusCode.PayloadTooLarge)
                    return@post
                }
                // Pocas subidas a la vez: cada una ocupa espacio temporal en el disco hasta terminar.
                if (!uploadSlots.tryAcquire()) {
                    call.respondText("Hay otras subidas en curso. Intenta en un momento.", status = HttpStatusCode.ServiceUnavailable)
                    return@post
                }
                val tmpDir = kotlin.io.path.createTempDirectory("selah-subida").toFile()
                try {
                    val tmp = File(tmpDir, name)
                    val channel = call.receiveChannel()
                    var total = 0L
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(1 shl 16)
                        while (true) {
                            val n = channel.readAvailable(buf, 0, buf.size)
                            if (n < 0) break
                            total += n
                            if (total > MAX_UPLOAD) {
                                call.respondText("El archivo es muy grande", status = HttpStatusCode.PayloadTooLarge)
                                return@post
                            }
                            out.write(buf, 0, n)
                        }
                    }
                    if (total == 0L) { call.respondText("Llegó vacío", status = HttpStatusCode.BadRequest); return@post }
                    // Se cortó a medias (se cayó el Wi-Fi): no guardar una foto o video incompleto.
                    if (declared != null && total != declared) {
                        call.respondText("La subida se cortó. Intenta otra vez.", status = HttpStatusCode.BadRequest)
                        return@post
                    }
                    // Revisar el espacio y copiar de una vez (dos subidas al mismo tiempo no se pasan del límite).
                    val saved = try {
                        library.addAnnouncement(tmp, MAX_ANNOUNCEMENTS_BYTES)
                    } catch (_: IllegalStateException) {
                        call.respondText("La carpeta de anuncios está llena (máximo ${MAX_ANNOUNCEMENTS_BYTES / 1_000_000_000} GB). Borra algunos en el computador.", status = HttpStatusCode.InsufficientStorage)
                        return@post
                    } ?: name // ya estaba (mismo contenido)
                    Log.info("Anuncio subido desde $address: $saved (${total / 1000} KB)")
                    call.respondText(ProtocolJson.encodeToString(kotlinx.serialization.json.JsonObject.serializer(),
                        kotlinx.serialization.json.buildJsonObject { put("ok", kotlinx.serialization.json.JsonPrimitive(true)); put("nombre", kotlinx.serialization.json.JsonPrimitive(saved)) }),
                        ContentType.Application.Json)
                } finally {
                    tmpDir.deleteRecursively()
                    uploadSlots.release()
                }
            }
            // Páginas web: control remoto (/), vista de escenario y letras para OBS.
            staticResources("/", "web", index = "index.html")
            // Letra para la congregación: dirección corta para el código QR.
            get(Congregation.PATH) {
                if (church.value.congregation) call.respondRedirect("${Congregation.PATH}.html")
                else call.respondText("La letra en el celular está apagada en este computador", status = HttpStatusCode.NotFound)
            }
            // Canal solo de lectura para esa página: recibe lo que se ve en el videobeam y nada más
            // (ni mensajes al escenario, ni tiempos, ni la biblioteca). Lo que mande el celular se ignora.
            webSocket(Congregation.WS_PATH) {
                val address = call.request.local.remoteAddress
                if (!NetGuard.trusted(call.request.headers["Host"], call.request.headers["Origin"], pcName)) {
                    close(io.ktor.websocket.CloseReason(io.ktor.websocket.CloseReason.Codes.VIOLATED_POLICY, "Origen no permitido"))
                    return@webSocket
                }
                if (!church.value.congregation) {
                    close(io.ktor.websocket.CloseReason(io.ktor.websocket.CloseReason.Codes.NORMAL, "Apagado"))
                    return@webSocket
                }
                if (viewers.incrementAndGet() > Congregation.MAX_VIEWERS || !NetGuard.acquire(address)) {
                    viewers.decrementAndGet()
                    close(io.ktor.websocket.CloseReason(io.ktor.websocket.CloseReason.Codes.TRY_AGAIN_LATER, "Demasiadas conexiones"))
                    return@webSocket
                }
                try {
                    val push = launch {
                        presenter.state
                            .map { Congregation.view(it, 0) }.distinctUntilChanged()
                            .collect { v ->
                                val withTime = kotlinx.serialization.json.JsonObject(v + ("serverNow" to kotlinx.serialization.json.JsonPrimitive(System.currentTimeMillis())))
                                send(Frame.Text(withTime.toString()))
                            }
                    }
                    try { for (frame in incoming) { /* solo lectura */ } } finally { push.cancel() }
                } finally {
                    viewers.decrementAndGet()
                    NetGuard.release(address)
                }
            }

            webSocket(WS_PATH) {
                val address = call.request.local.remoteAddress
                // Solo nuestras páginas y la app (no sitios ajenos ni DNS rebinding), y pocas conexiones por dispositivo.
                if (!NetGuard.trusted(call.request.headers["Host"], call.request.headers["Origin"], pcName)) {
                    Log.warn("Conexión rechazada (origen ajeno) desde $address: Host=${call.request.headers["Host"]} Origin=${call.request.headers["Origin"]}")
                    close(io.ktor.websocket.CloseReason(io.ktor.websocket.CloseReason.Codes.VIOLATED_POLICY, "Origen no permitido"))
                    return@webSocket
                }
                if (!NetGuard.acquire(address)) {
                    close(io.ktor.websocket.CloseReason(io.ktor.websocket.CloseReason.Codes.TRY_AGAIN_LATER, "Demasiadas conexiones"))
                    return@webSocket
                }
                val limiter = NetGuard.RateLimiter()
                Log.info("Remoto conectado desde $address")
                // Clave con la que esta conexión se autorizó (si la clave cambia, deja de valer).
                var granted: String? = null
                // Este celular (SHA-256 de su código) y su papel ahora.
                var deviceHash: String? = null
                val roleFlow = kotlinx.coroutines.flow.MutableStateFlow(app.selah.shared.Role.VIEWER)
                fun currentRole(): app.selah.shared.Role = when {
                    guard.allows(granted) -> app.selah.shared.Role.OPERATOR // con la clave (o sin clave en el PC)
                    else -> team?.member(deviceHash)?.role ?: app.selah.shared.Role.VIEWER
                }
                // ¿Este remoto entiende State.keepSlides? (lo dice en Hello). Y qué diapositivas tiene ya.
                val lean = java.util.concurrent.atomic.AtomicBoolean(false)
                // ¿Entiende las presentaciones en la lista? (las apps anteriores fallarían con un tipo nuevo)
                val knowsPresentations = kotlinx.coroutines.flow.MutableStateFlow(false)
                var sentSlides: List<app.selah.shared.Slide>? = null
                fun auth(message: String? = null): ServerMessage.Auth {
                    val r = currentRole()
                    roleFlow.value = r
                    val m = team?.member(deviceHash)
                    val waiting = deviceHash != null && m == null && team?.pending?.value?.any { it.idHash == deviceHash } == true
                    return ServerMessage.Auth(guard.required, r == app.selah.shared.Role.OPERATOR, message, role = team?.let { r }, instrument = m?.instrument, pending = waiting)
                }
                // Envía biblioteca y estado al conectarse, y luego cada vez que cambien.
                val pushJob = launch {
                    // La clave, el equipo o los que esperan cambiaron: el papel de este celular puede ser otro.
                    launch {
                        // Sin filtrar repetidos: el último aviso pudo mandarse por fuera (al dar la clave) y este puede ser distinto.
                        kotlinx.coroutines.flow.combine(pin, team?.team ?: kotlinx.coroutines.flow.flowOf(null), team?.pending ?: kotlinx.coroutines.flow.flowOf(null)) { _, _, _ -> auth() }
                            .collect { send(Frame.Text(it.encode())) }
                    }
                    launch {
                        // Con la fecha en que se cantó cada canción (historial) y el aviso de repetidas (cultos).
                        val musicAndRole = kotlinx.coroutines.flow.combine(church, musicCatalog, roleFlow) { ch, mus, r -> Triple(ch, mus, r) }
                        kotlinx.coroutines.flow.combine(library.catalog, historyEntries, servicesConfig, musicAndRole) { c, _, cfg, (ch, mus, role) ->
                            val sung = commands.history?.lastSung().orEmpty()
                            ServerMessage.Library(
                                withLastSung(c.songs, sung), c.bibleName, library.bible?.bookInfos().orEmpty(), c.announcements, c.sermons,
                                c.bibleNames, c.secondBibleName, c.presentations,
                                features = serverFeatures(ch) + listOfNotNull(app.selah.shared.FEATURE_ROLES.takeIf { team != null }), repeatDays = cfg.repeatDays,
                                // Las partituras solo a quien puede verlas (todos los músicos ven todas).
                                scores = if (ch.scores && (team == null || role.can(app.selah.shared.Permission.SCORES))) mus.scores else emptyList(),
                                sequences = if (ch.sequences) mus.sequences.keys.toList() else emptyList(),
                            )
                        }.distinctUntilChanged().collect { m -> send(Frame.Text(m.encode())) }
                    }
                    launch {
                        kotlinx.coroutines.flow.combine(commands.plan.state, commands.plan.saved, knowsPresentations, servicesConfig) { p, saved, knows, cfg ->
                            val (service, list) = todaySuggestion(cfg, saved, java.time.LocalDateTime.now())
                            ServerMessage.Plan(if (knows) p else p.forOldRemotes(), saved, service, list)
                        }
                            .distinctUntilChanged()
                            .collect { m -> send(Frame.Text(m.encode())) }
                    }
                    presenter.state.collect { s ->
                        val now = System.currentTimeMillis()
                        // Si solo cambió la posición (u otro detalle), no se repite el texto de todas las diapositivas.
                        val same = sentSlides.let { it != null && (it === s.slides || it == s.slides) }
                        val msg = if (lean.get() && same) ServerMessage.State(s.copy(slides = emptyList()), now, keepSlides = true)
                        else ServerMessage.State(s, now).also { sentSlides = s.slides }
                        send(Frame.Text(msg.encode()))
                    }
                }
                try {
                    for (frame in incoming) {
                        if (frame !is Frame.Text) continue
                        val msg = runCatching { decodeClientMessage(frame.readText()) }.getOrNull()?.let(NetGuard::clamp) ?: continue
                        // Demasiadas órdenes seguidas: se ignoran (nadie toca 20 veces por segundo).
                        if (!limiter.allow(msg)) continue
                        if (msg is ClientMessage.BibleSearch) {
                            // La respuesta va solo a quien buscó.
                            send(Frame.Text(bibleSearch(library, msg.query).encode()))
                            continue
                        }
                        if (msg is ClientMessage.SongSearch) {
                            send(Frame.Text(ServerMessage.SongResults(msg.query, library.searchSongs(msg.query)).encode()))
                            continue
                        }
                        if (msg is ClientMessage.BibleChapter) {
                            send(Frame.Text(bibleChapter(library, msg.book, msg.chapter).encode()))
                            continue
                        }
                        if (msg is ClientMessage.Ping) {
                            // Para sincronizar relojes (el click de los músicos): la hora del PC, sin clave.
                            send(Frame.Text(ServerMessage.Pong(msg.clientTime, System.currentTimeMillis()).encode()))
                            continue
                        }
                        if (msg is ClientMessage.Hello) {
                            lean.set(app.selah.shared.FEATURE_KEEP_SLIDES in msg.features)
                            knowsPresentations.value = app.selah.shared.FEATURE_PRESENTATIONS in msg.features
                            // El celular se presenta: si es del equipo, su papel; si no, queda pidiendo entrar.
                            val id = msg.deviceId
                            if (team != null && id != null && TeamStore.validId(id)) {
                                deviceHash = TeamStore.hash(id)
                                team.hello(deviceHash!!, TeamStore.cleanName(msg.deviceName), address)
                                send(Frame.Text(auth().encode()))
                            }
                            continue
                        }
                        if (msg is ClientMessage.Login) {
                            val reply = when (val r = guard.check(address, msg.pin)) {
                                PinGuard.Result.Ok -> { granted = pin.value; auth() }
                                is PinGuard.Result.Wrong -> { Log.warn("Clave incorrecta desde $address"); auth("Clave incorrecta (te quedan ${r.triesLeft} intentos)") }
                                is PinGuard.Result.Locked -> { Log.warn("Bloqueado por intentos: $address (${r.seconds} s)"); auth("Demasiados intentos. Espera ${r.seconds} segundos.") }
                            }
                            send(Frame.Text(reply.encode()))
                            continue
                        }
                        // Cada orden pide un permiso; el papel de este celular lo tiene o no (nadie se salta su papel).
                        val need = msg.requiredPermission()
                        val role = currentRole()
                        if (need != null && !role.can(need)) {
                            val reply = when {
                                role != app.selah.shared.Role.VIEWER -> ServerMessage.Error("Tu papel (${role.label}) no permite eso")
                                auth().pending -> auth("Esperando que el operador te dé acceso en el computador")
                                else -> auth("Escribe la clave para controlar")
                            }
                            send(Frame.Text(reply.encode()))
                            continue
                        }
                        commands.handle(msg)?.let { err -> send(Frame.Text(ServerMessage.Error(err).encode())) }
                    }
                } finally {
                    pushJob.cancel()
                    NetGuard.release(address)
                    Log.info("Remoto desconectado: $address")
                }
            }
        }
    }

    companion object {
        /** Cabecera HTTP con el código del celular (partituras, subir anuncios). */
        const val DEVICE_HEADER = "X-Selah-Dispositivo"

        /** Tope de lo que se puede subir desde un celular (un video corto). */
        const val MAX_UPLOAD = 300_000_000L

        /** Lo que este PC ofrece además de lo básico: las apps solo muestran eso si aparece aquí. */
        fun serverFeatures(c: ChurchFeatures): List<String> = buildList {
            add(app.selah.shared.FEATURE_CAROUSEL)
            if (c.click) add(app.selah.shared.FEATURE_CLICK)
            if (c.sequences) add(app.selah.shared.FEATURE_SEQUENCES)
            if (c.scores) add(app.selah.shared.FEATURE_SCORES)
            if (c.chords) add(app.selah.shared.FEATURE_CHORDS)
        }
        private val uploadSlots = kotlinx.coroutines.sync.Semaphore(3)

        /** Tope de toda la carpeta de anuncios al subir desde la red (para que nadie llene el disco). */
        const val MAX_ANNOUNCEMENTS_BYTES = 5_000_000_000L

        /** Desde el celular solo formatos comunes (los raros tienen más historial de fallas en los decodificadores). */
        val UPLOAD_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "mp4", "mov", "m4v")

        /**
         * Nombre seguro para un anuncio subido. Los nombres genéricos de las cámaras
         * ("image.jpg", "IMG_1234.HEIC", "VID_…") pasan a "Anuncio 2026-11-08 10-30.jpg".
         * null si no es imagen ni video.
         */
        fun uploadName(requested: String, now: Long): String? {
            val base = File(requested.replace('\\', '/').substringAfterLast('/')).name
            val ext = base.substringAfterLast('.', "").lowercase()
            if (ext !in UPLOAD_EXTENSIONS) return null
            val stem = cleanListName(base.substringBeforeLast('.')) ?: ""
            val generic = stem.isEmpty() || Regex("""^(image|img|photo|foto|video|vid|pxl|screenshot|captura)[ _-]?\d*.*$""", RegexOption.IGNORE_CASE).matches(stem) ||
                // iPhone: a veces el archivo solo trae un código ("610B91B5-1580-4A1F-8DB9-530B76F78203")
                Regex("""^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$""", RegexOption.IGNORE_CASE).matches(stem)
            val finalStem = if (generic) "Anuncio " + java.text.SimpleDateFormat("yyyy-MM-dd HH-mm-ss", java.util.Locale.ROOT).format(java.util.Date(now)) else stem
            return "$finalStem.$ext"
        }

        fun bibleSearch(library: Library, query: String): ServerMessage {
            val bible = library.bible ?: return ServerMessage.Error("No hay Biblia cargada")
            val (hits, total) = bible.search(query)
            return ServerMessage.BibleResults(query, hits, total)
        }

        fun bibleChapter(library: Library, book: String, chapter: Int): ServerMessage {
            val bible = library.bible ?: return ServerMessage.Error("No hay Biblia cargada")
            val verses = bible.chapter(book, chapter) ?: return ServerMessage.Error("$book no tiene capítulo $chapter")
            return ServerMessage.BibleChapter(book, chapter, verses)
        }

        /** Direcciones IPv4 de la red local de este equipo (las que se escriben en la tablet). */
        fun localAddresses(): List<String> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .filter { it.isSiteLocalAddress }
                .mapNotNull { it.hostAddress }
                .distinct()
        }.getOrDefault(emptyList())
    }
}
