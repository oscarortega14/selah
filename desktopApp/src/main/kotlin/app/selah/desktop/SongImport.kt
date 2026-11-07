package app.selah.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.w3c.dom.Element
import org.w3c.dom.Node
import javax.xml.parsers.DocumentBuilderFactory

/** Una canción traída de otro programa, ya en el formato de texto de Selah. */
data class ImportedSong(val title: String, val text: String)

/**
 * Convierte canciones de otros programas al formato de Selah:
 * - OpenLyrics (.xml): lo que exporta OpenLP (y otros) — una canción por archivo.
 * - Holyrics File JSON (.json): lista de canciones con título y párrafos.
 */
object SongImport {

    /** OpenLyrics → canción. null si el XML no es una canción OpenLyrics. */
    fun fromOpenLyrics(xml: String): ImportedSong? {
        val doc = runCatching {
            val f = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                // Archivos de afuera: sin DTD ni entidades externas.
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                isExpandEntityReferences = false
            }
            f.newDocumentBuilder().parse(xml.byteInputStream(Charsets.UTF_8))
        }.getOrNull() ?: return null
        val root = doc.documentElement
        if (root.localName != "song") return null

        val title = root.descendants("title").firstOrNull()?.textContent?.trim().orEmpty().ifEmpty { return null }
        val verses = LinkedHashMap<String, List<List<String>>>() // nombre → diapositivas (cada una, líneas)
        for (verse in root.descendants("verse")) {
            val name = verse.getAttribute("name").trim().lowercase().ifEmpty { "v${verses.size + 1}" }
            if (name in verses) continue // la misma estrofa en otro idioma
            val slides = verse.children("lines").map { linesOf(it) }.filter { it.isNotEmpty() }
            if (slides.isNotEmpty()) verses[name] = slides
        }
        if (verses.isEmpty()) return null

        val order = root.descendants("verseOrder").firstOrNull()?.textContent.orEmpty()
            .split(Regex("\\s+")).map { it.lowercase() }.filter { it in verses }
        val authors = root.descendants("author").map { it.textContent.trim() }.filter { it.isNotEmpty() }.distinct()
        val ccli = root.descendants("ccliNo").firstOrNull()?.textContent?.trim()?.takeIf { it.matches(Regex("\\d{1,10}")) }
        val text = buildString {
            append("# ").append(title).append('\n')
            if (authors.isNotEmpty()) append("Autor: ").append(authors.joinToString(", ")).append('\n')
            if (ccli != null) append("CCLI: ").append(ccli).append('\n')
            if (order.isNotEmpty()) append("Orden: ").append(order.joinToString(", ") { label(it) }).append('\n')
            for ((name, slides) in verses) {
                slides.forEachIndexed { i, lines ->
                    append('\n')
                    if (i == 0) append(label(name)).append(":\n")
                    lines.forEach { append(it).append('\n') }
                }
            }
        }
        return ImportedSong(title, text)
    }

    /** Holyrics File JSON → canciones (acepta una lista o una sola canción). */
    fun fromHolyricsJson(json: String): List<ImportedSong> {
        val root = runCatching { Json.parseToJsonElement(json) }.getOrNull() ?: return emptyList()
        val items = when (root) {
            is JsonArray -> root.toList()
            is JsonObject -> (root["songs"] as? JsonArray)?.toList() ?: listOf(root)
            else -> emptyList()
        }
        return items.mapNotNull { (it as? JsonObject)?.let(::holyricsSong) }
    }

    private fun holyricsSong(o: JsonObject): ImportedSong? {
        val title = o.str("title")?.trim().orEmpty().ifEmpty { return null }
        val lyrics = o["lyrics"]
        val blocks: List<Pair<String, String>> = when (lyrics) {
            is JsonObject -> (lyrics["paragraphs"] as? JsonArray).orEmpty().mapNotNull { p ->
                val obj = p as? JsonObject ?: return@mapNotNull null
                val text = obj.str("text")?.let(::cleanBlock).orEmpty()
                if (text.isEmpty()) null else (obj.str("description")?.trim().orEmpty() to text)
            }.ifEmpty { lyrics.str("full_text")?.let(::splitBlocks).orEmpty().map { "" to it } }
            is JsonPrimitive -> lyrics.contentOrNull?.let(::splitBlocks).orEmpty().map { "" to it }
            else -> emptyList()
        }
        if (blocks.isEmpty()) return null
        val text = buildString {
            append("# ").append(title).append('\n')
            for ((label, block) in blocks) {
                append('\n')
                if (label.isNotEmpty() && label.length <= 30) append(label.trimEnd(':')).append(":\n")
                append(block).append('\n')
            }
        }
        return ImportedSong(title, text)
    }

    // --- utilidades ---

    /** "v1" → "Verso 1", "c" → "Coro", "c2" → "Coro 2", "b" → "Puente"… */
    internal fun label(name: String): String {
        val m = Regex("""^([a-z]+?)(\d*)([a-z]?)$""").matchEntire(name) ?: return name.replaceFirstChar { it.uppercase() }
        val (type, num, part) = m.destructured
        val word = when (type) {
            "v" -> "Verso"; "c" -> "Coro"; "p" -> "Pre-coro"; "b" -> "Puente"
            "e" -> "Final"; "i" -> "Intro"; "o" -> "Otro"
            else -> type.replaceFirstChar { it.uppercase() }
        }
        return listOf(word, num, part).filter { it.isNotEmpty() }.joinToString(" ")
    }

    /** Líneas de un <lines>: <br/> corta la línea; los acordes y comentarios no se proyectan. */
    private fun linesOf(lines: Element): List<String> {
        val sb = StringBuilder()
        // Recorrido con una pila propia (no recursivo): un XML anidado 10.000 niveles no agota la pila.
        val stack = ArrayDeque<Node>()
        fun pushChildren(n: Node) {
            val kids = mutableListOf<Node>()
            var c = n.firstChild; while (c != null) { kids += c; c = c.nextSibling }
            for (k in kids.asReversed()) stack.addLast(k)
        }
        pushChildren(lines)
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            when {
                n.nodeType == Node.TEXT_NODE || n.nodeType == Node.CDATA_SECTION_NODE -> sb.append(n.nodeValue)
                n.nodeType == Node.ELEMENT_NODE && n.localName == "br" -> sb.append('\u0000')
                n.nodeType == Node.ELEMENT_NODE && n.localName == "comment" -> {}
                // Acordes de OpenLyrics → formato ChordPro "[D]" (los músicos los ven en la vista de escenario)
                n.nodeType == Node.ELEMENT_NODE && n.localName == "chord" -> {
                    (n as Element).getAttribute("name").trim().takeIf { it.isNotEmpty() }?.let { sb.append('[').append(it).append(']') }
                    pushChildren(n)
                }
                else -> pushChildren(n)
            }
        }
        return sb.toString().replace(Regex("\\s+"), " ").split('\u0000').map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun cleanBlock(s: String) = s.replace("\r\n", "\n").replace('\r', '\n')
        .lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")

    private fun splitBlocks(s: String) = s.replace("\r\n", "\n").split(Regex("\n\\s*\n")).map(::cleanBlock).filter { it.isNotEmpty() }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonElement.str(key: String): String? = (this as? JsonObject)?.str(key)

    private fun Element.descendants(local: String): List<Element> {
        val nl = getElementsByTagNameNS("*", local)
        return (0 until nl.length).map { nl.item(it) as Element }
    }

    private fun Element.children(local: String): List<Element> {
        val out = mutableListOf<Element>()
        var c = firstChild
        while (c != null) { if (c is Element && c.localName == local) out += c; c = c.nextSibling }
        return out
    }
}
