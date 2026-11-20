package app.selah.desktop

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

/** Letra automática desde una grabación (con letra inventada para las pruebas). */
class RecordingTimingsTest {
    private val song = """
        # Luz del camino
        Orden: Estrofa, Coro

        Estrofa:
        Caminamos en la mañana clara
        buscando tu voz en el viento

        Tu luz alumbra cada paso
        y llena de paz el corazón

        Coro:
        Cantaremos siempre a tu nombre
        cantaremos con el alma entera

        Cantaremos siempre a tu nombre
        por los siglos te alabaremos
    """.trimIndent()

    private val sections = RecordingTimings.sections(song)

    private fun p(start: Double, end: Double, text: String) = HeardPhrase(start, end, text)

    /** Como la entiende Whisper: palabras mal oídas, saludos, frases que junta o separa. */
    private val heard = listOf(
        p(0.0, 3.0, "Hola, bienvenidos."),
        p(20.0, 28.0, "Caminamos en la mañana clara buscando tu vos en el viento"),
        p(28.0, 36.0, "Tu luz alumbra cada paso y llena de pas el corazón"),
        p(36.0, 40.0, "Cantaremos siempre a tu hombre"),
        p(40.0, 44.0, "cantaremos con el alma entera"),
        p(44.0, 52.0, "Cantaremos siempre a tu nombre por los siglos te alabaremos"),
        p(52.0, 60.0, "Cantaremos siempre a tu nombre cantaremos con el alma entera"),
        p(60.0, 68.0, "Cantaremos siempre a tu nombre por los siglos te alabaremos"),
        // En el instrumental repite la última frase, y dura de más: inventada.
        p(68.0, 86.0, "Cantaremos siempre a tu nombre por los siglos te alabaremos"),
        p(90.0, 98.0, "Caminamos en la mañana clara buscando tu voz en el viento"),
        p(98.0, 106.0, "Tu luz alumbra cada paso y llena de paz el corazón"),
        p(106.0, 114.0, "Cantaremos siempre a tu nombre cantaremos con el alma entera"),
        p(114.0, 122.0, "Cantaremos siempre a tu nombre por los siglos te alabaremos"),
        // Al final, una frase cortada y algo que no es de la canción.
        p(124.0, 127.0, "Caminamos en la mañana clara buscando tu voz en el viento"),
        p(127.0, 131.0, "Subtítulos realizados por la comunidad"),
    )

    @Test
    fun ubicaLasVueltasConErroresDeWhisper() {
        val r = assertNotNull(LyricsAligner.align(heard, sections, 130.0))
        assertEquals(listOf("Estrofa", "Coro", "Coro", "Estrofa", "Coro"), r.order)
        assertEquals(listOf(20.0, 28.0, 36.0, 44.0, 52.0, 60.0, 90.0, 98.0, 106.0, 114.0), r.starts.map { it!!.let { s -> Math.round(s * 10) / 10.0 } })
    }

    @Test
    fun limpiaLoInventado() {
        val clean = LyricsAligner.clean(heard, 130.0)
        // La repetición larga del instrumental y la frase cortada del final se van; el saludo se queda (no es letra: lo ignora la alineación).
        assertFalse(clean.any { it.start == 68.0 })
        assertFalse(clean.any { it.start == 124.0 })
        assertTrue(clean.any { it.start == 0.0 })
        // Lo que pasa del final del audio no cuenta.
        assertTrue(LyricsAligner.clean(listOf(p(140.0, 150.0, "Cantaremos")), 130.0).isEmpty())
    }

    @Test
    fun unaDiapositivaQueNoSeCantoQuedaSinTiempo() {
        // Solo la segunda parte del coro.
        val r = assertNotNull(LyricsAligner.align(listOf(
            p(10.0, 18.0, "Caminamos en la mañana clara buscando tu voz en el viento"),
            p(18.0, 26.0, "Tu luz alumbra cada paso y llena de paz el corazón"),
            p(30.0, 38.0, "Cantaremos siempre a tu nombre por los siglos te alabaremos"),
        ), sections, 40.0))
        assertEquals(listOf("Estrofa", "Coro"), r.order)
        assertEquals(listOf(10.0, 18.0, null, 30.0), r.starts)
    }

    @Test
    fun otraCancionNoSeReconoce() {
        assertNull(LyricsAligner.align(listOf(p(0.0, 8.0, "Esta es otra canción que no tiene nada que ver"), p(8.0, 16.0, "con lo que dice la letra de arriba")), sections, 20.0))
    }

    @Test
    fun deSegundosAPulsos() {
        // Pulsos cada 0,5 s (120 BPM) desde el segundo 1.
        val beats = BeatTracker.Beats((0 until 300).map { 1.0 + it * 0.5 }, 120.0)
        val o = assertNotNull(RecordingTimings.outcome(heard, sections, beats, 130.0))
        assertEquals(120, o.bpm)
        assertEquals(listOf("Estrofa", "Coro", "Coro", "Estrofa", "Coro"), o.order)
        // Segundo 20 = pulso 38; la diapositiva aparece 2 pulsos antes.
        assertEquals(listOf(36, 52, 68, 84, 100, 116, 176, 192, 208, 224), o.timing)
    }

    @Test
    fun encuentraElPulsoAunqueLaBandaSeAcelere() {
        // Una "banda": golpes de 108 a 112 BPM a lo largo de 60 s, con ruido.
        val rate = RecordingAudio.RATE
        val samples = FloatArray(rate * 60)
        val rnd = Random(7)
        for (i in samples.indices) samples[i] = (rnd.nextFloat() - 0.5f) * 0.02f
        val truth = mutableListOf<Double>()
        var t = 1.0
        while (t < 59.0) {
            truth += t
            val start = (t * rate).toInt()
            for (k in 0 until rate / 20) samples[start + k] += (sin(2 * PI * 150 * k / rate) * 0.8 * (1 - k / (rate / 20.0))).toFloat()
            val bpm = 108 + 4 * t / 60
            t += 60 / bpm
        }
        val b = assertNotNull(BeatTracker.track(samples, null))
        assertTrue(abs(b.bpm - 110) < 1.5, "tempo ${b.bpm}")
        // Cada golpe verdadero tiene un pulso encontrado a menos de 30 ms.
        val missed = truth.count { tt -> b.times.none { abs(it - tt) < 0.03 } }
        assertTrue(missed <= 2, "pulsos perdidos: $missed de ${truth.size}; bpm ${b.bpm}; encontrados ${b.times.take(5)} verdad ${truth.take(5)}")
        assertEquals(10.0, BeatTracker.beatAt(listOf(0.0, 1.0, 2.0), 10.0), 1e-9)
        assertEquals(0.5, BeatTracker.beatAt(listOf(0.0, 1.0, 2.0), 0.5), 1e-9)
    }

    private lateinit var dir: File

    @BeforeTest
    fun setUp() { dir = createTempDirectory("selah-grabacion").toFile() }

    @AfterTest
    fun tearDown() { dir.deleteRecursively() }

    @Test
    fun guardaElTempoYSePuedeDeshacer() {
        File(dir, "canciones").mkdirs()
        File(dir, "canciones/Luz del camino.txt").writeText("$song\n")
        val library = Library(dir).also { it.reload() }
        library.applyRecording("Luz del camino", 112, listOf("Estrofa", "Coro", "Coro"), listOf(36, 52, 68, 84, 100, 116))
        val s = library.song("Luz del camino")!!
        assertEquals(112, s.bpm)
        assertTrue(s.timingSuggested)
        assertEquals(listOf(36, 52, 68, 84, 100, 116), s.timing)
        val text = File(dir, "canciones/Luz del camino.txt").readText()
        assertEquals(1, Regex("(?m)^BPM:").findAll(text).count())
        // Otra vez: no se duplica el BPM
        library.applyRecording("Luz del camino", 110, listOf("Estrofa", "Coro"), listOf(30, 46, 62, 78))
        assertEquals(110, library.song("Luz del camino")!!.bpm)
        assertEquals(1, Regex("(?m)^BPM:").findAll(File(dir, "canciones/Luz del camino.txt").readText()).count())
        library.undoSuggestion("Luz del camino")
        assertTrue(library.song("Luz del camino")!!.timing.isEmpty())
        assertTrue("Caminamos en la mañana clara" in File(dir, "canciones/Luz del camino.txt").readText())
    }
}
