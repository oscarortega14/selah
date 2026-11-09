package app.selah.desktop

import app.selah.shared.ScoreRef
import app.selah.shared.SongSummary
import app.selah.shared.findSongByName
import app.selah.shared.matchScores
import app.selah.shared.normalizeText
import app.selah.shared.splitMusicFileName
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** Partituras y secuencias ya asociadas con las canciones. */
data class MusicCatalog(
    val scores: List<ScoreRef> = emptyList(),
    /** Partituras cuyo nombre no corresponde a ninguna canción. */
    val scoreOrphans: List<String> = emptyList(),
    /** Canción (id) → archivo de su secuencia. */
    val sequences: Map<String, String> = emptyMap(),
    val sequenceOrphans: List<String> = emptyList(),
)

/**
 * Lo de los músicos en disco:
 *   <base>/partituras/   PDF por canción e instrumento: "Canción - Bajo.pdf" o "Canción/Bajo.pdf"
 *   <base>/secuencias/   audio de la canción: "Canción.mp3" (o .wav)
 * Las páginas de las partituras se convierten en imágenes una vez (como las presentaciones).
 */
class MusicLibrary(baseDir: File) {
    val scoresDir = File(baseDir, "partituras")
    val sequencesDir = File(baseDir, "secuencias")
    val scorePages = Presentations(scoresDir, nested = true, extensions = setOf("pdf"))

    private val _catalog = MutableStateFlow(MusicCatalog())
    val catalog: StateFlow<MusicCatalog> = _catalog.asStateFlow()

    /** Vuelve a mirar las carpetas y asocia los archivos con [songs]. */
    @Synchronized
    fun refresh(songs: List<SongSummary>) {
        scoresDir.mkdirs()
        sequencesDir.mkdirs()
        val (scores, orphans) = matchScores(scorePages.list(), songs)
        val seqFiles = sequencesDir.listFiles { f -> f.isFile && isSequenceFile(f.name) }.orEmpty().map { it.name }.sortedBy { normalizeText(it) }
        val sequences = LinkedHashMap<String, String>()
        val seqOrphans = mutableListOf<String>()
        for (f in seqFiles) {
            val stem = f.substringBeforeLast('.')
            // "Canción.mp3" o "Canción - secuencia.mp3"
            val song = findSongByName(stem, songs) ?: findSongByName(splitMusicFileName(f).first, songs)
            if (song != null && song.id !in sequences) sequences[song.id] = f else seqOrphans += f
        }
        val new = MusicCatalog(scores, orphans, sequences, seqOrphans)
        if (new != _catalog.value) {
            _catalog.value = new
            scorePages.prepareAllInBackground()
        }
    }

    /** Archivo de la secuencia de la canción [songId], si tiene. */
    fun sequenceFor(songId: String): File? = _catalog.value.sequences[songId]?.let { File(sequencesDir, it) }?.takeIf { it.isFile }

    /** Copia PDFs de partituras (o audios de secuencias) a su carpeta. Devuelve cuántos se agregaron. */
    fun addScores(files: List<File>): Int = copyInto(scoresDir, files.filter { it.extension.lowercase() == "pdf" })
    fun addSequences(files: List<File>): Int = copyInto(sequencesDir, files.filter { isSequenceFile(it.name) })

    private fun copyInto(dir: File, files: List<File>): Int {
        dir.mkdirs()
        var n = 0
        for (f in files.filter { it.isFile }) {
            val target = File(dir, app.selah.shared.avoidReservedName(f.nameWithoutExtension) + "." + f.extension)
            if (target.exists() && Library.sameContent(target, f)) continue
            f.copyTo(target, overwrite = true)
            n++
        }
        return n
    }

    companion object {
        /** Formatos que se pueden reproducir sin instalar nada. */
        val SEQUENCE_EXTENSIONS = setOf("mp3", "wav")
        fun isSequenceFile(name: String) = name.substringAfterLast('.', "").lowercase() in SEQUENCE_EXTENSIONS && !name.startsWith(".")
    }
}
