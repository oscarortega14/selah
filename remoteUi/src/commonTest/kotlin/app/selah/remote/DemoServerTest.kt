package app.selah.remote

import app.selah.shared.ClientMessage
import app.selah.shared.PlanKind
import app.selah.shared.ServerMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DemoServerTest {
    private val demo = DemoServer(clock = { 1_000L })

    private inline fun <reified T : ServerMessage> List<ServerMessage>.one(): T = filterIsInstance<T>().single()
    private fun state(msgs: List<ServerMessage>) = msgs.one<ServerMessage.State>().state

    @Test
    fun welcomeHasSongsBibleAndASongOnScreen() {
        val msgs = demo.welcome()
        val lib = msgs.one<ServerMessage.Library>()
        assertEquals(6, lib.songs.size)
        assertEquals("Reina-Valera 1909", lib.bibleName)
        assertTrue(lib.bibleBooks.any { it.name == "Juan" && it.chapters == 21 })
        assertTrue(msgs.one<ServerMessage.Auth>().ok)
        assertEquals("Tu luz en mí", state(msgs).title)
        assertTrue(msgs.one<ServerMessage.Plan>().plan.items.isNotEmpty())
    }

    @Test
    fun nextPrevAndBlankWorkLikeThePc() {
        val start = state(demo.welcome())
        assertEquals(0, start.index)
        assertEquals(1, state(demo.handle(ClientMessage.Next)).index)
        assertEquals(0, state(demo.handle(ClientMessage.Prev)).index)
        assertTrue(state(demo.handle(ClientMessage.SetBlank(true))).blank)
        // Otra canción conserva la pantalla en negro (igual que en el PC).
        val other = demo.welcome().one<ServerMessage.Library>().songs[2]
        val s = state(demo.handle(ClientMessage.SelectSong(other.id)))
        assertEquals(other.title, s.title)
        assertTrue(s.blank)
    }

    @Test
    fun bibleReferencesInTheDemo() {
        val s = state(demo.handle(ClientMessage.ShowBible("Juan 3:16")))
        assertTrue(s.isBible)
        assertEquals("Juan 3:16", s.title)
        assertTrue(s.slides.single().text.startsWith("Porque de tal manera amó Dios al mundo"))
        assertEquals(6, state(demo.handle(ClientMessage.ShowBible("sal 23"))).slides.size)
    }

    @Test
    fun chapterOutsideTheDemoExplainsWhy() {
        val msgs = demo.handle(ClientMessage.ShowBible("Génesis 1"))
        assertTrue(msgs.none { it is ServerMessage.State })
        assertTrue(msgs.one<ServerMessage.Error>().message.startsWith("En la demostración solo están"))
        assertEquals(DemoServer.DEMO_BIBLE_ONLY, demo.handle(ClientMessage.ShowBible("Salmos 1")).one<ServerMessage.Error>().message)
        assertEquals(emptyList(), demo.handle(ClientMessage.BibleChapter("Salmos", 1)).one<ServerMessage.BibleChapter>().verses)
        assertEquals(11, demo.handle(ClientMessage.BibleChapter("Salmos", 46)).one<ServerMessage.BibleChapter>().verses.size)
    }

    @Test
    fun searchSongsAndBible() {
        val songs = demo.handle(ClientMessage.SongSearch("tormenta")).one<ServerMessage.SongResults>()
        assertEquals("Tu luz en mí", songs.hits.single().title)
        val bible = demo.handle(ClientMessage.BibleSearch("encarece su caridad")).one<ServerMessage.BibleResults>()
        assertTrue(bible.hits.any { it.reference == "Romanos 5:8" })
    }

    @Test
    fun serviceListAddGoSaveAndOpen() {
        demo.handle(ClientMessage.PlanClear)
        demo.handle(ClientMessage.PlanAddBible("Juan 3:16"))
        val plan = demo.handle(ClientMessage.PlanAddMedia("Bienvenidos.jpg")).one<ServerMessage.Plan>().plan
        assertEquals(listOf(PlanKind.BIBLE, PlanKind.MEDIA), plan.items.map { it.kind })
        val go = demo.handle(ClientMessage.PlanNext)
        assertEquals(0, go.one<ServerMessage.Plan>().plan.current)
        assertEquals("Juan 3:16", state(go).title)
        assertEquals("Bienvenidos.jpg", state(demo.handle(ClientMessage.PlanNext)).media)
        assertEquals("No hay más elementos en la lista", demo.handle(ClientMessage.PlanNext).one<ServerMessage.Error>().message)
        assertEquals(listOf("Domingo"), demo.handle(ClientMessage.PlanSave("Domingo")).one<ServerMessage.Plan>().saved)
        demo.handle(ClientMessage.PlanClear)
        assertEquals(2, demo.handle(ClientMessage.PlanOpen("Domingo")).one<ServerMessage.Plan>().plan.items.size)
    }

    @Test
    fun presentationAndCountdown() {
        val p = state(demo.handle(ClientMessage.ShowPresentation("Informe anual.pdf")))
        assertEquals("Diapositiva 1 de ${DemoServer.PRESENTATION_PAGES}", p.slides.first().text)
        assertEquals(61_000L, state(demo.handle(ClientMessage.StartCountdown(60, "Comenzamos en"))).countdownEnd)
    }

    @Test
    fun carouselInTheDemo() {
        var now = 0L
        val d = DemoServer(clock = { now })
        assertTrue(app.selah.shared.FEATURE_CAROUSEL in d.welcome().filterIsInstance<ServerMessage.Library>().single().features)
        val first = d.handle(ClientMessage.StartCarousel(5)).filterIsInstance<ServerMessage.State>().single().state
        assertEquals(5, first.carouselSeconds)
        assertEquals("Bienvenidos.jpg", first.media)
        assertEquals(emptyList(), d.tick())
        now += 5_000
        assertEquals("Retiro de jóvenes.jpg", d.tick().filterIsInstance<ServerMessage.State>().single().state.media)
        assertEquals("Bienvenidos.jpg", d.handle(ClientMessage.Next).filterIsInstance<ServerMessage.State>().single().state.media)
        val stopped = d.handle(ClientMessage.SelectSong("demo-1")).filterIsInstance<ServerMessage.State>().single().state
        assertEquals(0, stopped.carouselSeconds)
        now += 60_000
        assertEquals(emptyList(), d.tick())
    }
}
