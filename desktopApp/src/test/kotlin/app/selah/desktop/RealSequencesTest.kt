package app.selah.desktop

import java.io.File
import androidx.compose.ui.use
import kotlin.test.Test

/**
 * Revisa secuencias reales con canciones reales (se omite si no se indica la carpeta Selah):
 *   SELAH_REAL_DIR=/ruta/Selah ./gradlew :desktopApp:test --tests '*RealSequencesTest*'
 * Escribe el reporte en <carpeta>/reporte-secuencias.txt.
 */
class RealSequencesTest {
    @Test
    fun report() {
        val dir = System.getenv("SELAH_REAL_DIR")?.let(::File) ?: return
        val library = Library(dir).also { it.reload() }
        val music = MusicLibrary(dir)
        music.refresh(library.catalog.value.songs) { id -> library.song(id)?.sequence }
        val started = System.currentTimeMillis()
        for (f in music.catalog.value.sequenceFiles) music.analysisOf(f.file)
        val cat = music.catalog.value
        val out = StringBuilder()
        fun title(id: String) = library.song(id)?.title ?: id
        val unreadable = cat.sequenceFiles.mapNotNull { f -> JavaSoundSequenceOutput().check(File(music.sequencesDir, f.file))?.let { "${f.file}: $it" } }
        out.appendLine("No se pueden reproducir: ${unreadable.size}"); unreadable.forEach { out.appendLine("   $it") }
        out.appendLine("Analizadas ${cat.sequenceFiles.size} en ${(System.currentTimeMillis() - started) / 1000} s")
        out.appendLine("\n== Con canción (${cat.sequences.size}):")
        for ((song, file) in cat.sequences.entries.sortedBy { title(it.key) }) {
            val i = cat.info(file)!!
            val a = i.analysis
            val versions = cat.versionsOf(song).size
            out.appendLine("${title(song)}  ←  $file  | nombre: ${i.name.bpm ?: "-"} BPM ${i.name.key ?: ""} | medido: ${if (a?.clickLeft == true) "click izq ${a.bpm} BPM, primer tiempo ${a.offsetMs} ms" + (if (a.tempoChanges) ", CAMBIA DE TEMPO" else "") else "SIN click a la izquierda"}" + if (versions > 1) " | $versions versiones" else "")
        }
        out.appendLine("\n== Para confirmar (${cat.toConfirm.size}):")
        cat.toConfirm.forEach { out.appendLine("${it.file}  ¿es?  ${title(it.songId!!)}") }
        out.appendLine("\n== Sin canción (${cat.sequenceOrphans.size}):")
        cat.sequenceOrphans.forEach { f -> val a = cat.info(f)?.analysis; out.appendLine("$f  | ${if (a?.clickLeft == true) "click izq ${a.bpm} BPM" else "sin click izq"}") }
        val noClick = cat.sequenceFiles.filter { it.analysis?.clickLeft != true }
        out.appendLine("\n== Sin click a la izquierda detectado (${noClick.size}):")
        noClick.forEach { f ->
            val (l, r, rate) = SequenceAnalysis.samples(File(music.sequencesDir, f.file)) ?: run { out.appendLine("${f.file}  (no se pudo leer)"); return@forEach }
            val ol = SequenceAnalysis.onsets(l, rate); val orr = SequenceAnalysis.onsets(r, rate)
            val guess = SequenceAnalysis.beatPeriod(ol)
            val dbg = if (guess == null) "sin pulso (${ol.size} golpes)" else {
                val (beat, ph) = SequenceAnalysis.fitGrid(ol, guess)
                "pulso %.0f ms (%.0f BPM), cubre izq %.2f der %.2f, golpes %d/%d".format(beat, 60000 / beat, SequenceAnalysis.coverage(ol, beat, ph), SequenceAnalysis.coverage(orr, beat, ph), ol.size, orr.size)
            }
            out.appendLine("${f.file}  | $dbg")
        }
        val mismatch = cat.sequenceFiles.filter { f -> val n = f.name.bpm; val m = f.analysis?.bpm; n != null && m != null && kotlin.math.abs(n - m) > 2 }
        out.appendLine("\n== Tempo del nombre distinto al medido (${mismatch.size}):")
        mismatch.forEach { out.appendLine("${it.file}: nombre ${it.name.bpm}, medido ${it.analysis?.bpm}") }
        // Seguir "de oído" el canal izquierdo real de algunas, como si llegara por la entrada de audio a otra hora.
        out.appendLine("\n== Escuchar el click (canal izquierdo real, empezando a una hora cualquiera):")
        for (f in cat.sequenceFiles.filter { it.analysis?.clickLeft == true }.take(12)) {
            val a = f.analysis!!
            val (left, _, rate) = SequenceAnalysis.samples(File(music.sequencesDir, f.file), 60) ?: continue
            var anchor: Long? = null
            var last: Long? = null
            val follower = ClickFollower(a.clicks, { anchor = it; last = it }, { last = it })
            val zero = 7_000_000L
            val chunk = rate / 50
            var i = 0
            while (i < left.size) {
                val n = minOf(chunk, left.size - i)
                val buf = ShortArray(n) { left[i + it].toShort() }
                follower.feed(buf, n, rate, zero + i * 1000L / rate)
                i += n
            }
            out.appendLine("${f.file}: " + (anchor?.let { "arrancó ${it - zero} ms corrido, al final ${last!! - zero} ms" } ?: "NO ARRANCÓ"))
        }
        File(dir, "reporte-secuencias.txt").writeText(out.toString())
        // Cómo se ve la ventana con estas secuencias
        val m = Musicians(church = ChurchFeatures(sequences = true), catalog = cat, songTitle = ::title, songs = library.catalog.value.songs)
        androidx.compose.ui.ImageComposeScene(820, 5200, androidx.compose.ui.unit.Density(1f)) { MusiciansContent(m, devices = emptyList(), start = ChurchTab.SEQUENCES) }.use { scene ->
            File(dir, "ventana-secuencias.png").writeBytes(scene.render(0).encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.bytes)
        }
    }
}
