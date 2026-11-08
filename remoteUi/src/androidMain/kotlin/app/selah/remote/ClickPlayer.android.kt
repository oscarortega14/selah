package app.selah.remote

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Build
import android.os.Process
import app.selah.shared.ClickState
import kotlin.math.ceil

/**
 * Click en Android: un hilo escribe audio sin parar (silencio y "tocs") en un AudioTrack. Para cada bloque
 * calcula a qué hora real va a sonar (con AudioTrack.getTimestamp, que dice qué muestra está saliendo por el
 * parlante y cuándo) y pone cada toc en la muestra exacta del tiempo del PC.
 */
actual class ClickPlayer actual constructor() {
    @Volatile private var click: ClickState? = null
    @Volatile private var offset: Long = 0
    @Volatile private var running = false
    private var thread: Thread? = null

    actual fun update(click: ClickState?, serverOffset: Long) {
        this.click = click
        this.offset = serverOffset
        if (click != null && !running) start()
        if (click == null && running) stop()
    }

    actual fun release() = stop()

    private fun start() {
        running = true
        thread = Thread(::loop, "selah-click").also { it.start() }
    }

    private fun stop() {
        running = false
        thread?.join(1_000)
        thread = null
    }

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val rate = 48_000
        val minBytes = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT).coerceAtLeast(4096)
        val builder = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(minBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
        if (Build.VERSION.SDK_INT >= 26) builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        val track = runCatching { builder.build() }.getOrNull() ?: run { running = false; return }
        val accent = clickSamples(rate, accent = true)
        val normal = clickSamples(rate, accent = false)
        val chunk = rate / 100 // 10 ms por bloque
        val buf = FloatArray(chunk)
        val ts = AudioTimestamp()
        var written = 0L
        try {
            track.play()
            while (running) {
                val c = click ?: break
                // Hora local (ms, con decimales) en que sonará la primera muestra de este bloque.
                val wallNow = System.currentTimeMillis()
                val nanoNow = System.nanoTime()
                val startLocal = if (track.getTimestamp(ts)) {
                    val playNano = ts.nanoTime + (written - ts.framePosition) * 1_000_000_000.0 / rate
                    wallNow + (playNano - nanoNow) / 1_000_000.0
                } else {
                    // Todavía sin marca de tiempo: lo escrito y aún no tocado, más una latencia típica.
                    val pending = written - track.playbackHeadPosition.toLong()
                    wallNow + pending * 1000.0 / rate + 20.0
                }
                val startServer = startLocal + offset
                val endServer = startServer + chunk * 1000.0 / rate
                buf.fill(0f)
                // Tiempos cuyo toc (30 ms) cae dentro de este bloque, desde la cuenta hasta el final.
                val soundMs = accent.size * 1000.0 / rate
                var beat = ceil((startServer - soundMs - c.startAt) / c.beatMs).toLong().coerceAtLeast(-c.countIn.toLong())
                while (true) {
                    val at = c.startAt + beat * c.beatMs
                    if (at >= endServer) break
                    val sound = if (isDownbeat(beat, c.beatsPerBar)) accent else normal
                    val first = Math.round((at - startServer) * rate / 1000.0).toInt() // puede ser negativo: empezó antes
                    for (i in 0 until chunk) {
                        val k = i - first
                        if (k in sound.indices) buf[i] += sound[k]
                    }
                    beat++
                }
                track.write(buf, 0, chunk, AudioTrack.WRITE_BLOCKING)
                written += chunk
            }
        } catch (_: Exception) {
            // Si el sistema se lleva el audio (llamada, otra app) se calla sin cerrar la app.
        } finally {
            runCatching { track.stop() }
            track.release()
            running = false
        }
    }
}
