package app.selah.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import app.selah.shared.Bible
import app.selah.shared.ClientMessage
import app.selah.shared.slideIndexOfVerse
import app.selah.shared.encode
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BibleBrowserTest {

    @Test
    fun verseMapsToItsSlideSkippingEmptyVerses() {
        val chapter = listOf("uno", "", "tres", "cuatro")
        assertEquals(0, slideIndexOfVerse(chapter, 1))
        assertEquals(1, slideIndexOfVerse(chapter, 3)) // el 2 está vacío y no es diapositiva
        assertEquals(0, slideIndexOfVerse(chapter, 2)) // quedó unido al 1: es la del 1
        assertEquals(2, slideIndexOfVerse(chapter, 4))
    }

    @Test
    fun clickedVerseIsTheSlideShownByThePresenter() {
        val bible = assertNotNull(Library.bundledBible)
        val juan = bible.books[bible.findBook("Juan")!!]
        val passage = bible.lookup("Juan 3")
        val slide = passage.slides[slideIndexOfVerse(juan.chapters[2], 16)]
        assertEquals("Juan 3:16", slide.label)
        assertTrue(slide.text.contains("amó Dios"))
    }

    @Test
    fun realBibleReadsSeveralPassages() {
        val bible = assertNotNull(Library.bundledBible)
        val p = bible.lookup("Juan 3:16; Romanos 5:8")
        assertEquals("Juan 3:16; Romanos 5:8", p.title)
        assertTrue(p.slides[0].text.contains("amó Dios") && p.slides[1].text.contains("Cristo"))
        val cross = bible.lookup("Mateo 5:47-6:2")
        assertEquals(listOf("Mateo 5:47", "Mateo 5:48", "Mateo 6:1", "Mateo 6:2"), cross.slides.map { it.label })
    }

    @Test
    fun bundledBibleHas66BooksInCanonicalOrder() {
        val bible = assertNotNull(Library.bundledBible)
        assertEquals(66, bible.books.size)
        assertEquals("Malaquías", bible.books[38].name) // último del Antiguo Testamento
        assertEquals("Mateo", bible.books[39].name)
    }

    @Test
    fun serverSendsBooksAndChapterTextToRemotes() {
        val dir = kotlin.io.path.createTempDirectory("selah-biblia").toFile()
        val library = Library(dir).also { it.reload() }
        val books = library.bible!!.bookInfos()
        assertEquals(66, books.size)
        assertEquals(app.selah.shared.BibleBookInfo("Salmos", 150), books[18])

        val reply = RemoteServer.bibleChapter(library, "Juan", 3)
        val chapter = reply as app.selah.shared.ServerMessage.BibleChapter
        assertEquals(36, chapter.verses.size)
        assertTrue(chapter.verses[15].contains("amó Dios"))
        // Viaja bien por el protocolo
        val round = app.selah.shared.decodeServerMessage(reply.encode())
        assertEquals(reply, round)
        assertTrue(RemoteServer.bibleChapter(library, "Juan", 99) is app.selah.shared.ServerMessage.Error)
        val ask = app.selah.shared.ClientMessage.BibleChapter("Juan", 3)
        assertEquals(ask, app.selah.shared.decodeClientMessage(ask.encode()))
        dir.deleteRecursively()
    }

    @Test
    fun webControlHasTheBibleBrowser() {
        val page = javaClass.getResource("/web/index.html")!!.readText()
        val js = javaClass.getResource("/web/selah.js")!!.readText()
        assertTrue("bible_chapter" in page && "renderBrowser" in page)
        assertTrue("bible_chapter" in js && "bibleBooks" in js)
    }

    /** SELAH_RENDER_DIR=/tmp/out ./gradlew :desktopApp:test --tests '*BibleBrowserTest*' */
    @Test
    fun renderBrowser() {
        val out = System.getenv("SELAH_RENDER_DIR")?.let(::File) ?: return
        out.mkdirs()
        val bible: Bible = Library.bundledBible!!
        val sent = mutableListOf<ClientMessage>()
        val levels = listOf("libros" to (null to null), "capitulos" to (18 to null), "versiculos" to (42 to 3))
        for ((name, start) in levels) {
            ImageComposeScene(330, 700, Density(1f)) {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Surface(Modifier.fillMaxSize()) {
                        BibleBrowser(bible, Modifier.fillMaxSize(), { sent += it }, { sent += it }, start.first, start.second)
                    }
                }
            }.use { File(out, "biblia-$name.png").writeBytes(it.render(0).encodeToData(EncodedImageFormat.PNG)!!.bytes) }
        }
    }
}
