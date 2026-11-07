package app.selah.desktop

import java.io.ByteArrayInputStream
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.zip.ZipInputStream

/**
 * Más Biblias: descargar traducciones libres de eBible.org o agregar un archivo propio.
 * Todo queda en la carpeta biblia/ y se usa sin internet desde ahí; los celulares las ven solas.
 *
 * Solo traducciones que se pueden repartir gratis (dominio público, Creative Commons o con permiso de
 * reproducirla sin fines de lucro). La RVR1960, NVI, NTV, LBLA… tienen derechos de autor: no van aquí.
 */
object BibleDownloads {

    /** Una Biblia para descargar. [title] es el nombre con el que aparece en Selah (y el del archivo). */
    data class Downloadable(val id: String, val title: String, val language: String, val description: String, val credit: String)

    val CATALOG = listOf(
        Downloadable("spabes", "Biblia en Español Sencillo", "Español", "Lenguaje sencillo y actual, fácil de entender.", "Dominio público · eBible.org"),
        Downloadable("spabll", "Biblia Libre Latinoamericana", "Español", "Español latinoamericano actual.", "Dominio público · eBible.org"),
        Downloadable("spablm", "Biblia Libre para el Mundo", "Español", "Español de España, actual.", "Dominio público · eBible.org"),
        Downloadable(
            "spaonbv", "Nueva Biblia Viva", "Español", "Paráfrasis en lenguaje de hoy.",
            "Biblica® Open Nueva Biblia Viva 2008 · © 2008 Biblica, Inc. · CC BY-SA 4.0 · eBible.org",
        ),
        Downloadable(
            "spapddpt", "Palabra de Dios para ti", "Español", "Traducción actual y clara.",
            "© 2020 Asociación Bíblica Latinoamericana · CC BY 4.0 · eBible.org",
        ),
        Downloadable(
            "sparvg", "Reina Valera Gómez", "Español", "Revisión de la Reina-Valera, lenguaje tradicional.",
            "© 2004, 2010, 2023 Dr. Humberto Gómez Caballero · reproducción gratuita sin fines de lucro · eBible.org",
        ),
        Downloadable(
            "spav1602p", "Valera 1602 Purificada", "Español", "Basada en la Valera de 1602, lenguaje tradicional.",
            "© 2007, 2019, 2024 Iglesia Bautista Bíblica de la Gracia · sin fines de lucro y sin alterar · eBible.org",
        ),
        Downloadable("engwebp", "World English Bible", "English", "Modern English.", "Public domain · eBible.org"),
        Downloadable("eng-kjv2006", "King James Version", "English", "Traditional English (1611).", "Public domain · eBible.org"),
        Downloadable("engbsb", "Berean Standard Bible", "English", "Modern English, close to the original.", "Public domain · eBible.org"),
        Downloadable(
            "porbr2018", "Bíblia Livre", "Português", "Português atual.",
            "© 2018 Diego Santos, Mario Sérgio e Marco Teles · CC BY 4.0 · eBible.org",
        ),
        Downloadable(
            "poronbv", "Nova Bíblia Viva", "Português", "Paráfrase em linguagem de hoje.",
            "Biblica® Open Nova Bíblia Viva 2007 · © 2007, 2010 Biblica, Inc. · CC BY-SA 4.0 · eBible.org",
        ),
    )

    /** Dirección de descarga (formato VPL comprimido, ~4-6 MB). Se puede cambiar solo para las pruebas. */
    internal var baseUrl = "https://ebible.org/Scriptures/"

    private val http by lazy {
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NORMAL).build()
    }

    /** Descarga [b], comprueba que se lea bien y la guarda en biblia/. Devuelve un mensaje de error o null. */
    fun download(library: Library, b: Downloadable): String? {
        val bytes = runCatching {
            val response = http.send(
                HttpRequest.newBuilder(URI("$baseUrl${b.id}_vpl.zip")).timeout(Duration.ofMinutes(2)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray(),
            )
            if (response.statusCode() != 200) return "eBible.org respondió ${response.statusCode()}. Intenta más tarde."
            response.body()
        }.getOrElse {
            Log.error("No pude descargar la Biblia ${b.id}", it)
            return "No pude descargarla. Revisa la conexión a internet."
        }
        val text = textFromZip(bytes, preferVpl = true)?.second ?: return "El archivo descargado no trae la Biblia"
        return save(library, text, "txt", b.title)
    }

    /**
     * Agrega una Biblia desde un archivo: VPL (.txt de eBible.org), Zefania (.xml), JSON, o un .zip
     * que traiga alguno de esos. Devuelve el nombre con el que quedó.
     * @throws IllegalArgumentException con un mensaje para mostrar si no se puede leer.
     */
    fun addFromFile(library: Library, file: File): String {
        val ext = file.extension.lowercase()
        val (innerExt, text) = when (ext) {
            "zip" -> textFromZip(file.readBytes(), preferVpl = false)
                ?: throw IllegalArgumentException("El .zip no trae una Biblia (.txt VPL, .xml Zefania o .json)")
            in Library.BIBLE_EXTENSIONS -> ext to Library.readTextSmart(file)
            else -> throw IllegalArgumentException("Formato no reconocido: usa .txt (VPL), .xml (Zefania), .json o .zip")
        }
        val name = suggestedName(file.nameWithoutExtension, text, innerExt, library)
        save(library, text, innerExt, name)?.let { throw IllegalArgumentException(it) }
        return name
    }

    /** Quita una Biblia agregada (las que vienen con el programa no se pueden quitar). */
    fun remove(library: Library, name: String) {
        if (name !in library.userBibleNames) return
        library.bibleDir.listFiles { f -> f.isFile && f.nameWithoutExtension == name && f.extension.lowercase() in Library.BIBLE_EXTENSIONS }
            .orEmpty().forEach { it.delete() }
        library.reload()
    }

    /** ¿Ya está en Selah (descargada antes)? */
    fun isInstalled(library: Library, b: Downloadable): Boolean = b.title in library.userBibleNames

    /** Comprueba que [text] sea una Biblia y la guarda como biblia/<name>.<ext>. */
    private fun save(library: Library, text: String, ext: String, name: String): String? {
        runCatching { Library.parseBible(text, ext, name) }.onFailure { return "No se puede usar como Biblia: ${it.message}" }
        // Una Biblia nueva en la carpeta pasaría a ser la de uso si nadie eligió una: que no cambie sola
        // la traducción en pantalla (a lo mejor están en pleno culto). Se elige con "Usar".
        if (library.preferredBible == null) library.preferredBible = library.catalog.value.bibleName
        return runCatching {
            library.bibleDir.mkdirs()
            File(library.bibleDir, "$name.$ext").writeText(text, Charsets.UTF_8)
            library.reload()
        }.exceptionOrNull()?.let { "No pude guardarla: ${it.message}" }
    }

    /** Busca la Biblia dentro de un .zip: (extensión, texto). Con [preferVpl], el *_vpl.txt de eBible.org. */
    internal fun textFromZip(bytes: ByteArray, preferVpl: Boolean): Pair<String, String>? {
        val found = mutableListOf<Pair<String, ByteArray>>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name.substringAfterLast('/')
                if (entry.isDirectory || name.startsWith(".")) continue
                if (name.substringAfterLast('.', "").lowercase() in Library.BIBLE_EXTENSIONS) found += name to zip.readBytes()
            }
        }
        val pick = found.firstOrNull { it.first.endsWith("_vpl.txt", ignoreCase = true) }
            ?: found.takeUnless { preferVpl }?.firstOrNull { it.first.endsWith(".xml", true) || it.first.endsWith(".json", true) }
            ?: found.takeUnless { preferVpl }?.firstOrNull()
            ?: return null
        return pick.first.substringAfterLast('.').lowercase() to Library.decodeSmart(pick.second)
    }

    private val ZEFANIA_NAME = Regex("""<XMLBIBLE\b[^>]*\bbiblename\s*=\s*"([^"]+)"""", RegexOption.IGNORE_CASE)

    /** Nombre para mostrar: el que trae el XML, o el del archivo sin "_vpl"; sin repetir uno que ya existe. */
    internal fun suggestedName(fileName: String, text: String, ext: String, library: Library): String {
        val fromXml = if (ext == "xml") ZEFANIA_NAME.find(text.take(4000))?.groupValues?.get(1) else null
        val base = (fromXml ?: fileName.removeSuffix("_vpl").removeSuffix("_VPL"))
            .replace(Regex("""[\\/:*?"<>|]"""), " ").replace(Regex("""\s+"""), " ").trim().take(60).ifEmpty { "Biblia" }
        val taken = library.userBibleNames + Library.BUNDLED_BIBLES
        if (base !in taken) return base
        return (2..99).map { "$base ($it)" }.first { it !in taken }
    }
}
