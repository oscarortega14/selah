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
        val beats = BeatTracker.track(samples, app.selah.shared.SongParser.parse("x", song).bpm)!!
        out.appendLine("pulsos: ${beats.times.size}, ${"%.1f".format(beats.bpm)} BPM, primero en ${"%.2f".format(beats.times.first())} s (${System.currentTimeMillis() - t1} ms)")
        val t2 = System.currentTimeMillis()
        // SELAH_HEARD: lo que ya se oyó con otro reconocedor ([{start, end, text}]), en vez de Whisper.
        val heard = System.getenv("SELAH_HEARD")?.let { f ->
            kotlinx.serialization.json.Json.parseToJsonElement(File(f).readText()).let { it as kotlinx.serialization.json.JsonArray }.map { e ->
                val o = e as kotlinx.serialization.json.JsonObject
                fun d(k: String) = (o[k] as kotlinx.serialization.json.JsonPrimitive).content.toDouble()
                HeardPhrase(d("start"), d("end"), (o["text"] as kotlinx.serialization.json.JsonPrimitive).content)
            }
        } ?: SungLyrics.hear(samples, model, "")
        out.appendLine("whisper: ${System.currentTimeMillis() - t2} ms")
        heard.forEach { out.appendLine("%7.2f-%7.2f %s".format(it.start, it.end, it.text)) }
        val sections = RecordingTimings.sections(song)
        out.appendLine("limpias:")
        LyricsAligner.clean(heard, RecordingAudio.seconds(samples), sections.flatMap { it.slides }.flatMap(LyricsAligner::tokens).toSet()).forEach { out.appendLine("%7.2f-%7.2f %s".format(it.start, it.end, it.text)) }
        out.appendLine("diapositivas:")
        LyricsAligner.entries(LyricsAligner.clean(heard, RecordingAudio.seconds(samples), sections.flatMap { it.slides }.flatMap(LyricsAligner::tokens).toSet()), sections.flatMap { it.slides }.map(LyricsAligner::tokens), sections.flatMap { s -> s.slides.indices.map { it == 0 } })
            .forEach { out.appendLine("  ${it.slide} en %.2f s".format(it.at)) }
        val o = RecordingTimings.outcome(heard, sections, beats, RecordingAudio.seconds(samples))
        out.appendLine("BPM: ${o?.bpm}\nOrden: ${o?.order?.joinToString(", ")}\nTiempos: ${o?.timing?.joinToString(" ")}")
        File(System.getenv("SELAH_REAL_OUT") ?: (rec.path + ".txt")).writeText(out.toString())
        // SELAH_GUARDAR=carpeta Selah:id de la canción → se guarda en esa canción.
        System.getenv("SELAH_GUARDAR")?.let { g ->
            val (base, id) = g.split(":", limit = 2)
            o?.let { Library(File(base)).also { it.reload() }.applyRecording(id, it.bpm, it.order, it.timing) }
        }
    }
}
