package app.selah.desktop

import app.selah.shared.normalizeText

/**
 * Ubica la letra de la canción en lo que Whisper entendió de una grabación: qué diapositiva se canta y desde qué
 * segundo, en el orden en que se cantó (con las vueltas). Whisper se equivoca en palabras sueltas ("vindación" por
 * "habitación"), así que se compara palabra por palabra con tolerancia, siguiendo la letra.
 */
object LyricsAligner {
    /** Una sección de la canción ("Coro") con el texto de cada diapositiva. */
    data class Section(val label: String, val slides: List<String>)

    /** El orden de secciones cantado y, por cada diapositiva de ese orden, el segundo en que empieza (null: no se cantó). */
    data class Result(val order: List<String>, val starts: List<Double?>)

    /** Una diapositiva que empieza a cantarse en el segundo [at]. */
    data class Entry(val slide: Int, val at: Double)

    private data class Word(val text: String, val at: Double)

    fun align(phrases: List<HeardPhrase>, sections: List<Section>, audioSeconds: Double): Result? {
        val slides = sections.flatMap { it.slides }
        val first = sections.flatMap { s -> s.slides.indices.map { it == 0 } }
        val words = slides.map(::tokens)
        val entries = entries(clean(phrases, audioSeconds, words.flatten().toSet()), words, first)
        if (entries.isEmpty()) return null
        // Diapositiva -> su sección y su lugar en ella.
        val sectionOf = IntArray(slides.size)
        val placeInSection = IntArray(slides.size)
        var g = 0
        sections.forEachIndexed { si, s -> s.slides.indices.forEach { k -> sectionOf[g] = si; placeInSection[g] = k; g++ } }
        // Las diapositivas cantadas, agrupadas en vueltas de su sección (Estrofa, Coro, Coro…).
        val order = mutableListOf<String>()
        val starts = mutableListOf<Double?>()
        var current = -1 // sección de la vuelta actual
        var next = 0 // la diapositiva de esa sección que debería seguir
        for (e in entries) {
            val s = sectionOf[e.slide]
            val k = placeInSection[e.slide]
            if (s != current || k != next) {
                // Vuelta nueva de esa sección (las diapositivas que no se cantaron quedan sin tiempo).
                current = s
                order += sections[s].label
                repeat(sections[s].slides.size) { starts += null }
            }
            starts[starts.size - sections[s].slides.size + k] = e.at
            next = k + 1
        }
        return Result(order, starts)
    }

    /**
     * Fuera lo que no es canto: frases inventadas en el instrumental o al final (duran mucho más o menos de lo normal).
     * Y la primera frase después de la introducción o de un instrumental: Whisper la hace empezar donde terminó lo anterior,
     * así que se le pone la duración que tiene esa misma frase en otra parte de la canción. [lyrics]: las palabras de la letra.
     */
    internal fun clean(phrases: List<HeardPhrase>, audioSeconds: Double, lyrics: Set<String> = emptySet()): List<HeardPhrase> {
        val real = phrases.filter { p -> p.start < audioSeconds - 0.5 && p.end > p.start && tokens(p.text).isNotEmpty() }
            .map { it.copy(end = minOf(it.end, audioSeconds)) }
        if (real.isEmpty()) return real
        fun isLyric(p: HeardPhrase): Boolean {
            if (lyrics.isEmpty()) return true
            val ws = tokens(p.text)
            return ws.count { w -> w in lyrics || lyrics.any { similarity(w, it) >= 0.75 } } >= ws.size * 0.6
        }
        val lyric = real.map(::isLyric)
        // Viene después de algo que no es letra (introducción, instrumental, saludo) o de un silencio: su comienzo no es fiable.
        // (Unos segundos entre frases es normal en una canción lenta: solo cuenta un silencio largo.)
        val afterMusic = real.indices.map { i -> i == 0 || !lyric[i - 1] || real[i].start - real[i - 1].end > 6.0 }
        // Las duraciones que sirven de medida: frases de la letra cantadas seguidas.
        val steady = real.indices.filter { lyric[it] && !afterMusic[it] }.map { real[it] }
        val perChar = (steady.ifEmpty { real }).map { (it.end - it.start) / key(it.text).length.coerceAtLeast(1) }.sorted().let { it[it.size / 2] }
        val byText = steady.groupBy { key(it.text) }
        fun same(p: HeardPhrase) = byText[key(p.text)].orEmpty().filter { it !== p }.map { it.end - it.start }
        fun expected(p: HeardPhrase) = same(p).takeIf { it.isNotEmpty() }?.sorted()?.let { it[it.size / 2] } ?: (key(p.text).length * perChar)
        // Primero, lo que se va.
        val keep = BooleanArray(real.size)
        var lastKept: HeardPhrase? = null
        for ((i, p) in real.withIndex()) {
            val ratio = (p.end - p.start) / expected(p)
            keep[i] = when {
                // Cortada o inventada (pasa al final del video). Solo comparando con la misma frase: por letras no es fiable.
                ratio < 0.65 && (same(p).isNotEmpty() || !lyric[i]) -> false
                ratio > 1.4 && lastKept?.let { key(it.text) == key(p.text) } == true -> false // repite la anterior en el instrumental
                else -> true
            }
            if (keep[i]) lastKept = p
        }
        val out = mutableListOf<HeardPhrase>()
        for ((i, p) in real.withIndex()) {
            if (!keep[i]) continue
            // Lo que debería durar al paso de la frase cantada vecina (las estrofas suelen ir más lentas que el coro).
            val near = (i + 1 until real.size).firstOrNull { keep[it] && lyric[it] } ?: (i - 1 downTo 0).firstOrNull { keep[it] && lyric[it] }
            val byNeighbor = near?.let { n -> key(p.text).length * (real[n].end - real[n].start) / key(real[n].text).length.coerceAtLeast(1) }
            // Más larga de lo normal: se le pegó lo instrumental de antes (Whisper la empieza donde terminó lo anterior).
            out += if (lyric[i] && byNeighbor != null && p.end - p.start > byNeighbor * 1.3) p.copy(start = p.end - byNeighbor) else p
        }
        return out
    }

    /**
     * En qué segundo empieza a cantarse cada diapositiva, en orden. Se recorre lo que se oyó palabra por palabra
     * siguiendo la letra: dentro de una diapositiva se avanza (se perdonan palabras que faltan o sobran) y al terminarla
     * se puede pasar al comienzo de cualquiera (la siguiente, o volver al coro).
     */
    internal fun entries(phrases: List<HeardPhrase>, slides: List<List<String>>, startsSection: List<Boolean> = slides.map { true }): List<Entry> {
        val heard = words(phrases)
        if (heard.isEmpty() || slides.none { it.isNotEmpty() }) return emptyList()
        val offset = IntArray(slides.size + 1)
        for (s in slides.indices) offset[s + 1] = offset[s] + slides[s].size
        val total = offset.last()
        val garbage = total // lo que no es letra (aplausos, "hola, bienvenidos", palabras inventadas)
        val states = total + 1
        val slideOf = IntArray(total)
        val posOf = IntArray(total)
        for (s in slides.indices) for (k in slides[s].indices) { slideOf[offset[s] + k] = s; posOf[offset[s] + k] = k }
        fun nearEnd(st: Int) = st < total && posOf[st] >= slides[slideOf[st]].size - 2
        fun emit(st: Int, w: String) = if (st == garbage) GARBAGE else 2 * similarity(w, slides[slideOf[st]][posOf[st]]) - 1
        val n = heard.size
        var score = DoubleArray(states) { Double.NEGATIVE_INFINITY }
        val back = Array(n) { IntArray(states) { -1 } }
        for (s in slides.indices) if (slides[s].isNotEmpty()) {
            val order = if (startsSection[s]) 0.0 else OUT_OF_ORDER
            score[offset[s]] = order + emit(offset[s], heard[0].text)
            if (slides[s].size > 1) score[offset[s] + 1] = order + MISS + emit(offset[s] + 1, heard[0].text)
        }
        score[garbage] = emit(garbage, heard[0].text)
        for (i in 1 until n) {
            val prev = score
            val cur = DoubleArray(states) { Double.NEGATIVE_INFINITY }
            // Lo mejor para "empezar otra": desde el final de una diapositiva, desde lo que no es letra, o (caro) desde la mitad.
            var exitBest = prev[garbage] + TO_NEW; var exitArg = garbage
            var anyBest = Double.NEGATIVE_INFINITY; var anyArg = -1
            for (st in 0 until total) {
                if (nearEnd(st) && prev[st] + TO_NEW > exitBest) { exitBest = prev[st] + TO_NEW; exitArg = st }
                if (prev[st] > anyBest) { anyBest = prev[st]; anyArg = st }
            }
            if (anyArg >= 0 && anyBest + JUMP > exitBest) { exitBest = anyBest + JUMP; exitArg = anyArg }
            // Dentro de una sección las diapositivas van en orden: la segunda parte del coro viene después de la primera
            // (en un coro donde todas las líneas empiezan igual, si no, se confunden).
            val fromPrevious = DoubleArray(slides.size) { Double.NEGATIVE_INFINITY }
            val fromPreviousArg = IntArray(slides.size) { -1 }
            for (s in 1 until slides.size) if (!startsSection[s]) {
                for (st in offset[s - 1] until offset[s]) if (nearEnd(st) && prev[st] + TO_NEW > fromPrevious[s]) { fromPrevious[s] = prev[st] + TO_NEW; fromPreviousArg[s] = st }
            }
            fun entry(s: Int): Pair<Double, Int> =
                if (startsSection[s] || exitBest + OUT_OF_ORDER < fromPrevious[s]) (if (startsSection[s]) exitBest to exitArg else fromPrevious[s] to fromPreviousArg[s])
                else (exitBest + OUT_OF_ORDER) to exitArg
            val w = heard[i].text
            for (st in 0 until total) {
                val k = posOf[st]
                var best = Double.NEGATIVE_INFINITY; var arg = -1
                fun take(v: Double, from: Int) { if (v > best) { best = v; arg = from } }
                if (k >= 1) take(prev[st - 1], st - 1)
                if (k >= 2) take(prev[st - 2] + MISS, st - 2)
                take(prev[st] + EXTRA, st)
                if (k <= 1) { val (v, a) = entry(slideOf[st]); if (a >= 0) take(v + if (k == 1) MISS else 0.0, a) }
                if (arg >= 0) { cur[st] = best + emit(st, w); back[i][st] = arg }
            }
            run {
                var best = prev[garbage]; var arg = garbage
                for (st in 0 until total) if (nearEnd(st) && prev[st] + TO_GARBAGE > best) { best = prev[st] + TO_GARBAGE; arg = st }
                if (anyArg >= 0 && anyBest + JUMP > best) { best = anyBest + JUMP; arg = anyArg }
                cur[garbage] = best + emit(garbage, w); back[i][garbage] = arg
            }
            score = cur
        }
        // De atrás para adelante: el camino que mejor explica lo que se oyó.
        val path = IntArray(n)
        path[n - 1] = score.indices.maxBy { score[it] }
        for (i in n - 1 downTo 1) path[i - 1] = back[i][path[i]]
        val out = mutableListOf<Entry>()
        var i = 0
        while (i < n) {
            val st = path[i]
            val fresh = st < total && posOf[st] <= 1 && (i == 0 || !continues(path[i - 1], st, posOf, slideOf, total))
            if (!fresh) { i++; continue }
            // La diapositiva cuenta si de verdad se reconoció buena parte de su letra.
            val s = slideOf[st]
            var j = i
            var good = 0
            while (j < n && path[j] < total && slideOf[path[j]] == s && (j == i || continues(path[j - 1], path[j], posOf, slideOf, total))) {
                if (similarity(heard[j].text, slides[s][posOf[path[j]]]) >= 0.6) good++
                j++
            }
            if (good >= minOf(3, (slides[s].size + 1) / 2)) out += Entry(s, heard[i].at)
            i = j.coerceAtLeast(i + 1)
        }
        return out
    }

    /** [to] sigue a [from] dentro de la misma diapositiva (no es empezarla de nuevo). */
    private fun continues(from: Int, to: Int, pos: IntArray, slideOf: IntArray, total: Int): Boolean =
        from < total && to < total && slideOf[from] == slideOf[to] && pos[to] - pos[from] in 0..2

    /** Cada palabra con el segundo en que se dice (repartido en la frase según sus letras). */
    private fun words(phrases: List<HeardPhrase>): List<Word> = phrases.flatMap { p ->
        val ws = tokens(p.text)
        val chars = ws.sumOf { it.length + 1 }.coerceAtLeast(1)
        var acc = 0
        ws.map { w -> Word(w, p.start + (p.end - p.start) * acc / chars).also { acc += w.length + 1 } }
    }

    internal fun tokens(text: String): List<String> =
        normalizeText(text).split(Regex("[^a-z0-9ñ]+")).filter { it.isNotEmpty() }

    private fun key(text: String) = tokens(text).joinToString(" ")

    /** 1 si son iguales, 0 si no se parecen nada (distancia de edición). */
    internal fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        val m = a.length; val n = b.length
        if (m == 0 || n == 0) return 0.0
        var prev = IntArray(n + 1) { it }
        for (i in 1..m) {
            val cur = IntArray(n + 1)
            cur[0] = i
            for (j in 1..n) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return 1.0 - prev[n].toDouble() / maxOf(m, n)
    }

    private const val GARBAGE = -0.3
    private const val MISS = -0.7
    private const val EXTRA = -0.7
    private const val TO_NEW = -0.2
    private const val TO_GARBAGE = -0.2
    private const val JUMP = -2.5
    /** Empezar una diapositiva que no es la primera de su sección sin venir de la anterior. */
    private const val OUT_OF_ORDER = -1.5
}
