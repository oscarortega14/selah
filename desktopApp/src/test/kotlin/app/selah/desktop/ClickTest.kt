package app.selah.desktop

import app.selah.shared.ClientMessage
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Click de los músicos y letra automática (en el PC: él lleva el compás, no suena). */
class ClickTest {
    private lateinit var dir: File
    private lateinit var library: Library
    private var now = 1_000_000L
    private lateinit var presenter: Presenter
    private val st get() = presenter.state.value
    private val click get() = st.click!!

    @BeforeTest
    fun setUp() {
        dir = createTempDirectory("selah-click").toFile()
        File(dir, "canciones").mkdirs()
        // 120 BPM = medio segundo por tiempo
        File(dir, "canciones/Con tempo.txt").writeText("# Con tempo\nBPM: 120\nCompás: 3/4\n\nuno\n\ndos\n\ntres\n\ncuatro\n")
        File(dir, "canciones/Sin tempo.txt").writeText("# Sin tempo\n\nuno\n\ndos\n")
        library = Library(dir).also { it.reload() }
        presenter = Presenter(library) { now }
    }

    @AfterTest
    fun tearDown() { dir.deleteRecursively() }

    /** Avanza el reloj hasta el tiempo [beat] del click y deja que corra el reloj del programa. */
    private fun goToBeat(beat: Double) { now = click.startAt + Math.round(beat * click.beatMs); presenter.tick() }

    @Test
    fun startsWithTheSongTempoAndAOneBarCountIn() {
        presenter.handle(ClientMessage.SelectSong("Con tempo"))
        assertNull(presenter.handle(ClientMessage.StartClick()))
        assertEquals(120, click.bpm)
        assertEquals(3, click.beatsPerBar)
        assertEquals(3, click.countIn)
        // La cuenta (3 tiempos de 500 ms) empieza después de un margen para que llegue la orden a todos
        assertEquals(now + Presenter.CLICK_LEAD_MS + 1500, click.startAt)
        assertFalse(click.hasTiming)

        presenter.handle(ClientMessage.SelectSong("Sin tempo"))
        assertNull(st.click) // otra canción: el click se detiene
        assertTrue(presenter.handle(ClientMessage.StartClick())!!.contains("BPM"))
        assertNull(presenter.handle(ClientMessage.StartClick(bpm = 75)))
        assertEquals(4, click.beatsPerBar) // sin "Compás:", 4/4
        assertEquals("El tempo debe ser de 30 a 250 BPM", presenter.handle(ClientMessage.StartClick(bpm = 400)))
        presenter.handle(ClientMessage.StopClick)
        assertNull(st.click)
    }

    @Test
    fun tempoChangeKeepsTheBeat() {
        presenter.handle(ClientMessage.SelectSong("Con tempo"))
        presenter.handle(ClientMessage.StartClick())
        goToBeat(10.0)
        presenter.handle(ClientMessage.SetTempo(60))
        assertEquals(60, click.bpm)
        assertEquals(10.0, click.beatAt(now), 0.01) // sigue en el tiempo 10, ahora más lento
        now += 1000
        assertEquals(11.0, click.beatAt(now), 0.01)
    }

    @Test
    fun recordOnceThenTheLyricsFollowTheClick() {
        presenter.handle(ClientMessage.SelectSong("Con tempo"))
        presenter.handle(ClientMessage.StartClick())
        goToBeat(0.0)
        assertNull(presenter.handle(ClientMessage.RecordTiming(true)))
        assertTrue(click.recording)
        goToBeat(8.0); presenter.handle(ClientMessage.Next)
        goToBeat(16.2); presenter.handle(ClientMessage.Next)
        goToBeat(23.8); presenter.handle(ClientMessage.Next)
        assertNull(presenter.handle(ClientMessage.RecordTiming(false)))
        assertFalse(click.recording)
        assertTrue(click.hasTiming)
        // Quedó en la canción
        assertTrue("Tiempos: 0 8 16 24" in File(dir, "canciones/Con tempo.txt").readText())
        assertEquals(listOf(0, 8, 16, 24), library.song("Con tempo")!!.timing)

        // Otra vez, con la letra automática
        presenter.handle(ClientMessage.StopClick)
        presenter.handle(ClientMessage.SelectSong("Con tempo"))
        presenter.handle(ClientMessage.StartClick())
        assertTrue(click.auto)
        goToBeat(-2.0); assertEquals(0, st.index) // la cuenta
        goToBeat(7.6); assertEquals(1, st.index) // medio tiempo antes del 8
        goToBeat(16.0); assertEquals(2, st.index)
        // "Pausar": el operador sigue a mano y la letra no le pelea
        presenter.handle(ClientMessage.SetAutoLyrics(false))
        presenter.handle(ClientMessage.Prev)
        assertFalse(click.auto)
        goToBeat(30.0); assertEquals(1, st.index)
        // "Retomar": salta a donde va la música
        presenter.handle(ClientMessage.SetAutoLyrics(true))
        assertEquals(3, st.index)
    }

    @Test
    fun aManualMoveRealignsTheLyricsAndTheyKeepGoing() {
        File(dir, "canciones/Con tempo.txt").writeText("# Con tempo\nBPM: 120\nTiempos: 0 8 16 24\n\nuno\n\ndos\n\ntres\n\ncuatro\n")
        library.reload()
        presenter.handle(ClientMessage.SelectSong("Con tempo"))
        presenter.handle(ClientMessage.StartClick())
        val start = click.startAt
        goToBeat(9.0); assertEquals(1, st.index)
        // La banda repitió "dos": en el tiempo 18 el operador vuelve a "dos". La letra no se pausa: sigue desde ahí.
        goToBeat(18.0); assertEquals(2, st.index)
        presenter.handle(ClientMessage.Prev)
        assertEquals(1, st.index)
        assertTrue(click.auto)
        goToBeat(20.0); assertEquals(1, st.index) // no salta de vuelta a "tres"
        goToBeat(25.6); assertEquals(2, st.index) // 8 tiempos después de volver a "dos" (medio tiempo antes)
        goToBeat(33.6); assertEquals(3, st.index)
        // El click de los músicos no se movió
        assertEquals(start, click.startAt)
        // Se adelantaron: el operador pasa a la siguiente antes y la letra sigue desde ahí
        presenter.handle(ClientMessage.Prev); goToBeat(34.0); assertEquals(2, st.index)
        presenter.handle(ClientMessage.Next); assertEquals(3, st.index)
        goToBeat(40.0); assertEquals(3, st.index)
        // "Retomar" vuelve a donde va la música, sin el ajuste
        presenter.handle(ClientMessage.SetAutoLyrics(true))
        goToBeat(41.0); assertEquals(3, st.index)
    }

    @Test
    fun lyricsOnlyDoesNotSoundAndTheDirectorClickTakesOver() {
        File(dir, "canciones/Con tempo.txt").writeText("# Con tempo\nBPM: 120\nTiempos: 0 8 16 24\n\nuno\n\ndos\n\ntres\n\ncuatro\n")
        library.reload()
        presenter.handle(ClientMessage.SelectSong("Con tempo"))
        assertNull(presenter.handle(ClientMessage.StartClick(lyricsOnly = true)))
        assertTrue(click.lyricsOnly)
        assertFalse(click.audible) // ningún celular suena
        assertTrue(click.auto)
        goToBeat(8.0); assertEquals(1, st.index)
        // Con el click de los músicos andando, la letra sola no lo reemplaza
        presenter.handle(ClientMessage.StartClick())
        assertFalse(click.lyricsOnly)
        assertTrue(click.audible)
        assertEquals("El director ya dio el click: la letra va con él", presenter.handle(ClientMessage.StartClick(lyricsOnly = true)))
    }

    @Test
    fun theTitleComesFirstAndDoesNotCountForTheTiming() {
        File(dir, "canciones/Con tempo.txt").writeText("# Con tempo\nBPM: 120\nTiempos: 0 8 16 24\n\nuno\n\ndos\n\ntres\n\ncuatro\n")
        library.reload()
        presenter.titleCardOn = { true }
        presenter.handle(ClientMessage.SelectSong("Con tempo"))
        assertTrue(st.titleCard)
        assertEquals(0, st.index)
        // "Siguiente" muestra la primera diapositiva (no se salta "uno")
        presenter.handle(ClientMessage.Next)
        assertFalse(st.titleCard); assertEquals(0, st.index)
        presenter.handle(ClientMessage.Next); assertEquals(1, st.index)
        // "Anterior" desde la primera vuelve al título
        presenter.handle(ClientMessage.Prev); presenter.handle(ClientMessage.Prev)
        assertTrue(st.titleCard); assertEquals(0, st.index)
        // Tocar una diapositiva quita el título
        presenter.handle(ClientMessage.GoTo(2)); assertFalse(st.titleCard)
        // Con la letra automática: el título se queda en la cuenta y se va cuando llega la letra (los tiempos no cambian)
        presenter.handle(ClientMessage.SelectSong("Con tempo"))
        presenter.handle(ClientMessage.StartClick(lyricsOnly = true))
        goToBeat(-2.0); assertTrue(st.titleCard)
        goToBeat(0.0); assertFalse(st.titleCard); assertEquals(0, st.index)
        goToBeat(8.0); assertEquals(1, st.index)
        // Apagado: directo a la letra
        presenter.titleCardOn = { false }
        presenter.handle(ClientMessage.SelectSong("Con tempo"))
        assertFalse(st.titleCard)
    }

    @Test
    fun changingSongWhileRecordingSavesWhatWasRecorded() {
        presenter.handle(ClientMessage.SelectSong("Con tempo"))
        presenter.handle(ClientMessage.StartClick())
        goToBeat(0.0)
        presenter.handle(ClientMessage.RecordTiming(true))
        goToBeat(12.0); presenter.handle(ClientMessage.Next)
        presenter.handle(ClientMessage.SelectSong("Sin tempo"))
        assertEquals(listOf(0, 12, -1, -1), library.song("Con tempo")!!.timing)
        assertNull(st.click)
    }

    @Test
    fun tooShortRecordingIsNotSavedAndAutoNeedsTiming() {
        presenter.handle(ClientMessage.SelectSong("Con tempo"))
        assertEquals("Primero inicia el click", presenter.handle(ClientMessage.RecordTiming(true)))
        presenter.handle(ClientMessage.StartClick())
        presenter.handle(ClientMessage.RecordTiming(true))
        assertNotNull(presenter.handle(ClientMessage.RecordTiming(false))) // ni una diapositiva más: no se guarda
        assertFalse("Tiempos:" in File(dir, "canciones/Con tempo.txt").readText())
        presenter.handle(ClientMessage.SetAutoLyrics(true))
        assertFalse(click.auto) // sin tiempos no hay letra automática
    }
}
