package app.selah.desktop

import app.selah.shared.ClientMessage
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Seguir la secuencia que suena en otro aparato escuchando su click. */
class ClickFollowerTest {
    private val rate = 22_050

    /** "Lo que se oye": el click de [clicks] (ms del archivo) empezando a la hora [zero], con ruido y una voz suelta. */
    private fun heard(clicks: List<Int>, zero: Long, untilMs: Int, drift: Double = 1.0): List<Pair<Long, ShortArray>> {
        val rnd = Random(3)
        val chunk = rate / 50
        val out = mutableListOf<Pair<Long, ShortArray>>()
        val startWall = zero - 3_000 // se empieza a escuchar 3 s antes (silencio y ruido)
        var frame = 0
        val totalFrames = ((untilMs + 3_000) * rate / 1000)
        while (frame < totalFrames) {
            val buf = ShortArray(chunk)
            for (i in 0 until chunk) {
                val tWall = startWall + (frame + i) * 1000.0 / rate
                val tFile = (tWall - zero) * drift // el otro aparato va un poquito más rápido o lento
                var v = rnd.nextInt(-150, 150).toDouble()
                val k = clicks.binarySearch(tFile.toInt()).let { if (it < 0) -it - 2 else it }
                clicks.getOrNull(k)?.let { c -> val d = tFile - c; if (d in 0.0..8.0) v += sin(2 * PI * 2000 * d / 1000) * 18_000 }
                // La voz guía ("Intro") a los 300 ms y a los 5,25 s
                if (tFile in 300.0..700.0 || tFile in 5_250.0..5_600.0) v += sin(2 * PI * 300 * tFile / 1000) * 6_000 + rnd.nextInt(-4000, 4000)
                buf[i] = v.toInt().coerceIn(-32768, 32767).toShort()
            }
            out += (startWall + frame * 1000L / rate) to buf
            frame += chunk
        }
        return out
    }

    private val grid = (0 until 400).map { 20 + it * 400 } // 150 BPM, primer golpe a los 20 ms

    @Test
    fun startsWhenItHearsTheClickAndStaysInTime() {
        var anchor: Long? = null
        var last: Long? = null
        val f = ClickFollower(grid, { anchor = it; last = it }, { last = it })
        val zero = 5_000_000L
        for ((t, buf) in heard(grid, zero, untilMs = 40_000, drift = 1.0015)) f.feed(buf, buf.size, rate, t)
        assertNotNull(anchor)
        assertTrue(abs(anchor!! - zero) <= 6, "arrancó ${anchor!! - zero} ms corrido")
        // A los 40 s el otro aparato va 60 ms adelantado (1,5 por mil): se corrigió solo
        val expectedZero = zero - (40_000 * 0.0015).toLong()
        assertTrue(abs(last!! - expectedZero) <= 25, "quedó ${last!! - expectedZero} ms corrido")
    }

    @Test
    fun silenceOrNoiseNeverStartsIt() {
        var anchor: Long? = null
        val f = ClickFollower(grid, { anchor = it }, {})
        val rnd = Random(5)
        for (i in 0 until 500) {
            val buf = ShortArray(441) { rnd.nextInt(-200, 200).toShort() }
            f.feed(buf, buf.size, rate, 1_000L + i * 20)
        }
        assertNull(anchor)
        assertFalse(f.anchored)
    }

    // --- con el presentador ---
    private lateinit var dir: File

    @BeforeTest
    fun setUp() { dir = createTempDirectory("selah-escuchar").toFile() }

    @AfterTest
    fun tearDown() { dir.deleteRecursively() }

    class FakeInput : ClickInput {
        var anchor: ((Long) -> Unit)? = null
        var adjust: ((Long) -> Unit)? = null
        var stopped = 0
        var clicks: List<Int> = emptyList()
        override fun start(fileClicks: List<Int>, onAnchor: (Long) -> Unit, onAdjust: (Long) -> Unit): String? {
            clicks = fileClicks; anchor = onAnchor; adjust = onAdjust; return null
        }
        override fun stop() { stopped++ }
    }

    @Test
    fun theLyricsWaitForTheClickThenFollowIt() {
        File(dir, "canciones").mkdirs()
        File(dir, "canciones/A.txt").writeText("# A\nTiempos: 0 8\n\nuno\n\ndos\n")
        val library = Library(dir).also { it.reload() }
        val src = SequenceSource(File(dir, "a.wav"), bpm = 150, clickLeft = true, offsetMs = 20, durationMs = 60_000, clicks = grid)
        val silent = MusiciansTest.FakeOutput()
        val input = FakeInput()
        var now = 1_000_000L
        val p = Presenter(library) { now }
        p.sequences = SequenceHookup({ src }, MusiciansTest.FakeOutput(), { ChurchFeatures.ALL.copy(sequencePlays = false, listenClick = true) }, silent, { input })
        p.handle(ClientMessage.SelectSong("A"))
        assertNull(p.handle(ClientMessage.StartSequence))
        assertTrue(p.state.value.click!!.waiting)
        assertEquals(grid, input.clicks)
        now += 30_000; p.tick()
        assertEquals(0, p.state.value.index) // esperando: nada se mueve
        assertTrue(silent.played.isEmpty())
        // Se oye el click: el archivo empezó hace 1 s
        input.anchor!!(now - 1_000)
        val c = p.state.value.click!!
        assertFalse(c.waiting)
        assertEquals(now - 1_000 + 20, c.startAt)
        assertEquals(59_000L, silent.played.single().second.durationMs) // lo que le falta
        // Se corrige solo y la letra lo sigue
        input.adjust!!(now - 1_100)
        assertEquals(now - 1_100 + 20, p.state.value.click!!.startAt)
        now = p.state.value.click!!.startAt + 8 * 400; p.tick()
        assertEquals(1, p.state.value.index)
        p.handle(ClientMessage.StopSequence)
        assertEquals(1, input.stopped)
    }
}
