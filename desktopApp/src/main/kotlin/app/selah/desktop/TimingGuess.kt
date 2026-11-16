package app.selah.desktop

import app.selah.shared.Slide
import app.selah.shared.SongParser
import app.selah.shared.normalizeText
import kotlin.math.roundToLong

/**
 * Sugiere el orden de las secciones y los tiempos de la letra a partir de la voz guía de la secuencia
 * ("Verso… uno, dos, tres, cuatro", "Coro…", "Repetir", "Final"). El operador lo revisa: si algo quedó mal, graba.
 */
object TimingGuess {
    enum class Kind { VERSE, PRE, CHORUS, BRIDGE, END, REPEAT, INSTRUMENTAL }

    /** Un aviso de la guía: qué parte viene y a qué hora del audio empieza (ms). */
    data class Cue(val kind: Kind, val saidAt: Int, val startMs: Long)

    /** Orden de secciones (etiquetas de la canción) y el tiempo de cada diapositiva de ese orden. */
    data class Suggestion(val order: List<String>, val timing: List<Int>, val cues: List<Cue>)

    private fun kindOfWord(w: String): Kind? = when (w) {
        "verso", "estrofa", "pregón" -> Kind.VERSE
        "coro" -> Kind.CHORUS
        "puente" -> Kind.BRIDGE
        "final" -> Kind.END
        "repetir", "repite" -> Kind.REPEAT
        "intro", "instrumental", "interludio", "solo", "toda", "banda" -> Kind.INSTRUMENTAL
        else -> null
    }

    private val COUNT = setOf("uno", "dos", "tres", "cuatro")

    /**
     * Los avisos con la hora en que empieza cada parte. [beatMs]/[offsetMs]: el pulso del click (si se conoce),
     * para poner cada comienzo justo en un tiempo.
     */
    fun cues(words: List<GuideVoice.Word>, beatMs: Double?, offsetMs: Int): List<Cue> {
        data class Raw(val kind: Kind, val at: Int, var counted: Long?)
        val raw = mutableListOf<Raw>()
        var i = 0
        while (i < words.size) {
            val w = words[i]
            var kind = kindOfWord(w.word)
            if (w.word == "pre" && words.getOrNull(i + 1)?.word == "coro") { kind = Kind.PRE; i++ }
            if (kind == null) { i++; continue }
            // Varias palabras juntas ("bajar intensidad coro", "instrumental final", "toda la banda"): una sola parte.
            val last = raw.lastOrNull()
            if (last != null && w.startMs - last.at < 1_800) {
                val better = when {
                    last.kind == Kind.INSTRUMENTAL && kind != Kind.INSTRUMENTAL -> kind
                    last.kind == Kind.REPEAT && kind != Kind.REPEAT && kind != Kind.INSTRUMENTAL -> kind
                    else -> last.kind
                }
                raw[raw.lastIndex] = last.copy(kind = better)
                i++; continue
            }
            raw += Raw(kind, w.startMs, null)
            i++
        }
        // La cuenta que sigue a cada aviso: la parte empieza un tiempo después del "cuatro".
        for ((k, r) in raw.withIndex()) {
            val limit = raw.getOrNull(k + 1)?.at ?: Int.MAX_VALUE
            val count = words.filter { it.startMs > r.at && it.startMs < minOf(limit, r.at + 6_000) && it.word in COUNT }
            val four = count.lastOrNull { it.word == "cuatro" } ?: continue
            val three = count.lastOrNull { it.word == "tres" && it.startMs < four.startMs }
            val beat = beatMs ?: three?.let { (four.startMs - it.startMs).toDouble() } ?: continue
            r.counted = (four.startMs + beat).roundToLong()
        }
        // Sin cuenta: la distancia típica entre el aviso y el comienzo (la de los que sí tienen cuenta).
        val gaps = raw.mapNotNull { r -> r.counted?.let { it - r.at } }.sorted()
        val gap = gaps.getOrNull(gaps.size / 2) ?: beatMs?.let { (it * 8).roundToLong() } ?: 4_000L
        val starts = raw.map { it.counted ?: (it.at + gap) }.toMutableList()
        // "Repetir" sin cuenta: las repeticiones se reparten parejo entre la parte anterior y la siguiente.
        var k = 0
        while (k < raw.size) {
            if (raw[k].kind == Kind.REPEAT && raw[k].counted == null && k > 0) {
                var end = k
                while (end + 1 < raw.size && raw[end + 1].kind == Kind.REPEAT && raw[end + 1].counted == null) end++
                val from = starts[k - 1]
                val to = starts.getOrNull(end + 1)
                if (to != null && to > from) {
                    val n = end - k + 2
                    for (j in k..end) starts[j] = from + (to - from) * (j - k + 1) / n
                }
                k = end + 1
            } else k++
        }
        // Al tiempo más cercano del click.
        val snapped = starts.map { t -> if (beatMs == null) t else (offsetMs + ((t - offsetMs) / beatMs).roundToLong() * beatMs).roundToLong() }
        return raw.indices.map { Cue(raw[it].kind, raw[it].at, snapped[it]) }.sortedBy { it.startMs }
    }

    private fun kindOfLabel(label: String): Kind? {
        val l = normalizeText(label)
        return when {
            "final" in l || l.startsWith("cierre") -> Kind.END
            l.startsWith("precoro") || l.startsWith("pre coro") -> Kind.PRE
            l.startsWith("coro") -> Kind.CHORUS
            l.startsWith("estrofa") || l.startsWith("verso") || l.startsWith("pregon") -> Kind.VERSE
            l.startsWith("puente") -> Kind.BRIDGE
            else -> null
        }
    }

    /**
     * El orden y los tiempos para la canción [songText] (su texto, sin importar su "Orden:"). [unitMs]: cuánto dura una
     * unidad de "Tiempos:" (un tiempo del click, o un segundo si no hay); [offsetMs]: dónde cae el tiempo 0;
     * [durationMs]: lo que dura el audio. null si la canción no tiene secciones que reconocer.
     */
    fun suggest(songText: String, cues: List<Cue>, unitMs: Double, offsetMs: Int, durationMs: Long): Suggestion? {
        val noOrder = songText.lines().filterNot { Regex("""^\s*orden\s*:""", RegexOption.IGNORE_CASE).containsMatchIn(it) }.joinToString("\n")
        val slides = SongParser.parse("x", noOrder).slides
        // Secciones de la canción, en orden, con sus diapositivas.
        val sections = mutableListOf<Pair<String, MutableList<Slide>>>()
        for (s in slides) {
            if (s.label.isNotEmpty() || sections.isEmpty()) sections += (s.label.ifEmpty { SongParser.START_LABEL }) to mutableListOf()
            sections.last().second += s
        }
        val byKind = sections.groupBy { kindOfLabel(it.first) }
        if (byKind.keys.filterNotNull().isEmpty()) return null
        val used = mutableMapOf<Kind, Int>()
        var lastLyric: Pair<String, MutableList<Slide>>? = null
        var lastKind: Kind? = null
        data class Part(val section: Pair<String, MutableList<Slide>>?, val start: Long)
        val parts = mutableListOf<Part>()
        for (c in cues) {
            val section = when (c.kind) {
                Kind.INSTRUMENTAL -> null
                Kind.REPEAT -> if (lastKind == Kind.INSTRUMENTAL) null else lastLyric
                else -> {
                    val options = byKind[c.kind] ?: when (c.kind) {
                        Kind.END -> byKind[Kind.CHORUS]?.takeLast(1)
                        Kind.PRE, Kind.BRIDGE -> null
                        else -> null
                    }
                    options?.let { o ->
                        // Estrofa 1, Estrofa 2…: cada aviso nuevo pasa a la siguiente (y vuelve a la primera).
                        val n = used.getOrDefault(c.kind, 0)
                        used[c.kind] = n + 1
                        o[n % o.size]
                    }
                }
            }
            if (c.kind != Kind.REPEAT) lastKind = c.kind
            if (section != null) lastLyric = section
            parts += Part(section, c.startMs)
        }
        val order = mutableListOf<String>()
        val timing = mutableListOf<Int>()
        fun units(ms: Double) = ((ms - offsetMs) / unitMs).toInt().coerceAtLeast(0)
        for ((k, p) in parts.withIndex()) {
            val sec = p.section ?: continue
            val end = parts.getOrNull(k + 1)?.start ?: durationMs.takeIf { it > p.start } ?: (p.start + 20_000)
            order += sec.first
            // Cada diapositiva de la sección según cuántas líneas tiene.
            val lines = sec.second.map { maxOf(1, it.text.lines().count { l -> l.isNotBlank() }) }
            val total = lines.sum().toDouble()
            var acc = 0
            for (n in lines) {
                timing += units(p.start + (end - p.start) * acc / total)
                acc += n
            }
        }
        if (order.isEmpty()) return null
        for (j in 1 until timing.size) if (timing[j] < timing[j - 1]) timing[j] = timing[j - 1]
        return Suggestion(order, timing, cues)
    }
}
