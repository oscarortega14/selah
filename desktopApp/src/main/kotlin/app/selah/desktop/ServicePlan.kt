package app.selah.desktop

import app.selah.shared.cleanListName
import app.selah.shared.ClientMessage
import app.selah.shared.PlanItem
import app.selah.shared.PlanKind
import app.selah.shared.ProtocolJson
import app.selah.shared.ServicePlanState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Lista del culto: el orden de canciones y lecturas preparado de antemano.
 * Se guarda en [file] después de cada cambio para sobrevivir a un cierre del programa.
 */
class ServicePlan(
    private val file: File,
    private val library: Library,
    private val present: (ClientMessage) -> String?,
    /** Carpeta de las listas guardadas con nombre (Selah/listas). */
    private val listsDir: File = File(file.parentFile, "listas"),
) {
    private val _state = MutableStateFlow(load(file) ?: ServicePlanState())
    val state: StateFlow<ServicePlanState> = _state.asStateFlow()

    private val _saved = MutableStateFlow(scanSaved())
    /** Nombres de las listas guardadas, la más reciente primero. */
    val saved: StateFlow<List<String>> = _saved.asStateFlow()

    @Synchronized
    fun handle(msg: ClientMessage): String? = when (msg) {
        is ClientMessage.PlanAddSong, is ClientMessage.PlanAddBible, is ClientMessage.PlanAddSermon,
        is ClientMessage.PlanAddMedia, is ClientMessage.PlanAddPresentation,
        -> itemFor(msg).fold({ item -> update { it.copy(items = it.items + item) }; null }, { it.message })
        is ClientMessage.PlanRemove -> { remove(msg.index); null }
        is ClientMessage.PlanMove -> { move(msg.from, msg.to); null }
        is ClientMessage.PlanGo -> go(msg.index)
        ClientMessage.PlanNext -> {
            val next = _state.value.current + 1
            if (next < _state.value.items.size) go(next) else "No hay más elementos en la lista"
        }
        ClientMessage.PlanClear -> { update { ServicePlanState() }; null }
        is ClientMessage.PlanSave -> saveAs(msg.name)
        is ClientMessage.PlanOpen -> open(msg.name)
        else -> null
    }

    /** Se abrió una lista guardada (para que avance la rotación de los cultos). */
    var onOpened: ((String) -> Unit)? = null

    /** Guarda la lista actual en listas/<nombre>.json. Devuelve un mensaje de error o null. */
    @Synchronized
    fun saveAs(name: String): String? {
        val typed = cleanListName(name) ?: return "Escribe un nombre para la lista"
        if (_state.value.items.isEmpty()) return "La lista está vacía"
        // En Windows (y en la Mac) "Lista" y "lista" son el mismo archivo: se usa el nombre que ya tenía.
        val clean = scanSaved().firstOrNull { it.equals(typed, ignoreCase = true) } ?: typed
        val saved = _state.value.copy(current = -1, name = clean)
        val ok = runCatching {
            listsDir.mkdirs()
            File(listsDir, "$clean.json").writeText(ProtocolJson.encodeToString(ServicePlanState.serializer(), saved), Charsets.UTF_8)
        }.isSuccess
        if (!ok) return "No pude guardar la lista"
        update { it.copy(name = clean) }
        _saved.value = scanSaved()
        return null
    }

    /**
     * Guarda una lista con nombre sin tocar la de ahora ("Planificar la semana"). [adds] son las mismas órdenes
     * "+ Lista" de siempre; si alguna no sirve (canción borrada, cita mal escrita) no se guarda nada.
     */
    @Synchronized
    fun saveItems(name: String, adds: List<ClientMessage>): String? {
        val typed = cleanListName(name) ?: return "Escribe un nombre para la lista"
        val items = adds.map { msg -> itemFor(msg).getOrElse { return it.message } }
        if (items.isEmpty()) return "La lista está vacía"
        val clean = scanSaved().firstOrNull { it.equals(typed, ignoreCase = true) } ?: typed
        val ok = runCatching {
            listsDir.mkdirs()
            File(listsDir, "$clean.json").writeText(ProtocolJson.encodeToString(ServicePlanState.serializer(), ServicePlanState(items, name = clean)), Charsets.UTF_8)
        }.isSuccess
        if (!ok) return "No pude guardar la lista"
        _saved.value = scanSaved()
        return null
    }

    /** Lo que tiene una lista guardada, sin abrirla (null si no existe). */
    @Synchronized
    fun savedItems(name: String): List<PlanItem>? {
        val found = scanSaved().firstOrNull { it.equals(name, ignoreCase = true) } ?: return null
        return load(File(listsDir, "$found.json"))?.items
    }

    /** El elemento que agrega una orden "+ Lista", o por qué no se puede. */
    private fun itemFor(msg: ClientMessage): Result<PlanItem> {
        fun fail(m: String?) = Result.failure<PlanItem>(IllegalArgumentException(m))
        return when (msg) {
            is ClientMessage.PlanAddSong -> library.song(msg.id)?.let { Result.success(PlanItem(PlanKind.SONG, it.id, it.title)) } ?: fail("Canción no encontrada")
            is ClientMessage.PlanAddBible -> {
                val bible = library.bible ?: return fail("No hay Biblia cargada")
                try { bible.lookup(msg.reference).let { Result.success(PlanItem(PlanKind.BIBLE, it.title, it.title)) } } catch (e: IllegalArgumentException) { fail(e.message) }
            }
            is ClientMessage.PlanAddSermon -> library.sermon(msg.id)?.let { Result.success(PlanItem(PlanKind.SERMON, it.id, "Predicación: ${it.title}")) } ?: fail("Predicación no encontrada")
            is ClientMessage.PlanAddMedia ->
                if (library.announcementFile(msg.name) == null) fail("Anuncio no encontrado")
                else Result.success(PlanItem(PlanKind.MEDIA, msg.name, "Anuncio: ${msg.name.substringBeforeLast('.')}"))
            is ClientMessage.PlanAddPresentation ->
                if (library.presentations.file(msg.name) == null) fail("Presentación no encontrada")
                else Result.success(PlanItem(PlanKind.PRESENTATION, msg.name, "Presentación: ${msg.name.substringBeforeLast('.')}"))
            else -> fail("No se puede agregar a la lista")
        }
    }

    /** Reemplaza la lista actual por una guardada. */
    @Synchronized
    fun open(name: String): String? {
        // Solo una de las listas guardadas (nada de "../" para leer otros archivos).
        val found = scanSaved().let { saved -> saved.firstOrNull { it == name } ?: saved.firstOrNull { it.equals(name, ignoreCase = true) } }
            ?: return "No encontré la lista \"$name\""
        val loaded = load(File(listsDir, "$found.json")) ?: return "No encontré la lista \"$name\""
        update { loaded.copy(current = -1, name = found) }
        onOpened?.invoke(found)
        return null
    }

    /** Volver a leer de disco (después de restaurar un respaldo). */
    @Synchronized
    fun reloadFromDisk() {
        _state.value = load(file) ?: ServicePlanState()
        _saved.value = scanSaved()
    }

    @Synchronized
    fun deleteSaved(name: String) {
        File(listsDir, "$name.json").delete()
        _saved.value = scanSaved()
    }

    private fun scanSaved(): List<String> = listsDir.listFiles { f -> f.isFile && f.extension == "json" }.orEmpty()
        .sortedByDescending { it.lastModified() }
        .map { it.nameWithoutExtension }

    private fun go(index: Int): String? {
        val item = _state.value.items.getOrNull(index) ?: return null
        val error = when (item.kind) {
            PlanKind.SONG -> present(ClientMessage.SelectSong(item.ref))
            PlanKind.BIBLE -> present(ClientMessage.ShowBible(item.ref))
            PlanKind.MEDIA -> present(ClientMessage.ShowMedia(item.ref))
            PlanKind.SERMON -> present(ClientMessage.ShowSermon(item.ref))
            PlanKind.PRESENTATION -> present(ClientMessage.ShowPresentation(item.ref))
        }
        if (error == null) update { it.copy(current = index) }
        return error
    }

    private fun remove(index: Int) = update { s ->
        if (index !in s.items.indices) return@update s
        val current = when {
            index < s.current -> s.current - 1
            index == s.current -> -1
            else -> s.current
        }
        // copy: la lista conserva su nombre (para "Guardar" otra vez con el mismo).
        s.copy(items = s.items.filterIndexed { i, _ -> i != index }, current = current)
    }

    /** Mueve un elemento; el "actual" sigue apuntando al mismo elemento. */
    private fun move(from: Int, to: Int) = update { s ->
        if (from !in s.items.indices || to !in s.items.indices || from == to) return@update s
        val items = s.items.toMutableList().apply { add(to, removeAt(from)) }
        val current = when (s.current) {
            from -> to
            in minOf(from, to)..maxOf(from, to) -> if (from < to) s.current - 1 else s.current + 1
            else -> s.current
        }
        s.copy(items = items, current = current)
    }

    /**
     * Una canción o predicación cambió de nombre en el editor (su id es el nombre del archivo):
     * la lista actual y las guardadas pasan a apuntar al nombre nuevo.
     */
    @Synchronized
    fun renamed(kind: PlanKind, oldId: String, newId: String, title: String) {
        val newTitle = if (kind == PlanKind.SERMON) "Predicación: $title" else title
        fun ServicePlanState.fixed() = copy(items = items.map { if (it.kind == kind && it.ref == oldId) it.copy(ref = newId, title = newTitle) else it })
        update { it.fixed() }
        for (f in listsDir.listFiles { f -> f.isFile && f.extension == "json" }.orEmpty()) {
            val saved = load(f) ?: continue
            val fixed = saved.fixed()
            if (fixed != saved) runCatching { f.writeText(ProtocolJson.encodeToString(ServicePlanState.serializer(), fixed), Charsets.UTF_8) }
        }
    }

    private fun update(f: (ServicePlanState) -> ServicePlanState) {
        val new = f(_state.value)
        if (new == _state.value) return
        _state.value = new
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(ProtocolJson.encodeToString(ServicePlanState.serializer(), new), Charsets.UTF_8)
        }
    }

    private fun load(f: File): ServicePlanState? = runCatching {
        ProtocolJson.decodeFromString(ServicePlanState.serializer(), f.readText(Charsets.UTF_8))
    }.getOrNull()
}


internal val MONTHS = listOf("ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sep", "oct", "nov", "dic")

/** Nombre sugerido: el próximo domingo (o hoy si es domingo), ej. "Culto domingo 8 nov 2026". */
fun suggestedListName(today: java.time.LocalDate = java.time.LocalDate.now()): String {
    val sunday = today.with(java.time.temporal.TemporalAdjusters.nextOrSame(java.time.DayOfWeek.SUNDAY))
    return "Culto domingo ${sunday.dayOfMonth} ${MONTHS[sunday.monthValue - 1]} ${sunday.year}"
}

/** Reparte cada orden: lo de la lista a [ServicePlan], el resto al [Presenter]. */
class Commands(
    val presenter: Presenter,
    val plan: ServicePlan,
    val history: History? = null,
    /** Cultos (día, plantilla, rotación); null en algunas pruebas. */
    val services: ServicesStore? = null,
) {
    init {
        services?.let { s -> plan.onOpened = s::markUsed }
        presenter.renameListeners += { kind, oldId, newId, title ->
            plan.renamed(if (kind == HistoryEntry.Kind.SERMON) PlanKind.SERMON else PlanKind.SONG, oldId, newId, title)
            history?.renamed(kind, oldId, newId, title)
        }
    }

    /** Cambiar de traducción (Main también la guarda en ajustes). */
    var onSelectBible: (String) -> Unit = {}
    var onSelectSecondBible: (String?) -> Unit = {}

    /** "¿Qué usa tu iglesia?": lo apagado no se puede pedir ni desde un celular viejo. */
    var church: () -> ChurchFeatures = { ChurchFeatures.ALL }

    fun handle(msg: ClientMessage): String? = gate(msg) ?: when (msg) {
        is ClientMessage.SelectBible -> { onSelectBible(msg.name); null }
        is ClientMessage.SetSecondBible -> { onSelectSecondBible(msg.name); null }
        is ClientMessage.PlanAddSong, is ClientMessage.PlanAddBible, is ClientMessage.PlanRemove,
        is ClientMessage.PlanMove, is ClientMessage.PlanGo, ClientMessage.PlanNext, ClientMessage.PlanClear,
        is ClientMessage.PlanSave, is ClientMessage.PlanOpen, is ClientMessage.PlanAddMedia, is ClientMessage.PlanAddSermon,
        is ClientMessage.PlanAddPresentation,
        -> plan.handle(msg)
        else -> presenter.handle(msg)
    }

    private fun gate(msg: ClientMessage): String? {
        val c = church()
        return when (msg) {
            is ClientMessage.StartClick -> if (!c.click) OFF_CLICK else null
            ClientMessage.StartSequence, is ClientMessage.SequenceNudge -> if (!c.sequences) OFF_SEQUENCES else null
            is ClientMessage.SetTempo, is ClientMessage.RecordTiming, is ClientMessage.SetAutoLyrics -> if (!c.click && !c.sequences) OFF_CLICK else null
            else -> null
        }
    }

    companion object {
        const val OFF_CLICK = "El click está apagado en \"¿Qué usa tu iglesia?\" (en el computador)"
        const val OFF_SEQUENCES = "Las secuencias están apagadas en \"¿Qué usa tu iglesia?\" (en el computador)"

        fun create(library: Library): Commands {
            val presenter = Presenter(library)
            val plan = ServicePlan(File(library.baseDir, "lista-del-culto.json"), library, presenter::handle)
            val history = History(File(library.baseDir, "historial.csv"))
            presenter.onShown = history::record
            val services = ServicesStore(File(library.baseDir, "cultos.json"))
            return Commands(presenter, plan, history, services).apply {
                onSelectBible = { library.selectBible(it) }
                onSelectSecondBible = { library.selectSecondBible(it) }
            }
        }
    }
}
