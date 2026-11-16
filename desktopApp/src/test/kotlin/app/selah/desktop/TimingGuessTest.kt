package app.selah.desktop

import app.selah.desktop.GuideVoice.Word
import app.selah.desktop.TimingGuess.Kind
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TimingGuessTest {
    /** "verso" a los [at] ms y la cuenta "uno dos tres cuatro" un tiempo después, a [beat] ms por tiempo. */
    private fun counted(word: String, at: Int, beat: Int = 500) = listOf(Word(word, at, at + 300)) +
        listOf("uno", "dos", "tres", "cuatro").mapIndexed { i, w -> Word(w, at + 2 * beat + i * beat, at + 2 * beat + i * beat + 200) }

    @Test
    fun cuesStartAfterTheCountAndRepeatsSplitEvenly() {
        // 120 BPM (500 ms por tiempo): "intro", "verso" con cuenta, "repetir" sin cuenta, "coro" con cuenta, "final"
        val words = counted("intro", 0) + counted("verso", 10_000) + listOf(Word("repetir", 26_000, 26_400)) +
            counted("coro", 34_000) + listOf(Word("bajar", 50_000, 50_300), Word("intensidad", 50_300, 50_800), Word("coro", 50_900, 51_200)) +
            counted("final", 60_000)
        val cues = TimingGuess.cues(words, beatMs = 500.0, offsetMs = 0)
        assertEquals(listOf(Kind.INSTRUMENTAL, Kind.VERSE, Kind.REPEAT, Kind.CHORUS, Kind.CHORUS, Kind.END), cues.map { it.kind })
        // "verso" a los 10 s: cuenta 11,0-12,5 → empieza en 13,0
        assertEquals(13_000L, cues[1].startMs)
        // El "repetir" (sin cuenta) queda en la mitad entre el verso (13 s) y el coro (37 s)
        assertEquals(37_000L, cues[3].startMs)
        assertEquals(25_000L, cues[2].startMs)
        // "bajar intensidad coro" es un coro; sin cuenta, con la distancia típica aviso→comienzo (3 s)
        assertEquals(54_000L, cues[4].startMs)
    }

    private val song = """
        # Bueno es alabar
        Orden: Estrofa, Coro

        Estrofa 1:
        uno
        dos

        Estrofa 2:
        tres

        Coro:
        cuatro
        cinco
        seis

        Final:
        siete
    """.trimIndent()

    @Test
    fun theSuggestionFollowsTheSequenceArrangement() {
        val cues = listOf(
            TimingGuess.Cue(Kind.INSTRUMENTAL, 0, 0), TimingGuess.Cue(Kind.VERSE, 0, 10_000), TimingGuess.Cue(Kind.REPEAT, 0, 20_000),
            TimingGuess.Cue(Kind.CHORUS, 0, 30_000), TimingGuess.Cue(Kind.INSTRUMENTAL, 0, 45_000), TimingGuess.Cue(Kind.VERSE, 0, 50_000),
            TimingGuess.Cue(Kind.END, 0, 60_000),
        )
        val s = TimingGuess.suggest(song, cues, unitMs = 500.0, offsetMs = 0, durationMs = 70_000)!!
        // Repetir vuelve a la misma estrofa; el segundo "verso" pasa a la Estrofa 2
        assertEquals(listOf("Estrofa 1", "Estrofa 1", "Coro", "Estrofa 2", "Final"), s.order)
        // En tiempos de 500 ms: la estrofa a los 10 s (20); el coro de 3 líneas se reparte de 30 s a 45 s (antes del instrumental)
        assertEquals(listOf(20, 40, 60, 100, 120), s.timing)
    }

    @Test
    fun aSongWithoutSectionsCannotBeGuessed() {
        assertNull(TimingGuess.suggest("# A\n\nuno\n\ndos\n", listOf(TimingGuess.Cue(Kind.VERSE, 0, 1_000)), 500.0, 0, 10_000))
    }

    private lateinit var dir: File

    @BeforeTest
    fun setUp() { dir = createTempDirectory("selah-sugerir").toFile() }

    @AfterTest
    fun tearDown() { dir.deleteRecursively() }

    @Test
    fun applyReviewAndUndo() {
        File(dir, "canciones").mkdirs()
        File(dir, "canciones/Bueno es alabar.txt").writeText(song + "\n")
        val library = Library(dir).also { it.reload() }
        library.applySuggestion("Bueno es alabar", listOf("Estrofa 1", "Coro", "Final"), listOf(0, 8, 12, 16, 24, 30))
        var s = library.song("Bueno es alabar")!!
        assertTrue(s.timingSuggested)
        assertEquals(listOf("Estrofa 1", "Coro", "Final"), s.slides.map { it.label })
        assertTrue("Orden sin secuencia: Estrofa, Coro" in File(dir, "canciones/Bueno es alabar.txt").readText())
        // Sugerir otra vez no pierde el orden original
        library.applySuggestion("Bueno es alabar", listOf("Coro"), listOf(0, 4, 8))
        assertTrue("Orden sin secuencia: Estrofa, Coro" in File(dir, "canciones/Bueno es alabar.txt").readText())
        // Grabar a mano: ya no es sugerido
        library.setSongTiming("Bueno es alabar", listOf(0, 5, 9))
        assertFalse(library.song("Bueno es alabar")!!.timingSuggested)
        // Deshacer: el orden de antes y sin tiempos
        library.applySuggestion("Bueno es alabar", listOf("Final"), listOf(0))
        library.undoSuggestion("Bueno es alabar")
        s = library.song("Bueno es alabar")!!
        assertFalse(s.timingSuggested)
        assertTrue(s.timing.isEmpty())
        assertEquals(app.selah.shared.SongParser.parse("x", song).slides.map { it.label }, s.slides.map { it.label })
        assertFalse("Orden sin secuencia" in File(dir, "canciones/Bueno es alabar.txt").readText())
        // La letra sigue intacta
        assertTrue("cinco" in File(dir, "canciones/Bueno es alabar.txt").readText())
    }
}
