package app.selah.desktop

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Encuentra los pulsos de una grabación con banda (sin click): dónde cae cada tiempo, aunque la banda se acelere un
 * poco. Así los tiempos de la letra se cuentan en pulsos y calzan con el click de la iglesia aunque el video vaya
 * un poco más rápido o más lento. (Seguimiento de pulsos por programación dinámica, como en Ellis 2007.)
 */
object BeatTracker {
    /** Cada cuánto se mide (s): 10 ms. */
    private const val HOP = 160
    private const val FRAME = 1024
    private const val FPS = RecordingAudio.RATE.toDouble() / HOP

    data class Beats(val times: List<Double>, val bpm: Double)

    /** [samples] a 16 kHz. [hintBpm]: el tempo que se espera (el de la canción), si se sabe. */
    fun track(samples: FloatArray, hintBpm: Int? = null): Beats? {
        val env = onsetEnvelope(samples)
        if (env.size < FPS * 10) return null
        val period = period(env, hintBpm) ?: return null
        val beats = dynamicBeats(env, period)
        if (beats.size < 8) return null
        // El tempo que se usa en la iglesia: el promedio de toda la grabación.
        val bpm = 60.0 * (beats.size - 1) / (beats.last() - beats.first())
        return Beats(beats, bpm)
    }

    /** Cuánto cambia el sonido en cada momento (los golpes, los acordes que entran): flujo espectral. */
    internal fun onsetEnvelope(samples: FloatArray): DoubleArray {
        val n = (samples.size - FRAME) / HOP
        if (n <= 0) return DoubleArray(0)
        val window = DoubleArray(FRAME) { 0.5 - 0.5 * cos(2 * PI * it / FRAME) }
        val bins = FRAME / 2
        var prev = DoubleArray(bins)
        val out = DoubleArray(n)
        val re = DoubleArray(FRAME)
        val im = DoubleArray(FRAME)
        for (f in 0 until n) {
            val o = f * HOP
            for (i in 0 until FRAME) { re[i] = samples[o + i] * window[i]; im[i] = 0.0 }
            fft(re, im)
            val mag = DoubleArray(bins)
            var flux = 0.0
            // Hasta ~6 kHz: los golpes y los acordes; lo de arriba es casi solo ruido.
            for (b in 1 until bins * 3 / 4) {
                mag[b] = ln(1 + 100 * sqrt(re[b] * re[b] + im[b] * im[b]))
                val d = mag[b] - prev[b]
                if (d > 0) flux += d
            }
            prev = mag
            out[f] = flux
        }
        // Se quita la tendencia lenta (lo que suena parejo) y se normaliza.
        val w = (FPS * 0.5).toInt()
        val smooth = movingAverage(out, w)
        for (i in out.indices) out[i] = max(0.0, out[i] - smooth[i])
        val sd = sqrt(out.sumOf { it * it } / out.size).takeIf { it > 0 } ?: 1.0
        for (i in out.indices) out[i] /= sd
        return out
    }

    /** El periodo del pulso en cuadros: la autocorrelación, prefiriendo tempos cerca del esperado (o de 120). */
    internal fun period(env: DoubleArray, hintBpm: Int?): Double? {
        val minLag = (FPS * 60 / 200).toInt()
        val maxLag = (FPS * 60 / 50).toInt()
        if (env.size < maxLag * 4) return null
        val center = hintBpm?.toDouble() ?: 120.0
        val width = if (hintBpm != null) 0.15 else 1.0 // en octavas
        var best = -1
        var bestScore = Double.NEGATIVE_INFINITY
        val ac = DoubleArray(maxLag + 2)
        for (lag in minLag..maxLag + 1) {
            var s = 0.0
            for (i in 0 until env.size - lag) s += env[i] * env[i + lag]
            ac[lag] = s / (env.size - lag)
        }
        for (lag in minLag..maxLag) {
            val bpm = FPS * 60 / lag
            val weight = exp(-0.5 * (log2(bpm / center) / width).let { it * it })
            val score = ac[lag] * weight
            if (score > bestScore) { bestScore = score; best = lag }
        }
        if (best <= minLag || best >= maxLag) return best.toDouble().takeIf { it > 0 }
        // Afinar entre cuadros.
        val a = ac[best - 1]; val b = ac[best]; val c = ac[best + 1]
        val d = (a - 2 * b + c).takeIf { it != 0.0 }?.let { 0.5 * (a - c) / it } ?: 0.0
        return best + d.coerceIn(-0.5, 0.5)
    }

    /** Los pulsos: cada uno cae en un golpe y a un periodo del anterior (lo más parejo posible). */
    internal fun dynamicBeats(env: DoubleArray, period: Double, tightness: Double = 100.0): List<Double> {
        val n = env.size
        val score = DoubleArray(n)
        val from = IntArray(n) { -1 }
        val lo = (period / 2).toInt().coerceAtLeast(1)
        val hi = (period * 2).toInt()
        for (t in 0 until n) {
            var best = Double.NEGATIVE_INFINITY
            var arg = -1
            for (p in t - hi..t - lo) {
                if (p < 0) continue
                val r = ln((t - p) / period)
                val s = score[p] - tightness * r * r
                if (s > best) { best = s; arg = p }
            }
            score[t] = env[t] + if (arg >= 0 && best > 0) best else 0.0
            from[t] = if (arg >= 0 && best > 0) arg else -1
        }
        // El último pulso: el de mejor puntaje en el último periodo.
        var t = (max(0, n - period.toInt() - 1) until n).maxByOrNull { score[it] } ?: return emptyList()
        val out = ArrayList<Int>()
        while (t >= 0) { out += t; t = from[t] }
        out.reverse()
        // Fuera los pulsos del comienzo y del final donde no hay golpe (antes de que entre la banda, después del final).
        fun strength(f: Int) = (maxOf(0, f - 3)..minOf(n - 1, f + 3)).maxOf { env[it] }
        val level = out.map(::strength).sorted().let { it[it.size / 2] } * 0.25
        var a = 0; var b = out.lastIndex
        while (a < b && strength(out[a]) < level) a++
        while (b > a && strength(out[b]) < level) b--
        // El cambio se nota un poco después de que empieza el cuadro de análisis: se corrige (medido con golpes de prueba).
        return out.subList(a, b + 1).map { it / FPS + ONSET_DELAY }
    }

    private const val ONSET_DELAY = 0.045

    private fun movingAverage(x: DoubleArray, w: Int): DoubleArray {
        val half = max(1, w / 2)
        val prefix = DoubleArray(x.size + 1)
        for (i in x.indices) prefix[i + 1] = prefix[i] + x[i]
        return DoubleArray(x.size) { i ->
            val a = max(0, i - half); val b = minOf(x.size, i + half + 1)
            (prefix[b] - prefix[a]) / (b - a)
        }
    }

    /** FFT en el lugar (tamaño potencia de 2). */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang); val wi = sin(ang)
            for (i in 0 until n step len) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val ar = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val ai = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k + len / 2] = re[i + k] - ar; im[i + k + len / 2] = im[i + k] - ai
                    re[i + k] += ar; im[i + k] += ai
                    val nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr
                }
            }
            len = len shl 1
        }
    }

    /** En qué pulso (con decimales) cae el segundo [t]: entre pulsos se reparte parejo; fuera, con el tempo. */
    fun beatAt(beats: List<Double>, t: Double): Double {
        if (beats.size < 2) return 0.0
        if (t <= beats.first()) return (t - beats.first()) / (beats[1] - beats[0])
        if (t >= beats.last()) return beats.lastIndex + (t - beats.last()) / (beats.last() - beats[beats.lastIndex - 1])
        var lo = 0; var hi = beats.lastIndex
        while (hi - lo > 1) { val m = (lo + hi) / 2; if (beats[m] <= t) lo = m else hi = m }
        return lo + (t - beats[lo]) / (beats[hi] - beats[lo])
    }
}
