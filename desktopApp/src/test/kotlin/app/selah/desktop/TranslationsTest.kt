package app.selah.desktop

import app.selah.shared.ServerMessage
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TranslationsTest {

    @Test
    fun bothBundledBiblesAreCompleteAndReadable() {
        for (name in Library.BUNDLED_BIBLES) {
            val bible = assertNotNull(Library.bundled(name), name)
            assertEquals(66, bible.books.size, name)
            assertEquals("Génesis", bible.books.first().name)
            assertEquals("Apocalipsis", bible.books.last().name)
            assertTrue(bible.lookup("Juan 3:16").slides.single().text.contains("mundo"), name)
            assertEquals(150, bible.books[bible.findBook("Salmos")!!].chapters.size, name)
        }
        assertTrue(Library.bundled("Versión Biblia Libre")!!.lookup("Génesis 1:3").slides.single().text.contains("luz"))
    }

    @Test
    fun choosingATranslationChangesWhatIsShownAndIsOfferedToRemotes() {
        val dir = createTempDirectory("selah-trad").toFile()
        val library = Library(dir).also { it.reload() }
        val c = library.catalog.value
        assertEquals("Reina-Valera 1909", c.bibleName) // por defecto
        assertEquals(listOf("Reina-Valera 1909", "Versión Biblia Libre"), c.bibleNames)
        assertNull(c.bibleNotice)
        val rv = library.bible!!.lookup("Juan 3:16").slides.single().text

        library.selectBible("Versión Biblia Libre")
        assertEquals("Versión Biblia Libre", library.catalog.value.bibleName)
        assertTrue(library.catalog.value.bibleNotice!!.contains("CC BY-SA 4.0")) // créditos de la licencia
        val vbl = library.bible!!.lookup("Juan 3:16").slides.single().text
        assertTrue(rv != vbl)
        // Las búsquedas de los remotos usan la traducción elegida
        val hits = RemoteServer.bibleSearch(library, "luz") as ServerMessage.BibleResults
        assertTrue(hits.total > 0)

        // Se mantiene al recargar la biblioteca; pedir una que no existe no cambia la que está en uso
        library.reload()
        assertEquals("Versión Biblia Libre", library.catalog.value.bibleName)
        assertFalse(library.selectBible("No existe"))
        assertEquals("Versión Biblia Libre", library.catalog.value.bibleName)
        // Si la elegida desaparece de la carpeta (guardada en ajustes), se vuelve a la de por defecto
        library.preferredBible = "Ya no está"
        library.reload()
        assertEquals("Reina-Valera 1909", library.catalog.value.bibleName)
        dir.deleteRecursively()
    }

    @Test
    fun ownBibleInTheFolderIsAddedToTheList() {
        val dir = createTempDirectory("selah-propia").toFile()
        java.io.File(dir, "biblia").mkdirs()
        val vpl = Library.BUNDLED_BIBLES.first().let { javaClass.getResource("/biblia/$it.txt")!!.readText() }
        java.io.File(dir, "biblia/Mi Biblia.txt").writeText(vpl)
        val library = Library(dir).also { it.reload() }
        assertEquals("Mi Biblia", library.catalog.value.bibleName) // la propia tiene prioridad, como antes
        assertEquals(listOf("Mi Biblia", "Reina-Valera 1909", "Versión Biblia Libre"), library.catalog.value.bibleNames)
        dir.deleteRecursively()
    }
}
