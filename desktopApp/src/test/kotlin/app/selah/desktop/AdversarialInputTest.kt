package app.selah.desktop

import app.selah.shared.Bible
import app.selah.shared.cleanListName
import app.selah.shared.BibleReference
import app.selah.shared.SongParser
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** Entradas hostiles o raras en todo lo que lee archivos o texto de afuera. */
class AdversarialInputTest {
    private lateinit var dir: File

    @BeforeTest
    fun setUp() { dir = createTempDirectory("selah-adv").toFile() }

    @AfterTest
    fun tearDown() { dir.deleteRecursively() }

    private fun <T> within(ms: Long, block: () -> T): T {
        val ex = Executors.newSingleThreadExecutor { Thread(it).apply { isDaemon = true } }
        try {
            return ex.submit(Callable(block)).get(ms, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            fail("Tardó más de $ms ms")
        } catch (e: ExecutionException) {
            throw e.cause!!
        } finally {
            ex.shutdownNow()
        }
    }

    private val vpl by lazy { javaClass.getResource("/biblia/Reina-Valera 1909.txt")!!.readText() }

    // ---------------- Canciones ----------------

    /** "Orden:" que repite mil veces una sección grande: un archivo de ~60 KB no debe dar millones de diapositivas. */
    @Test
    fun orderLineCannotExplodeTheSlideCount() {
        val text = buildString {
            append("# Bomba\n")
            append("Orden: ").append(List(10_000) { "Coro" }.joinToString(", ")).append('\n')
            append("\nCoro:\nla\n")
            repeat(499) { append("\nla $it\n") }
        }
        assertTrue(text.length < 100_000)
        val song = within(10_000) { SongParser.parse("x", text) }
        assertTrue(song.slides.size <= 10_000, "Un archivo de ${text.length / 1000} KB dio ${song.slides.size} diapositivas")
    }

    /** Bloc de notas guarda "Unicode" como UTF-16 con BOM: la canción debe leerse bien. */
    @Test
    fun utf16SongsAreDecoded() {
        val original = "# Cuán grande es Él\n\nMi corazón entona la canción\n"
        val le = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + original.toByteArray(Charsets.UTF_16LE)
        val be = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + original.toByteArray(Charsets.UTF_16BE)
        assertEquals(original, Library.decodeSmart(le))
        assertEquals(original, Library.decodeSmart(be))
        val f = File(dir, "u16.txt").apply { writeBytes(le) }
        assertEquals(original, Library.readTextSmart(f))
    }

    /** Un título largo con emoji justo en el corte de 100 caracteres no debe dejar media letra en el nombre. */
    @Test
    fun longTitleWithEmojiAtTheCutStillSavesAndLoads() {
        val title = "a".repeat(99) + "😀 fin"
        val lib = Library(File(dir, "lib"))
        lib.reload()
        val saved = runCatching { lib.save(null, "# $title\n\nletra\n") }.getOrElse { fail("No se pudo guardar: $it") }
        assertNotNull(lib.song(saved), "save() devolvió \"$saved\" pero esa canción no existe al recargar")
        assertFalse(Library.fileNameFor(title).last().isHighSurrogate(), "El nombre termina en medio emoji")
    }

    /** Windows no deja crear CON.txt, NUL.txt, COM1.txt… (nombres reservados). */
    @Test
    fun windowsReservedNamesAreNotUsedAsFileNames() {
        for (t in listOf("CON", "nul", "Aux", "COM1", "LPT1", "PRN")) {
            assertNotEquals(t.lowercase(), Library.fileNameFor(t).lowercase(), "fileNameFor(\"$t\")")
            assertNotEquals(t.lowercase(), cleanListName(t)?.lowercase(), "cleanListName(\"$t\")")
        }
    }

    /** OpenLyrics con <lines> anidado muy hondo: no debe tumbar la importación con StackOverflowError. */
    @Test
    fun deeplyNestedOpenLyricsDoesNotOverflowTheStack() {
        val depth = 200_000
        val xml = buildString {
            append("""<song xmlns="http://openlyrics.info/namespace/2009/song"><properties><titles><title>T</title></titles></properties><lyrics><verse name="v1"><lines>""")
            repeat(depth) { append("<tag>") }
            append("hola")
            repeat(depth) { append("</tag>") }
            append("</lines></verse></lyrics></song>")
        }
        within(20_000) { SongImport.fromOpenLyrics(xml) } // null o una canción: lo que sea, sin Error
    }

    /** .zip de canciones con una entrada de 1 GB de ceros (unos pocos MB comprimido). */
    @Test
    fun zipBombInSongImportIsRejectedNotOom() {
        val zip = File(dir, "bomba.zip")
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("bomba.txt"))
            val chunk = ByteArray(1 shl 20) { 'a'.code.toByte() }
            repeat(1024) { z.write(chunk) }
            z.closeEntry()
        }
        val lib = Library(File(dir, "lib"))
        lib.reload()
        val r = runCatching { within(60_000) { lib.importSongs(listOf(zip)) } }
        val e = r.exceptionOrNull()
        assertFalse(e is OutOfMemoryError, "Se quedó sin memoria con un .zip de ${zip.length() / 1_000_000} MB")
    }

    // ---------------- Biblias ----------------

    /** Una Biblia .json cuyo "name" no es el del archivo: después no se puede quitar. */
    @Test
    fun jsonBibleWithInnerNameCanBeRemoved() {
        val lib = Library(File(dir, "lib"))
        lib.reload()
        val f = File(dir, "mia.json").apply {
            writeText("""{"name":"Otra cosa","books":[{"name":"Juan","chapters":[["En el principio"]]}]}""")
        }
        val name = BibleDownloads.addFromFile(lib, f)
        assertTrue(name in lib.userBibleNames, "addFromFile devolvió \"$name\" pero la lista dice ${lib.userBibleNames}")
        for (n in lib.userBibleNames.toList()) BibleDownloads.remove(lib, n)
        assertTrue(lib.userBibleNames.isEmpty(), "Quedó sin poder quitarse: ${lib.userBibleNames}")
        assertTrue(lib.bibleDir.listFiles().orEmpty().none { it.name == "mia.json" }, "El archivo sigue en biblia/")
    }

    /** JSON con null en un versículo: no debe proyectarse la palabra "null". */
    @Test
    fun jsonBibleNullVerseIsNotTheWordNull() {
        val b = Bible.fromJson("""{"books":[{"name":"Juan","chapters":[["uno",null,"tres"]]}]}""", "x")
        assertNotEquals("null", b.chapter("Juan", 1)!![1])
    }

    /** VPL con un número de versículo absurdo: no debe armar una lista de millones de versículos vacíos. */
    @Test
    fun vplWithAbsurdVerseNumberIsBounded() {
        val text = vpl + "\nGEN 1:5000000 versículo trampa\n"
        val r = runCatching { within(20_000) { Bible.fromVpl(text, "x") } }
        val e = r.exceptionOrNull()
        if (e != null) { assertTrue(e is IllegalArgumentException, "Falló con $e"); return }
        val size = r.getOrThrow().chapter("Génesis", 1)!!.size
        assertTrue(size < 1000, "Génesis 1 quedó con $size versículos")
    }

    @Test
    fun zefaniaWithAbsurdChapterNumberIsBounded() {
        val xml = buildString {
            append("<XMLBIBLE>")
            for (b in 1..66) append("""<BIBLEBOOK bnumber="$b"><CHAPTER cnumber="1"><VERS vnumber="1">t</VERS></CHAPTER></BIBLEBOOK>""")
            append("""<BIBLEBOOK bnumber="1"><CHAPTER cnumber="3000000"><VERS vnumber="1">t</VERS></CHAPTER></BIBLEBOOK>""")
            append("</XMLBIBLE>")
        }
        val r = runCatching { within(20_000) { Bible.fromZefania(xml, "x") } }
        val e = r.exceptionOrNull()
        if (e != null) { assertTrue(e is IllegalArgumentException, "Falló con $e"); return }
        val n = r.getOrThrow().bookInfos().first().chapters
        assertTrue(n < 1000, "Génesis quedó con $n capítulos")
    }

    /** Zefania con <VERS> sin cerrar: cada uno busca "</VERS>" hasta el final del archivo (cuadrático). */
    @Test
    fun zefaniaWithUnclosedVersesIsNotQuadratic() {
        val xml = buildString {
            append("""<XMLBIBLE><BIBLEBOOK bnumber="1"><CHAPTER cnumber="1">""")
            repeat(30_000) { append("""<VERS vnumber="$it">texto del verso """) }
        }
        runCatching { within(3_000) { Bible.fromZefania(xml, "x") } }.exceptionOrNull()?.let {
            if (it is AssertionError) throw it
        }
    }

    /** Zefania con "<VERS" sin ">" (archivo cortado): la regex recorre el resto del texto por cada uno. */
    @Test
    fun zefaniaWithTagsWithoutCloseBracketIsNotQuadratic() {
        val xml = "<XMLBIBLE>" + "<VERS a ".repeat(30_000)
        runCatching { within(3_000) { Bible.fromZefania(xml, "x") } }.exceptionOrNull()?.let {
            if (it is AssertionError) throw it
        }
    }

    /** .zip de Biblia con 1 GB de ceros: debe decir que es muy grande, no quedarse sin memoria. */
    @Test
    fun zipBombBibleIsRejectedNotOom() {
        val zip = File(dir, "biblia.zip")
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("x_vpl.txt"))
            val chunk = ByteArray(1 shl 20) { 'a'.code.toByte() }
            repeat(1024) { z.write(chunk) }
            z.closeEntry()
        }
        val lib = Library(File(dir, "lib"))
        lib.reload()
        val e = runCatching { within(60_000) { BibleDownloads.addFromFile(lib, zip) } }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException, "Se esperaba un mensaje claro, llegó: $e")
    }

    // ---------------- Citas ----------------

    /** parse() promete null si no entiende: un capítulo enorme no debe lanzar NumberFormatException. */
    @Test
    fun hugeChapterNumberIsNullNotAnException() {
        assertNull(runCatching { BibleReference.parse("Juan 99999999999") }.getOrElse { fail("parse lanzó $it") })
    }

    @Test
    fun hugeChapterNumberGivesAClearMessage() {
        val bible = Library.bundledBible!!
        val e = assertFailsWith<IllegalArgumentException> { bible.lookup("Juan 99999999999") }
        assertFalse(e.message.orEmpty().startsWith("For input string"), "Mensaje técnico: ${e.message}")
    }

    /** "Juan 3:99999999999" no existe: no debe mostrar el capítulo 3 entero en silencio. */
    @Test
    fun hugeVerseNumberIsAnErrorNotTheWholeChapter() {
        val bible = Library.bundledBible!!
        val r = runCatching { bible.lookup("Juan 3:99999999999") }
        assertTrue(r.isFailure, "Mostró \"${r.getOrNull()?.title}\" con ${r.getOrNull()?.slides?.size} versículos")
    }

    @Test
    fun citationRegexHasNoCatastrophicBacktracking() {
        val q = "Juan" + " ".repeat(30_000) + "x"
        within(1_000) { BibleReference.parseAll(q) }
        val q2 = "a" + ".".repeat(30_000)
        within(1_000) { BibleReference.parseAll(q2) }
    }

    // ---------------- Respaldos ----------------

    /** El respaldo solo debe restaurar canciones/listas/…: "canciones/../ajustes.json" se cuela. */
    @Test
    fun restoreCannotEscapeTheAllowListWithDotDot() {
        val settings = SettingsStore(File(dir, "ajustes.json"))
        settings.update { it.copy(bible = "Reina-Valera 1909") }
        val before = settings.value
        val zip = File(dir, "malo.zip")
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("canciones/../ajustes.json")); z.write("{\"pin\":\"0000\"}".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("historial.csv/../biblia/trampa.json")); z.write("{}".toByteArray()); z.closeEntry()
        }
        Backups(dir, settings).restore(zip)
        // (restore hace antes un respaldo y anota su fecha: eso sí cambia; lo demás no)
        assertEquals(before.copy(lastBackup = settings.value.lastBackup), SettingsStore(File(dir, "ajustes.json")).value, "El respaldo cambió ajustes.json")
        assertFalse(File(dir, "biblia/trampa.json").exists(), "El respaldo escribió en biblia/")
    }

    // ---------------- Presentaciones y anuncios ----------------

    /** PowerPoint con diapositiva angosta y altísima (válido en el formato): mensaje claro, no OutOfMemoryError. */
    @Test
    fun pptxWithAbsurdSlideSizeGivesAMessageNotOom() {
        val presDir = File(dir, "presentaciones").apply { mkdirs() }
        org.apache.poi.xslf.usermodel.XMLSlideShow().use { show ->
            show.pageSize = java.awt.Dimension(72, 4032) // 1 × 56 pulgadas: lo máximo que permite el formato
            show.createSlide()
            File(presDir, "alta.pptx").outputStream().use { show.write(it) }
        }
        val p = Presentations(presDir)
        val e = runCatching { within(60_000) { p.pages("alta.pptx") } }.exceptionOrNull()
        // Presenter.showPresentation solo atrapa IllegalArgumentException
        assertTrue(e == null || e is IllegalArgumentException, "Se escapó: $e")
    }

    /** Dos presentaciones de nombre largo cuyo hashCode coincide comparten la carpeta de imágenes. */
    @Test
    fun presentationsWithCollidingNamesDoNotShareCache() {
        val presDir = File(dir, "presentaciones").apply { mkdirs() }
        val prefix = "Predicación del domingo sobre el buen pastor y las noventa y nueve"
        val a = "${prefix}Aa.pdf"; val b = "${prefix}BB.pdf"
        assertEquals(a.hashCode(), b.hashCode())
        for (n in listOf(a, b)) org.apache.pdfbox.pdmodel.PDDocument().use { d ->
            repeat(if (n == a) 1 else 2) { d.addPage(org.apache.pdfbox.pdmodel.PDPage()) }
            d.save(File(presDir, n))
        }
        val p = Presentations(presDir)
        p.pages(a); p.pages(b)
        assertNotEquals(p.page(a, 0), p.page(b, 0), "Las dos usan el mismo archivo de imagen")
        assertNull(p.page(a, 1), "La de 1 página muestra una segunda diapositiva (la de la otra)")
    }

    /** Un anuncio nuevo con el mismo nombre y tamaño que uno viejo (pero otra imagen) se descarta en silencio. */
    @Test
    fun announcementWithSameNameAndSizeButDifferentContentIsAdded() {
        val lib = Library(File(dir, "lib"))
        lib.reload()
        val old = File(dir, "a/aviso.bmp").apply { parentFile.mkdirs(); writeBytes(ByteArray(1000) { 1 }) }
        assertEquals(1, lib.addAnnouncements(listOf(old)))
        val new = File(dir, "b/aviso.bmp").apply { parentFile.mkdirs(); writeBytes(ByteArray(1000) { 2 }) }
        assertEquals(1, lib.addAnnouncements(listOf(new)), "El anuncio nuevo no se agregó")
    }

    // ---------------- Lo que debe aguantar (control) ----------------

    @Test
    fun weirdButHarmlessInputsHoldUp() {
        // Solo BOM, solo "#", CR solos, NUL, línea de 1 MB, 100k líneas vacías, acordes rotos
        SongParser.parse("x", "﻿")
        assertEquals("x", SongParser.parse("x", "#").title)
        assertEquals(2, SongParser.parse("x", "# T\r\ra\r\rb").slides.size)
        SongParser.parse("x", "# T\n\n\u0000\u0000")
        within(2_000) { SongParser.parse("x", "# T\n\n" + "[G".repeat(300_000)) }
        within(2_000) { SongParser.parse("x", "# T\n\n" + "[[[".repeat(300_000)) }
        within(2_000) { SongParser.parse("x", "# T" + "\n".repeat(100_000) + "a") }
        within(2_000) { SongParser.parse("x", "# T\n\n" + "a".repeat(1_000_000)) }
        // Holyrics anidado: lista vacía, sin Error
        within(5_000) { assertTrue(SongImport.fromHolyricsJson("[".repeat(100_000) + "]".repeat(100_000)).isEmpty()) }
        // XXE / billion laughs: rechazado
        assertNull(SongImport.fromOpenLyrics("""<?xml version="1.0"?><!DOCTYPE s [<!ENTITY x SYSTEM "file:///etc/passwd">]><song><properties><titles><title>&x;</title></titles></properties><lyrics><verse name="v1"><lines>a</lines></verse></lyrics></song>"""))
        // RTF raro
        within(2_000) { EasyWorshipImport.rtfToText("{\\rtf1" + "{".repeat(100_000) + "\\u99999999 \\'zz\\uc2000000000 hola\\") }
        // Citas raras
        val bible = Library.bundledBible!!
        for (q in listOf("Juan 3:16-3:1", "Juan 0", "Juan 3:0", "Salmos 1-150", "1 1 1", "Juan 3:16;;;;", "Juan 3:16-2:1",
            List(5000) { "Juan 3:16" }.joinToString(";"), "Juan ３:１６")) {
            runCatching { bible.lookup(q) }.exceptionOrNull()?.let { assertTrue(it is IllegalArgumentException, "$q → $it") }
        }
        // Zip slip en importación de canciones: queda dentro de canciones/
        val zip = File(dir, "slip.zip")
        ZipOutputStream(zip.outputStream()).use { z ->
            for (n in listOf("../../fuera.txt", "..\\..\\fuera2.txt", "/abs/fuera3.txt")) { z.putNextEntry(ZipEntry(n)); z.write("# F\n\nx\n".toByteArray()); z.closeEntry() }
        }
        val lib = Library(File(dir, "lib")); lib.reload()
        lib.importSongs(listOf(zip))
        assertTrue(File(dir, "lib").walkTopDown().filter { it.name.contains("fuera") }.all { it.parentFile == lib.songsDir })
        assertFalse(File(dir, "fuera.txt").exists())
        // Biblia: zip slip y bnumber enorme
        assertNull(BibleDownloads.textFromZip(ByteArrayOutputStream().also { o -> ZipOutputStream(o).use { z -> z.putNextEntry(ZipEntry("../a.bin")); z.closeEntry() } }.toByteArray(), false))
    }
}
