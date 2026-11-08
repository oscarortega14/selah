package app.selah.shared

import kotlinx.serialization.Serializable

/** Una "diapositiva": una estrofa de canción o un versículo. */
@Serializable
data class Slide(
    val label: String = "",
    val text: String,
    /** El mismo versículo en una segunda traducción (Biblia a dos traducciones), o "". */
    val text2: String = "",
    /** La letra con acordes en línea ("[G]Cuán grande es [C]Él") para los músicos, o "" si no tiene. */
    val chords: String = "",
)

/** Resumen de una canción para listarla en el control remoto. */
@Serializable
data class SongSummary(
    val id: String,
    val title: String,
    val firstLine: String = "",
    /** Línea "Tipo:" (Alabanza, Adoración…). Vacío = sin tipo: vale para cualquier espacio de la plantilla. */
    val tags: List<String> = emptyList(),
    /** Última vez que se proyectó ("2026-11-02"), según el historial del PC; null = nunca o PC viejo. */
    val lastSung: String? = null,
)

private val VERSE_LABEL = Regex(""".*\d+:\d+$""")

/** Lo que se está proyectando en este momento. */
@Serializable
data class PresentationState(
    val title: String = "",
    val slides: List<Slide> = emptyList(),
    val index: Int = -1,
    val blank: Boolean = false,
    val isBible: Boolean = false,
    /** Id (nombre de archivo) de la canción en pantalla; null si es Biblia o nada. */
    val songId: String? = null,
    /** Mensaje del operador para la vista de escenario (no sale en el videobeam). */
    val stageMessage: String = "",
    /** Fondo propio de la canción en pantalla (archivo en Selah/fondos), si tiene. */
    val background: String? = null,
    /** Pantalla de espera: fondo + logo de la iglesia en vez de la letra. */
    val logo: Boolean = false,
    /** Aviso en una franja abajo ("" = sin aviso). Sale encima de la letra, no la reemplaza. */
    val alert: String = "",
    /** Cuenta regresiva activa: termina en este instante (milisegundos de época del PC), o null. */
    val countdownEnd: Long? = null,
    /** Texto sobre la cuenta regresiva, ej. "Comenzamos en". */
    val countdownText: String = "",
    /** Anuncio en pantalla: imagen o video (archivo en Selah/anuncios) en vez de letra, o null. */
    val media: String? = null,
    /**
     * Presentación en pantalla (archivo en Selah/presentaciones): cada diapositiva es una imagen
     * y el texto de las diapositivas solo dice "Diapositiva 3 de 10" (para los remotos).
     */
    val presentation: String? = null,
    /** Predicación en pantalla (songId es el id de la predicación): las etiquetas salen como encabezado. */
    val sermon: Boolean = false,
    /** Temporizador del predicador: cuándo empezó (hora del PC) o null. Solo se ve en la vista de escenario. */
    val timerStart: Long? = null,
    /** Minutos que tiene el predicador (0 = sin límite, solo cuenta hacia arriba). */
    val timerMinutes: Int = 0,
    /** Nombre para la transmisión (OBS), ej. "Pastor Juan Pérez" ("" = no se muestra). No sale en el videobeam. */
    val lowerThird: String = "",
    /** Segunda línea del nombre, ej. "Predicación" o "Alabanza". */
    val lowerThirdSub: String = "",
    /** Carrusel de anuncios andando: cada cuántos segundos cambia la imagen (0 = no hay carrusel). */
    val carouselSeconds: Int = 0,
) {
    /** Segundos que lleva el predicador, o null si el temporizador no está andando. */
    fun timerElapsed(now: Long): Long? = timerStart?.let { ((now - it) / 1000).coerceAtLeast(0) }

    /** Segundos que le quedan (negativo = se pasó), o null si no hay límite o no está andando. */
    fun timerLeft(now: Long): Long? = timerElapsed(now)?.takeIf { timerMinutes > 0 }?.let { timerMinutes * 60L - it }

    /** Segundos que faltan de la cuenta regresiva (0 si terminó), o null si no hay. */
    fun countdownSeconds(now: Long): Long? = countdownEnd?.let { ((it - now + 999) / 1000).coerceAtLeast(0) }

    val current: Slide? get() = slides.getOrNull(index)

    /** Secciones para saltar rápido (Coro, Pregón 1…). Vacío para la Biblia. */
    val sections: List<Section> get() = when {
        isBible -> emptyList()
        // En una predicación, los versículos ("Juan 10:11") no son secciones para saltar: solo los puntos.
        sermon -> slides.sections().filterNot { VERSE_LABEL.matches(it.label) }
        else -> slides.sections()
    }

    /** Posición en [sections] de la sección que contiene la diapositiva actual, o -1. */
    val currentSection: Int get() = sections.indexOfLast { it.start <= index }

    /** Etiquetas sin repetir, en orden de aparición: los botones de salto. */
    val sectionLabels: List<String> get() = sections.map { it.label }.distinct()

    /** Etiqueta de la sección actual, o null. */
    val currentLabel: String? get() = sections.getOrNull(currentSection)?.label

    /**
     * A dónde saltar al tocar [label]. Si la sección se repite (por la línea "Orden:"),
     * va a la siguiente aparición después de la actual; si no hay más, a la primera.
     */
    fun jumpTarget(label: String): Int? {
        val starts = sections.filter { it.label == label }.map { it.start }
        if (starts.isEmpty()) return null
        val here = sections.getOrNull(currentSection)?.start ?: -1
        return starts.firstOrNull { it > here } ?: starts.first()
    }

    /** La diapositiva que sigue, para la vista de escenario. */
    val next: Slide? get() = slides.getOrNull(index + 1)
}

/** Una sección de canción: empieza en la diapositiva [start] y sigue hasta la próxima etiqueta. */
data class Section(val label: String, val start: Int)

/**
 * Cada diapositiva con etiqueta ("Coro", "Pregón 1"…) abre una sección; las diapositivas
 * sin etiqueta que la siguen le pertenecen. Lo que va antes de la primera etiqueta es "Inicio".
 * Si la canción no tiene etiquetas no hay secciones (no hace falta saltar).
 */
fun List<Slide>.sections(): List<Section> {
    if (none { it.label.isNotEmpty() }) return emptyList()
    val out = mutableListOf<Section>()
    forEachIndexed { i, s ->
        when {
            s.label.isNotEmpty() -> out += Section(s.label, i)
            i == 0 -> out += Section("Inicio", 0)
        }
    }
    return out
}

/** Canción completa (solo vive en el escritorio). */
data class Song(
    val id: String,
    val title: String,
    val slides: List<Slide>,
    /** Nombres de la línea "Orden:" que no corresponden a ninguna sección. */
    val unknownOrder: List<String> = emptyList(),
    /** Línea "Fondo:" de la canción: archivo en Selah/fondos. */
    val background: String? = null,
    /** Línea "Autor:" (no se proyecta). */
    val author: String? = null,
    /** Línea "CCLI:": número de la canción en CCLI, para el reporte de licencias. */
    val ccli: String? = null,
    /** Línea "Tipo:" (Alabanza, Adoración…). */
    val tags: List<String> = emptyList(),
) {
    fun summary() = SongSummary(id, title, slides.firstOrNull()?.text?.lineSequence()?.firstOrNull().orEmpty(), tags)
}

/** 125 -> "02:05"; 3725 -> "1:02:05". */
fun formatClock(totalSeconds: Long): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val sec = totalSeconds % 60
    fun two(n: Long) = n.toString().padStart(2, '0')
    return if (h > 0) "$h:${two(m)}:${two(sec)}" else "${two(m)}:${two(sec)}"
}

/** Un resultado de búsqueda en la Biblia. */
@Serializable
data class BibleHit(val reference: String, val text: String)
