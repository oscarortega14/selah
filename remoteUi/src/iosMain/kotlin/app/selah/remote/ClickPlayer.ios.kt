package app.selah.remote

import app.selah.shared.ClickState
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.get
import kotlinx.cinterop.set
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioFormat
import platform.AVFAudio.AVAudioPCMBuffer
import platform.AVFAudio.AVAudioPlayerNode
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVAudioTime
import platform.AVFAudio.setActive
import platform.AVFAudio.outputLatency
import platform.QuartzCore.CACurrentMediaTime
import kotlin.math.ceil

/**
 * Click en iPhone: AVAudioEngine con un reproductor al que se le entrega cada "toc" con su hora exacta
 * (AVAudioTime en tiempo de máquina). Se programan con ~1,5 s de anticipación; si cambia el tempo o el
 * click, se borra lo programado y se vuelve a programar.
 */
@OptIn(ExperimentalForeignApi::class)
actual class ClickPlayer actual constructor() {
    private var engine: AVAudioEngine? = null
    private var player: AVAudioPlayerNode? = null
    private var accent: AVAudioPCMBuffer? = null
    private var normal: AVAudioPCMBuffer? = null
    private var click: ClickState? = null
    private var offset: Long = 0
    private var nextBeat: Long? = null
    private var job: Job? = null

    actual fun update(click: ClickState?, serverOffset: Long) {
        val changed = click?.startAt != this.click?.startAt || click?.bpm != this.click?.bpm || click?.beatsPerBar != this.click?.beatsPerBar
        this.click = click
        this.offset = serverOffset
        if (click == null) { stop(); return }
        if (engine == null && !start()) return
        if (changed) { player?.stop(); player?.play(); nextBeat = null }
        if (job == null) job = MainScope().launch { while (isActive) { schedule(); delay(250) } }
    }

    actual fun release() = stop()

    private fun start(): Boolean = runCatching {
        // "Reproducción": suena aunque el iPhone esté en silencio, como una app de música.
        AVAudioSession.sharedInstance().setCategory(AVAudioSessionCategoryPlayback, error = null)
        AVAudioSession.sharedInstance().setActive(true, error = null)
        val e = AVAudioEngine()
        val p = AVAudioPlayerNode()
        val rate = 48_000.0
        val format = AVAudioFormat(standardFormatWithSampleRate = rate, channels = 1u) ?: return false
        e.attachNode(p)
        e.connect(p, to = e.mainMixerNode, format = format)
        accent = buffer(format, clickSamples(rate.toInt(), accent = true))
        normal = buffer(format, clickSamples(rate.toInt(), accent = false))
        if (!e.startAndReturnError(null)) return false
        p.play()
        engine = e
        player = p
        true
    }.getOrDefault(false)

    private fun buffer(format: AVAudioFormat, samples: FloatArray): AVAudioPCMBuffer? {
        val b = AVAudioPCMBuffer(pCMFormat = format, frameCapacity = samples.size.toUInt()) ?: return null
        b.frameLength = samples.size.toUInt()
        val ch = b.floatChannelData?.get(0) ?: return null
        for (i in samples.indices) ch[i] = samples[i]
        return b
    }

    /** Programa los tocs del próximo segundo y medio que todavía no estén programados. */
    private fun schedule() {
        val c = click ?: return
        val p = player ?: return
        val nowLocal = nowMillis()
        val nowServer = nowLocal + offset
        // La salida del iPhone tarda unos ms en sonar: se adelanta eso.
        val latencyMs = (AVAudioSession.sharedInstance().outputLatency * 1000.0)
        var beat = nextBeat ?: ceil((nowServer + 30 - c.startAt) / c.beatMs).toLong().coerceAtLeast(-c.countIn.toLong())
        val horizon = nowServer + 1_500
        while (true) {
            val atServer = c.startAt + beat * c.beatMs
            if (atServer > horizon) break
            val atLocal = atServer - offset - latencyMs
            // Reloj de la máquina (segundos) → "host time" de AVAudioTime.
            val inSeconds = (atLocal - nowLocal) / 1000.0
            if (inSeconds > 0.005) {
                val host = AVAudioTime.hostTimeForSeconds(CACurrentMediaTime() + inSeconds)
                val buf = if (isDownbeat(beat, c.beatsPerBar)) accent else normal
                if (buf != null) p.scheduleBuffer(buf, atTime = AVAudioTime(hostTime = host), options = 0u, completionHandler = null)
            }
            beat++
        }
        nextBeat = beat
    }

    private fun stop() {
        job?.cancel(); job = null
        player?.stop()
        engine?.stop()
        player = null; engine = null; nextBeat = null; click = null
    }
}
