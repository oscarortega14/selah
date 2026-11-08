package app.selah.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClickTest {
    @Test
    fun clockSyncUsesTheFastestRoundTrip() {
        val c = ClockSync()
        // El PC va 1000 ms adelante. Muestra lenta (ida 200, vuelta 20): se equivocaría por 90 ms.
        c.add(t0 = 0, server = 1200, t1 = 220)
        assertEquals(1090, c.offset)
        // Muestra rápida y pareja: ida 5, vuelta 5 → exacta
        c.add(t0 = 1000, server = 2005, t1 = 1010)
        assertEquals(1000, c.offset)
        assertEquals(10, c.bestRtt)
        // Una más lenta después no la reemplaza
        c.add(t0 = 2000, server = 3300, t1 = 2400)
        assertEquals(1000, c.offset)
        // Respuestas absurdas se ignoran
        c.add(t0 = 5000, server = 0, t1 = 4000)
        assertEquals(1000, c.offset)
    }

    @Test
    fun beatsBarsAndTimes() {
        val click = ClickState(bpm = 120, beatsPerBar = 4, startAt = 10_000)
        assertEquals(500.0, click.beatMs)
        assertEquals(0.0, click.beatAt(10_000))
        assertEquals(-4.0, click.beatAt(8_000)) // la cuenta
        assertEquals(2.5, click.beatAt(11_250))
        assertEquals(12_000L, click.timeOfBeat(4))
        assertEquals(1L to 1, click.barAndBeat(0))
        assertEquals(2L to 3, click.barAndBeat(6))
        assertEquals(3L to 2, ClickState(90, 3, 0).barAndBeat(7)) // en 3/4
    }

    @Test
    fun autoSlideFollowsTheTiming() {
        val timing = listOf(0, 16, 32, -1, 48)
        assertNull(autoSlide(timing, -2.0)) // en la cuenta, nada
        assertEquals(0, autoSlide(timing, 0.0))
        assertEquals(0, autoSlide(timing, 15.0))
        assertEquals(1, autoSlide(timing, 15.5)) // medio tiempo antes
        assertEquals(2, autoSlide(timing, 40.0))
        assertEquals(4, autoSlide(timing, 47.6)) // la 3 no tiene tiempo: se salta
        assertNull(autoSlide(emptyList(), 10.0))
    }

    @Test
    fun recordingBecomesTiming() {
        assertEquals(listOf(0, 16, -1, 40), timingFromRecording(4, mapOf(0 to 0, 1 to 16, 3 to 40)))
        // Se devolvió a la 1 después de grabar la 2: la marca vieja (menor) se descarta
        assertEquals(listOf(0, 16, -1), timingFromRecording(3, mapOf(0 to 0, 1 to 16, 2 to 8)))
        assertTrue(usableTiming(listOf(0, -1, 16)))
        assertTrue(!usableTiming(listOf(-1, 3)))
    }

    @Test
    fun songLinesForTheClick() {
        val s = SongParser.parse("x", "# T\nBPM: 72\nCompás: 6/8\nTiempos: 0 16 x 32\n\nuno\n\ndos\n\ntres\n\ncuatro\n")
        assertEquals(72, s.bpm)
        assertEquals(6, s.beatsPerBar)
        assertEquals(listOf(0, 16, -1, 32), s.timing)
        assertEquals(4, s.slides.size) // las líneas no se proyectan
        assertNull(SongParser.parse("y", "# T\nBPM: 900\n\nuno\n").bpm) // fuera de rango
    }
}
