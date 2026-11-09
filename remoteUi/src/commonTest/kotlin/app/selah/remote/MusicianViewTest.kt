package app.selah.remote

import app.selah.shared.FEATURE_CHORDS
import app.selah.shared.FEATURE_CLICK
import app.selah.shared.FEATURE_SCORES
import app.selah.shared.FEATURE_SEQUENCES
import app.selah.shared.ScoreRef
import app.selah.shared.ServerMessage
import app.selah.shared.SongSummary
import app.selah.shared.decodeServerMessage
import app.selah.shared.encode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MusicianViewTest {
    @Test
    fun theMusicianSeesTheBestThereIs() {
        val all = listOf(FEATURE_SCORES, FEATURE_CHORDS)
        assertEquals(listOf(MusicView.SCORE, MusicView.CHORDS, MusicView.LYRICS), musicViews(all, hasScore = true, hasChords = true))
        assertEquals(listOf(MusicView.CHORDS, MusicView.LYRICS), musicViews(all, hasScore = false, hasChords = true))
        // Apagado en el PC: aunque la canción tenga acordes, no se ofrecen
        assertEquals(listOf(MusicView.LYRICS), musicViews(listOf(FEATURE_SCORES), hasScore = false, hasChords = true))
    }

    @Test
    fun onlyAskWhoUsesThePhoneWhenThePcHasSomethingForMusicians() {
        assertFalse(hasMusicianFeatures(emptyList()))
        assertFalse(hasMusicianFeatures(listOf("carousel", FEATURE_CLICK)))
        assertTrue(hasMusicianFeatures(listOf(FEATURE_SEQUENCES)))
    }

    @Test
    fun oldPcsWithoutScoresStillDecode() {
        // Un PC 2.6 no manda scores ni sequences: quedan vacías.
        val old = decodeServerMessage("""{"type":"library","songs":[{"id":"a","title":"A"}]}""") as ServerMessage.Library
        assertTrue(old.scores.isEmpty() && old.sequences.isEmpty())
        val new = ServerMessage.Library(listOf(SongSummary("a", "A")), scores = listOf(ScoreRef("a", "Bajo", "a - Bajo.pdf")), sequences = listOf("a"))
        assertEquals(new, decodeServerMessage(new.encode()))
    }
}
