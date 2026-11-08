package app.selah.desktop

import app.selah.shared.takeWhole
import app.selah.shared.cleanListName
import app.selah.shared.Bible
import app.selah.shared.Song
import app.selah.shared.SongParser
import app.selah.shared.SongSummary
import app.selah.shared.normalizeText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Lo que el remoto necesita saber de la biblioteca. */
data class ImportResult(val added: Int, val skipped: Int, val renamed: Int)

data class Catalog(
    val songs: List<SongSummary> = emptyList(),
    val bibleName: String? = null,
    val bibleError: String? = null,
    /** Traducciones para elegir (las de la carpeta biblia/ y las que vienen con el programa). */
    val bibleNames: List<String> = emptyList(),
    /** Créditos que exige la licencia de la traducción en uso. */
    val bibleNotice: String? = null,
    /** Archivos de la carpeta anuncios (imágenes y videos). */
    val announcements: List<String> = emptyList(),
    /** Predicaciones (carpeta predicaciones), con el mismo formato que las canciones. */
    val sermons: List<SongSummary> = emptyList(),
    /** Segunda traducción que sale debajo de la primera, o null. */
    val secondBibleName: String? = null,
    /** Presentaciones (PowerPoint y PDF) de la carpeta presentaciones. */
    val presentations: List<String> = emptyList(),
)

/**
 * Biblioteca en disco:
 *   <base>/canciones/  un archivo .txt por canción
 *   <base>/biblia/     Biblias propias (.txt VPL, .xml Zefania o .json), además de las que vienen con el programa
 */
class Library(val baseDir: File) {
    val songsDir = File(baseDir, "canciones")
    val bibleDir = File(baseDir, "biblia")
    val backgroundsDir = File(baseDir, "fondos")
    val announcementsDir = File(baseDir, "anuncios")
    val sermonsDir = File(baseDir, "predicaciones")
    val presentations = Presentations(File(baseDir, "presentaciones"))
    @Volatile private var sermons: Map<String, Song> = emptyMap()

    fun sermon(id: String): Song? = sermons[id]

    @Volatile private var songs: Map<String, Song> = emptyMap()
    @Volatile var bible: Bible? = null
        private set

    /** Traducción elegida por el operador (null = la primera de la carpeta, o la Reina-Valera 1909). */
    @Volatile var preferredBible: String? = null

    /** Segunda traducción elegida (null = ninguna). */
    @Volatile var preferredSecondBible: String? = null

    /** Segunda traducción en uso (nunca la misma que la primera). */
    @Volatile var secondBible: Bible? = null
        private set

    /** Biblias de la carpeta biblia/, ya leídas. */
    @Volatile private var userBibles: Map<String, Bible> = emptyMap()

    /** Nombres de las Biblias agregadas (descargadas o desde un archivo); las que vienen con el programa no cuentan. */
    val userBibleNames: Set<String> get() = userBibles.keys
    @Volatile private var bibleErrors: String? = null

    private val _catalog = MutableStateFlow(Catalog())
    val catalog: StateFlow<Catalog> = _catalog.asStateFlow()

    fun song(id: String): Song? = songs[id]

    /** Canciones por título o por cualquier frase de la letra. */
    fun searchSongs(query: String): List<app.selah.shared.SongHit> = app.selah.shared.searchSongsFull(songs.values, query)

    private fun songFile(id: String, sermon: Boolean = false) = File(if (sermon) sermonsDir else songsDir, "$id.txt")

    /** Texto tal como está en el archivo, para el editor. */
    fun rawText(id: String, sermon: Boolean = false): String? = songFile(id, sermon).takeIf { it.isFile }?.let { readTextSmart(it) }

    /**
     * Guarda una canción y recarga la biblioteca. El nombre del archivo sale del título,
     * así que si el título cambia el archivo se renombra. Devuelve el id (nombre) final.
     * Lanza IllegalArgumentException con un mensaje para el usuario si algo no cuadra.
     */
    @Synchronized
    fun save(oldId: String?, text: String, sermon: Boolean = false): String {
        val dir = if (sermon) sermonsDir else songsDir
        val what = if (sermon) "una predicación" else "una canción"
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n').trimEnd() + "\n"
        if (!normalized.trimStart().startsWith("#")) {
            throw IllegalArgumentException("La primera línea debe ser el título, por ejemplo: # " + if (sermon) "El buen pastor" else "Cuán grande es Él")
        }
        val title = SongParser.parse("", normalized).title.trim()
        if (title.isEmpty()) throw IllegalArgumentException("Falta el título después de #")
        val newId = fileNameFor(title)
        if (newId.isEmpty()) throw IllegalArgumentException("El título no sirve como nombre de archivo")
        val existing = songFile(newId, sermon)
        val renaming = oldId != null && !newId.equals(oldId, ignoreCase = true)
        if ((oldId == null || renaming) && existing.exists()) {
            throw IllegalArgumentException("Ya existe $what llamada \"$title\"")
        }

        dir.mkdirs()
        // Escribir primero a un temporal para no dejar el archivo a medias si algo falla.
        val tmp = File(dir, ".$newId.tmp")
        tmp.writeText(normalized, Charsets.UTF_8)
        if (oldId != null) songFile(oldId, sermon).takeIf { it.exists() }?.delete()
        if (!tmp.renameTo(existing)) {
            existing.writeText(normalized, Charsets.UTF_8)
            tmp.delete()
        }
        reload()
        return newId
    }

    /** Imágenes disponibles en Selah/fondos (nombres de archivo, en orden alfabético). */
    fun backgrounds(): List<String> {
        backgroundsDir.mkdirs()
        return backgroundsDir.listFiles { f -> f.isFile && f.extension.lowercase() in IMAGE_EXTENSIONS }
            .orEmpty().map { it.name }.sortedBy { it.lowercase() }
    }

    /** Videos para fondo en Selah/fondos (se reproducen con VLC). */
    fun videos(): List<String> {
        backgroundsDir.mkdirs()
        return backgroundsDir.listFiles { f -> f.isFile && isVideoFile(f.name) }
            .orEmpty().map { it.name }.sortedBy { it.lowercase() }
    }

    fun backgroundFile(name: String): File? =
        File(backgroundsDir, name).takeIf { it.isFile && it.parentFile == backgroundsDir }

    /**
     * Pone (o quita, con null) la línea "Fondo:" de una canción, justo debajo del título,
     * conservando el resto del texto. Devuelve el id de la canción.
     */
    @Synchronized
    fun setSongBackground(id: String, background: String?): String {
        val text = rawText(id) ?: throw IllegalArgumentException("Canción no encontrada")
        val lines = text.replace("\r\n", "\n").split('\n').filterNot { FONDO_LINE.matches(it.trim()) }.toMutableList()
        if (background != null) {
            val titleAt = lines.indexOfFirst { it.trimStart().startsWith("#") }
            lines.add(titleAt + 1, "Fondo: $background")
        }
        return save(id, lines.joinToString("\n"))
    }

    /** Cambia la línea "Tipo:" de una canción (vacío = sin tipo). Devuelve su id (no cambia). */
    fun setSongTags(id: String, tags: List<String>): String {
        val text = rawText(id) ?: throw IllegalArgumentException("Canción no encontrada")
        // Solo en el encabezado (antes de la primera estrofa): una línea de la letra que diga "Tipo:" no se toca.
        val all = text.replace("\r\n", "\n").split('\n')
        val firstLyric = all.indexOfFirst { l -> val t = l.trim(); t.isNotEmpty() && !t.startsWith("#") && HEADER_LINES.none { it.matches(t) } }
            .let { if (it < 0) all.size else it }
        val lines = all.filterIndexed { i, l -> i >= firstLyric || !TYPE_LINE.matches(l.trim()) }.toMutableList()
        val clean = SongParser.parseTags(tags.joinToString(","))
        if (clean.isNotEmpty()) {
            val titleAt = lines.indexOfFirst { it.trimStart().startsWith("#") }
            lines.add(titleAt + 1, "Tipo: " + clean.joinToString(", "))
        }
        return save(id, lines.joinToString("\n"))
    }

    /**
     * Importa canciones: archivos .txt sueltos o dentro de .zip. Si ya existe una con el mismo
     * nombre y el mismo texto se omite; si el texto es distinto se guarda como "Nombre (2)".
     */
    @Synchronized
    fun importSongs(sources: List<File>): ImportResult {
        songsDir.mkdirs()
        var added = 0; var skipped = 0; var renamed = 0
        fun take(name: String, text: String) {
            val base = cleanListName(name) ?: return
            var target = File(songsDir, "$base.txt")
            if (target.exists()) {
                if (readTextSmart(target).trim() == text.trim()) { skipped++; return }
                var n = 2
                while (File(songsDir, "$base ($n).txt").exists()) n++
                target = File(songsDir, "$base ($n).txt")
                renamed++
            }
            target.writeText(text, Charsets.UTF_8)
            added++
        }
        // .txt de Selah, .xml OpenLyrics (OpenLP) y .json de Holyrics
        fun takeFile(name: String, bytes: ByteArray) {
            val ext = name.substringAfterLast('.', "").lowercase()
            when (ext) {
                "txt" -> take(File(name).nameWithoutExtension, decodeSmart(bytes))
                "xml" -> SongImport.fromOpenLyrics(decodeSmart(bytes))?.let { take(it.title, it.text) }
                "json" -> SongImport.fromHolyricsJson(decodeSmart(bytes)).forEach { take(it.title, it.text) }
            }
        }
        fun isSongSource(name: String) = name.substringAfterLast('.', "").lowercase() in IMPORT_EXTENSIONS
        for (src in sources) {
            when {
                // EasyWorship 6/7: Songs.db + SongWords.db, o un respaldo .ewsx
                EasyWorshipImport.isSource(src.name) -> EasyWorshipImport.read(src).forEach { take(it.title, it.text) }
                src.isDirectory -> src.listFiles { f -> f.isFile && isSongSource(f.name) }.orEmpty().forEach { takeFile(it.name, it.readBytes()) }
                src.extension.equals("zip", true) -> java.util.zip.ZipFile(src).use { zip ->
                    zip.entries().asSequence()
                        .filter { !it.isDirectory && isSongSource(it.name) && !it.name.contains("__MACOSX") }
                        .forEach { e -> takeFile(e.name.substringAfterLast('/'), zip.getInputStream(e).use { readLimited(it, MAX_SONG_BYTES, e.name) }) }
                }
                isSongSource(src.name) -> takeFile(src.name, src.inputStream().use { readLimited(it, MAX_SONG_BYTES, src.name) })
            }
        }
        reload()
        return ImportResult(added, skipped, renamed)
    }

    /** Guarda todas las canciones en un .zip (respaldo o para pasarlas a otro PC). Devuelve cuántas. */
    fun exportSongs(target: File): Int {
        val files = songsDir.listFiles { f -> f.isSongFile() }.orEmpty().sortedBy { it.name }
        java.util.zip.ZipOutputStream(target.outputStream()).use { zip ->
            for (f in files) {
                zip.putNextEntry(java.util.zip.ZipEntry("canciones/${f.name}"))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return files.size
    }

    @Synchronized
    fun delete(id: String, sermon: Boolean = false) {
        songFile(id, sermon).delete()
        reload()
    }

    @Synchronized
    fun reload() {
        songsDir.mkdirs()
        bibleDir.mkdirs()
        backgroundsDir.mkdirs()
        announcementsDir.mkdirs()
        sermonsDir.mkdirs()
        presentations.dir.mkdirs()
        presentations.prepareAllInBackground()
        if (sermonsDir.listFiles { f -> f.isSongFile() }.isNullOrEmpty()) writeExampleSermon()
        sermons = sermonsDir.listFiles { f -> f.isSongFile() }.orEmpty()
            .map { f -> SongParser.parse(f.nameWithoutExtension, readTextSmart(f), f.nameWithoutExtension) }
            .associateBy { it.id }
        if (songsDir.listFiles { f -> f.isSongFile() }.isNullOrEmpty()) writeExampleSong()

        val loaded = songsDir.listFiles { f -> f.isSongFile() }.orEmpty()
            .map { f -> SongParser.parse(f.nameWithoutExtension, readTextSmart(f), f.nameWithoutExtension) }
            .sortedBy { normalizeText(it.title) }
        songs = loaded.associateBy { it.id }

        val errors = mutableListOf<String>()
        // .txt (formato VPL de eBible.org), .xml (Zefania) o .json (formato propio)
        userBibles = bibleDir.listFiles { f -> f.isFile && f.extension.lowercase() in BIBLE_EXTENSIONS }
            .orEmpty().sortedBy { it.name.lowercase() }
            .mapNotNull { f ->
                runCatching { parseBible(readTextSmart(f), f.extension, f.nameWithoutExtension) }
                    .onFailure { errors += "No pude leer ${f.name}: ${it.message}"; Log.error("No pude leer la Biblia ${f.name}", it) }
                    .getOrNull()
            }
            .associateBy { it.name }
        bibleErrors = errors.joinToString(" · ").ifEmpty { null }
        refreshBible(loaded.map { it.summary() })
    }

    /** Cambiar de traducción (se recuerda en [preferredBible]). */
    @Synchronized
    fun selectBible(name: String): Boolean {
        // Solo una de las que hay (un nombre inventado desde la red no cambia la Biblia en uso).
        if (name !in _catalog.value.bibleNames) return false
        preferredBible = name
        refreshBible(_catalog.value.songs)
        return true
    }

    /** Elegir la segunda traducción (null = ninguna). */
    @Synchronized
    fun selectSecondBible(name: String?): Boolean {
        if (name != null && name !in _catalog.value.bibleNames) return false
        preferredSecondBible = name
        refreshBible(_catalog.value.songs)
        return true
    }

    private fun refreshBible(songs: List<SongSummary>) {
        val names = (userBibles.keys + BUNDLED_BIBLES).distinct()
        val active = preferredBible?.takeIf { it in names } ?: userBibles.keys.firstOrNull() ?: BUNDLED_BIBLES.first()
        bible = userBibles[active] ?: bundled(active)
        secondBible = preferredSecondBible?.takeIf { it in names && it != active }?.let { userBibles[it] ?: bundled(it) }
        // El índice de búsqueda se arma en segundo plano: la primera búsqueda ya no espera (~350 ms → ~50 ms).
        listOfNotNull(bible, secondBible).forEach { b -> Thread({ runCatching { b.warmUp() } }, "indice-biblia").apply { isDaemon = true; start() } }
        val error = bibleErrors ?: if (bible == null) "No hay Biblia. Usa \"＋ Más Biblias…\" para descargar una o agregarla desde un archivo" else null
        _catalog.value = Catalog(
            songs, bible?.name, error, names, BIBLE_NOTICES[bible?.name], scanAnnouncements(),
            sermons.values.sortedBy { normalizeText(it.title) }.map { it.summary() },
            secondBible?.name,
            presentations.list(),
        )
    }

    private fun scanAnnouncements(): List<String> = announcementsDir.listFiles { f -> f.isFile && isMediaFile(f.name) }.orEmpty()
        .sortedBy { normalizeText(it.name) }.map { it.name }

    /** Archivo de un anuncio, solo si está dentro de la carpeta (nada de "../"). */
    fun announcementFile(name: String): File? =
        File(announcementsDir, name).takeIf { it.isFile && it.parentFile == announcementsDir && isMediaFile(name) }

    /** Copia imágenes o videos a la carpeta de anuncios. Devuelve cuántos se agregaron. */
    @Synchronized
    fun addAnnouncements(files: List<File>): Int = files.count { addAnnouncement(it) != null }

    /**
     * Copia un anuncio. Si ya hay uno con ese nombre y el MISMO contenido, no se repite (devuelve null);
     * si el contenido es otro (aunque pese igual), se guarda como "nombre (2).jpg". Devuelve el nombre final.
     * [maxFolderBytes]: no copiar si la carpeta pasaría de ese tamaño (se revisa y copia sin que otra
     * subida se meta en medio). Lanza IllegalStateException si no cabe.
     */
    @Synchronized
    fun addAnnouncement(f: File, maxFolderBytes: Long = Long.MAX_VALUE): String? {
        if (!f.isFile || !isMediaFile(f.name)) return null
        announcementsDir.mkdirs()
        var target = File(announcementsDir, avoidReservedName(f.nameWithoutExtension) + "." + f.extension)
        var i = 2
        while (target.exists()) {
            if (sameContent(target, f)) return null
            target = File(announcementsDir, "${f.nameWithoutExtension} ($i).${f.extension}").also { i++ }
        }
        if (announcementsBytes() + f.length() > maxFolderBytes) throw IllegalStateException("sin espacio")
        f.copyTo(target)
        refreshAnnouncements()
        return target.name
    }

    /** Lo que ocupa la carpeta de anuncios. */
    fun announcementsBytes(): Long = announcementsDir.listFiles().orEmpty().sumOf { it.length() }

    /** Copia presentaciones a su carpeta y las empieza a convertir. Devuelve cuántas se agregaron. */
    @Synchronized
    fun addPresentations(files: List<File>): Int {
        val n = presentations.add(files)
        _catalog.value = _catalog.value.copy(presentations = presentations.list())
        presentations.prepareAllInBackground()
        return n
    }

    @Synchronized
    fun refreshPresentations() {
        _catalog.value = _catalog.value.copy(presentations = presentations.list())
    }

    @Synchronized
    fun refreshAnnouncements() {
        _catalog.value = _catalog.value.copy(announcements = scanAnnouncements())
    }

    private fun File.isSongFile() = isFile && extension.equals("txt", true)

    private fun writeExampleSermon() {
        File(sermonsDir, "Ejemplo de predicación.txt").writeText(
            """
            # El buen pastor

            Introducción:
            Dios nos busca primero
            aunque nos alejemos

            Biblia: Juan 10:11

            Punto 1:
            Él conoce a sus ovejas

            Punto 2:
            Él da su vida por ellas

            Biblia: Salmos 23:1-3

            Conclusión:
            Volvamos al buen pastor
            """.trimIndent() + "\n",
            Charsets.UTF_8,
        )
    }

    private fun writeExampleSong() {
        File(songsDir, "Canción de ejemplo.txt").writeText(
            """
            # Canción de ejemplo

            Esta es la primera estrofa
            Cada bloque separado por una línea en blanco
            es una diapositiva

            Coro:
            Si la primera línea termina en dos puntos
            se usa como etiqueta (Coro, Verso 2, Puente…)

            Puedes borrar este archivo y crear
            un .txt por cada canción en esta carpeta
            """.trimIndent() + "\n",
            Charsets.UTF_8,
        )
    }

    companion object {
        /** Biblias que vienen con el programa (archivos VPL en resources/biblia). */
        val BUNDLED_BIBLES = listOf("Reina-Valera 1909", "Versión Biblia Libre")

        /** Formatos de Biblia que se leen de la carpeta biblia/. */
        val BIBLE_EXTENSIONS = setOf("txt", "xml", "json")

        /** Lee una Biblia según su formato (por la extensión del archivo). */
        fun parseBible(text: String, extension: String, name: String): Bible = when (extension.lowercase()) {
            // El nombre es siempre el del archivo (así se puede quitar, y un JSON no se hace pasar por otra).
            "json" -> Bible.fromJson(text, name).let { Bible(name, it.books) }
            "xml" -> Bible.fromZefania(text, name)
            else -> Bible.fromVpl(text, name)
        }

        /** Créditos que pide la licencia (CC BY-SA 4.0) de cada traducción incluida. */
        val BIBLE_NOTICES = mapOf(
            "Versión Biblia Libre" to "© 2018-2020 Jonathan Gallagher y Shelly Barrios de Avila · CC BY-SA 4.0 · eBible.org",
        ) + BibleDownloads.CATALOG.associate { it.title to it.credit }

        private val bundledCache = java.util.concurrent.ConcurrentHashMap<String, Bible>()

        /** Una Biblia incluida, leída la primera vez que se usa. */
        fun bundled(name: String): Bible? {
            if (name !in BUNDLED_BIBLES) return null
            bundledCache[name]?.let { return it }
            val bible = Library::class.java.getResourceAsStream("/biblia/$name.txt")?.use { input ->
                runCatching { Bible.fromVpl(decodeSmart(input.readBytes()), name) }.getOrNull()
            } ?: return null
            return bundledCache.putIfAbsent(name, bible) ?: bible
        }

        /** Reina-Valera 1909 (dominio público) incluida en el programa. */
        val bundledBible: Bible? get() = bundled("Reina-Valera 1909")

        fun decodeSmart(bytes: ByteArray): String = when {
            // "Unicode" del Bloc de notas (UTF-16 con su marca al inicio)
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            else -> decodeUtf8OrAnsi(bytes)
        }.removePrefix("\uFEFF")

        private fun decodeUtf8OrAnsi(bytes: ByteArray): String = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            String(bytes, Charset.forName("windows-1252"))
        }.removePrefix("\uFEFF")

        /** Tope de un archivo de canciones (un .json de Holyrics con cientos de canciones cabe de sobra). */
        const val MAX_SONG_BYTES = 20_000_000

        /**
         * Lee hasta [max] bytes; si hay más, lanza IllegalArgumentException (un .zip de 1 MB que se
         * descomprime en 1 GB agotaba la memoria en pleno culto).
         */
        fun readLimited(input: java.io.InputStream, max: Int, name: String): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) return out.toByteArray()
                if (out.size() + n > max) throw IllegalArgumentException("\"${name.substringAfterLast('/')}\" es demasiado grande")
                out.write(buf, 0, n)
            }
        }

        /** Lo que se puede importar: .txt (Selah), .xml (OpenLyrics/OpenLP), .json (Holyrics) y .zip con esos. */
        val IMPORT_EXTENSIONS = setOf("txt", "xml", "json")

        fun isMediaFile(name: String) = name.substringAfterLast('.', "").lowercase().let { it in IMAGE_EXTENSIONS || it in VIDEO_EXTENSIONS }

        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "bmp")
        private val TYPE_LINE = Regex("""^tipos?\s*:.*$""", RegexOption.IGNORE_CASE)
        /** Líneas que van antes de la letra (no se proyectan). */
        private val HEADER_LINES = listOf(TYPE_LINE, Regex("""^(orden|fondo|autor(es)?|ccli)\b.*$""", RegexOption.IGNORE_CASE))
        private val FONDO_LINE = Regex("""^fondo\s*:.*$""", RegexOption.IGNORE_CASE)
        private val FORBIDDEN = Regex("""[\\/:*?"<>|\u0000-\u001F]""")

        /** "Salmo 2:7" -> "Salmo 2-7": caracteres que Windows no permite en nombres de archivo. */
        fun fileNameFor(title: String): String =
            avoidReservedName(title.replace(FORBIDDEN, "-").replace(Regex("\\s+"), " ").trim().trimEnd('.').takeWhole(100).trim())

        /** Lee UTF-8, UTF-16 ("Unicode" del Bloc de notas) o ANSI (Windows-1252), según el archivo. */
        fun readTextSmart(f: File): String = decodeSmart(f.readBytes())

        internal fun sameContent(a: File, b: File): Boolean {
            if (a.length() != b.length()) return false
            a.inputStream().buffered().use { x -> b.inputStream().buffered().use { y ->
                while (true) { val c = x.read(); if (c != y.read()) return false; if (c < 0) return true }
            } }
        }

        fun avoidReservedName(name: String): String = app.selah.shared.avoidReservedName(name)
    }
}
