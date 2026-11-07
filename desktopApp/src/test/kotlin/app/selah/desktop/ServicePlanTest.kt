package app.selah.desktop

import app.selah.shared.cleanListName
import app.selah.shared.ClientMessage
import app.selah.shared.PlanKind
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ServicePlanTest {
    private lateinit var dir: File
    private lateinit var library: Library
    private lateinit var commands: Commands
    private val plan get() = commands.plan.state.value

    @BeforeTest
    fun setUp() {
        dir = createTempDirectory("selah-plan").toFile()
        File(dir, "canciones").mkdirs()
        for (t in listOf("A", "B", "C")) File(dir, "canciones/$t.txt").writeText("# $t\n\nletra $t\n")
        File(dir, "biblia").mkdirs()
        File(dir, "biblia/b.json").writeText("""{"name":"T","books":[{"name":"Juan","chapters":[["uno","dos"]]}]}""")
        library = Library(dir).also { it.reload() }
        commands = Commands.create(library)
    }

    @AfterTest
    fun tearDown() { dir.deleteRecursively() }

    private fun add(vararg ids: String) = ids.forEach { commands.handle(ClientMessage.PlanAddSong(it)) }

    @Test
    fun addsSongsAndReadings() {
        add("A")
        assertEquals(null, commands.handle(ClientMessage.PlanAddBible("jn 1:2")))
        assertNotNull(commands.handle(ClientMessage.PlanAddBible("jn 9")))
        assertNotNull(commands.handle(ClientMessage.PlanAddSong("nope")))
        assertEquals(listOf(PlanKind.SONG to "A", PlanKind.BIBLE to "Juan 1:2"), plan.items.map { it.kind to it.title })
    }

    @Test
    fun goAndNextPresentItems() {
        add("A", "B")
        commands.handle(ClientMessage.PlanGo(0))
        assertEquals("A", commands.presenter.state.value.title)
        commands.handle(ClientMessage.PlanNext)
        assertEquals("B", commands.presenter.state.value.title)
        assertEquals(1, plan.current)
        assertNotNull(commands.handle(ClientMessage.PlanNext)) // no hay más
    }

    @Test
    fun currentFollowsItsItemWhenMovingOrRemoving() {
        add("A", "B", "C")
        commands.handle(ClientMessage.PlanGo(1)) // B
        commands.handle(ClientMessage.PlanMove(1, 2))
        assertEquals(listOf("A", "C", "B"), plan.items.map { it.title }); assertEquals(2, plan.current)
        commands.handle(ClientMessage.PlanMove(0, 2))
        assertEquals(listOf("C", "B", "A"), plan.items.map { it.title }); assertEquals(1, plan.current)
        commands.handle(ClientMessage.PlanRemove(0))
        assertEquals(0, plan.current)
        commands.handle(ClientMessage.PlanRemove(0))
        assertEquals(-1, plan.current)
        commands.handle(ClientMessage.PlanMove(0, 5)) // fuera de rango: no hace nada
        assertEquals(listOf("A"), plan.items.map { it.title })
    }

    @Test
    fun survivesARestart() {
        add("A", "B")
        commands.handle(ClientMessage.PlanGo(1))
        val again = Commands.create(Library(dir).also { it.reload() })
        assertEquals(listOf("A", "B"), again.plan.state.value.items.map { it.title })
        assertEquals(1, again.plan.state.value.current)
        commands.handle(ClientMessage.PlanClear)
        assertEquals(0, Commands.create(library).plan.state.value.items.size)
    }

    @Test
    fun savesAndOpensNamedLists() {
        add("A", "B")
        commands.handle(ClientMessage.PlanGo(1))
        assertEquals(null, commands.handle(ClientMessage.PlanSave("Culto domingo 8 nov 2026")))
        assertEquals("Culto domingo 8 nov 2026", plan.name)
        assertEquals(listOf("Culto domingo 8 nov 2026"), commands.plan.saved.value)

        // Otra lista para el miércoles
        commands.handle(ClientMessage.PlanClear)
        add("C")
        commands.handle(ClientMessage.PlanSave("Miércoles"))
        assertEquals(listOf("C"), plan.items.map { it.ref })

        // Abrir la del domingo: reemplaza la actual y empieza desde el principio
        assertEquals(null, commands.handle(ClientMessage.PlanOpen("Culto domingo 8 nov 2026")))
        assertEquals(listOf("A", "B"), plan.items.map { it.ref })
        assertEquals(-1, plan.current)
        assertEquals("Culto domingo 8 nov 2026", plan.name)
        assertEquals(setOf("Culto domingo 8 nov 2026", "Miércoles"), commands.plan.saved.value.toSet())

        // Sigue ahí al reiniciar el programa
        val again = Commands.create(library)
        assertEquals(setOf("Culto domingo 8 nov 2026", "Miércoles"), again.plan.saved.value.toSet())
        assertEquals("Culto domingo 8 nov 2026", again.plan.state.value.name)

        commands.plan.deleteSaved("Miércoles")
        assertEquals(listOf("Culto domingo 8 nov 2026"), commands.plan.saved.value)
        assertNotNull(commands.handle(ClientMessage.PlanOpen("Miércoles")))
    }

    @Test
    fun rejectsEmptyListsAndBadNames() {
        assertNotNull(commands.handle(ClientMessage.PlanSave("Vacía"))) // no hay nada que guardar
        add("A")
        assertNotNull(commands.handle(ClientMessage.PlanSave("   ")))
        assertEquals(null, commands.handle(ClientMessage.PlanSave("Culto 8/11: noche?")))
        assertEquals(listOf("Culto 8-11- noche-"), commands.plan.saved.value) // sin caracteres prohibidos en Windows
        assertEquals(null, cleanListName(" .. "))
    }

    @Test
    fun suggestsNextSunday() {
        assertEquals("Culto domingo 8 nov 2026", suggestedListName(java.time.LocalDate.of(2026, 11, 4))) // miércoles
        assertEquals("Culto domingo 8 nov 2026", suggestedListName(java.time.LocalDate.of(2026, 11, 8))) // domingo: hoy
        assertEquals("Culto domingo 3 ene 2027", suggestedListName(java.time.LocalDate.of(2026, 12, 28)))
    }
}
