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
