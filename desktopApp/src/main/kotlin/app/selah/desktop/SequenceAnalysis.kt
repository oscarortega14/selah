package app.selah.desktop

import kotlinx.serialization.Serializable
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Lo que se sabe de un audio de secuencia escuchándolo: muchas traen el click (metrónomo y guía) en el canal
 * izquierdo y la música en el derecho, para mandar cada uno a un lugar distinto en la consola.
 */
@Serializable
data class AudioAnalysis(
    /** El canal izquierdo es un click (golpes parejos) y el derecho es otra cosa. */
    val clickLeft: Boolean = false,
    /** Tempo medido en el click, o null. */
    val bpm: Int? = null,
    /** Dónde cae el primer tiempo respecto al inicio del audio (ms; puede ser un poco negativo). */
    val offsetMs: Int = 0,
    /** El click cambia de tempo en la canción: el de los celulares no lo seguiría (suena solo el del audio). */
    val tempoChanges: Boolean = false,
    /** Lo que dura el audio (ms); 0 = no se sabe. Para seguirlo cuando suena en otro aparato. */
    val durationMs: Long = 0,
    /** Dónde cae cada golpe del click en toda la canción (ms): para alinearse con el click que se escucha. */
    val clicks: List<Int> = emptyList(),
)

object SequenceAnalysis {
    /**
     * Escucha toda la canción por tramos de [window] segundos: el primero dice si hay click, su tempo y dónde cae;
     * los demás, si el tempo cambia. Si no se puede leer, devuelve "no sé" (sin click).
     */
    fun analyze(file: File, window: Int = 40): AudioAnalysis = runCatching {
        openPcm(file).use { input ->
            val rate = input.format.sampleRate.toInt()
            val channels = input.format.channels
            val frameBytes = channels * 2
            val bytes = ByteArray(rate * window * frameBytes)
            var first: AudioAnalysis? = null
            var changes = false
            var totalFrames = 0L
            val clicks = mutableListOf<Int>()
            var clickLeft = channels == 2
            while (true) {
                var n = 0
                while (n < bytes.size) { val r = input.read(bytes, n, bytes.size - n); if (r <= 0) break; n += r }
                val frames = n / frameBytes
                val startMs = totalFrames * 1000 / rate
                totalFrames += frames
                if (frames >= rate * 8 && clickLeft) {
                    val left = IntArray(frames) { (bytes[frameBytes * it + 1].toInt() shl 8) or (bytes[frameBytes * it].toInt() and 0xff) }
                    val right = IntArray(frames) { (bytes[frameBytes * it + 3].toInt() shl 8) or (bytes[frameBytes * it + 2].toInt() and 0xff) }
                    val a = analyze(left, right, rate)
                    if (first == null) {
                        if (!a.clickLeft) clickLeft = false else first = a
                    } else if (a.clickLeft && a.bpm != null && first.bpm != null && abs(a.bpm - first.bpm) > maxOf(2, first.bpm / 30)) {
                        changes = true
                    }
                    // Los golpes del click de este tramo (los que caen en su pulso: no la voz guía).
                    if (clickLeft) {
                        val on = onsets(left, rate)
                        val grid = beatPeriod(on)?.let { fitGrid(on, it) }
                        for (t in on) {
                            val keep = grid == null || run {
                                val (beat, phase) = grid
                                val d = (t - phase).mod(beat)
                                minOf(d, beat - d) <= maxOf(8.0, beat * 0.06)
                            }
                            if (keep) clicks += (startMs + t).roundToInt()
                        }
                    }
                }
                if (n < bytes.size) break
            }
            val duration = totalFrames * 1000 / rate
            (first?.copy(tempoChanges = changes, clicks = clicks) ?: AudioAnalysis()).copy(durationMs = duration)
        }
    }.getOrElse { Log.warn("No pude analizar ${file.name}: ${it.message}"); AudioAnalysis() }

    /** Las muestras de los primeros [seconds] segundos (izquierda, derecha, frecuencia), o null. */
    internal fun samples(file: File, seconds: Int = 45): Triple<IntArray, IntArray, Int>? = runCatching {
        openPcm(file).use { input ->
            val rate = input.format.sampleRate.toInt()
            if (input.format.channels != 2) return null
            val bytes = ByteArray(rate * seconds * 4)
            var n = 0
            while (n < bytes.size) { val r = input.read(bytes, n, bytes.size - n); if (r <= 0) break; n += r }
            val frames = n / 4
            Triple(IntArray(frames) { (bytes[4 * it + 1].toInt() shl 8) or (bytes[4 * it].toInt() and 0xff) },
                IntArray(frames) { (bytes[4 * it + 3].toInt() shl 8) or (bytes[4 * it + 2].toInt() and 0xff) }, rate)
        }
    }.getOrNull()

    /** Lo mismo con las muestras ya leídas (para las pruebas). */
    fun analyze(left: IntArray, right: IntArray, rate: Int): AudioAnalysis {
        // Los dos canales llevan lo mismo: estéreo normal. (Algunas traen algo de música también en el izquierdo:
        // eso todavía es "click a la izquierda".)
        if (correlation(left, right) > 0.95) return AudioAnalysis()
        val l = onsets(left, rate)
        val r = onsets(right, rate)
        val (beat, phase) = beatPeriod(l)?.let { fitGrid(l, it) } ?: return AudioAnalysis()
        // El click cubre casi todos los tiempos; la música del otro canal, muchos menos (aunque tenga batería).
        val coverL = coverage(l, beat, phase)
        val coverR = coverage(r, beat, phase)
        if (coverL < 0.55 || coverL - coverR < 0.2) return AudioAnalysis()
        var offset = phase
        if (offset > beat / 2) offset -= beat
        return AudioAnalysis(clickLeft = true, bpm = (60_000.0 / beat).roundToInt(), offsetMs = offset.roundToInt())
    }

    /**
     * Duración de un tiempo (ms): la distancia que más se repite entre golpes cercanos (de 230 a 2100 ms).
     * Mirar todos los pares, no solo los seguidos: la voz guía mete golpes en medio y no estorba.
     */
    internal fun beatPeriod(on: List<Double>): Double? {
        if (on.size < 8) return null
        val bins = IntArray(2_100 / 5 + 1)
        for (i in on.indices) for (j in i + 1 until on.size) {
            val d = on[j] - on[i]
            if (d > 2_100) break
            if (d >= 230) bins[(d / 5).toInt()]++
        }
        // Suavizado (±5 ms) y el pico más corto que tenga al menos la mitad del más alto (no el doble ni el compás).
        val smooth = IntArray(bins.size) { i -> (maxOf(0, i - 1)..minOf(bins.size - 1, i + 1)).sumOf { bins[it] } }
        val top = smooth.maxOrNull() ?: return null
        if (top < 6) return null
        val first = (0 until smooth.size).firstOrNull { i -> smooth[i] >= top / 2 && (i == 0 || smooth[i] >= smooth[i - 1]) && (i == smooth.size - 1 || smooth[i] >= smooth[i + 1]) } ?: return null
        val guess = first * 5.0 + 2.5
        val near = mutableListOf<Double>()
        for (i in on.indices) for (j in i + 1 until on.size) {
            val d = on[j] - on[i]
            if (d > guess * 1.03) break
            if (d >= guess * 0.97) near += d
        }
        return if (near.isEmpty()) guess else near.average()
    }

    /** Dónde cae el pulso dentro de un tiempo (ms): donde se juntan más golpes (la voz guía queda repartida). */
    internal fun phaseOf(on: List<Double>, beat: Double): Double {
        val bins = 50
        val h = IntArray(bins)
        for (t in on) h[(((t % beat) / beat) * bins).toInt().coerceIn(0, bins - 1)]++
        val best = (0 until bins).maxBy { i -> h[(i + bins - 1) % bins] + 2 * h[i] + h[(i + 1) % bins] }
        // Promedio circular de los golpes cercanos a ese punto, para afinar.
        val center = (best + 0.5) / bins * beat
        var sx = 0.0; var sy = 0.0
        for (t in on) {
            var d = t % beat - center
            if (d > beat / 2) d -= beat else if (d < -beat / 2) d += beat
            if (kotlin.math.abs(d) <= beat * 0.06) { val a = 2 * PI * (t % beat) / beat; sx += cos(a); sy += sin(a) }
        }
        return (atan2(sy, sx) / (2 * PI)).let { if (it < 0) it + 1 else it } * beat
    }

    /**
     * Afina el tempo y la fase con una recta por los golpes que caen en la rejilla: un error de medio milisegundo
     * en el tempo se notaría a los dos minutos.
     */
    internal fun fitGrid(on: List<Double>, guess: Double): Pair<Double, Double> {
        var beat = guess
        var phase = phaseOf(on, beat)
        repeat(3) {
            val tol = maxOf(8.0, beat * 0.06)
            val pts = on.mapNotNull { t ->
                val n = kotlin.math.round((t - phase) / beat)
                if (kotlin.math.abs(t - (phase + n * beat)) <= tol) n to t else null
            }
            if (pts.size < 4) return beat to phase
            val mn = pts.sumOf { it.first } / pts.size
            val mt = pts.sumOf { it.second } / pts.size
            val sxx = pts.sumOf { (it.first - mn) * (it.first - mn) }
            if (sxx == 0.0) return beat to phase
            beat = pts.sumOf { (it.first - mn) * (it.second - mt) } / sxx
            phase = ((mt - beat * mn) % beat).let { if (it < 0) it + beat else it }
        }
        return beat to phase
    }

    /** De los tiempos del pulso entre el primer y el último golpe, cuántos tienen un golpe encima (0 a 1). */
    internal fun coverage(on: List<Double>, beat: Double, phase: Double): Double {
        if (on.size < 2) return 0.0
        val sorted = on.sorted()
        val tol = maxOf(8.0, beat * 0.04)
        var k = kotlin.math.floor((sorted.first() - phase) / beat).toLong()
        var hits = 0; var total = 0; var idx = 0
        while (true) {
            val t = phase + k * beat
            if (t > sorted.last() + tol) break
            if (t >= sorted.first() - tol) {
                total++
                while (idx < sorted.size && sorted[idx] < t - tol) idx++
                if (idx < sorted.size && abs(sorted[idx] - t) <= tol) hits++
            }
            k++
        }
        return if (total == 0) 0.0 else hits.toDouble() / total
    }

    /** Momentos (ms) de los golpes fuertes y agudos del canal. */
    internal fun onsets(x: IntArray, rate: Int): List<Double> {
        val hop = (rate / 500).coerceAtLeast(1) // 2 ms
        val env = DoubleArray(x.size / hop)
        for (i in env.indices) {
            var m = 0
            for (k in i * hop until minOf((i + 1) * hop, x.size)) { val d = abs(x[k] - (if (k > 0) x[k - 1] else 0)); if (d > m) m = d }
            env[i] = m.toDouble()
        }
        if (env.isEmpty()) return emptyList()
        // Umbral sobre los golpes más fuertes "normales" (no el más fuerte de todos): así cuentan también los tiempos
        // débiles del click, que suenan más bajito que el primero del compás.
        val loud = env.sortedArray()[(env.size * 0.995).toInt().coerceAtMost(env.size - 1)]
        val th = loud * 0.3
        if (th <= 0) return emptyList()
        val out = mutableListOf<Double>()
        var last = -1_000
        // 150 ms sin contar otro golpe: el eco de un click no es otro tiempo.
        for (i in env.indices) if (env[i] > th && i - last > 75) { out += i * 2.0; last = i }
        return out
    }

    private fun correlation(a: IntArray, b: IntArray): Double {
        var sab = 0.0; var saa = 0.0; var sbb = 0.0
        var i = 0
        while (i < a.size) { sab += a[i].toDouble() * b[i]; saa += a[i].toDouble() * a[i]; sbb += b[i].toDouble() * b[i]; i += 7 }
        // Un canal en silencio no se parece al otro.
        return if (saa == 0.0 || sbb == 0.0) 0.0 else sab / sqrt(saa * sbb)
    }
}
