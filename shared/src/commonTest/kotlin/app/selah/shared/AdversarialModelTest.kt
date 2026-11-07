package app.selah.shared

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Propiedades de las secciones, los saltos, los tiempos y mergeState con datos al azar. */
class AdversarialModelTest {
    private val labels = listOf("", "", "", "Coro", "Verso 1", "Inicio", "Juan 3:16", "Puente", "coro")

    private fun randomState(r: Random): PresentationState {
        val slides = List(r.nextInt(0, 12)) { Slide(labels.random(r), "t$it") }
        val index = if (slides.isEmpty()) -1 else r.nextInt(slides.size)
        return PresentationState("x", slides, index, isBible = r.nextInt(5) == 0, sermon = r.nextInt(4) == 0)
    }

    @Test
    fun sectionsAndJumpsAlwaysValid() {
        val r = Random(42)
        repeat(50_000) {
            val s = randomState(r)
            val sec = s.sections
            assertTrue(sec.zipWithNext().all { (a, b) -> a.start < b.start }, "secciones desordenadas $s")
            assertTrue(sec.all { it.start in s.slides.indices })
            assertTrue(s.currentSection in -1..sec.lastIndex)
            for (l in s.sectionLabels) {
                val t = s.jumpTarget(l)!!
                assertTrue(t in s.slides.indices)
                assertEquals(l, sec.last { it.start <= t }.label, "el salto a $l cae en otra sección: $s")
            }
            assertEquals(null, s.jumpTarget("no existe"))
            // Tocar el mismo botón varias veces recorre todas sus apariciones
            for (l in s.sectionLabels) {
                val starts = sec.filter { it.label == l }.map { it.start }.toSet()
                var st = s
                val seen = mutableSetOf<Int>()
                repeat(starts.size) { st = st.copy(index = st.jumpTarget(l)!!); seen += st.index }
                assertEquals(starts, seen, "no recorre todas las apariciones de $l: $s")
            }
        }
    }

    @Test
    fun timesNeverNegativeOrCrash() {
        val r = Random(1)
        repeat(20_000) {
            val now = r.nextLong(-1_000_000_000_000, 4_000_000_000_000)
            val s = PresentationState(
                countdownEnd = r.nextLong(-1_000_000_000_000, 4_000_000_000_000),
                timerStart = r.nextLong(-1_000_000_000_000, 4_000_000_000_000),
                timerMinutes = r.nextInt(0, 601),
            )
            assertTrue(s.countdownSeconds(now)!! >= 0)
            assertTrue(s.timerElapsed(now)!! >= 0)
            if (s.timerMinutes > 0) assertTrue(s.timerLeft(now)!! <= s.timerMinutes * 60L)
        }
    }

    @Test
    fun mergeStateRoundTrip() {
        val r = Random(3)
        repeat(5_000) {
            val prev = randomState(r)
            val next = prev.copy(index = if (prev.slides.isEmpty()) -1 else r.nextInt(prev.slides.size), blank = r.nextBoolean())
            val lean = ServerMessage.State(next.copy(slides = emptyList()), 0, keepSlides = true)
            val decoded = decodeServerMessage(lean.encode()) as ServerMessage.State
            assertEquals(next, mergeState(prev, decoded))
            assertEquals(next, mergeState(PresentationState(), ServerMessage.State(next)))
        }
    }

    @Test
    fun forOldRemotesNeverLeaksPresentation() {
        val items = PlanKind.entries.map { PlanItem(it, "r", "t") }
        val s = ServicePlanState(items, 2, "n").forOldRemotes()
        assertTrue(s.items.none { it.kind == PlanKind.PRESENTATION })
        assertEquals(2, s.current)
        assertEquals("n", s.name)
        assertTrue("PRESENTATION" !in ProtocolJson.encodeToString(ServicePlanState.serializer(), s))
    }
}
