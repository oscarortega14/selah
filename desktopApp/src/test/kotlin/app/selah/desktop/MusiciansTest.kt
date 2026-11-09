package app.selah.desktop

import app.selah.shared.ClientMessage
import app.selah.shared.FEATURE_CHORDS
import app.selah.shared.FEATURE_CLICK
import app.selah.shared.FEATURE_SCORES
import app.selah.shared.FEATURE_SEQUENCES
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.common.PDRectangle
import java.io.File
import java.net.ServerSocket
import javax.imageio.ImageIO
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Secuencias, partituras e interruptores de "¿Qué usa tu iglesia?". */
class MusiciansTest {
    private lateinit var dir: File
    private lateinit var library: Library
    private lateinit var music: MusicLibrary
    private lateinit var commands: Commands
    private val presenter get() = commands.presenter
    private var now = 1_000_000L
    private var church = ChurchFeatures.ALL
    private val output = FakeOutput()
    private val st get() = presenter.state.value
    private val click get() = st.click!!

    /** Reproductor falso: anota lo que le piden y deja mandar los avisos a mano. */
    class FakeOutput : SequenceOutput {
        val played = mutableListOf<Pair<File, SequencePlan>>()
        var listener: SequenceOutput.Listener? = null
        var stops = 0
        var problem: String? = null
        override fun check(file: File) = problem
        override fun play(file: File, plan: SequencePlan, listener: SequenceOutput.Listener) { played += file to plan; this.listener = listener }
        override fun stop() { stops++ }
    }

    @BeforeTest
    fun setUp() {
        dir = createTempDirectory("selah-musicos").toFile()
        File(dir, "canciones").mkdirs()
        File(dir, "canciones/Con tempo.txt").writeText("# Con tempo\nBPM: 120\nCompás: 3/4\nTiempos: 0 8 16\n\nuno\n\ndos\n\ntres\n")
        File(dir, "canciones/Sin tempo.txt").writeText("# Sin tempo\n\nuno\n\ndos\n")
        File(dir, "canciones/Otra.txt").writeText("# Otra\n\nA\n\nB\n")
        library = Library(dir).also { it.reload() }
        music = MusicLibrary(dir)
        File(music.sequencesDir.also { it.mkdirs() }, "Con tempo.mp3").writeText("x")
        File(music.sequencesDir, "Sin tempo - secuencia.wav").writeText("x")
        File(music.sequencesDir, "Nadie.mp3").writeText("x")
        music.refresh(library.catalog.value.songs)
        commands = Commands(Presenter(library) { now }, ServicePlan(File(dir, "lista.json"), library, present = { null })).also { c ->
            c.church = { church }
            c.presenter.sequences = SequenceHookup({ id -> music.sequenceFor(id) }, output, { church })
        }
    }

    @AfterTest
    fun tearDown() { dir.deleteRecursively() }

    @Test
    fun sequencesAreFoundByTheSongName() {
        val cat = music.catalog.value
        assertEquals(mapOf("Con tempo" to "Con tempo.mp3", "Sin tempo" to "Sin tempo - secuencia.wav"), cat.sequences)
        assertEquals(listOf("Nadie.mp3"), cat.sequenceOrphans)
    }

    @Test
    fun theSequencePlaysAndTheLyricsFollowIt() {
        commands.handle(ClientMessage.SelectSong("Con tempo"))
        assertNull(commands.handle(ClientMessage.StartSequence))
        val (file, plan) = output.played.single()
        assertEquals("Con tempo.mp3", file.name)
        assertEquals(SequencePlan(120, 3, countIn = 0, splitClick = false, device = null), plan)
        assertEquals("Con tempo.mp3", click.sequence)
        assertTrue(click.audible)
        assertTrue(click.auto) // tiene "Tiempos:"
        assertEquals(now + Presenter.SEQUENCE_LEAD_MS, click.startAt)

        // La tarjeta de sonido dice cuándo sonó de verdad: el click de los celulares se corre a eso.
        output.listener!!.musicStartsAt(now + 450)
        assertEquals(now + 450, click.startAt)
        output.listener!!.musicStartsAt(now + 452) // 2 ms: no vale la pena mandarlo a todos
        assertEquals(now + 450, click.startAt)

        now = click.startAt + 8 * 500; presenter.tick()
        assertEquals(1, st.index)
        // No se le cambia el tempo a una grabación
        assertEquals("El tempo lo lleva la secuencia", commands.handle(ClientMessage.SetTempo(100)))
        assertEquals("Ya suena la secuencia: el click va con ella", commands.handle(ClientMessage.StartClick(90)))
    }

    @Test
    fun changingWhatIsOnScreenDoesNotCutTheMusic() {
        commands.handle(ClientMessage.SelectSong("Con tempo"))
        commands.handle(ClientMessage.StartSequence)
        commands.handle(ClientMessage.SelectSong("Otra"))
        assertEquals(0, output.stops) // la música sigue
        assertNotNull(st.click)
        assertFalse(click.auto)
        now = click.startAt + 16 * 500; presenter.tick()
        assertEquals(0, st.index) // la letra de "Otra" no se mueve sola con los tiempos de otra canción
        commands.handle(ClientMessage.SetAutoLyrics(true))
        assertFalse(click.auto)
        assertEquals("Con la secuencia andando, se graban los tiempos de su canción", commands.handle(ClientMessage.RecordTiming(true)))
        // De vuelta a la canción de la secuencia: la letra la sigue de nuevo, donde va la música
        commands.handle(ClientMessage.SelectSong("Con tempo"))
        assertTrue(click.auto)
        assertEquals(2, st.index)
        commands.handle(ClientMessage.StopSequence)
        assertEquals(1, output.stops)
        assertNull(st.click)
    }

    @Test
    fun whenTheSongEndsTheClickGoesAway() {
        commands.handle(ClientMessage.SelectSong("Con tempo"))
        commands.handle(ClientMessage.StartSequence)
        val first = output.listener!!
        commands.handle(ClientMessage.StartSequence) // otra vez: la anterior se olvida
        first.finished(null)
        assertNotNull(st.click) // aviso de la secuencia anterior: no cuenta
        output.listener!!.finished("Se detuvo la secuencia: algo")
        assertNull(st.click)
    }

    @Test
    fun withoutBpmThereIsNoClickAndTimingCountsSeconds() {
        church = church.copy(splitClick = true)
        commands.handle(ClientMessage.SelectSong("Sin tempo"))
        commands.handle(ClientMessage.StartSequence)
        val plan = output.played.single().second
        assertNull(plan.bpm)
        assertFalse(plan.splitClick) // sin tempo no hay click que separar
        assertEquals(0, plan.countIn)
        assertEquals(60, click.bpm)
        assertFalse(click.audible)
        // Se pueden grabar los tiempos igual (en segundos)
        now = click.startAt
        assertNull(commands.handle(ClientMessage.RecordTiming(true)))
        now += 12_000; commands.handle(ClientMessage.Next)
        assertNull(commands.handle(ClientMessage.RecordTiming(false)))
        assertEquals(listOf(0, 12), library.song("Sin tempo")!!.timing)
    }

    @Test
    fun splitClickAddsAOneBarCountIn() {
        church = church.copy(splitClick = true, audioDevice = "Tarjeta USB")
        commands.handle(ClientMessage.SelectSong("Con tempo"))
        commands.handle(ClientMessage.StartSequence)
        assertEquals(SequencePlan(120, 3, countIn = 3, splitClick = true, device = "Tarjeta USB"), output.played.single().second)
        assertEquals(3, click.countIn)
        assertEquals(now + Presenter.SEQUENCE_LEAD_MS + 1500, click.startAt)
    }

    @Test
    fun clearMessagesWhenSomethingIsMissing() {
        commands.handle(ClientMessage.SelectSong("Otra"))
        assertTrue(commands.handle(ClientMessage.StartSequence)!!.contains("no tiene secuencia"))
        commands.handle(ClientMessage.ShowMedia("nada.jpg"))
        commands.handle(ClientMessage.SelectSong("Con tempo"))
        output.problem = "No pude leer la secuencia"
        assertEquals("No pude leer la secuencia", commands.handle(ClientMessage.StartSequence))
        assertNull(st.click)
    }

    @Test
    fun switchedOffFeaturesCannotBeUsedEvenFromAnOldPhone() {
        church = ChurchFeatures()
        commands.handle(ClientMessage.SelectSong("Con tempo"))
        assertEquals(Commands.OFF_SEQUENCES, commands.handle(ClientMessage.StartSequence))
        assertEquals(Commands.OFF_CLICK, commands.handle(ClientMessage.StartClick(80)))
        assertEquals(Commands.OFF_CLICK, commands.handle(ClientMessage.RecordTiming(true)))
        assertTrue(output.played.isEmpty())
        assertEquals(listOf("carousel"), RemoteServer.serverFeatures(ChurchFeatures()))
        assertEquals(
            listOf("carousel", FEATURE_CLICK, FEATURE_SEQUENCES, FEATURE_SCORES, FEATURE_CHORDS),
            RemoteServer.serverFeatures(ChurchFeatures.ALL),
        )
        // Solo secuencias: la app muestra el pulso y "▶ Secuencia", pero no ofrece el click suelto
        assertEquals(listOf("carousel", FEATURE_SEQUENCES), RemoteServer.serverFeatures(ChurchFeatures(sequences = true)))
    }

    @Test
    fun mixerPutsTheClickLeftAndTheMusicRight() {
        val rate = 1_000 // 1000 cuadros por segundo: 120 BPM = un tiempo cada 500 cuadros
        val m = SequenceMixer(rate, SequencePlan(120, 4, countIn = 4, splitClick = true))
        assertEquals(2_000, m.countInFrames)
        val music = ShortArray(20) { if (it % 2 == 0) 10_000 else 20_000 } // izquierda 10000, derecha 20000
        val out = ShortArray(20)
        // En la cuenta: hay click y nada de música
        m.render(out, 0, 10, music, 0)
        assertTrue(out[2] != 0.toShort(), "click al empezar la cuenta")
        assertEquals(0, out[3].toInt())
        // Ya en la música, lejos de un tiempo: sin click a la izquierda, la música en mono a la derecha
        m.render(out, 2_100, 10, music, 0)
        assertEquals(0, out[0].toInt())
        assertEquals(14_999, out[1].toInt(), 1)
        // Sin separar: la música tal cual y sin click
        val plain = SequenceMixer(rate, SequencePlan(120, 4, countIn = 0, splitClick = false))
        plain.render(out, 0, 10, music, 0)
        assertEquals(9_999, out[0].toInt(), 1)
        assertEquals(19_999, out[1].toInt(), 1)
        assertEquals(0.0, plain.click(0))
        // Bajar el volumen al parar
        plain.render(out, 0, 10, music, 0) { 0.5 }
        assertEquals(4_999, out[0].toInt(), 1)
    }

    private fun assertEquals(expected: Int, actual: Int, tolerance: Int) =
        assertTrue(kotlin.math.abs(expected - actual) <= tolerance, "esperaba $expected ± $tolerance, fue $actual")

    @Test
    fun realWavIsReadable() {
        val wav = File(dir, "tono.wav")
        val format = AudioFormat(44_100f, 16, 2, true, false)
        val bytes = ByteArray(44_100 * 4 / 10)
        AudioSystem.write(AudioInputStream(bytes.inputStream(), format, (bytes.size / 4).toLong()), AudioFileFormat.Type.WAVE, wav)
        val out = JavaSoundSequenceOutput()
        assertNull(out.check(wav))
        val junk = File(dir, "malo.mp3").apply { writeText("esto no es audio") }
        assertTrue(out.check(junk)!!.contains("MP3 o WAV"))
    }

    @Test
    fun scoresAreServedAsImagesOnlyWhenSwitchedOn() = runBlocking {
        music.scoresDir.mkdirs()
        PDDocument().use { doc -> repeat(2) { doc.addPage(PDPage(PDRectangle.A4)) }; doc.save(File(music.scoresDir, "Con tempo - Bajo.pdf")) }
        File(music.scoresDir, "Otra").mkdirs()
        PDDocument().use { doc -> doc.addPage(PDPage(PDRectangle.A4)); doc.save(File(music.scoresDir, "Otra/Teclado.pdf")) }
        music.refresh(library.catalog.value.songs)
        assertEquals(listOf("Bajo", "Teclado"), music.catalog.value.scores.map { it.instrument })
        val flags = MutableStateFlow(ChurchFeatures.ALL)
        val port = ServerSocket(0).use { it.localPort }
        val server = RemoteServer(commands, library, port, church = flags, music = music).also { it.start() }
        val http = HttpClient(CIO)
        try {
            val base = "http://127.0.0.1:$port"
            val count = http.get("$base/partitura/paginas?nombre=Con%20tempo%20-%20Bajo.pdf")
            assertEquals(HttpStatusCode.OK, count.status)
            assertEquals("""{"paginas":2}""", count.bodyAsText())
            val page = http.get("$base/partitura?nombre=Otra%2FTeclado.pdf&n=0".replace("%2F", "/"))
            assertEquals(HttpStatusCode.OK, page.status)
            val img = ImageIO.read(page.readRawBytes().inputStream())
            assertTrue(img.height > img.width) // vertical, como la hoja
            // Nada fuera de la carpeta
            for (bad in listOf("../canciones/Otra.txt", "..%2Fajustes.json", "Otra/../../x.pdf", "/etc/x.pdf")) {
                assertEquals(HttpStatusCode.NotFound, http.get("$base/partitura/paginas?nombre=$bad").status, bad)
            }
            flags.value = ChurchFeatures(congregation = false)
            assertEquals(HttpStatusCode.NotFound, http.get("$base/partitura/paginas?nombre=Con%20tempo%20-%20Bajo.pdf").status)
            assertEquals(HttpStatusCode.NotFound, http.get("$base/letra").status)
        } finally {
            http.close()
            server.stop()
        }
    }
}
