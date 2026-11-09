package app.selah.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MusicTest {
    private val songs = listOf(
        SongSummary("Cuan grande es El", "Cuán grande es Él"),
        SongSummary("Sublime gracia", "Sublime gracia"),
        SongSummary("Dios de pactos - en vivo", "Dios de pactos - en vivo"),
    )

    @Test
    fun scoreFileNames() {
        assertEquals("Cuán grande es Él" to "Bajo", splitMusicFileName("Cuán grande es Él - Bajo.pdf"))
        assertEquals("Cuán grande es Él" to "Bajo", splitMusicFileName("Cuán grande es Él/Bajo.pdf"))
        assertEquals("Sublime gracia" to null, splitMusicFileName("Sublime gracia.pdf"))
        assertEquals("A - B" to "Teclado 2", splitMusicFileName("A - B - Teclado 2.pdf"))
    }

    @Test
    fun scoresAreMatchedByTitleOrFileIgnoringAccentsAndCase() {
        val (found, orphans) = matchScores(
            listOf(
                "cuan grande es el - Bajo.pdf", "Cuán grande es Él/Teclado.pdf", "Sublime gracia.pdf",
                "Dios de pactos - en vivo.pdf", "Dios de pactos - en vivo - Batería.pdf", "Otra canción - Bajo.pdf",
            ),
            songs,
        )
        assertEquals(
            listOf(
                ScoreRef("Cuan grande es El", "Bajo", "cuan grande es el - Bajo.pdf"),
                ScoreRef("Cuan grande es El", "Teclado", "Cuán grande es Él/Teclado.pdf"),
                ScoreRef("Dios de pactos - en vivo", "Batería", "Dios de pactos - en vivo - Batería.pdf"),
                // el guion era parte del título: es la partitura para todos
                ScoreRef("Dios de pactos - en vivo", GENERAL_INSTRUMENT, "Dios de pactos - en vivo.pdf"),
                ScoreRef("Sublime gracia", GENERAL_INSTRUMENT, "Sublime gracia.pdf"),
            ),
            found,
        )
        assertEquals(listOf("Otra canción - Bajo.pdf"), orphans)
        assertEquals(listOf("Bajo", "Batería", "Teclado", GENERAL_INSTRUMENT), instrumentsOf(found))
    }

    @Test
    fun eachMusicianGetsTheirPartOrTheGeneralOne() {
        val (found, _) = matchScores(listOf("Sublime gracia.pdf", "Sublime gracia - Bajo.pdf", "Cuán grande es Él - Bajo.pdf"), songs)
        assertEquals("Sublime gracia - Bajo.pdf", scoreFor(found, "Sublime gracia", "bajo")?.file)
        assertEquals("Sublime gracia.pdf", scoreFor(found, "Sublime gracia", "Guitarra")?.file)
        assertEquals("Sublime gracia.pdf", scoreFor(found, "Sublime gracia", null)?.file)
        assertNull(scoreFor(found, "Cuan grande es El", "Guitarra")) // solo hay de bajo
        assertNull(scoreFor(found, "nada", "Bajo"))
    }

    @Test
    fun transposeChords() {
        assertEquals("A", Transpose.chord("G", 2))
        assertEquals("G#m7/D#", Transpose.chord("F#m7/C#", 2))
        assertEquals("Abm7/Eb", Transpose.chord("F#m7/C#", 2, flats = true))
        assertEquals("Bb", Transpose.chord("C", -2, flats = true))
        assertEquals("C", Transpose.chord("Bb", 2)) // de bemol a natural
        assertEquals("Ebmaj7", Transpose.chord("Dmaj7", 1, flats = true))
        assertEquals("Gsus4", Transpose.chord("Gsus4", 12))
        assertEquals("[A]Cuán grande [D/F#]Él", Transpose.line("[G]Cuán grande [C/E]Él", 2, flats = false))
    }

    @Test
    fun keyAndFlatsFollowTheTargetKey() {
        val slides = listOf(Slide(text = "x"), Slide(text = "y", chords = "[Em7]Señor [C]mi Dios"))
        assertEquals("Em", Transpose.keyOf(slides))
        assertNull(Transpose.keyOf(listOf(Slide(text = "sin acordes"))))
        assertEquals(false, Transpose.flatsFor("G", 2)) // La: sostenidos
        assertEquals(true, Transpose.flatsFor("G", 3)) // Si bemol
        assertEquals(false, Transpose.flatsFor("Em", 2)) // Fa# menor (relativo de La): sostenidos
        assertEquals(true, Transpose.flatsFor("Dm", 0)) // Re menor: relativo de Fa, con bemoles
        assertEquals("Bb", Transpose.key("G", 3))
        assertEquals("F#m", Transpose.key("Em", 2))
    }

    @Test
    fun chordsGoAboveTheirSyllable() {
        assertEquals("G        C" to "Señor mi Dios", Transpose.layout("[G]Señor mi [C]Dios"))
        // Dos acordes pegados: se separa la letra para que no se pisen
        val (c, t) = Transpose.layout("[Am7]a[D]lle")
        assertEquals("Am7 D", c)
        assertEquals("a   lle", t)
        assertEquals("G C D" to "", Transpose.layout("[G]  [C]  [D]"))
        assertEquals("" to "solo letra", Transpose.layout("solo letra"))
    }
}
