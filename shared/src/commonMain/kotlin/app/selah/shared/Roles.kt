package app.selah.shared

import kotlinx.serialization.Serializable

/**
 * Papeles del equipo. Cada celular lo aprueba el computador principal y le da un papel; el PC rechaza lo que el
 * papel no permite (no basta con esconder botones). Nadie se salta su papel: un músico no hace cosas de director
 * ni un director cosas de operador.
 */
@Serializable
enum class Role(val label: String, val permissions: Set<Permission>) {
    OPERATOR("Operador", Permission.entries.toSet()),
    DIRECTOR("Director de alabanza", setOf(Permission.SCORES, Permission.MUSIC, Permission.STAGE)),
    MUSICIAN("Músico", setOf(Permission.SCORES)),
    PREACHER("Predicador", setOf(Permission.TIMER)),
    MEDIA("Medios y transmisión", setOf(Permission.BROADCAST, Permission.UPLOAD)),
    VIEWER("Solo mirar", emptySet()),
    ;

    fun can(p: Permission) = p in permissions
}

/** Lo que se puede hacer, agrupado. */
@Serializable
enum class Permission {
    /** Pasar la letra, poner canciones, Biblia, anuncios, presentaciones, negro, logo, avisos, cuenta regresiva. */
    PROJECT,
    /** Armar y cambiar la lista del culto. */
    PLAN,
    /** Click, secuencias, letra automática y grabar tiempos. */
    MUSIC,
    /** Mensajes a la vista de escenario. */
    STAGE,
    /** Temporizador del predicador. */
    TIMER,
    /** Nombres en pantalla para la transmisión. */
    BROADCAST,
    /** Subir fotos y videos a Anuncios. */
    UPLOAD,
    /** Ver las partituras (todas: cada músico tiene una sugerida, pero puede ver las de los demás). */
    SCORES,
}

/** El permiso que pide cada orden; null = cualquiera puede (preguntar, buscar, presentarse). */
fun ClientMessage.requiredPermission(): Permission? = when (this) {
    is ClientMessage.Hello, is ClientMessage.Login, is ClientMessage.Ping,
    is ClientMessage.BibleSearch, is ClientMessage.SongSearch, is ClientMessage.BibleChapter,
    -> null
    is ClientMessage.PlanAddSong, is ClientMessage.PlanAddBible, is ClientMessage.PlanRemove, is ClientMessage.PlanMove,
    is ClientMessage.PlanGo, ClientMessage.PlanNext, ClientMessage.PlanClear, is ClientMessage.PlanSave, is ClientMessage.PlanOpen,
    is ClientMessage.PlanAddMedia, is ClientMessage.PlanAddPresentation, is ClientMessage.PlanAddSermon,
    -> Permission.PLAN
    is ClientMessage.StartClick, ClientMessage.StopClick, is ClientMessage.SetTempo, is ClientMessage.SetAutoLyrics,
    is ClientMessage.RecordTiming, ClientMessage.StartSequence, ClientMessage.StopSequence, is ClientMessage.SequenceNudge,
    -> Permission.MUSIC
    is ClientMessage.StageMessage -> Permission.STAGE
    is ClientMessage.StartTimer, ClientMessage.StopTimer -> Permission.TIMER
    is ClientMessage.SetLowerThird -> Permission.BROADCAST
    else -> Permission.PROJECT
}

/** Instrumentos para elegir al aprobar a un músico (más los que aparezcan en las partituras). */
val COMMON_INSTRUMENTS = listOf("Bajo", "Batería", "Piano", "Teclado", "Guitarra", "Guitarra eléctrica", "Voz", "Vientos", "Violín")

/** Capacidad del PC (Library.features): aprueba celulares y les da papel (Hello.deviceId, Auth.role). */
const val FEATURE_ROLES = "roles"
