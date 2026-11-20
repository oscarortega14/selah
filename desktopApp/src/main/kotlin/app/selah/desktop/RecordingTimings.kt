package app.selah.desktop

import app.selah.shared.SongParser
import java.io.File
import kotlin.math.roundToInt

/**
 * Letra automática desde una grabación: con un video o audio de cómo se toca la canción (siempre igual), Selah
 * saca el tempo, las vueltas (el Orden) y en qué tiempo del click cambia cada diapositiva. En el culto, con el click
 * a ese tempo, la letra pasa sola.
 */
class RecordingTimings(private val library: Library, private val model: WhisperModelFile) {
    data class Outcome(val bpm: Int, val order: List<String>, val timing: List<Int>)

    /** Lo hace todo y lo guarda en la canción. [status] va diciendo qué hace. */
    fun run(songId: String, recording: File, status: (String) -> Unit): Outcome {
        SungLyrics.problem?.let { throw IllegalStateException(it) }
        val song = library.song(songId) ?: throw IllegalArgumentException("Canción no encontrada")
        val text = library.rawText(songId) ?: throw IllegalStateException("No pude leer la canción")
        val sections = sections(text)
        if (sections.isEmpty()) throw IllegalStateException("La canción no tiene letra para ubicar")
        if (!model.ready) model.download { p -> status("Descargando lo necesario para entender el canto (una sola vez)… ${(p * 100).toInt()} %") }
        status("Leyendo el audio de «${recording.name}»…")
        val samples = RecordingAudio.read(recording)
        status("Buscando el pulso…")
        val beats = BeatTracker.track(samples, song.bpm)
            ?: throw IllegalStateException("No encontré el pulso de la canción en la grabación")
        status("Escuchando la letra (puede tardar un par de minutos)…")
        val heard = SungLyrics.hear(samples, model.file, "")
        val outcome = outcome(heard, sections, beats, RecordingAudio.seconds(samples))
            ?: throw IllegalStateException("No reconocí la letra de «${song.title}» en la grabación. ¿Es la misma canción?")
        library.applyRecording(songId, outcome.bpm, outcome.order, outcome.timing)
        return outcome
    }

    companion object {
        /** La diapositiva aparece 2 tiempos antes de que se empiece a cantar: la gente alcanza a leer. */
        const val LEAD_BEATS = 2.0

        /** Las secciones de la canción con su letra, en el orden en que están escritas (sin el "Orden:"). */
        fun sections(songText: String): List<LyricsAligner.Section> {
            val noOrder = songText.lines().filterNot { Regex("""^\s*orden\s*:""", RegexOption.IGNORE_CASE).containsMatchIn(it) }.joinToString("\n")
            val out = mutableListOf<Pair<String, MutableList<String>>>()
            for (s in SongParser.parse("x", noOrder).slides) {
                if (s.label.isNotEmpty() || out.isEmpty()) out += (s.label.ifEmpty { SongParser.START_LABEL }) to mutableListOf()
                out.last().second += s.text
            }
            return out.map { LyricsAligner.Section(it.first, it.second) }
        }

        /** De lo oído y los pulsos: el tempo, el orden y los tiempos (en pulsos desde el primero de la grabación). */
        fun outcome(heard: List<HeardPhrase>, sections: List<LyricsAligner.Section>, beats: BeatTracker.Beats, seconds: Double): Outcome? {
            val aligned = LyricsAligner.align(heard, sections, seconds) ?: return null
            if (aligned.starts.count { it != null } < 2) return null
            var last = -1
            val timing = aligned.starts.map { at ->
                if (at == null) -1
                else (BeatTracker.beatAt(beats.times, at) - LEAD_BEATS).roundToInt().coerceAtLeast(maxOf(0, last + 1)).also { last = it }
            }
            return Outcome(beats.bpm.roundToInt(), aligned.order, timing)
        }
    }
}
