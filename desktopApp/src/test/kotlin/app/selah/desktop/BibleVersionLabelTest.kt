package app.selah.desktop

import app.selah.shared.ClientMessage
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** La traducción junto a la cita ("Josué 1:9 - NTV") y los versículos unidos de una Biblia en JSON. */
class BibleVersionLabelTest {
    private lateinit var dir: File
    private lateinit var library: Library
    private lateinit var presenter: Presenter
    private val st get() = presenter.state.value

    @BeforeTest
    fun setUp() {
        dir = createTempDirectory("selah-version").toFile()
        File(dir, "canciones").mkdirs()
        File(dir, "biblia").mkdirs()
        // Como las que exporta Holyrics a JSON: la Lenguaje Sencillo une el 1 y el 2.
        File(dir, "biblia/Nueva Traducción Viviente.json").writeText(
            """{"name":"Nueva Traducción Viviente","books":[{"name":"Josué","chapters":[["Uno.","Dos."]]}]}""",
        )
        File(dir, "biblia/Biblia en Lenguaje Sencillo.json").writeText(
            """{"name":"Biblia en Lenguaje Sencillo","books":[{"name":"Josué","chapters":[["Uno y dos.",""]]}]}""",
        )
        library = Library(dir).also { it.reload() }
        library.selectBible("Nueva Traducción Viviente")
        presenter = Presenter(library)
    }

    @AfterTest
    fun tearDown() { dir.deleteRecursively() }

    @Test
    fun theVersionGoesNextToTheReference() {
        assertNull(presenter.handle(ClientMessage.ShowBible("Josué 1:2")))
        assertEquals("NTV", st.version)
        assertEquals("Josué 1:2 - NTV", st.labelWithVersion(st.current!!.label))
        // Con segunda traducción, las dos; el versículo unido de la segunda se empareja igual.
        library.selectSecondBible("Biblia en Lenguaje Sencillo")
        presenter.handle(ClientMessage.ShowBible("Josué 1:2"))
        assertEquals("NTV / BLS", st.version)
        assertEquals("Uno y dos.", st.current!!.text2)
    }

    @Test
    fun theOptionTurnsItOffRightAway() {
        var on = true
        presenter.versionOn = { on }
        presenter.handle(ClientMessage.ShowBible("Josué 1:1"))
        assertEquals("NTV", st.version)
        on = false
        presenter.refreshVersion()
        assertEquals("Josué 1:1", st.labelWithVersion(st.current!!.label))
    }

    @Test
    fun aJoinedVerseShowsTheVerseThatHasIt() {
        library.selectBible("Biblia en Lenguaje Sencillo")
        assertNull(presenter.handle(ClientMessage.ShowBible("Josué 1:2")))
        assertEquals("Josué 1:1-2", st.current!!.label)
        assertEquals("Uno y dos.", st.current!!.text)
        assertEquals("Josué 1:1-2 - BLS", st.labelWithVersion(st.current!!.label))
    }
}
