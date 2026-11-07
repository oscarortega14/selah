package app.selah.remote

import app.selah.shared.ClientMessage
import app.selah.shared.PresentationState
import app.selah.shared.ServerMessage
import app.selah.shared.ServicePlanState
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** El servidor de demostración bajo órdenes al azar y comparado con lo que hace el PC. */
class AdversarialDemoServerTest {
    private val songIds = (1..6).map { "demo-$it" } + listOf("nope", "")
    private val refs = listOf("Juan 3:16", "Salmos 23", "Juan 99", "Génesis 1", "xyz", "", "Juan 3:16; 3:17", "1 Corintios 13:4-7")
    private val names = listOf("", "   ", "Domingo", "domingo", "../x", "x".repeat(300), "😀")

    private fun random(r: Random): ClientMessage {
        fun idx() = r.nextInt(-2, 9)
        return when (r.nextInt(26)) {
            0, 1 -> ClientMessage.SelectSong(songIds.random(r))
            2 -> ClientMessage.GoTo(idx())
            3, 4 -> ClientMessage.Next
            5 -> ClientMessage.Prev
            6 -> ClientMessage.SetBlank(r.nextBoolean())
            7 -> ClientMessage.ShowBible(refs.random(r))
            8 -> ClientMessage.ShowMedia(listOf("Bienvenidos.jpg", "nada.jpg").random(r))
            9 -> ClientMessage.ShowPresentation(listOf("Informe anual.pdf", "x.pdf").random(r))
            10 -> ClientMessage.ShowLogo(r.nextBoolean())
            11 -> ClientMessage.StartCountdown(r.nextInt(-5, 90_000), "t")
            12 -> ClientMessage.StartTimer(r.nextInt(-2, 700))
            13 -> ClientMessage.SetAlert("a")
            14 -> ClientMessage.PlanAddSong(songIds.random(r))
            15 -> ClientMessage.PlanAddBible(refs.random(r))
            16 -> ClientMessage.PlanAddPresentation("Informe anual.pdf")
            17 -> ClientMessage.PlanRemove(idx())
            18, 19 -> ClientMessage.PlanMove(idx(), idx())
            20, 21 -> ClientMessage.PlanGo(idx())
            22 -> ClientMessage.PlanNext
            23 -> ClientMessage.PlanSave(names.random(r))
            24 -> ClientMessage.PlanOpen(names.random(r))
            else -> ClientMessage.PlanClear
        }
    }

    @Test
    fun randomSequencesKeepInvariants() {
        for (seed in 0 until 200) {
            val demo = DemoServer(clock = { 1_000L })
            val welcome = demo.welcome()
            var screen: PresentationState = welcome.filterIsInstance<ServerMessage.State>().single().state
            var plan: ServicePlanState = welcome.filterIsInstance<ServerMessage.Plan>().single().plan
            val r = Random(seed)
            val log = mutableListOf<ClientMessage>()
            repeat(300) {
                val msg = random(r); log += msg
                val out = try { demo.handle(msg) } catch (e: Throwable) { fail("excepción ${e::class.simpleName} seed=$seed ${log.takeLast(8)}", e) }
                out.filterIsInstance<ServerMessage.State>().lastOrNull()?.let { screen = it.state }
                out.filterIsInstance<ServerMessage.Plan>().lastOrNull()?.let { plan = it.plan }
                val ctx = "seed=$seed ${log.takeLast(8)}"
                if (screen.slides.isEmpty()) assertEquals(-1, screen.index, ctx) else assertTrue(screen.index in screen.slides.indices, ctx)
                assertTrue(plan.current in -1..plan.items.lastIndex, ctx)
                for (l in screen.sectionLabels) assertTrue(screen.jumpTarget(l)!! in screen.slides.indices, ctx)
            }
        }
    }

    private fun planOf(out: List<ServerMessage>) = out.filterIsInstance<ServerMessage.Plan>().single()

    /** DIVERGENCIA: el PC cambia / : * ? " < > | por "-" y rechaza " .. "; la demostración guarda el nombre tal cual. */
    @Test
    fun listNamesAreCleanedLikeOnThePc() {
        val demo = DemoServer(clock = { 1_000L })
        assertEquals("Culto 8-11- noche-", planOf(demo.handle(ClientMessage.PlanSave("Culto 8/11: noche?"))).plan.name)
        assertTrue(demo.handle(ClientMessage.PlanSave(" .. ")).any { it is ServerMessage.Error }, "el PC rechaza \" .. \"")
    }

    /** En el PC, quitar o mover borra el nombre de la lista (ver AdversarialStateTest); aquí se conserva. */
    @Test
    fun removeKeepsNameInDemo() {
        val demo = DemoServer(clock = { 1_000L })
        demo.handle(ClientMessage.PlanSave("Domingo"))
        assertEquals("Domingo", planOf(demo.handle(ClientMessage.PlanRemove(0))).plan.name)
        assertEquals("Domingo", planOf(demo.handle(ClientMessage.PlanMove(0, 1))).plan.name)
    }
}
