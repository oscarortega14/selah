package app.selah.desktop

import java.io.File
import kotlin.test.Test

/** SELAH_REAL_DIR=/ruta/Selah SELAH_VOSK=/ruta/modelo ./gradlew :desktopApp:test --tests '*GuideVoiceRealTest*' */
class GuideVoiceRealTest {
    @Test
    fun readRealGuides() {
        val dir = System.getenv("SELAH_REAL_DIR")?.let(::File) ?: return
        val model = System.getenv("SELAH_VOSK")?.let(::File) ?: return
        val out = StringBuilder()
        for (name in listOf("Bueno es Alabar - 115 BMP - G .wav", "Salmo 23 No Bass.wav")) {
            val t0 = System.currentTimeMillis()
            val words = GuideVoice.read(File(dir, "secuencias/$name"), model)
            out.appendLine("== $name (${(System.currentTimeMillis() - t0) / 1000} s)")
            val music = MusicLibrary(dir)
            val a = SequenceAnalysis.analyze(File(dir, "secuencias/$name"))
            val beat = a.bpm?.takeIf { a.clickLeft && !a.tempoChanges }?.let { 60_000.0 / it }
            val cues = TimingGuess.cues(words, beat, a.offsetMs)
            cues.forEach { out.appendLine("  %6.1f → empieza %6.1f  %s".format(it.saidAt / 1000.0, it.startMs / 1000.0, it.kind)) }
            val songName = mapOf("Bueno es Alabar - 115 BMP - G .wav" to "Bueno es alabar", "Salmo 23 No Bass.wav" to "Salmo 23")[name]!!
            val text = File(dir, "canciones/$songName.txt").readText()
            val sug = TimingGuess.suggest(text, cues, beat ?: 1000.0, a.offsetMs, a.durationMs)
            out.appendLine("  Orden: ${sug?.order}")
            out.appendLine("  Tiempos: ${sug?.timing}")
        }
        File(dir, "reporte-guia.txt").writeText(out.toString())
    }

    /** SELAH_REAL_DIR=… SELAH_SUGERIR=1: sugiere para todas las canciones con secuencia de esa carpeta (¡las modifica!). */
    @Test
    fun suggestAll() {
        val dir = System.getenv("SELAH_REAL_DIR")?.let(::File) ?: return
        if (System.getenv("SELAH_SUGERIR") != "1") return
        val library = Library(dir).also { it.reload() }
        val music = MusicLibrary(dir)
        music.refresh(library.catalog.value.songs) { id -> library.song(id)?.sequence }
        val guide = GuideTimings(library, music, VoiceModel(dir))
        val t0 = System.currentTimeMillis()
        val results = guide.run(guide.pending()) {}
        val out = StringBuilder("${results.count { it.ok }} de ${results.size} en ${(System.currentTimeMillis() - t0) / 1000} s\n")
        results.forEach { out.appendLine((if (it.ok) "✓ " else "✗ ") + it.title + ": " + it.message) }
        File(dir, "reporte-sugeridos.txt").writeText(out.toString())
    }
}
