package app.selah.shared

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Los primeros 39 libros son el Antiguo Testamento. */
const val OLD_TESTAMENT_BOOKS = 39

/**
 * Posiciones de [names] que son del Antiguo Testamento. Se decide por el nombre del libro
 * (una Biblia puede traer solo el Nuevo Testamento, o la demostración solo algunos libros);
 * si los nombres no son los de siempre (una Biblia en JSON con otros nombres), por la posición.
 */
fun oldTestamentIndices(names: List<String>): Set<Int> {
    val canonical = names.map { Bible.BOOK_NAMES.indexOf(it) }
    return if (canonical.all { it >= 0 }) names.indices.filter { canonical[it] < OLD_TESTAMENT_BOOKS }.toSet()
    else names.indices.filter { it < OLD_TESTAMENT_BOOKS }.toSet()
}

/**
 * Posición del versículo [verse] (1…) entre las diapositivas del capítulo ya proyectado
 * (los versículos vacíos no se proyectan, así que no cuentan).
 */
fun slideIndexOfVerse(chapter: List<String>, verse: Int): Int =
    chapter.take(verse - 1).count { it.isNotBlank() }

/**
 * Una cita ya interpretada, ej. "1 Corintios 13:4-7".
 * [chapterTo] permite cruzar capítulos ("Mateo 5:3-6:4") o tomar varios enteros ("Salmos 1-2").
 */
data class BibleReference(
    val book: String,
    val chapter: Int,
    val verseFrom: Int? = null,
    val verseTo: Int? = null,
    val chapterTo: Int? = null,
) {
    companion object {
        // libro (puede empezar con 1-3), capítulo, versículo opcional y "hasta" opcional (versículo o capítulo:versículo)
        private val RX = Regex("""^\s*([1-3]?\s*[^\d\s;][^\d;]*?)\.?\s*(\d+)(?:\s*[:.,\s]\s*(\d+))?(?:\s*[-–]\s*(?:(\d+)\s*[:.]\s*)?(\d+))?\s*$""")
        // Continuación sin libro tras ";": "5:24", "18", "18-20", "6:1-7:2"
        private val CONT = Regex("""^\s*(\d+)(?:\s*[:.]\s*(\d+))?(?:\s*[-–]\s*(?:(\d+)\s*[:.]\s*)?(\d+))?\s*$""")

        /** Una sola cita (sin ";"). */
        fun parse(query: String): BibleReference? = parseAll(query)?.singleOrNull()

        /**
         * Una o varias citas separadas por ";": "Juan 3:16; Romanos 5:8", "Juan 3:16; 5:24" (mismo libro),
         * "Juan 3:16; 18" (mismo capítulo). null si alguna parte no se entiende.
         */
        fun parseAll(query: String): List<BibleReference>? {
            val parts = query.split(';').map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.isEmpty()) return null
            val out = mutableListOf<BibleReference>()
            for (part in parts) {
                val full = RX.matchEntire(part)
                if (full != null) {
                    val (book, ch, from, toCh, to) = full.destructured
                    out += make(book.trim(), ch.toInt(), from.toIntOrNull(), toCh.toIntOrNull(), to.toIntOrNull())
                    continue
                }
                val prev = out.lastOrNull() ?: return null
                val m = CONT.matchEntire(part) ?: return null
                val (a, b, toCh, to) = m.destructured
                val prevEnd = prev.chapterTo ?: prev.chapter
                out += if (b.isEmpty() && prev.verseFrom != null) {
                    // "18" o "18-20" después de una cita con versículos: versículos del mismo capítulo
                    make(prev.book, prevEnd, a.toInt(), toCh.toIntOrNull(), to.toIntOrNull())
                } else {
                    make(prev.book, a.toInt(), b.toIntOrNull(), toCh.toIntOrNull(), to.toIntOrNull())
                }
            }
            return out
        }

        private fun make(book: String, chapter: Int, from: Int?, toChapter: Int?, to: Int?): BibleReference = when {
            toChapter != null -> BibleReference(book, chapter, from, to, toChapter.takeIf { it != chapter })
            from == null && to != null -> BibleReference(book, chapter, null, null, to.takeIf { it != chapter }) // "Salmos 1-2"
            else -> BibleReference(book, chapter, from, to)
        }
    }
}

class Bible(val name: String, val books: List<Book>) {

    class Book(val name: String, val chapters: List<List<String>>) {
        internal val key = normalizeKey(name)
    }

    data class Passage(val title: String, val slides: List<Slide>)

    /**
     * Busca una o varias citas: "Juan 3:16", "sal 23", "1 co 13 4-7", "Mateo 5:3-6:4", "Salmos 1-2",
     * "Juan 3:16; Romanos 5:8". Lanza IllegalArgumentException con un mensaje claro.
     */
    fun lookup(query: String): Passage {
        val refs = BibleReference.parseAll(query)
            ?: throw IllegalArgumentException("No entiendo la cita \"$query\". Ejemplo: Juan 3:16")
        val slides = mutableListOf<Slide>()
        val titles = mutableListOf<String>()
        var lastBook: String? = null
        for (ref in refs) {
            val (title, part) = passage(ref)
            // "Juan 3:16; 5:24": el libro no se repite en el título
            val bookName = part.first().label.substringBeforeLast(' ')
            titles += if (bookName == lastBook) title.removePrefix("$bookName ") else title
            lastBook = bookName
            slides += part
            if (slides.size > MAX_VERSES) {
                throw IllegalArgumentException("La lectura es muy larga (más de $MAX_VERSES versículos). Divídela en partes.")
            }
        }
        return Passage(titles.joinToString("; "), slides)
    }

    private fun passage(ref: BibleReference): Pair<String, List<Slide>> {
        val bookIndex = findBook(ref.book)
            ?: throw IllegalArgumentException("No encontré el libro \"${ref.book}\"")
        val book = books[bookIndex]
        val lastChapter = ref.chapterTo ?: ref.chapter
        for (c in listOf(ref.chapter, lastChapter)) {
            if (book.chapters.getOrNull(c - 1) == null) throw IllegalArgumentException("${book.name} no tiene capítulo $c")
        }
        if (lastChapter < ref.chapter) throw IllegalArgumentException("${book.name} ${ref.chapter}-$lastChapter: el final va antes del inicio")
        val first = book.chapters[ref.chapter - 1]
        val from = ref.verseFrom ?: 1
        if (from !in 1..first.size) throw IllegalArgumentException("${book.name} ${ref.chapter} no tiene el versículo $from")

        val slides = mutableListOf<Slide>()
        var to = 0
        for (c in ref.chapter..lastChapter) {
            val verses = book.chapters[c - 1]
            val start = if (c == ref.chapter) from else 1
            val end = when {
                c != lastChapter -> verses.size
                ref.verseTo != null -> ref.verseTo.coerceAtMost(verses.size)
                ref.verseFrom != null && ref.chapterTo == null -> from // un solo versículo
                else -> verses.size
            }
            if (c == lastChapter) to = end
            if (end < start) {
                if (c == ref.chapter) throw IllegalArgumentException("${book.name} ${ref.chapter}:$from-${ref.verseTo}: el final va antes del inicio")
                continue
            }
            // Algunas traducciones dejan versículos vacíos (numeración distinta); no se proyectan.
            for (v in start..end) {
                verses[v - 1].trim().takeIf { it.isNotEmpty() }?.let { slides += Slide("${book.name} $c:$v", it) }
            }
        }
        if (slides.isEmpty()) {
            throw IllegalArgumentException("${book.name} ${ref.chapter}:$from no tiene texto en esta traducción")
        }
        val title = buildString {
            append(book.name).append(' ').append(ref.chapter)
            when {
                ref.chapterTo != null && ref.verseFrom == null && ref.verseTo == null -> append('-').append(lastChapter)
                ref.chapterTo != null -> append(':').append(from).append('-').append(lastChapter).append(':').append(to)
                ref.verseFrom != null -> {
                    append(':').append(from)
                    if (to != from) append('-').append(to)
                }
            }
        }
        return title to slides
    }

    /** Índice de búsqueda (texto normalizado de cada versículo), se arma la primera vez. */
    private val searchIndex: List<Pair<BibleHit, String>> by lazy {
        books.flatMap { book ->
            book.chapters.flatMapIndexed { c, verses ->
                verses.mapIndexedNotNull { v, text ->
                    text.trim().takeIf { it.isNotEmpty() }?.let { BibleHit("${book.name} ${c + 1}:${v + 1}", it) to normalizeText(it) }
                }
            }
        }
    }

    /** Arma el índice de búsqueda ya (en segundo plano), para que la primera búsqueda no espere. */
    fun warmUp(): Int = searchIndex.size

    /**
     * Busca versículos que contengan todas las palabras (sin importar tildes ni mayúsculas).
     * Primero los que tienen la frase exacta, luego el resto en orden bíblico.
     * Devuelve hasta [limit] resultados y el total encontrado.
     */
    fun search(query: String, limit: Int = 50): Pair<List<BibleHit>, Int> {
        val phrase = normalizeText(query).split(' ').filter { it.isNotBlank() }.joinToString(" ")
        val words = phrase.split(' ').filter { it.length >= 2 }
        if (words.isEmpty()) return emptyList<BibleHit>() to 0
        val matches = searchIndex.filter { (_, norm) -> words.all { it in norm } }
        val (exact, rest) = matches.partition { (_, norm) -> phrase in norm }
        return (exact + rest).take(limit).map { it.first } to matches.size
    }

    /** Libros con su número de capítulos, para los exploradores de los remotos. */
    fun bookInfos(): List<BibleBookInfo> = books.map { BibleBookInfo(it.name, it.chapters.size) }

    /** Versículos de un capítulo, o null si no existe. */
    fun chapter(book: String, chapter: Int): List<String>? =
        findBook(book)?.let { books[it].chapters.getOrNull(chapter - 1) }

    fun findBook(query: String): Int? {
        val q = normalizeKey(query)
        if (q.isEmpty()) return null
        ALIASES[q]?.let { alias -> books.indexOfFirst { it.key == alias }.takeIf { it >= 0 }?.let { return it } }
        books.indexOfFirst { it.key == q }.takeIf { it >= 0 }?.let { return it }
        return books.indexOfFirst { it.key.startsWith(q) }.takeIf { it >= 0 }
    }

    companion object {
        /** Tope para que una lectura no sea enorme (Salmos 1-150 son 2.461 versículos). */
        const val MAX_VERSES = 400

        val BOOK_NAMES = listOf(
            "Génesis", "Éxodo", "Levítico", "Números", "Deuteronomio", "Josué", "Jueces", "Rut",
            "1 Samuel", "2 Samuel", "1 Reyes", "2 Reyes", "1 Crónicas", "2 Crónicas", "Esdras",
            "Nehemías", "Ester", "Job", "Salmos", "Proverbios", "Eclesiastés", "Cantares", "Isaías",
            "Jeremías", "Lamentaciones", "Ezequiel", "Daniel", "Oseas", "Joel", "Amós", "Abdías",
            "Jonás", "Miqueas", "Nahúm", "Habacuc", "Sofonías", "Hageo", "Zacarías", "Malaquías",
            "Mateo", "Marcos", "Lucas", "Juan", "Hechos", "Romanos", "1 Corintios", "2 Corintios",
            "Gálatas", "Efesios", "Filipenses", "Colosenses", "1 Tesalonicenses", "2 Tesalonicenses",
            "1 Timoteo", "2 Timoteo", "Tito", "Filemón", "Hebreos", "Santiago", "1 Pedro", "2 Pedro",
            "1 Juan", "2 Juan", "3 Juan", "Judas", "Apocalipsis",
        )

        private val ALIASES = mapOf(
            "jn" to "juan", "mt" to "mateo", "mc" to "marcos", "mr" to "marcos", "lc" to "lucas",
            "sal" to "salmos", "sl" to "salmos", "salmo" to "salmos", "stg" to "santiago",
            "heb" to "hebreos", "ap" to "apocalipsis", "apoc" to "apocalipsis", "flm" to "filemon",
            "cnt" to "cantares", "ec" to "eclesiastes", "pr" to "proverbios", "prov" to "proverbios",
        )

        private val VPL_LINE = Regex("""^(\S+)\s+(\d+):(\d+)\s+(.*)$""")
        // En la RV1909 "JEHOVÁ" va en mayúsculas siempre; todo lo demás en mayúsculas al inicio es letra capital.
        private val KEEP_UPPERCASE = setOf("JEHOVÁ", "JEHOVA")

        /**
         * Código de cada libro (posición canónica 0…65). Los de eBible.org (JOH, PHI, SOL…) y los de USFM
         * (JHN, PHP, SNG…). Los deuterocanónicos (TOB, SIR, 1MA…) no están: se saltan.
         */
        private val BOOK_CODES: Map<String, Int> = listOf(
            "GEN", "EXO", "LEV", "NUM", "DEU", "JOS", "JDG", "RUT", "1SA", "2SA", "1KI", "2KI", "1CH", "2CH", "EZR",
            "NEH", "EST", "JOB", "PSA", "PRO", "ECC", "SOL", "ISA", "JER", "LAM", "EZE", "DAN", "HOS", "JOE", "AMO", "OBA",
            "JON", "MIC", "NAH", "HAB", "ZEP", "HAG", "ZEC", "MAL",
            "MAT", "MAR", "LUK", "JOH", "ACT", "ROM", "1CO", "2CO", "GAL", "EPH", "PHI", "COL", "1TH", "2TH",
            "1TI", "2TI", "TIT", "PHM", "HEB", "JAM", "1PE", "2PE", "1JO", "2JO", "3JO", "JUD", "REV",
        ).withIndex().associate { (i, c) -> c to i } + mapOf(
            "SNG" to 21, "EZK" to 25, "JOL" to 28, "NAM" to 33, "MRK" to 40, "JHN" to 42, "PHP" to 49,
            "JAS" to 58, "1JN" to 61, "2JN" to 62, "3JN" to 63, "JDE" to 64,
        )

        /** Mínimo de libros para aceptar un archivo como Biblia (el Nuevo Testamento solo tiene 27). */
        private const val MIN_BOOKS = 27

        /** Arma la Biblia con los libros que vinieron (posición canónica → capítulo → versículo → texto). */
        private fun build(name: String, verses: Map<Int, Map<Int, Map<Int, String>>>): Bible {
            if (verses.size < MIN_BOOKS) {
                throw IllegalArgumentException("El archivo no parece una Biblia: tiene ${verses.size} libros reconocibles")
            }
            val books = verses.keys.sorted().map { b ->
                val chapters = verses.getValue(b)
                Book(
                    BOOK_NAMES[b],
                    (1..chapters.keys.max()).map { c ->
                        val vs = chapters[c].orEmpty()
                        if (vs.isEmpty()) emptyList() else (1..vs.keys.max()).map { vs[it].orEmpty() }
                    },
                )
            }
            return Bible(name, books)
        }

        /**
         * Formato VPL de eBible.org (un versículo por línea), ej. spaRV1909_vpl.txt:
         *     GEN 1:1 EN el principio crió Dios los cielos y la tierra.
         * Los libros se reconocen por su código (de eBible.org o USFM); los deuterocanónicos se saltan.
         * Si ningún código se reconoce pero hay 66 libros, se toman en el orden en que aparecen.
         */
        fun fromVpl(text: String, name: String): Bible {
            val order = mutableListOf<String>()
            val verses = mutableMapOf<String, MutableMap<Int, MutableMap<Int, String>>>()
            for (raw in text.removePrefix("﻿").lineSequence()) {
                val m = VPL_LINE.matchEntire(raw.trim()) ?: continue
                val (code, ch, v, body) = m.destructured
                if (code !in verses) { order += code; verses[code] = mutableMapOf() }
                val chapter = ch.toInt()
                val verse = v.toInt()
                verses.getValue(code).getOrPut(chapter) { mutableMapOf() }[verse] =
                    if (verse == 1) fixDropCap(body) else body
            }
            val known = order.filter { it.uppercase() in BOOK_CODES }
            val byBook: Map<Int, Map<Int, Map<Int, String>>> = when {
                known.isNotEmpty() -> known.associate { BOOK_CODES.getValue(it.uppercase()) to verses.getValue(it) }
                order.size == BOOK_NAMES.size -> order.withIndex().associate { (i, code) -> i to verses.getValue(code) }
                else -> throw IllegalArgumentException("Se esperaban 66 libros y el archivo tiene ${order.size}")
            }
            return build(name, byBook)
        }

        private val ZEFANIA_TAG = Regex("""<(BIBLEBOOK|CHAPTER|VERS)\b([^>]*?)(/?)>""", RegexOption.IGNORE_CASE)
        private val NOTE = Regex("""<NOTE\b[^>]*>.*?</NOTE>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val TAG = Regex("""<[^>]*>""")
        private val SPACES = Regex("""\s+""")

        private fun attr(attrs: String, key: String): Int? =
            Regex("""\b$key\s*=\s*["']?(\d+)""", RegexOption.IGNORE_CASE).find(attrs)?.groupValues?.get(1)?.toIntOrNull()

        /**
         * Zefania XML, el formato de Biblias gratuitas más común en internet:
         *     <XMLBIBLE><BIBLEBOOK bnumber="43"><CHAPTER cnumber="3"><VERS vnumber="16">Porque de tal…</VERS>
         * bnumber 1…66 en orden canónico; los demás (deuterocanónicos) se saltan.
         */
        fun fromZefania(text: String, name: String): Bible {
            val verses = mutableMapOf<Int, MutableMap<Int, MutableMap<Int, String>>>()
            var book = -1
            var chapter = 0
            for (m in ZEFANIA_TAG.findAll(text)) {
                val attrs = m.groupValues[2]
                when (m.groupValues[1].uppercase()) {
                    "BIBLEBOOK" -> book = (attr(attrs, "bnumber") ?: 0) - 1
                    "CHAPTER" -> chapter = attr(attrs, "cnumber") ?: 0
                    "VERS" -> {
                        if (book !in BOOK_NAMES.indices || chapter < 1 || m.groupValues[3] == "/") continue
                        val verse = attr(attrs, "vnumber") ?: continue
                        val end = text.indexOf("</VERS>", m.range.last + 1, ignoreCase = true).takeIf { it >= 0 } ?: continue
                        val body = cleanXml(text.substring(m.range.last + 1, end))
                        if (body.isNotEmpty()) verses.getOrPut(book) { mutableMapOf() }.getOrPut(chapter) { mutableMapOf() }[verse] = body
                    }
                }
            }
            return build(name, verses)
        }

        /** Texto de un versículo en XML: sin notas ni etiquetas, con los &amp; ya convertidos. */
        private fun cleanXml(s: String): String = decodeEntities(s.replace(NOTE, " ").replace(TAG, " ")).replace(SPACES, " ").trim()

        private fun decodeEntities(s: String): String {
            if ('&' !in s) return s
            return Regex("""&(#x[0-9a-fA-F]+|#\d+|amp|lt|gt|quot|apos|nbsp);""").replace(s) { m ->
                val e = m.groupValues[1]
                when {
                    e.startsWith("#x") -> e.drop(2).toIntOrNull(16)?.let { codePointToString(it) } ?: m.value
                    e.startsWith("#") -> e.drop(1).toIntOrNull()?.let { codePointToString(it) } ?: m.value
                    else -> mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ")[e]!!
                }
            }
        }

        private fun codePointToString(cp: Int): String =
            if (cp < 0x10000) cp.toChar().toString()
            else (cp - 0x10000).let { charArrayOf((0xD800 + (it shr 10)).toChar(), (0xDC00 + (it and 0x3FF)).toChar()).concatToString() }

        /** "EN el principio" -> "En el principio" (letra capital de la edición impresa). */
        internal fun fixDropCap(s: String): String {
            val first = s.substringBefore(' ')
            val isAllCaps = first.length > 1 && first.any { it.isLetter() } && first == first.uppercase()
            if (!isAllCaps || first.trimEnd(',', '.', ':', ';') in KEEP_UPPERCASE) return s
            return first[0] + first.substring(1).lowercase() + s.substring(first.length)
        }

        /**
         * Acepta dos formatos JSON:
         *  - {"name": "...", "books": [{"name": "Génesis", "chapters": [["v1", "v2"], ...]}, ...]}
         *  - [{"abbrev": "gn", "chapters": [[...]]}, ...]   (66 libros en orden canónico)
         */
        fun fromJson(text: String, fallbackName: String): Bible {
            val root = Json.parseToJsonElement(text.removePrefix("﻿"))
            val (name, array) = when (root) {
                is JsonObject -> (root["name"]?.jsonPrimitive?.contentOrNull ?: fallbackName) to
                    (root["books"]?.jsonArray ?: throw IllegalArgumentException("Falta \"books\" en el JSON"))
                is JsonArray -> fallbackName to root
                else -> throw IllegalArgumentException("Formato de Biblia no reconocido")
            }
            val books = array.mapIndexed { i, el ->
                val o = el.jsonObject
                val bookName = o["name"]?.jsonPrimitive?.contentOrNull ?: BOOK_NAMES.getOrElse(i) { "Libro ${i + 1}" }
                val chapters = (o["chapters"] ?: throw IllegalArgumentException("Falta \"chapters\" en $bookName"))
                    .jsonArray.map { ch -> ch.jsonArray.map { it.jsonPrimitive.content } }
                Book(bookName, chapters)
            }
            return Bible(name, books)
        }
    }
}
