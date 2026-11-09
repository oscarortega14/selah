package app.selah.shared

import kotlinx.serialization.Serializable

/**
 * Lo de los músicos: acordes (cambiar el tono), partituras por instrumento y secuencias.
 * Cada iglesia enciende en el PC lo que usa; el PC anuncia en Library.features lo que está encendido.
 */

/** Una partitura: archivo de la carpeta partituras para la canción [songId] y el instrumento [instrument]. */
@Serializable
data class ScoreRef(val songId: String, val instrument: String, val file: String)

/** Instrumento de las partituras que son para todos (archivo sin " - Instrumento"). */
const val GENERAL_INSTRUMENT = "General"

/**
 * Nombre de un archivo de partitura o secuencia → (canción, instrumento o null). Formas aceptadas:
 * "Cuán grande es Él - Bajo.pdf", "Cuán grande es Él/Bajo.pdf" (carpeta por canción) y "Cuán grande es Él.pdf".
 */
fun splitMusicFileName(path: String): Pair<String, String?> {
    val clean = path.replace('\\', '/').trim('/')
    val stem = clean.substringAfterLast('/').let { if ('.' in it) it.substringBeforeLast('.') else it }.trim()
    val folder = clean.substringBeforeLast('/', "").substringAfterLast('/').trim()
    if (folder.isNotEmpty()) return folder to stem.ifEmpty { null }
    val dash = stem.lastIndexOf(" - ")
    return if (dash > 0) stem.substring(0, dash).trim() to stem.substring(dash + 3).trim().ifEmpty { null } else stem to null
}

/** La canción de [songs] que corresponde a [name] (por título o por nombre de archivo, sin tildes ni mayúsculas). */
fun findSongByName(name: String, songs: List<SongSummary>): SongSummary? {
    val key = normalizeKey(name)
    if (key.isEmpty()) return null
    return songs.firstOrNull { normalizeKey(it.id) == key } ?: songs.firstOrNull { normalizeKey(it.title) == key }
}

/**
 * Asocia los archivos de partituras con las canciones. Devuelve las partituras y los archivos que no
 * corresponden a ninguna canción (para avisarle al operador).
 */
fun matchScores(files: List<String>, songs: List<SongSummary>): Pair<List<ScoreRef>, List<String>> {
    val found = mutableListOf<ScoreRef>()
    val orphan = mutableListOf<String>()
    for (f in files) {
        val (songPart, instrument) = splitMusicFileName(f)
        val song = findSongByName(songPart, songs)
        when {
            song != null -> found += ScoreRef(song.id, instrument ?: GENERAL_INSTRUMENT, f)
            // "Bajo - otra cosa.pdf" sin canción: quizá el guion es parte del título.
            else -> findSongByName(f.substringAfterLast('/').substringBeforeLast('.'), songs)
                ?.let { found += ScoreRef(it.id, GENERAL_INSTRUMENT, f) } ?: run { orphan += f }
        }
    }
    return found.sortedWith(compareBy({ normalizeKey(it.songId) }, { normalizeKey(it.instrument) })) to orphan
}

/** Instrumentos distintos (sin repetir por tildes o mayúsculas), con "General" al final. */
fun instrumentsOf(scores: List<ScoreRef>): List<String> =
    scores.map { it.instrument }.distinctBy { normalizeKey(it) }
        .sortedWith(compareBy({ normalizeKey(it) == normalizeKey(GENERAL_INSTRUMENT) }, { normalizeKey(it) }))

/** La partitura de [songId] para [instrument]; si no hay, la general; si tampoco, null. */
fun scoreFor(scores: List<ScoreRef>, songId: String, instrument: String?): ScoreRef? {
    val mine = scores.filter { it.songId == songId }
    if (mine.isEmpty()) return null
    return instrument?.let { i -> mine.firstOrNull { normalizeKey(it.instrument) == normalizeKey(i) } }
        ?: mine.firstOrNull { normalizeKey(it.instrument) == normalizeKey(GENERAL_INSTRUMENT) }
}

/** Cambiar de tono los acordes ("[G]Cuán grande") y mostrarlos encima de la letra. */
object Transpose {
    private val SHARPS = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    private val FLATS = listOf("C", "Db", "D", "Eb", "E", "F", "Gb", "G", "Ab", "A", "Bb", "B")
    private val NOTE = Regex("""^([A-G])([#b]?)""")
    /** Tonos que se escriben con bemoles (Fa, Si♭, Mi♭, La♭, Re♭ y sus relativos menores). */
    private val FLAT_KEYS = setOf(5, 10, 3, 8, 1)

    fun noteIndex(note: String): Int? {
        val m = NOTE.find(note) ?: return null
        val base = SHARPS.indexOf(m.groupValues[1])
        return (base + when (m.groupValues[2]) { "#" -> 1; "b" -> -1; else -> 0 }).mod(12)
    }

    private fun move(note: String, semis: Int, flats: Boolean): String {
        val i = noteIndex(note) ?: return note
        return (if (flats) FLATS else SHARPS)[(i + semis).mod(12)]
    }

    /** "F#m7/C#" subido [semis] semitonos. [flats] escribir con bemoles (null = como venía). */
    fun chord(chord: String, semis: Int, flats: Boolean? = null): String {
        if (semis.mod(12) == 0 && flats == null) return chord
        val useFlats = flats ?: (chord.getOrNull(1) == 'b')
        val root = NOTE.find(chord) ?: return chord
        var out = move(root.value, semis, useFlats) + chord.substring(root.value.length)
        val slash = out.lastIndexOf('/')
        if (slash >= 0) {
            val bass = NOTE.find(out.substring(slash + 1))
            if (bass != null) out = out.substring(0, slash + 1) + move(bass.value, semis, useFlats) + out.substring(slash + 1 + bass.value.length)
        }
        return out
    }

    /** Primer acorde de la canción: su tono (p. ej. "G" o "Em"), o null si no tiene acordes. */
    fun keyOf(slides: List<Slide>): String? {
        val first = slides.firstNotNullOfOrNull { s -> SongParser.CHORD.find(s.chords)?.groupValues?.get(1) } ?: return null
        val root = NOTE.find(first)?.value ?: return null
        val minor = first.removePrefix(root).let { it.startsWith("m") && !it.startsWith("maj") }
        return root + if (minor) "m" else ""
    }

    /** ¿Escribir con bemoles en el tono que queda al subir [semis] desde [key]? */
    fun flatsFor(key: String?, semis: Int): Boolean? {
        val i = key?.let(::noteIndex) ?: return null
        // Para el relativo mayor de un tono menor se cuentan 3 semitonos arriba (Re m → Fa).
        val major = (i + semis + if (key.endsWith("m")) 3 else 0).mod(12)
        return major in FLAT_KEYS
    }

    /** El tono [key] subido [semis] semitonos ("G" +2 → "A"). */
    fun key(key: String, semis: Int): String = chord(key, semis, flatsFor(key, semis))

    /** Una línea con acordes en línea, en otro tono. */
    fun line(line: String, semis: Int, flats: Boolean?): String =
        if (semis.mod(12) == 0) line
        else SongParser.CHORD.replace(line) { m -> "[" + chord(m.groupValues[1], semis, flats) + "]" }

    /**
     * Para mostrar con letra de ancho fijo: la fila de acordes y la fila de letra, alineados
     * ("[G]Señor mi [C]Dios" → "G        C" / "Señor mi Dios"). Si dos acordes se pisan, se separa la letra.
     * La fila de letra es "" si la línea solo tiene acordes.
     */
    fun layout(line: String): Pair<String, String> {
        val chords = StringBuilder()
        val text = StringBuilder()
        var i = 0
        for (m in SongParser.CHORD.findAll(line)) {
            text.append(line, i, m.range.first)
            if (chords.isNotEmpty() && chords.length >= text.length) text.append(" ".repeat(chords.length - text.length + 1))
            while (chords.length < text.length) chords.append(' ')
            chords.append(m.groupValues[1])
            i = m.range.last + 1
        }
        text.append(line.substring(i))
        return chords.toString().trimEnd() to text.toString().trimEnd().let { if (it.isBlank()) "" else it }
    }
}
