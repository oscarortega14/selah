package app.selah.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServicesTest {
    private val dominical = WorshipService(
        "Dominical", day = 7, time = "9:00",
        steps = listOf(
            ServiceStep(StepKind.SONG, "Alabanza"), ServiceStep(StepKind.SONG, "Alabanza"), ServiceStep(StepKind.SONG, "Adoración"),
            ServiceStep(StepKind.BIBLE), ServiceStep(StepKind.ANNOUNCEMENT), ServiceStep(StepKind.SERMON),
        ),
        rotation = listOf("Dominical A", "Dominical B", "Dominical C"),
    )

    @Test
    fun rotationGoesInTurnAndSkipsDeletedLists() {
        val saved = setOf("Dominical A", "Dominical B", "Dominical C", "Otra")
        assertEquals("Dominical A", dominical.nextList(saved)) // nunca usada: la primera
        assertEquals("Dominical B", dominical.copy(lastUsed = "Dominical A").nextList(saved))
        assertEquals("Dominical A", dominical.copy(lastUsed = "Dominical C").nextList(saved)) // da la vuelta
        assertEquals("Dominical C", dominical.copy(lastUsed = "Dominical A").nextList(saved - "Dominical B")) // borrada: se salta
        assertNull(dominical.nextList(setOf("Otra")))
        // Abrir una lista la marca como usada en los cultos que la tienen
        val cfg = ServicesConfig(listOf(dominical, WorshipService("Oración", 2, rotation = listOf("Oración 1"))))
        val after = cfg.markUsed("Dominical B")
        assertEquals("Dominical B", after.services[0].lastUsed)
        assertNull(after.services[1].lastUsed)
    }

    @Test
    fun todaysServiceByDayAndTime() {
        val cfg = ServicesConfig(
            listOf(
                dominical,
                WorshipService("Dominical noche", 7, "6:00 p.m."),
                WorshipService("Oración", 2, "7:00 p.m."),
            ),
        )
        assertEquals("Dominical", cfg.serviceFor(7, 7 * 60)?.name) // domingo 7 a.m.
        assertEquals("Dominical", cfg.serviceFor(7, 11 * 60)?.name) // empezó hace 2 h: todavía cuenta
        assertEquals("Dominical noche", cfg.serviceFor(7, 15 * 60)?.name)
        assertEquals("Dominical noche", cfg.serviceFor(7, 23 * 60)?.name) // ya pasaron todos: el último
        assertEquals("Oración", cfg.serviceFor(2, 0)?.name)
        assertNull(cfg.serviceFor(3, 600))
        assertEquals("Dominical · domingo 9:00", dominical.label)
    }

    @Test
    fun timesAndLabels() {
        assertEquals(9 * 60, parseMinutes("9:00"))
        assertEquals(19 * 60 + 30, parseMinutes("19:30"))
        assertEquals(19 * 60, parseMinutes("7:00 p.m."))
        assertEquals(19 * 60, parseMinutes("7 pm"))
        assertEquals(0, parseMinutes("12:00 a.m."))
        assertNull(parseMinutes("mañana"))
        assertNull(parseMinutes("25:00"))
        assertEquals(listOf("Alabanza 1", "Alabanza 2", "Adoración", "Lectura", "Anuncio", "Predicación"), dominical.stepLabels())
    }

    @Test
    fun suggestionsPreferTheTypeAndTheLongestUnsung() {
        val songs = listOf(
            SongSummary("a", "A", tags = listOf("Alabanza"), lastSung = "2026-11-01"),
            SongSummary("b", "B", tags = listOf("Alabanza"), lastSung = null), // nunca
            SongSummary("c", "C", tags = listOf("Adoración"), lastSung = "2026-01-01"),
            SongSummary("d", "D", tags = emptyList(), lastSung = "2025-05-05"), // sin tipo: vale, pero después
            SongSummary("e", "E", tags = listOf("alabanza"), lastSung = "2026-06-01"),
        )
        assertEquals(listOf("b", "e", "a", "d"), suggestSongs(songs, "Alabanza").map { it.id })
        assertEquals(listOf("e", "a", "d"), suggestSongs(songs, "ALABANZA", exclude = setOf("b")).map { it.id })
        assertEquals(listOf("b", "d", "c", "e", "a"), suggestSongs(songs, "").map { it.id }) // sin tipo pedido: todas por antigüedad
    }

    @Test
    fun daysAndRecentRepeats() {
        val today = Triple(2026, 11, 8)
        assertEquals(6, daysSince("2026-11-02", today))
        assertEquals(366, daysSince("2025-11-07", today))
        assertNull(daysSince(null, today))
        assertNull(daysSince("roto", today))
        assertTrue(recentlySung("2026-11-02", today, 14))
        assertFalse(recentlySung("2026-10-20", today, 14))
        assertFalse(recentlySung("2026-11-02", today, 0)) // 0 = no avisar
        assertEquals("hace 6 días", agoText(6))
        assertEquals("hace 3 semanas", agoText(21))
        assertEquals("ayer", agoText(1))
    }

    @Test
    fun typeLineInSongs() {
        val s = SongParser.parse("x", "# Santo\nTipo: Alabanza, adoración ; alabanza\nAutor: Ana\n\nSanto, santo\n")
        assertEquals(listOf("Alabanza", "adoración"), s.tags)
        assertEquals("Ana", s.author)
        assertEquals(listOf("Santo, santo"), s.slides.map { it.text }) // "Tipo:" no se proyecta
        assertEquals(listOf("Alabanza", "adoración"), s.summary().tags)
        // "Tipo:" dentro de la letra (después de la primera estrofa) sí es letra
        assertEquals(2, SongParser.parse("y", "# T\n\nuno\n\nTipo: algo\n").slides.size)
    }
}
