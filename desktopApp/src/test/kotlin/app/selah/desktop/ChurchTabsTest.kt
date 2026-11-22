package app.selah.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

class ChurchTabsTest {
    @Test
    fun soloLoEncendidoTienePestaña() {
        assertEquals(listOf(ChurchTab.FEATURES), churchTabs(ChurchFeatures(click = false, sequences = false, scores = false, chords = false)))
        // Con el click, la pestaña de la letra automática; la letra de la congregación no tiene ajustes.
        assertEquals(listOf(ChurchTab.FEATURES, ChurchTab.AUTO), churchTabs(ChurchFeatures(click = true, congregation = true)))
        assertEquals(
            listOf(ChurchTab.FEATURES, ChurchTab.AUTO, ChurchTab.SEQUENCES, ChurchTab.SCORES, ChurchTab.CHORDS),
            churchTabs(ChurchFeatures(click = true, sequences = true, scores = true, chords = true)),
        )
        assertEquals(listOf(ChurchTab.FEATURES, ChurchTab.SCORES), churchTabs(ChurchFeatures(scores = true)))
        // Solo secuencias: sin pestaña de letra automática (ahí la letra la da la voz guía).
        assertEquals(listOf(ChurchTab.FEATURES, ChurchTab.SEQUENCES), churchTabs(ChurchFeatures(sequences = true)))
    }
}

class ScoresAlphabetTest {
    @kotlin.test.Test
    fun letraDeCadaCancion() {
        kotlin.test.assertEquals("E", initialOf("Él es mi rey"))
        kotlin.test.assertEquals("N", initialOf("Ñandutí"))
        kotlin.test.assertEquals("#", initialOf("1 Corintios 13"))
        kotlin.test.assertEquals("A", initialOf("¡Aleluya!"))
        kotlin.test.assertEquals("#", initialOf(""))
    }
}

class SongBackgroundLineTest {
    @kotlin.test.Test
    fun ponerYQuitarElFondoEnElEditor() {
        val v = androidx.compose.ui.text.input.TextFieldValue("# Luz\nTipo: Alabanza\n\nuno\n")
        val con = v.withBackground("mar.jpg")
        kotlin.test.assertEquals("# Luz\nFondo: mar.jpg\nTipo: Alabanza\n\nuno\n", con.text)
        kotlin.test.assertEquals("mar.jpg", app.selah.shared.SongParser.parse("x", con.text).background)
        kotlin.test.assertEquals("# Luz\nFondo: otro.mp4\nTipo: Alabanza\n\nuno\n", con.withBackground("otro.mp4").text)
        kotlin.test.assertEquals("# Luz\nTipo: Alabanza\n\nuno\n", con.withBackground(null).text)
    }
}
