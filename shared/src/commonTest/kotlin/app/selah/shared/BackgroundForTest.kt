package app.selah.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Qué fondo se ve: el de la canción, el de la Biblia o el de su tipo, o el de siempre. */
class BackgroundForTest {
    private val theme = Theme(image = "general.jpg", bibleImage = "biblia.jpg", typeImages = mapOf("Adoración" to "adoracion.mp4", "Alabanza" to "alabanza.jpg"))
    private val song = PresentationState(songId = "x", slides = listOf(Slide(text = "a")), index = 0)

    @Test
    fun loMasEspecificoGana() {
        assertEquals("adoracion.mp4", theme.imageFor(song, listOf("adoracion"))) // sin tildes ni mayúsculas
        assertEquals("alabanza.jpg", theme.imageFor(song, listOf("Alabanza", "Adoración"))) // el primer tipo que tenga fondo
        assertEquals("general.jpg", theme.imageFor(song, listOf("Himno")))
        assertEquals("general.jpg", theme.imageFor(song, emptyList()))
        assertEquals("propio.jpg", theme.imageFor(song.copy(background = "propio.jpg"), listOf("Adoración")))
        assertEquals("biblia.jpg", theme.imageFor(PresentationState(isBible = true), emptyList()))
        // Sin fondo de Biblia: el de siempre; sin fondo general: null (el incluido o el color)
        assertEquals("general.jpg", theme.copy(bibleImage = null).imageFor(PresentationState(isBible = true), emptyList()))
        assertNull(Theme().imageFor(song, listOf("Adoración")))
    }

    @Test
    fun limpiaLoVacio() {
        val t = Theme(bibleImage = " ", typeImages = mapOf("" to "a.jpg", "Adoración" to "")).sanitized()
        assertNull(t.bibleImage)
        assertEquals(emptyMap(), t.typeImages)
    }
}
