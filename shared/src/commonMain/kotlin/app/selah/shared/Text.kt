package app.selah.shared

private val ACCENTS = mapOf(
    'á' to 'a', 'é' to 'e', 'í' to 'i', 'ó' to 'o', 'ú' to 'u', 'ü' to 'u', 'ñ' to 'n',
    'à' to 'a', 'è' to 'e', 'ì' to 'i', 'ò' to 'o', 'ù' to 'u', 'â' to 'a', 'ê' to 'e',
    'î' to 'i', 'ô' to 'o', 'û' to 'u', 'ç' to 'c',
)

/** Minúsculas y sin tildes, conservando espacios: para buscar canciones. */
fun normalizeText(s: String): String = buildString(s.length) {
    for (c in s.lowercase()) append(ACCENTS[c] ?: c)
}

/** Solo letras y números, sin tildes ni espacios: para comparar nombres de libros. */
fun normalizeKey(s: String): String = buildString(s.length) {
    for (c in s.lowercase()) {
        val m = ACCENTS[c] ?: c
        if (m in 'a'..'z' || m in '0'..'9') append(m)
    }
}

/** Filtra canciones por título o primera línea; todas las palabras deben aparecer. */
fun List<SongSummary>.search(query: String): List<SongSummary> {
    val words = normalizeText(query).split(' ').filter { it.isNotBlank() }
    if (words.isEmpty()) return this
    return filter { s ->
        val hay = normalizeText(s.title + " " + s.firstLine)
        words.all { it in hay }
    }
}

/** Una canción encontrada; [snippet] es la línea de la letra donde apareció (vacío si fue por el título). */
@kotlinx.serialization.Serializable
data class SongHit(val id: String, val title: String, val firstLine: String = "", val snippet: String = "")

/**
 * Busca en el título y en toda la letra (sin tildes ni mayúsculas; todas las palabras deben aparecer).
 * Primero las que coinciden por título o primera línea, después las que coinciden por la letra,
 * con la línea donde aparece ("…me gozaré en tu presencia…") para reconocerla.
 */
fun searchSongsFull(songs: Collection<Song>, query: String, limit: Int = 100): List<SongHit> {
    val words = normalizeText(query).split(' ').filter { it.isNotBlank() }
    val sorted = songs.sortedBy { normalizeText(it.title) }
    if (words.isEmpty()) return sorted.take(limit).map { it.hit("") }
    val byTitle = mutableListOf<SongHit>()
    val byLyrics = mutableListOf<SongHit>()
    for (song in sorted) {
        val s = song.summary()
        if (words.all { it in normalizeText(s.title + " " + s.firstLine) }) { byTitle += song.hit(""); continue }
        val lines = song.slides.asSequence().flatMap { it.text.lineSequence() }.distinct().toList()
        if (!words.all { w -> lines.any { w in normalizeText(it) } }) continue
        // La línea con más palabras buscadas (y, si se puede, con la frase seguida)
        val phrase = words.joinToString(" ")
        val best = lines.maxByOrNull { l -> val n = normalizeText(l); (if (phrase in n) 100 else 0) + words.count { it in n } }.orEmpty()
        byLyrics += song.hit(best.trim())
    }
    return (byTitle + byLyrics).take(limit)
}

private fun Song.hit(snippet: String) = summary().let { SongHit(it.id, it.title, it.firstLine, snippet) }

/**
 * Los primeros [n] caracteres sin partir un emoji (u otro carácter de dos mitades): si el corte cae en medio,
 * se deja fuera la mitad suelta (un nombre de archivo con media pareja falla o sale con basura).
 */
fun String.takeWhole(n: Int): String {
    val cut = take(n)
    return if (cut.isNotEmpty() && cut.last().isHighSurrogate()) cut.dropLast(1) else cut
}

/** Nombre seguro para archivo (sin / \\ : * ? " < > |), o null si queda vacío. Lo usan el PC y la demostración. */
fun cleanListName(name: String): String? =
    name.replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), "-").trim().trim('.').takeWhole(80).trim().ifEmpty { null }?.let(::avoidReservedName)

/** Nombres que Windows reserva para dispositivos (CON, NUL, COM1…): un archivo así no se guarda. */
private val RESERVED_NAMES = setOf("CON", "PRN", "AUX", "NUL") + (1..9).flatMap { listOf("COM$it", "LPT$it") }

/** "CON" → "CON_", "nul.txt" → "nul_.txt". Los demás nombres no cambian. */
fun avoidReservedName(name: String): String {
    val stem = name.substringBefore('.')
    return if (stem.trim().uppercase() in RESERVED_NAMES) stem + "_" + name.drop(stem.length) else name
}

