package app.selah.desktop

import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BibleDownloadsTest {
    private val dir = createTempDirectory("selah-biblias").toFile()
    private val vpl = javaClass.getResource("/biblia/Reina-Valera 1909.txt")!!.readText()

    @AfterTest
    fun cleanUp() { dir.deleteRecursively() }

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> for ((name, text) in entries) { z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry() } }
        return out.toByteArray()
    }

    /** Un "eBible.org" de mentira en este equipo. */
    private fun <T> withServer(files: Map<String, ByteArray>, block: () -> T): T {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val body = files[ex.requestURI.path.removePrefix("/")]
            ex.sendResponseHeaders(if (body == null) 404 else 200, body?.size?.toLong() ?: -1)
            body?.let { ex.responseBody.write(it) }
            ex.close()
        }
        server.start()
        val old = BibleDownloads.baseUrl
        BibleDownloads.baseUrl = "http://127.0.0.1:${server.address.port}/"
        try { return block() } finally { BibleDownloads.baseUrl = old; server.stop(0) }
    }

    @Test
    fun catalogHasOnlyFreeTranslationsWithCredits() {
        assertTrue(BibleDownloads.CATALOG.size >= 10)
        assertEquals(BibleDownloads.CATALOG.size, BibleDownloads.CATALOG.map { it.title }.toSet().size) // sin nombres repetidos
        assertTrue(BibleDownloads.CATALOG.none { it.title in Library.BUNDLED_BIBLES })
        assertTrue(BibleDownloads.CATALOG.all { it.credit.contains("eBible.org") })
        val copyrighted = listOf("1960", "NVI", "NTV", "Américas")
        assertTrue(BibleDownloads.CATALOG.none { b -> copyrighted.any { it in b.title } })
        // Los créditos que pide la licencia se muestran al usarla
        assertTrue(Library.BIBLE_NOTICES["Nueva Biblia Viva"]!!.contains("CC BY-SA 4.0"))
    }

    @Test
    fun downloadSavesItWithoutChangingTheTranslationOnScreen() {
        val library = Library(dir).also { it.reload() }
        val b = BibleDownloads.CATALOG.first { it.id == "spabes" }
        val error = withServer(mapOf("spabes_vpl.zip" to zip("spabes_about.htm" to "<html/>", "spabes_vpl.txt" to vpl))) {
            BibleDownloads.download(library, b)
        }
        assertNull(error)
        assertTrue(File(dir, "biblia/Biblia en Español Sencillo.txt").isFile)
        assertTrue(BibleDownloads.isInstalled(library, b))
        val c = library.catalog.value
        assertTrue("Biblia en Español Sencillo" in c.bibleNames)
        assertEquals("Reina-Valera 1909", c.bibleName) // la de antes sigue en pantalla

        library.selectBible(b.title)
        assertEquals(b.title, library.catalog.value.bibleName)
        assertEquals("Dominio público · eBible.org", Library.BIBLE_NOTICES[b.title])

        BibleDownloads.remove(library, b.title)
        assertFalse(BibleDownloads.isInstalled(library, b))
        assertEquals("Reina-Valera 1909", library.catalog.value.bibleName)
        // Las que vienen con el programa no se quitan
        BibleDownloads.remove(library, "Reina-Valera 1909")
        assertTrue("Reina-Valera 1909" in library.catalog.value.bibleNames)
    }

    @Test
    fun downloadErrorsAreClear() {
        val library = Library(dir).also { it.reload() }
        val b = BibleDownloads.CATALOG.first()
        val notFound = withServer(emptyMap()) { BibleDownloads.download(library, b) }
        assertTrue(notFound!!.contains("404"))
        val noBible = withServer(mapOf("${b.id}_vpl.zip" to zip("leeme.htm" to "x"))) { BibleDownloads.download(library, b) }
        assertEquals("El archivo descargado no trae la Biblia", noBible)
        assertFalse(File(dir, "biblia/${b.title}.txt").exists())
    }

    @Test
    fun addFromZefaniaXmlUsesItsName() {
        val library = Library(dir).also { it.reload() }
        val books = (1..66).joinToString("") { n ->
            """<BIBLEBOOK bnumber="$n"><CHAPTER cnumber="1"><VERS vnumber="1">Texto $n</VERS></CHAPTER></BIBLEBOOK>"""
        }
        val file = File(dir, "bajada.xml").apply { writeText("""<XMLBIBLE biblename="Mi Traducción">$books</XMLBIBLE>""") }
        assertEquals("Mi Traducción", BibleDownloads.addFromFile(library, file))
        assertTrue(File(dir, "biblia/Mi Traducción.xml").isFile)
        library.selectBible("Mi Traducción")
        assertEquals("Texto 43", library.bible!!.lookup("Juan 1:1").slides.single().text)
        // Otra vez el mismo: no pisa la que ya estaba
        assertEquals("Mi Traducción (2)", BibleDownloads.addFromFile(library, file))
    }

    @Test
    fun addFromZipOfEbibleAndRejectsWhatIsNotABible() {
        val library = Library(dir).also { it.reload() }
        val zipFile = File(dir, "spaRV1909_vpl.zip").apply { writeBytes(zip("spaRV1909_vpl.txt" to vpl, "spaRV1909_vpl.xml" to "<x/>")) }
        assertEquals("spaRV1909", BibleDownloads.addFromFile(library, zipFile))
        assertNotNull(library.userBibleNames.single { it == "spaRV1909" })

        val notes = File(dir, "notas.txt").apply { writeText("Hola, esto no es una Biblia") }
        val e = assertFailsWith<IllegalArgumentException> { BibleDownloads.addFromFile(library, notes) }
        assertTrue(e.message!!.startsWith("No se puede usar como Biblia"))
        assertFalse(File(dir, "biblia/notas.txt").exists())
        assertFailsWith<IllegalArgumentException> { BibleDownloads.addFromFile(library, File(dir, "x.pdf").apply { writeText("x") }) }
    }
}
