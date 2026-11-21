package app.selah.desktop

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test

/**
 * Letra automática de muchas canciones a la vez desde sus grabaciones (herramienta, no prueba normal):
 * SELAH_LOTE=lote.json (videos con sus canciones posibles) SELAH_LOTE_DIR=carpeta Selah SELAH_REAL_MODEL=modelo.
 * Cada video se escucha una vez; cada canción se queda con la grabación donde mejor se reconoce su letra.
 */
class BatchRecordingTest {
    @Serializable
    data class Video(val name: String, val file: String, val key: String = "", val songs: List<String>)

    data class Try(val video: String, val coverage: Double, val outcome: RecordingTimings.Outcome)

    @Test
    fun lote() {
        val list = File(System.getenv("SELAH_LOTE") ?: return)
        val base = File(System.getenv("SELAH_LOTE_DIR") ?: return)
        val model = File(System.getenv("SELAH_REAL_MODEL") ?: return)
        val apply = System.getenv("SELAH_LOTE_GUARDAR") == "si"
        val videos = Json { ignoreUnknownKeys = true }.decodeFromString<List<Video>>(list.readText())
        val library = Library(base).also { it.reload() }
        val best = mutableMapOf<String, Try>()
        val report = StringBuilder()
        val log = File(list.parentFile, "avance.txt").also { it.writeText("") }
        for ((i, v) in videos.withIndex()) {
            if (v.songs.isEmpty()) { report.appendLine("— ${v.name}: ninguna canción parecida"); continue }
            val t0 = System.currentTimeMillis()
            val r = runCatching {
                val samples = RecordingAudio.read(File(v.file))
                val heard = SungLyrics.hear(samples, model, "")
                val seconds = RecordingAudio.seconds(samples)
                v.songs.mapNotNull { id ->
                    val song = library.song(id) ?: return@mapNotNull null
                    val text = library.rawText(id) ?: return@mapNotNull null
                    val sections = RecordingTimings.sections(text)
                    val aligned = LyricsAligner.align(heard, sections, seconds) ?: return@mapNotNull null
                    // Qué parte de la letra se reconoció: diapositivas distintas de la canción que aparecen cantadas.
                    val total = sections.sumOf { it.slides.size }.coerceAtLeast(1)
                    val seen = mutableSetOf<Pair<String, Int>>()
                    var pos = 0
                    for (label in aligned.order) {
                        val n = sections.first { it.label == label }.slides.size
                        for (k in 0 until n) if (aligned.starts[pos + k] != null) seen += label to k
                        pos += n
                    }
                    val beats = BeatTracker.track(samples, song.bpm) ?: return@mapNotNull null
                    val o = RecordingTimings.outcome(heard, sections, beats, seconds) ?: return@mapNotNull null
                    id to Try(v.name, seen.size.toDouble() / total, o)
                }
            }
            r.onSuccess { tries ->
                for ((id, t) in tries) if (t.coverage > (best[id]?.coverage ?: -1.0)) best[id] = t
                report.appendLine("• ${v.name} (${(System.currentTimeMillis() - t0) / 1000} s): " +
                    tries.joinToString("; ") { (id, t) -> "$id ${(t.coverage * 100).toInt()} %" }.ifEmpty { "no se reconoció ninguna" })
            }.onFailure { report.appendLine("✗ ${v.name}: ${it.message}") }
            log.appendText("${i + 1}/${videos.size} ${v.name}\n")
        }
        report.appendLine("\n== Resultado por canción (se guarda si se reconoció al menos el 60 % de su letra)")
        for ((id, t) in best.toSortedMap()) {
            val ok = t.coverage >= 0.6
            report.appendLine((if (ok) "✓ " else "✗ ") + "$id ← ${t.video} · ${(t.coverage * 100).toInt()} % · ${t.outcome.bpm} BPM · ${t.outcome.order.joinToString(", ")}")
            if (ok && apply && library.song(id)?.timing.isNullOrEmpty()) library.applyRecording(id, t.outcome.bpm, t.outcome.order, t.outcome.timing)
        }
        File(list.parentFile, "reporte.txt").writeText(report.toString())
    }
}
