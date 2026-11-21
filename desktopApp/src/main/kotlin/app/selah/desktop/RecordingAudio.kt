package app.selah.desktop

import io.github.givimad.whisperjni.WhisperFullParams
import io.github.givimad.whisperjni.WhisperJNI
import io.github.givimad.whisperjni.WhisperSamplingStrategy
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

/** El audio de una grabación (video o audio de cómo se toca la canción), en mono a 16 kHz. */
object RecordingAudio {
    const val RATE = 16_000

    /** Lo que se puede elegir: audio (MP3, WAV, M4A) o video (MP4, MOV) con audio AAC. */
    val EXTENSIONS = setOf("mp3", "wav", "m4a", "mp4", "mov", "aac")

    fun read(file: File): FloatArray = try {
        decode(file)
    } catch (e: Exception) {
        // El decodificador de AAC falla con algunos archivos a mitad de camino (no solo al abrirlos).
        if (e is IllegalStateException && e.message?.startsWith("«") == true) throw e
        Log.error("No pude leer el audio de ${file.name}", e)
        throw IllegalStateException("No pude leer el audio de «${file.name}». Prueba con el mismo audio en MP4, MP3 o WAV.", e)
    }

    private fun decode(file: File): FloatArray {
        openPcm(file).use { pcm ->
            val rate = pcm.format.sampleRate.toDouble()
            val ch = pcm.format.channels
            val buf = ByteArray(rate.toInt() / 5 * ch * 2)
            val mono = FloatArrayBuilder()
            while (true) {
                val n = pcm.readNBytes(buf, 0, buf.size)
                if (n <= 0) break
                val frames = n / (ch * 2)
                for (i in 0 until frames) {
                    var s = 0
                    for (c in 0 until ch) {
                        val o = (i * ch + c) * 2
                        s += (buf[o + 1].toInt() shl 8) or (buf[o].toInt() and 0xff)
                    }
                    mono.add(s / (ch * 32768f))
                }
            }
            val m = mono.toArray()
            if (m.isEmpty()) throw IllegalStateException("«${file.name}» no tiene audio")
            return resample(m, rate)
        }
    }

    /**
     * A 16 kHz con un filtro que corta lo que pasa de ~7 kHz (sinc con ventana): sin él, lo agudo se "dobla" y suena a
     * otra cosa, y Whisper entiende peor la letra.
     */
    internal fun resample(x: FloatArray, fromRate: Double): FloatArray {
        if (fromRate == RATE.toDouble()) return x
        val step = fromRate / RATE
        val cutoff = minOf(1.0, RATE / fromRate) * 0.9 // fracción de la mitad de la frecuencia original
        val half = 16 // muestras de la salida a cada lado (en escala de la entrada: half / cutoff)
        val reach = (half / cutoff).toInt()
        val n = ((x.size - 1) / step).toInt() + 1
        val out = FloatArray(n)
        for (o in 0 until n) {
            val center = o * step
            val c = center.toInt()
            var s = 0.0
            var wsum = 0.0
            for (i in maxOf(0, c - reach + 1)..minOf(x.size - 1, c + reach)) {
                val d = (i - center) * cutoff
                val w = (if (d == 0.0) 1.0 else kotlin.math.sin(Math.PI * d) / (Math.PI * d)) *
                    (0.5 + 0.5 * kotlin.math.cos(Math.PI * (i - center) / (reach + 1)))
                s += x[i] * w; wsum += w
            }
            out[o] = if (wsum != 0.0) (s / wsum).toFloat() else 0f
        }
        return out
    }

    /** Duración en segundos de [samples] a 16 kHz. */
    fun seconds(samples: FloatArray): Double = samples.size.toDouble() / RATE
}

internal class FloatArrayBuilder {
    private var data = FloatArray(1 shl 16)
    var size = 0; private set
    fun add(v: Float) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = v
    }
    fun toArray(): FloatArray = data.copyOf(size)
}

/**
 * El modelo de Whisper para entender lo que se canta (whisper.cpp "medium" cuantizado, MIT; el "small" se confunde con
 * los coros que repiten el comienzo de cada línea). No viene con Selah: se descarga una vez (540 MB) la primera vez que alguien saca los tiempos de una grabación, y queda en Selah/modelos.
 */
class WhisperModelFile(baseDir: File, private val url: String = URL, private val sha256: String = SHA256) {
    val dir = File(baseDir, "modelos")
    val file = File(dir, NAME)

    val ready: Boolean get() = file.isFile && file.length() > 500_000_000

    fun download(progress: (Double) -> Unit) {
        if (ready) return
        dir.mkdirs()
        val part = File(dir, "$NAME.part")
        val c = URI(url).toURL().openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 30_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "Selah")
        if (c.responseCode != 200) throw IllegalStateException("No pude descargar el modelo para entender el canto (${c.responseCode})")
        val total = c.contentLengthLong.takeIf { it > 0 } ?: 539_000_000L
        val md = MessageDigest.getInstance("SHA-256")
        c.inputStream.use { input ->
            part.outputStream().use { out ->
                val b = ByteArray(1 shl 16)
                var done = 0L
                while (true) {
                    val n = input.read(b); if (n < 0) break
                    out.write(b, 0, n); md.update(b, 0, n); done += n
                    progress((done.toDouble() / total).coerceAtMost(0.99))
                }
            }
        }
        val got = md.digest().joinToString("") { "%02x".format(it) }
        if (got != sha256) { part.delete(); throw IllegalStateException("El modelo llegó dañado: intenta de nuevo") }
        if (!part.renameTo(file)) { part.copyTo(file, overwrite = true); part.delete() }
        progress(1.0)
    }

    companion object {
        const val NAME = "ggml-medium-q5_0.bin"
        const val URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$NAME"
        const val SHA256 = "19fea4b380c3a618ec4723c3eef2eb785ffba0d0538cf43f8f235e7b3b34220f"
    }
}

/** Lo que Whisper entendió: frases con su comienzo y fin en segundos. */
data class HeardPhrase(val start: Double, val end: Double, val text: String)

/** Whisper (whisper.cpp por whisper-jni): entiende lo que se canta en una grabación. */
object SungLyrics {
    private val loaded: Throwable? by lazy { runCatching { WhisperJNI.loadLibrary() }.exceptionOrNull() }

    /** null si funciona en este equipo; si no, por qué. */
    val problem: String? get() = loaded?.let { "El reconocimiento del canto no funciona en este equipo (${it.message ?: it.javaClass.simpleName})" }

    /** [hint]: la letra de la canción, para que Whisper reconozca mejor sus palabras. */
    fun hear(samples: FloatArray, model: File, hint: String): List<HeardPhrase> {
        problem?.let { throw IllegalStateException(it) }
        val w = WhisperJNI()
        val ctx = w.init(model.toPath())
        try {
            val p = WhisperFullParams(WhisperSamplingStrategy.GREEDY)
            p.language = "es"
            p.nThreads = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
            // Cada frase por su cuenta: si se equivoca en una, no arrastra el error a las demás.
            p.noContext = true
            p.suppressNonSpeechTokens = true
            p.initialPrompt = hint.take(MAX_HINT)
            val r = w.full(ctx, p, samples, samples.size)
            if (r != 0) throw IllegalStateException("No pude reconocer el canto (código $r)")
            return (0 until w.fullNSegments(ctx)).map { i ->
                HeardPhrase(w.fullGetSegmentTimestamp0(ctx, i) / 100.0, w.fullGetSegmentTimestamp1(ctx, i) / 100.0, w.fullGetSegmentText(ctx, i).trim())
            }.filter { it.text.isNotEmpty() }
        } finally {
            w.free(ctx)
        }
    }

    /** Whisper admite unas 220 palabras de pista: con la letra sin repetir alcanza. */
    private const val MAX_HINT = 800
}
