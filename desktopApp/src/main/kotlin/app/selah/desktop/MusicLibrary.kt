package app.selah.desktop

import app.selah.shared.ProtocolJson
import app.selah.shared.ScoreRef
import app.selah.shared.SequenceName
import app.selah.shared.SongSummary
import app.selah.shared.matchScores
import app.selah.shared.matchSequence
import app.selah.shared.normalizeText
import app.selah.shared.parseSequenceName
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File

/** Un audio de la carpeta secuencias: qué dice su nombre, a qué canción va y qué se oyó en él. */
data class SequenceFile(
    val file: String,
    val name: SequenceName,
    /** Canción que le corresponde (por el nombre, o porque la canción la eligió con "Secuencia:"). */
    val songId: String? = null,
    /** El título coincide completo; si no, solo el comienzo ("Gracias Señor Jesús" ↔ "Gracias"): hay que confirmarlo. */
    val exact: Boolean = false,
    /** null = todavía no se analizó. */
    val analysis: AudioAnalysis? = null,
)

/** Partituras y secuencias ya asociadas con las canciones. */
data class MusicCatalog(
    val scores: List<ScoreRef> = emptyList(),
    /** Partituras cuyo nombre no corresponde a ninguna canción. */
    val scoreOrphans: List<String> = emptyList(),
    /** Canción (id) → audio que suena con ella (la versión elegida o la que coincide). */
    val sequences: Map<String, String> = emptyMap(),
    /** Todos los audios de la carpeta. */
    val sequenceFiles: List<SequenceFile> = emptyList(),
) {
    /** Audios que no corresponden a ninguna canción. */
    val sequenceOrphans: List<String> get() = sequenceFiles.filter { it.songId == null }.map { it.file }

    /** Audios que se parecen a una canción solo por el comienzo y nadie ha confirmado. */
    val toConfirm: List<SequenceFile> get() = sequenceFiles.filter { it.songId != null && !it.exact && sequences[it.songId] != it.file }

    /** Versiones que puede elegir una canción (las que coinciden y la elegida). */
    fun versionsOf(songId: String): List<SequenceFile> = sequenceFiles.filter { it.songId == songId && (it.exact || sequences[songId] == it.file) }

    fun info(file: String): SequenceFile? = sequenceFiles.firstOrNull { it.file == file }
}

/** Lo que necesita el presentador para hacer sonar una secuencia. */
data class SequenceSource(
    val file: File, val bpm: Int?, val clickLeft: Boolean, val offsetMs: Int, val tempoChanges: Boolean = false,
    val durationMs: Long = 0,
    /** Golpes del click en el audio (ms), para seguir el click que se escucha por la entrada de audio. */
    val clicks: List<Int> = emptyList(),
)

/**
 * Lo de los músicos en disco:
 *   <base>/partituras/   PDF por canción e instrumento: "Canción - Bajo.pdf" o "Canción/Bajo.pdf"
 *   <base>/secuencias/   audio de la canción: "Canción.mp3", "Canción - 150 BPM - D.wav", "Canción No Bass.wav"…
 * Las páginas de las partituras se convierten en imágenes una vez (como las presentaciones). Cada audio se
 * escucha una vez (en segundo plano) para saber si trae el click a la izquierda y su tempo; se guarda en
 * secuencias/.analisis.json.
 */
class MusicLibrary(baseDir: File) {
    val scoresDir = File(baseDir, "partituras")
    val sequencesDir = File(baseDir, "secuencias")
    val scorePages = Presentations(scoresDir, nested = true, extensions = setOf("pdf"))
    private val analysisFile = File(sequencesDir, ".analisis.json")

    private val _catalog = MutableStateFlow(MusicCatalog())
    val catalog: StateFlow<MusicCatalog> = _catalog.asStateFlow()

    @Serializable
    private data class Cached(val stamp: String, val analysis: AudioAnalysis)
    private val cache: MutableMap<String, Cached> = loadCache()
    @Volatile private var analyzing = false

    /**
     * Vuelve a mirar las carpetas y asocia los archivos con [songs]. [chosen] dice qué eligió cada canción con su
     * línea "Secuencia:" (un archivo, "ninguna", o null = la que coincida).
     */
    @Synchronized
    fun refresh(songs: List<SongSummary>, chosen: (songId: String) -> String? = { null }) {
        scoresDir.mkdirs()
        sequencesDir.mkdirs()
        val (scores, orphans) = matchScores(scorePages.list(), songs)
        val names = sequencesDir.listFiles { f -> f.isFile && isSequenceFile(f.name) }.orEmpty().map { it.name }.sortedBy { normalizeText(it) }
        val explicit = songs.mapNotNull { s -> chosen(s.id)?.let { s.id to it } }.toMap()
        val claimedBy = explicit.entries.filter { it.value in names }.associate { it.value to it.key }
        val files = names.map { f ->
            val name = parseSequenceName(f)
            val owner = claimedBy[f]
            val match = if (owner == null) matchSequence(name, songs) else null
            SequenceFile(f, name, owner ?: match?.first?.id, exact = owner != null || match?.second == true, analysis = cached(f))
        }
        val sequences = LinkedHashMap<String, String>()
        for (s in songs) {
            val pick = explicit[s.id]
            when {
                pick != null && pick.equals(NONE, ignoreCase = true) -> {}
                pick != null && pick in names -> sequences[s.id] = pick
                // Varias versiones: primero la que no dice versión, luego por nombre.
                else -> files.filter { it.songId == s.id && it.exact }.minWithOrNull(compareBy({ it.name.version.isNotEmpty() }, { normalizeText(it.file) }))
                    ?.let { sequences[s.id] = it.file }
            }
        }
        val new = MusicCatalog(scores, orphans, sequences, files)
        if (new != _catalog.value) {
            _catalog.value = new
            scorePages.prepareAllInBackground()
        }
        analyzeMissingInBackground()
    }

    // Con el número de formato: si el análisis aprende algo nuevo, se vuelve a escuchar todo.
    private fun stamp(f: File) = "${f.length()}|${f.lastModified()}|$ANALYSIS_VERSION"

    private fun cached(name: String): AudioAnalysis? = File(sequencesDir, name).let { f -> cache[name]?.takeIf { it.stamp == stamp(f) }?.analysis }

    /** Escucha los audios que falten, uno por uno, sin estorbar. */
    private fun analyzeMissingInBackground() {
        if (analyzing || _catalog.value.sequenceFiles.none { it.analysis == null }) return
        analyzing = true
        Thread({
            try {
                while (true) {
                    val next = _catalog.value.sequenceFiles.firstOrNull { it.analysis == null } ?: break
                    analysisOf(next.file)
                }
            } finally { analyzing = false }
        }, "analizar-secuencias").apply { isDaemon = true; priority = Thread.MIN_PRIORITY; start() }
    }

    /** El análisis de un audio (lo hace ahora si falta) y lo deja en el catálogo. */
    fun analysisOf(name: String): AudioAnalysis {
        cached(name)?.let { return it }
        val f = File(sequencesDir, name)
        val a = SequenceAnalysis.analyze(f)
        synchronized(this) {
            cache[name] = Cached(stamp(f), a)
            saveCache()
            _catalog.value = _catalog.value.let { c -> c.copy(sequenceFiles = c.sequenceFiles.map { if (it.file == name) it.copy(analysis = a) else it }) }
        }
        return a
    }

    /** El audio de la canción [songId] con su tempo (de la canción, del nombre o medido) y si trae el click. */
    fun sourceFor(songId: String): SequenceSource? {
        val name = _catalog.value.sequences[songId] ?: return null
        val f = File(sequencesDir, name).takeIf { it.isFile } ?: return null
        val info = _catalog.value.info(name)
        val a = analysisOf(name)
        // Si el audio trae su click, el de los celulares va igual al que la banda ya oye (lo medido, no el nombre:
        // "Dios mío - 90 BPM" marca corcheas a 180). Si ese click cambia de tempo, los celulares no suenan.
        val bpm = when {
            a.clickLeft && a.tempoChanges -> null
            a.clickLeft -> a.bpm
            else -> info?.name?.bpm
        }
        return SequenceSource(f, bpm, a.clickLeft, if (a.clickLeft) a.offsetMs else 0, tempoChanges = a.tempoChanges, durationMs = a.durationMs, clicks = a.clicks)
    }

    /** Archivo de la secuencia de la canción [songId], si tiene. */
    fun sequenceFor(songId: String): File? = _catalog.value.sequences[songId]?.let { File(sequencesDir, it) }?.takeIf { it.isFile }

    private fun loadCache(): MutableMap<String, Cached> = runCatching {
        ProtocolJson.decodeFromString(MapSerializer(String.serializer(), Cached.serializer()), analysisFile.readText()).toMutableMap()
    }.getOrDefault(mutableMapOf())

    private fun saveCache() = runCatching {
        // Solo los que siguen en la carpeta.
        val keep = cache.filterKeys { File(sequencesDir, it).isFile }
        analysisFile.writeText(ProtocolJson.encodeToString(MapSerializer(String.serializer(), Cached.serializer()), keep))
    }

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
        /** "Secuencia: ninguna" en una canción: no usar ningún audio. */
        const val NONE = "ninguna"
        private const val ANALYSIS_VERSION = 2
        fun isSequenceFile(name: String) = name.substringAfterLast('.', "").lowercase() in SEQUENCE_EXTENSIONS && !name.startsWith(".")
    }
}
