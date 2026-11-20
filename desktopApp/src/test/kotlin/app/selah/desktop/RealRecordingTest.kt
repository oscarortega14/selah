package app.selah.desktop

import java.io.File
import kotlin.test.Test

/**
 * Con una grabación real: SELAH_REAL_RECORDING=video.mp4 SELAH_REAL_SONG=cancion.txt SELAH_REAL_MODEL=ggml-small-q5_1.bin.
 * Escribe lo que entendió y los tiempos sacados en SELAH_REAL_OUT (o junto al video).
 */
class RealRecordingTest {
    @Test
    fun grabacionReal() {
        val rec = System.getenv("SELAH_REAL_RECORDING")?.let(::File) ?: return
        val model = File(System.getenv("SELAH_REAL_MODEL") ?: return)
        val song = File(System.getenv("SELAH_REAL_SONG") ?: return).readText()
        val t0 = System.currentTimeMillis()
        val samples = RecordingAudio.read(rec)
        val out = StringBuilder("audio: ${"%.1f".format(RecordingAudio.seconds(samples))} s, leído en ${System.currentTimeMillis() - t0} ms\n")
        val t1 = System.currentTimeMillis()
        val beats = BeatTracker.track(samples, null)!!
        out.appendLine("pulsos: ${beats.times.size}, ${"%.1f".format(beats.bpm)} BPM, primero en ${"%.2f".format(beats.times.first())} s (${System.currentTimeMillis() - t1} ms)")
        val t2 = System.currentTimeMillis()
        val heard = SungLyrics.hear(samples, model, "")
        out.appendLine("whisper: ${System.currentTimeMillis() - t2} ms")
        heard.forEach { out.appendLine("%7.2f-%7.2f %s".format(it.start, it.end, it.text)) }
        val sections = RecordingTimings.sections(song)
        out.appendLine("limpias:")
        LyricsAligner.clean(heard, RecordingAudio.seconds(samples)).forEach { out.appendLine("%7.2f-%7.2f %s".format(it.start, it.end, it.text)) }
        out.appendLine("diapositivas:")
        LyricsAligner.entries(LyricsAligner.clean(heard, RecordingAudio.seconds(samples)), sections.flatMap { it.slides }.map(LyricsAligner::tokens))
            .forEach { out.appendLine("  ${it.slide} en %.2f s".format(it.at)) }
        val o = RecordingTimings.outcome(heard, sections, beats, RecordingAudio.seconds(samples))
        out.appendLine("BPM: ${o?.bpm}\nOrden: ${o?.order?.joinToString(", ")}\nTiempos: ${o?.timing?.joinToString(" ")}")
        File(System.getenv("SELAH_REAL_OUT") ?: (rec.path + ".txt")).writeText(out.toString())
    }
}
