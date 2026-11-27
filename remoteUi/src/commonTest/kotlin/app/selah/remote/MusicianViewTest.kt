package app.selah.remote

import app.selah.shared.FEATURE_CHORDS
import app.selah.shared.FEATURE_CLICK
import app.selah.shared.FEATURE_SCORES
import app.selah.shared.FEATURE_SEQUENCES
import app.selah.shared.PlanItem
import app.selah.shared.PlanKind
import app.selah.shared.PresentationState
import app.selah.shared.ScoreRef
import app.selah.shared.ServicePlanState
import app.selah.shared.Slide
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
    fun theMusicianLooksAtAnotherSongWithoutBeingPulledBack() {
        val live = PresentationState("Juan 3:16", listOf(Slide("16", "Porque de tal manera…")), 0, isBible = true)
        // Siguiendo al operador: lo que está en pantalla (Biblia incluida)
        assertEquals(live, musicianShown(live, browsing = null, viewed = null))
        // Eligió otra canción y todavía no llega: se espera
        assertEquals(null, musicianShown(live, browsing = "b", viewed = null))
        assertEquals(null, musicianShown(live, browsing = "b", viewed = ServerMessage.SongView("a", "A", listOf(Slide(text = "x")))))
        // Llegó: la ve aunque el operador esté en la Biblia, sin diapositiva resaltada
        val b = musicianShown(live, "b", ServerMessage.SongView("b", "B", listOf(Slide("Coro", "la la", chords = "[G]la la"))))!!
        assertEquals("B", b.title)
        assertEquals("b", b.songId)
        assertEquals(-1, b.index)
        assertFalse(b.isBible)
        assertEquals("[G]la la", b.slides.single().chords)
    }

    @Test
    fun theWeekServicesOfferTheirSongs() {
        val songs = listOf(SongSummary("a", "Águila"), SongSummary("b", "Bendito"))
        val week = listOf(
            app.selah.shared.WeekService("Domingo", "2026-11-29", "domingo 29 nov", items = listOf(
                PlanItem(PlanKind.SONG, "b", "Bendito"), PlanItem(PlanKind.SERMON, "p", "Predicación: X"), PlanItem(PlanKind.SONG, "zz", "Nueva"),
            )),
            app.selah.shared.WeekService("Miércoles", "2026-12-02", "miércoles 2 dic"),
        )
        val all = weekChoices(week, songs, "")
        assertEquals(1, all.size) // el miércoles sin canciones no sale
        assertEquals(listOf("b", "zz"), all[0].second.map { it.id }) // sin la predicación; la que no está en el catálogo, con su título
        assertEquals(listOf("zz"), weekChoices(week, songs, "nueva")[0].second.map { it.id })
    }

    @Test
    fun theServiceListComesFirstWhenChoosingASong() {
        val songs = listOf(SongSummary("a", "Águila"), SongSummary("b", "Bendito"), SongSummary("c", "Cordero"), SongSummary("d", "Digno"))
        val plan = ServicePlanState(listOf(
            PlanItem(PlanKind.SONG, "c", "Cordero"), PlanItem(PlanKind.BIBLE, "Juan 3:16", "Juan 3:16"),
            PlanItem(PlanKind.SONG, "a", "Águila"), PlanItem(PlanKind.SONG, "zz", "Borrada"),
        ))
        val (inPlan, others) = musicianChoices(plan, songs, "")
        assertEquals(listOf("c", "a"), inPlan.map { it.id }) // en el orden del culto, sin la Biblia ni la que ya no existe
        assertEquals(listOf("b", "d"), others.map { it.id })
        // Buscar sin tildes filtra las dos partes
        val (p2, o2) = musicianChoices(plan, songs, "aguila")
        assertEquals(listOf("a"), p2.map { it.id })
        assertTrue(o2.isEmpty())
    }

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

    @Test
    fun scorePagesFollowTheLyricsAndRememberCorrections() {
        // 12 diapositivas y 3 páginas: sin correcciones, en proporción
        assertEquals(listOf(0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2), (0 until 12).map { pageFor(it, 12, 3, emptyMap()) })
        // El músico marcó que la página 2 (índice 1) empieza en la diapositiva 2 y la 3 en la 9
        val marks = parsePageMarks("2:1,9:2")
        assertEquals(mapOf(2 to 1, 9 to 2), marks)
        assertEquals(listOf(0, 0, 1, 1, 1, 1, 1, 1, 1, 2, 2, 2), (0 until 12).map { pageFor(it, 12, 3, marks) })
        assertEquals(0, pageFor(5, 12, 1, marks)) // una sola página
        assertEquals(emptyMap(), parsePageMarks("basura,1:x,"))
    }
}
