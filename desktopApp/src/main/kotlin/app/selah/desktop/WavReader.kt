package app.selah.desktop

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream

/**
 * Lector de WAV propio: el de Java no abre muchos de los que salen de los programas de grabación (con un bloque
 * "JUNK" antes del formato, de 24 bits, en coma flotante o "extensible"). Entrega siempre PCM de 16 bits
 * intercalado, con los canales y la frecuencia del archivo.
 */
object WavReader {
    private const val PCM = 1
    private const val FLOAT = 3
    private const val EXTENSIBLE = 0xFFFE

    fun open(file: File): AudioInputStream {
        val input = DataInputStream(BufferedInputStream(file.inputStream(), 1 shl 16))
        try {
            val riff = ByteArray(12).also { input.readFully(it) }
            val tag = String(riff, 0, 4, Charsets.US_ASCII)
            if ((tag != "RIFF" && tag != "RF64") || String(riff, 8, 4, Charsets.US_ASCII) != "WAVE") throw IOException("no es un WAV")
            var format = -1; var channels = 0; var rate = 0; var bits = 0
            while (true) {
                val id = ByteArray(4).also { input.readFully(it) }.toString(Charsets.US_ASCII)
                val size = Integer.toUnsignedLong(Integer.reverseBytes(input.readInt()))
                when (id) {
                    "fmt " -> {
                        val fmt = ByteArray(size.toInt()).also { input.readFully(it) }
                        fun u16(o: Int) = (fmt[o].toInt() and 0xff) or ((fmt[o + 1].toInt() and 0xff) shl 8)
                        fun u32(o: Int) = u16(o) or (u16(o + 2) shl 16)
                        format = u16(0); channels = u16(2); rate = u32(4); bits = u16(14)
                        // "Extensible": el formato verdadero está en los dos primeros bytes del subformato.
                        if (format == EXTENSIBLE && fmt.size >= 26) format = u16(24)
                        if (size % 2 == 1L) input.skipBytes(1)
                    }
                    "data" -> {
                        if (format != PCM && format != FLOAT) throw IOException("WAV comprimido (formato $format): conviértelo a PCM o MP3")
                        if (channels !in 1..8 || rate !in 8_000..192_000 || bits !in setOf(8, 16, 24, 32)) throw IOException("WAV raro: $channels canales, $rate Hz, $bits bits")
                        if (format == FLOAT && bits != 32) throw IOException("WAV de coma flotante de $bits bits")
                        // Más de 2 canales: se usan los dos primeros.
                        val outCh = minOf(channels, 2)
                        val frameIn = channels * bits / 8
                        // RF64 o tamaño 0/desconocido: hasta el final del archivo.
                        val frames = if (size == 0L || size == 0xFFFFFFFFL) -1L else size / frameIn
                        val stream = Converter(input, channels, outCh, bits, format == FLOAT, if (frames < 0) Long.MAX_VALUE else frames)
                        return AudioInputStream(stream, AudioFormat(rate.toFloat(), 16, outCh, true, false), if (frames < 0) AudioInputStreamUnknown else frames)
                    }
                    else -> skipFully(input, size + (size and 1))
                }
            }
        } catch (e: Exception) {
            input.close()
            throw if (e is EOFException) IOException("el WAV no tiene datos de audio") else e
        }
    }

    private const val AudioInputStreamUnknown = -1L

    private fun skipFully(input: InputStream, n: Long) {
        var left = n
        while (left > 0) {
            val k = input.skip(left)
            if (k <= 0) { if (input.read() < 0) throw EOFException(); left-- } else left -= k
        }
    }

    /** Pasa cada cuadro a 16 bits (los primeros [outCh] canales). */
    private class Converter(
        private val input: DataInputStream, private val inCh: Int, private val outCh: Int,
        private val bits: Int, private val float: Boolean, private var framesLeft: Long,
    ) : InputStream() {
        private val frameIn = ByteArray(inCh * bits / 8)
        private val frameOut = ByteArray(outCh * 2)
        private var pos = frameOut.size

        override fun read(): Int {
            if (pos >= frameOut.size && !nextFrame()) return -1
            return frameOut[pos++].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            var n = 0
            while (n < len) {
                if (pos >= frameOut.size && !nextFrame()) break
                val k = minOf(len - n, frameOut.size - pos)
                System.arraycopy(frameOut, pos, b, off + n, k)
                pos += k; n += k
            }
            return if (n == 0 && len > 0) -1 else n
        }

        private fun nextFrame(): Boolean {
            if (framesLeft <= 0) return false
            try { input.readFully(frameIn) } catch (_: EOFException) { framesLeft = 0; return false }
            framesLeft--
            val bytes = bits / 8
            for (c in 0 until outCh) {
                val o = c * bytes
                val v: Int = when {
                    float -> {
                        val raw = (frameIn[o].toInt() and 0xff) or ((frameIn[o + 1].toInt() and 0xff) shl 8) or
                            ((frameIn[o + 2].toInt() and 0xff) shl 16) or (frameIn[o + 3].toInt() shl 24)
                        (java.lang.Float.intBitsToFloat(raw).coerceIn(-1f, 1f) * 32767).toInt()
                    }
                    bits == 8 -> ((frameIn[o].toInt() and 0xff) - 128) shl 8
                    else -> (frameIn[o + bytes - 1].toInt() shl 8) or (frameIn[o + bytes - 2].toInt() and 0xff) // los 16 bits altos
                }
                frameOut[2 * c] = v.toByte()
                frameOut[2 * c + 1] = (v shr 8).toByte()
            }
            pos = 0
            return true
        }

        override fun close() = input.close()
    }
}
