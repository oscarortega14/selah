package app.selah.remote

import app.selah.shared.BibleBookInfo
import app.selah.shared.ClientMessage
import app.selah.shared.ProtocolJson
import app.selah.shared.DiscoveryReply
import app.selah.shared.DEFAULT_PORT
import app.selah.shared.PresentationState
import app.selah.shared.ServerMessage
import app.selah.shared.ServicePlanState
import app.selah.shared.SongSummary
import app.selah.shared.WS_PATH
import app.selah.shared.decodeServerMessage
import app.selah.shared.encode
import io.ktor.client.HttpClient
import io.ktor.client.statement.bodyAsText
import io.ktor.client.request.get
import io.ktor.client.request.setBody
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.pingInterval
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

data class FoundServer(val name: String, val host: String)

sealed interface ConnectionStatus {
    data object Idle : ConnectionStatus
    data class Connecting(val host: String) : ConnectionStatus
    data class Connected(val host: String) : ConnectionStatus
    data class Failed(val host: String, val reason: String) : ConnectionStatus
    /** Demostración: sin computador, con canciones y Biblia de ejemplo dentro de la app. */
    data object Demo : ConnectionStatus
}

/** Conexión WebSocket con el escritorio. Se reconecta sola si se cae el Wi-Fi. */
class RemoteClient(private val scope: CoroutineScope) {
    private val http = platformHttpClient {
        install(WebSockets) { pingInterval = 10.seconds }
    }

    private val _status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Idle)
    val status: StateFlow<ConnectionStatus> = _status.asStateFlow()

    private val _state = MutableStateFlow(PresentationState())
    val state: StateFlow<PresentationState> = _state.asStateFlow()

    private val _songs = MutableStateFlow<List<SongSummary>>(emptyList())
    val songs: StateFlow<List<SongSummary>> = _songs.asStateFlow()

    private val _bibleResults = MutableStateFlow<ServerMessage.BibleResults?>(null)
    val bibleResults: StateFlow<ServerMessage.BibleResults?> = _bibleResults.asStateFlow()

    /** Hora del PC - hora de la tablet, para mostrar bien la cuenta regresiva. */
    @Volatile var serverOffset: Long = 0
        private set

    private val _plan = MutableStateFlow(ServicePlanState())
    val plan: StateFlow<ServicePlanState> = _plan.asStateFlow()

    /** Listas guardadas en el PC. */
    private val _savedLists = MutableStateFlow<List<String>>(emptyList())
    val savedLists: StateFlow<List<String>> = _savedLists.asStateFlow()

    private val _bibleName = MutableStateFlow<String?>(null)
    val bibleName: StateFlow<String?> = _bibleName.asStateFlow()

    /** Libros de la Biblia del PC (para el explorador). */
    /** Traducciones del PC y la segunda elegida. */
    private val _bibleNames = MutableStateFlow<List<String>>(emptyList())
    val bibleNames: StateFlow<List<String>> = _bibleNames.asStateFlow()
    private val _secondBible = MutableStateFlow<String?>(null)
    val secondBible: StateFlow<String?> = _secondBible.asStateFlow()

    /** Última respuesta a una búsqueda de canciones (título o letra). */
    private val _songResults = MutableStateFlow<ServerMessage.SongResults?>(null)
    val songResults: StateFlow<ServerMessage.SongResults?> = _songResults.asStateFlow()

    /** Predicaciones del PC. */
    private val _sermons = MutableStateFlow<List<SongSummary>>(emptyList())
    val sermons: StateFlow<List<SongSummary>> = _sermons.asStateFlow()

    /** Anuncios (imágenes y videos) del PC. */
    private val _announcements = MutableStateFlow<List<String>>(emptyList())
    val announcements: StateFlow<List<String>> = _announcements.asStateFlow()

    /** Presentaciones (PowerPoint y PDF) del computador. */
    private val _presentations = MutableStateFlow<List<String>>(emptyList())
    val presentations: StateFlow<List<String>> = _presentations.asStateFlow()

    private val _bibleBooks = MutableStateFlow<List<BibleBookInfo>>(emptyList())
    val bibleBooks: StateFlow<List<BibleBookInfo>> = _bibleBooks.asStateFlow()

    /** Último capítulo pedido con [ClientMessage.BibleChapter]. */
    private val _bibleChapter = MutableStateFlow<ServerMessage.BibleChapter?>(null)
    val bibleChapter: StateFlow<ServerMessage.BibleChapter?> = _bibleChapter.asStateFlow()

    /** ¿Este dispositivo puede controlar? (el PC puede pedir una clave de 4 números) */
    private val _auth = MutableStateFlow<ServerMessage.Auth?>(null)
    val auth: StateFlow<ServerMessage.Auth?> = _auth.asStateFlow()

    /**
     * La clave guardada para ese computador (se manda sola al conectarse). Queda atada a la dirección
     * donde se aceptó: un enlace malicioso que nos haga conectar a otro equipo no se la lleva.
     */
    var savedPin: (host: String) -> String? = { null }
    /** Se llama cuando el PC aceptó una clave, para guardarla junto con su dirección. */
    var onPinAccepted: (host: String, pin: String) -> Unit = { _, _ -> }
    @Volatile private var currentHost: String? = null
    @Volatile private var triedSavedPin = false
    @Volatile private var pendingPin: String? = null

    /** Mandar la clave que escribió el usuario. */
    fun login(pin: String) {
        pendingPin = pin.trim()
        queue.trySend(ClientMessage.Login(pin.trim()))
    }

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    private val queue = Channel<ClientMessage>(Channel.UNLIMITED)
    private var job: Job? = null

    /** Se llama cuando el PC cambió de dirección y se encontró de nuevo en la red (para guardarla). */
    var onHostChanged: ((String) -> Unit)? = null

    /** El computador de mentira mientras se usa la demostración. */
    private var demo: DemoServer? = null

    /** Probar la app sin computador: todo funciona con canciones y Biblia de ejemplo. */
    fun startDemo() {
        job?.cancel()
        job = null
        currentHost = null
        clearAll()
        val server = DemoServer()
        demo = server
        _status.value = ConnectionStatus.Demo
        server.welcome().forEach(::onMessage)
    }

    /** Salir de la demostración (para buscar un computador de verdad). */
    fun stopDemo() {
        if (demo == null) return
        demo = null
        clearAll()
        _status.value = ConnectionStatus.Idle
    }

    /** Olvidar todo lo que llegó (al pasar de la demostración a un computador, o al revés). */
    private fun clearAll() {
        _state.value = PresentationState()
        _songs.value = emptyList(); _bibleName.value = null; _bibleBooks.value = emptyList()
        _bibleChapter.value = null; _bibleResults.value = null; _songResults.value = null
        _announcements.value = emptyList(); _presentations.value = emptyList(); _sermons.value = emptyList()
        _bibleNames.value = emptyList(); _secondBible.value = null
        _plan.value = ServicePlanState(); _savedLists.value = emptyList()
        _auth.value = null
        serverOffset = 0
    }

    fun connect(initialHost: String) {
        if (demo != null) stopDemo()
        job?.cancel()
        job = scope.launch {
            var host = initialHost
            var failures = 0
            while (isActive) {
                _status.value = ConnectionStatus.Connecting(host)
                try {
                    http.webSocket(host = host, port = DEFAULT_PORT, path = WS_PATH) {
                        _status.value = ConnectionStatus.Connected(host)
                        failures = 0
                        triedSavedPin = false
                        _auth.value = null
                        currentHost = host
                        // descartar órdenes que se acumularon mientras no había conexión
                        while (queue.tryReceive().isSuccess) Unit
                        // Que el PC no repita el texto de todas las diapositivas en cada "Siguiente".
                        send(Frame.Text(ClientMessage.Hello(listOf(app.selah.shared.FEATURE_KEEP_SLIDES, app.selah.shared.FEATURE_PRESENTATIONS)).encode()))
                        val sender = launch { for (m in queue) send(Frame.Text(m.encode())) }
                        try {
                            for (frame in incoming) {
                                if (frame is Frame.Text) onMessage(decodeServerMessage(frame.readText()))
                            }
                        } finally {
                            sender.cancel()
                        }
                    }
                    _status.value = ConnectionStatus.Failed(host, "Se cerró la conexión")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _status.value = ConnectionStatus.Failed(host, e.message ?: e::class.simpleName.orEmpty())
                }
                // Si falla varias veces seguidas, puede que el router le haya dado otra IP al PC: buscarlo.
                if (++failures % 3 == 0) {
                    val found = discover()
                    if (found.size == 1 && found[0].host != host) {
                        host = found[0].host
                        onHostChanged?.invoke(host)
                        continue
                    }
                }
                delay(2_000)
            }
        }
    }

    /**
     * Busca a Selah en la red local: envía un mensaje a todos (broadcast) y espera respuestas.
     * Devuelve los PCs encontrados (normalmente uno).
     */
    suspend fun discover(timeoutMs: Long = 1_500): List<FoundServer> = discoverServers(timeoutMs)

    /** Nombre del PC (consulta /info), o null si no responde. */
    suspend fun pcName(host: String): String? {
        return runCatching {
            val text = http.get("http://$host:$DEFAULT_PORT/info").bodyAsText()
            ProtocolJson.decodeFromString(DiscoveryReply.serializer(), text).name.ifBlank { null }
        }.getOrNull()
    }

    /**
     * Sube una foto o video a los anuncios del PC (con la clave guardada).
     * Devuelve un mensaje para mostrar. Se envía por partes, sin cargar todo el video en memoria.
     */
    suspend fun uploadAnnouncement(file: PickedFile): String {
        if (demo != null) return "En la demostración no se suben archivos: conéctate al computador con Selah"
        val host = (_status.value as? ConnectionStatus.Connected)?.host ?: return "No hay conexión con el computador"
        return runCatching {
            val response = http.post("http://$host:$DEFAULT_PORT/anuncios") {
                url { parameters.append("nombre", file.name) }
                header("X-Proyecta-Pin", savedPin(host).orEmpty())
                setBody(object : io.ktor.http.content.OutgoingContent.ReadChannelContent() {
                    override val contentLength: Long? = file.size
                    override fun readFrom(): io.ktor.utils.io.ByteReadChannel = file.open()
                })
            }
            when (response.status.value) {
                200 -> "Listo: ya está en Anuncios del computador"
                401 -> "Falta la clave para subir anuncios"
                else -> response.bodyAsText().ifBlank { "No se pudo subir" }
            }
        }.getOrElse { "No se pudo subir: ${it.message}" }
    }

    fun clearBibleResults() { _bibleResults.value = null }

    fun send(msg: ClientMessage) {
        demo?.let { server -> server.handle(msg).forEach(::onMessage); return }
        if (_status.value is ConnectionStatus.Connected) queue.trySend(msg)
    }

    private fun onMessage(m: ServerMessage) {
        when (m) {
            is ServerMessage.Library -> {
                // Otra traducción: el capítulo guardado ya no sirve.
                if (m.bibleName != _bibleName.value) _bibleChapter.value = null
                _songs.value = m.songs; _bibleName.value = m.bibleName; _bibleBooks.value = m.bibleBooks
                _announcements.value = m.announcements
                _presentations.value = m.presentations
                _sermons.value = m.sermons
                _bibleNames.value = m.bibleNames
                _secondBible.value = m.secondBible
            }
            is ServerMessage.BibleChapter -> _bibleChapter.value = m
            is ServerMessage.State -> {
                if (m.serverNow > 0) serverOffset = m.serverNow - nowMillis()
                _state.value = app.selah.shared.mergeState(_state.value, m)
            }
            is ServerMessage.BibleResults -> _bibleResults.value = m
            is ServerMessage.SongResults -> _songResults.value = m
            is ServerMessage.Plan -> { _plan.value = m.plan; _savedLists.value = m.saved }
            is ServerMessage.Error -> _errors.tryEmit(m.message)
            is ServerMessage.Auth -> {
                if (m.ok) {
                    val host = currentHost
                    if (host != null) pendingPin?.let { onPinAccepted(host, it) }
                    pendingPin = null
                } else if (m.required && !triedSavedPin) {
                    // Primero se prueba la clave guardada, sin molestar al usuario.
                    triedSavedPin = true
                    val saved = currentHost?.let(savedPin)
                    if (saved != null) { pendingPin = saved; queue.trySend(ClientMessage.Login(saved)); return }
                }
                _auth.value = m
            }
        }
    }

    fun close() {
        job?.cancel()
        http.close()
    }
}
