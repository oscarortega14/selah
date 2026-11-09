package app.selah.desktop

import java.io.File
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin

/** Cómo debe sonar una secuencia. */
data class SequencePlan(
    /** Tempo de la canción (línea "BPM:"), o null si no tiene: entonces no hay click. */
    val bpm: Int?,
    val beatsPerBar: Int = 4,
    /** Tiempos de cuenta (solo click) antes de que arranque la música. */
    val countIn: Int = 0,
    /** Click a la izquierda y la música (en mono) a la derecha. */
    val splitClick: Boolean = false,
    /** Salida de sonido (nombre); null = la del sistema. */
    val device: String? = null,
)

/** Lo que reproduce las secuencias (el real usa Java Sound; las pruebas, uno falso). */
interface SequenceOutput {
    /** null si se puede reproducir; si no, por qué (para mostrárselo al operador). */
    fun check(file: File): String?

    /** Empieza a sonar. Los avisos llegan desde otro hilo. */
    fun play(file: File, plan: SequencePlan, listener: Listener)

    /** Para, bajando el volumen en un segundo y medio (no de golpe en pleno culto). */
    fun stop()

    interface Listener {
        /** Hora del PC (ms) en que suena el inicio de la música. Llega varias veces: se va afinando. */
        fun musicStartsAt(wallMs: Long)

        /** Terminó (o se detuvo); [error] si falló. */
        fun finished(error: String?)
    }
}

/**
 * Arma el sonido de salida (estéreo, 16 bits) cuadro por cuadro: la cuenta, el click a la izquierda si se pidió,
 * y la música. Separado del reproductor para poder probarlo sin tarjeta de sonido.
 */
class SequenceMixer(private val rate: Int, private val plan: SequencePlan) {
    private val beatFrames: Double = plan.bpm?.let { rate * 60.0 / it } ?: 0.0
    /** Cuadros de cuenta (solo click) antes de la música. */
    val countInFrames: Long = if (plan.bpm != null) Math.round(plan.countIn * beatFrames) else 0
    private val accent = clickSound(rate, 1_600.0, 0.9)
    private val normal = clickSound(rate, 1_000.0, 0.6)
    private val withClick = plan.splitClick && plan.bpm != null

    /** El click en el cuadro [frame] (desde el inicio de la reproducción), de -1 a 1. */
    fun click(frame: Long): Double {
        if (!withClick) return 0.0
        val sinceStart = frame - countInFrames
        val beat = floor(sinceStart / beatFrames).toLong()
        if (beat < -plan.countIn) return 0.0
        val offset = (sinceStart - beat * beatFrames).toInt()
        val table = if (Math.floorMod(beat, plan.beatsPerBar.toLong()) == 0L) accent else normal
        return if (offset in table.indices) table[offset] else 0.0
    }

    /**
     * Llena [out] (estéreo intercalado, [frames] cuadros) desde el cuadro [first]. [music] trae la música en
     * estéreo intercalado (cuadros [musicFrom] en adelante de la canción) o null en la cuenta. [gain] baja el volumen.
     */
    fun render(out: ShortArray, first: Long, frames: Int, music: ShortArray?, musicOffset: Int, gain: (Long) -> Double = { 1.0 }) {
        for (i in 0 until frames) {
            val frame = first + i
            val m = if (music != null && frame >= countInFrames) i - musicOffset else -1
            val l = if (m >= 0 && music != null && 2 * m + 1 < music.size) music[2 * m] / 32768.0 else 0.0
            val r = if (m >= 0 && music != null && 2 * m + 1 < music.size) music[2 * m + 1] / 32768.0 else 0.0
            val g = gain(frame)
            val (left, right) = if (withClick) click(frame) to (l + r) / 2 else l to r
            out[2 * i] = toShort(left * g)
            out[2 * i + 1] = toShort(right * g)
        }
    }

    private fun toShort(v: Double): Short = (v.coerceIn(-1.0, 1.0) * 32767).toInt().toShort()

    companion object {
        /** "Toc" de 30 ms: un seno que se apaga rápido. */
        fun clickSound(rate: Int, freq: Double, volume: Double): DoubleArray {
            val n = rate * 30 / 1000
            return DoubleArray(n) { i -> volume * sin(2 * PI * freq * i / rate) * exp(-i / (rate * 0.006)) }
        }
    }
}

/** Reproduce las secuencias con el sonido de Java (WAV, y MP3 con mp3spi). Una a la vez. */
class JavaSoundSequenceOutput : SequenceOutput {
    @Volatile private var current: Playback? = null

    override fun check(file: File): String? = try {
        open(file).use { null }
    } catch (e: Exception) {
        "No pude leer la secuencia \"${file.name}\": usa MP3 o WAV (${e.message ?: e::class.simpleName})"
    }

    override fun play(file: File, plan: SequencePlan, listener: SequenceOutput.Listener) {
        stop()
        current = Playback(file, plan, listener).also { it.start() }
    }

    override fun stop() {
        current?.fadeOut()
        current = null
    }

    /** La música como PCM de 16 bits, intercalado, en su frecuencia original. */
    private fun open(file: File): AudioInputStream {
        val src = AudioSystem.getAudioInputStream(file)
        val f = src.format
        val rate = f.sampleRate.takeIf { it > 0 } ?: 44_100f
        val pcm = AudioFormat(AudioFormat.Encoding.PCM_SIGNED, rate, 16, f.channels.coerceIn(1, 2), 2 * f.channels.coerceIn(1, 2), rate, false)
        return if (f.matches(pcm)) src else AudioSystem.getAudioInputStream(pcm, src)
    }

    private inner class Playback(val file: File, val plan: SequencePlan, val listener: SequenceOutput.Listener) : Thread("secuencia") {
        @Volatile private var fadeFrom: Long = -1
        @Volatile private var fadeRequested = false

        init { isDaemon = true; priority = MAX_PRIORITY }

        fun fadeOut() { fadeRequested = true }

        override fun run() {
            var error: String? = null
            try { playAll() } catch (e: Exception) {
                Log.error("Falló la secuencia ${file.name}", e)
                error = "Se detuvo la secuencia: ${e.message ?: e::class.simpleName}"
            }
            listener.finished(error)
        }

        private fun playAll() {
            open(file).use { input ->
                val rate = input.format.sampleRate.toInt()
                val channels = input.format.channels
                val outFormat = AudioFormat(rate.toFloat(), 16, 2, true, false)
                val line = openLine(outFormat)
                val mixer = SequenceMixer(rate, plan)
                val chunk = rate / 50 // 20 ms
                val inBytes = ByteArray(chunk * channels * 2)
                val music = ShortArray(chunk * 2)
                val out = ShortArray(chunk * 2)
                val outBytes = ByteArray(chunk * 4)
                val fadeFrames = rate * 3 / 2
                var frame = 0L
                var musicLeft = chunk // cuadros de música válidos en el bloque
                var ended = false
                var lastReport = 0L
                try {
                    line.start()
                    while (true) {
                        if (fadeRequested && fadeFrom < 0) fadeFrom = frame
                        if (fadeFrom >= 0 && frame - fadeFrom >= fadeFrames) break
                        // Cuántos cuadros de este bloque son música (después de la cuenta).
                        val musicStartInChunk = (mixer.countInFrames - frame).coerceIn(0, chunk.toLong()).toInt()
                        var musicFrames = 0
                        if (musicStartInChunk < chunk && !ended) {
                            val want = chunk - musicStartInChunk
                            val got = readFully(input, inBytes, want * channels * 2) / (channels * 2)
                            if (got < want) ended = true
                            for (i in 0 until got) {
                                val l = ((inBytes[i * channels * 2 + 1].toInt() shl 8) or (inBytes[i * channels * 2].toInt() and 0xff)).toShort()
                                val r = if (channels == 2) ((inBytes[i * 4 + 3].toInt() shl 8) or (inBytes[i * 4 + 2].toInt() and 0xff)).toShort() else l
                                music[2 * (musicStartInChunk + i)] = l
                                music[2 * (musicStartInChunk + i) + 1] = r
                            }
                            musicFrames = got
                            musicLeft = musicStartInChunk + got
                        }
                        if (ended && musicFrames == 0 && frame >= mixer.countInFrames) break
                        val frames = if (ended) musicLeft.coerceAtLeast(1) else chunk
                        val ff = fadeFrom
                        mixer.render(out, frame, frames, music, 0) { f -> if (ff < 0) 1.0 else (1.0 - (f - ff).toDouble() / fadeFrames).coerceIn(0.0, 1.0) }
                        for (i in 0 until frames * 2) { outBytes[2 * i] = out[i].toInt().toByte(); outBytes[2 * i + 1] = (out[i].toInt() shr 8).toByte() }
                        line.write(outBytes, 0, frames * 4)
                        frame += frames
                        music.fill(0)
                        // Cada 2 s: a qué hora del PC sonó el inicio de la música (la tarjeta y el reloj se separan un poco).
                        val now = System.currentTimeMillis()
                        if (now - lastReport >= 2_000 && line.longFramePosition > 0) {
                            lastReport = now
                            listener.musicStartsAt(now + Math.round((mixer.countInFrames - line.longFramePosition) * 1000.0 / rate))
                        }
                        if (ended) break
                    }
                    if (fadeFrom < 0) line.drain()
                } finally {
                    line.stop()
                    line.close()
                }
            }
        }

        private fun readFully(input: AudioInputStream, buf: ByteArray, len: Int): Int {
            var total = 0
            while (total < len) {
                val n = input.read(buf, total, len - total)
                if (n <= 0) break
                total += n
            }
            return total - total % 2
        }

        private fun openLine(format: AudioFormat): SourceDataLine {
            val info = DataLine.Info(SourceDataLine::class.java, format)
            val mixer = plan.device?.let { name -> AudioSystem.getMixerInfo().firstOrNull { it.name == name } }
            val line = (if (mixer != null) AudioSystem.getMixer(mixer).getLine(info) else AudioSystem.getLine(info)) as SourceDataLine
            // 100 ms de colchón: suficiente para que no se corte y poco para que "parar" responda rápido.
            line.open(format, (format.frameRate * 0.1).toInt() * format.frameSize)
            return line
        }
    }

    companion object {
        /** Salidas de sonido que pueden reproducir (para elegir la tarjeta que va a la consola). */
        fun devices(): List<String> = runCatching {
            val probe = DataLine.Info(SourceDataLine::class.java, AudioFormat(48_000f, 16, 2, true, false))
            AudioSystem.getMixerInfo().filter { info -> runCatching { AudioSystem.getMixer(info).isLineSupported(probe) }.getOrDefault(false) }
                .map { it.name }.distinct()
        }.getOrDefault(emptyList())
    }
}
