package app.selah.shared

/**
 * Formato de las canciones (.txt):
 *
 *     # Título de la canción
 *
 *     Primera estrofa, línea 1
 *     Primera estrofa, línea 2
 *
 *     Coro:
 *     Línea del coro
 *     Línea del coro
 *
 * - La línea que empieza con "#" es el título (si falta, se usa el nombre del archivo).
 * - Cada bloque separado por una línea en blanco es una diapositiva.
 * - Si la primera línea de un bloque es corta y termina en ":" (ej. "Coro:"), es la etiqueta.
 * - Opcional, antes de la primera estrofa: "Orden: Inicio, Coro, Pregón 1, Coro" arma la
 *   secuencia repitiendo secciones sin copiar el texto. "Inicio" es lo que va antes de la
 *   primera etiqueta. Los nombres que no existen se ignoran (y se reportan en unknownOrder).
 * - Opcional, también antes de la primera estrofa: "Fondo: montañas.jpg" usa esa imagen
 *   (de la carpeta Selah/fondos) solo para esta canción.
 * - Opcional, antes de la primera estrofa: "Autor: …" y "CCLI: 1234567" (número de la canción en CCLI,
 *   para el reporte de licencias). No se proyectan.
 * - Acordes (formato ChordPro) justo antes de la sílaba: "[G]Cuán grande es [C]Él". En el videobeam
 *   sale solo la letra; los músicos los ven en la vista de escenario. Una línea solo de acordes
 *   ("[G] [D] [Em]") no se proyecta.
 */
object SongParser {
    private const val MAX_LABEL = 30
    private val ORDER_LINE = Regex("""^orden\s*:(.*)$""", RegexOption.IGNORE_CASE)
    private val BACKGROUND_LINE = Regex("""^fondo\s*:(.*)$""", RegexOption.IGNORE_CASE)
    private val AUTHOR_LINE = Regex("""^autor(?:es)?\s*:(.*)$""", RegexOption.IGNORE_CASE)
    private val CCLI_LINE = Regex("""^ccli\s*(?:#|n[ºo°.]*)?\s*:?\s*(\d{1,10})\s*$""", RegexOption.IGNORE_CASE)
    const val START_LABEL = "Inicio"

    /** Ninguna canción real tiene tantas diapositivas; es el tope al repetir secciones con "Orden:". */
    const val MAX_SLIDES = 500

    fun parse(id: String, text: String, fallbackTitle: String = id): Song {
        var title = fallbackTitle
        var titleFound = false
        val blocks = mutableListOf<List<String>>()
        var current = mutableListOf<String>()
        var order: List<String>? = null
        var background: String? = null
        var author: String? = null
        var ccli: String? = null

        for (raw in text.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
            val line = raw.trim()
            if (!titleFound && line.startsWith("#")) {
                title = line.trimStart('#').trim().ifEmpty { fallbackTitle }
                titleFound = true
                continue
            }
            val orderMatch = ORDER_LINE.matchEntire(line)
            if (orderMatch != null && order == null && blocks.isEmpty() && current.isEmpty()) {
                order = orderMatch.groupValues[1].split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
                continue
            }
            val bgMatch = BACKGROUND_LINE.matchEntire(line)
            if (bgMatch != null && background == null && blocks.isEmpty() && current.isEmpty()) {
                background = bgMatch.groupValues[1].trim().ifEmpty { null }
                continue
            }
            val beforeLyrics = blocks.isEmpty() && current.isEmpty()
            val authorMatch = AUTHOR_LINE.matchEntire(line)
            if (authorMatch != null && author == null && beforeLyrics) {
                author = authorMatch.groupValues[1].trim().ifEmpty { null }
                continue
            }
            val ccliMatch = CCLI_LINE.matchEntire(line)
            if (ccliMatch != null && ccli == null && beforeLyrics) {
                ccli = ccliMatch.groupValues[1]
                continue
            }
            if (line.isEmpty()) {
                if (current.isNotEmpty()) { blocks += current; current = mutableListOf() }
            } else {
                current += line
            }
        }
        if (current.isNotEmpty()) blocks += current

        val slides = blocks.map { block ->
            val first = block.first()
            val (label, lines) = if (block.size > 1 && first.endsWith(":") && first.length <= MAX_LABEL) {
                first.dropLast(1).trim() to block.drop(1)
            } else {
                "" to block
            }
            withChords(label, lines)
        }
        val song = if (order.isNullOrEmpty()) Song(id, title, slides) else expand(id, title, slides, order)
        return song.copy(background = background, author = author, ccli = ccli)
    }

    /** Acorde entre corchetes: empieza con una nota (A–G), sin espacios. "[coro x2]" no es acorde. */
    val CHORD = Regex("""\[([A-G][^\]\s]{0,11})\]""")

    /** La letra sin acordes (para proyectar). */
    fun stripChords(line: String): String = line.replace(CHORD, "").replace(Regex(" {2,}"), " ").trim()

    private fun withChords(label: String, lines: List<String>): Slide {
        if (lines.none { CHORD.containsMatchIn(it) }) return Slide(label, lines.joinToString("\n"))
        val text = lines.map(::stripChords).filter { it.isNotEmpty() }.joinToString("\n")
        return Slide(label, text, chords = lines.joinToString("\n"))
    }

    private fun expand(id: String, title: String, slides: List<Slide>, order: List<String>): Song {
        // Agrupa las diapositivas por sección (clave sin tildes ni mayúsculas).
        val groups = LinkedHashMap<String, List<Slide>>()
        var key: String? = null
        var group = mutableListOf<Slide>()
        fun flush() { key?.let { k -> if (k !in groups) groups[k] = group } }
        for (s in slides) {
            if (s.label.isNotEmpty() || key == null) {
                flush()
                key = normalizeKey(s.label.ifEmpty { START_LABEL })
                group = mutableListOf()
            }
            group += s
        }
        flush()

        val unknown = mutableListOf<String>()
        val expanded = mutableListOf<Slide>()
        for (name in order) {
            val g = groups[normalizeKey(name)]
            if (g == null) { unknown += name; continue }
            // Un "Orden:" que repite miles de veces no debe armar millones de diapositivas.
            if (expanded.size + g.size > MAX_SLIDES) break
            // La primera diapositiva de cada repetición lleva la etiqueta (para los saltos).
            expanded += g.first().copy(label = g.first().label.ifEmpty { START_LABEL })
            expanded += g.drop(1)
        }
        return Song(id, title, expanded.ifEmpty { slides }, unknownOrder = unknown)
    }
}
