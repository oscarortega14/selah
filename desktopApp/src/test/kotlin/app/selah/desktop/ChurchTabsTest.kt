package app.selah.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

class ChurchTabsTest {
    @Test
    fun soloLoEncendidoTienePestaña() {
        assertEquals(listOf(ChurchTab.FEATURES), churchTabs(ChurchFeatures(click = false, sequences = false, scores = false, chords = false)))
        // El click y la letra de la congregación no tienen ajustes: no suman pestañas.
        assertEquals(listOf(ChurchTab.FEATURES), churchTabs(ChurchFeatures(click = true, congregation = true)))
        assertEquals(
            listOf(ChurchTab.FEATURES, ChurchTab.SEQUENCES, ChurchTab.SCORES, ChurchTab.CHORDS),
            churchTabs(ChurchFeatures(sequences = true, scores = true, chords = true)),
        )
        assertEquals(listOf(ChurchTab.FEATURES, ChurchTab.SCORES), churchTabs(ChurchFeatures(scores = true)))
    }
}
