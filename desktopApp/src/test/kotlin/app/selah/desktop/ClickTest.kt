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
        // El operador toca algo a mano: la letra automática se pausa y no le pelea
        presenter.handle(ClientMessage.Prev)
        assertFalse(click.auto)
        goToBeat(30.0); assertEquals(1, st.index)
        // "Retomar": salta a donde va la música
        presenter.handle(ClientMessage.SetAutoLyrics(true))
        assertEquals(3, st.index)
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
