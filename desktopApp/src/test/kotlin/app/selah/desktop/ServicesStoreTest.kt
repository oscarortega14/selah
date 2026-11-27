package app.selah.desktop

import app.selah.shared.ClientMessage
import app.selah.shared.ServiceStep
import app.selah.shared.SongSummary
import app.selah.shared.StepKind
import app.selah.shared.WorshipService
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Cultos: rotación de listas, el culto de hoy, el tipo de las canciones y las repetidas. */
class ServicesStoreTest {
    private lateinit var dir: File
    private lateinit var library: Library
    private lateinit var commands: Commands

    @BeforeTest
    fun setUp() {
        dir = createTempDirectory("selah-cultos").toFile()
        File(dir, "canciones").mkdirs()
        File(dir, "canciones/A.txt").writeText("# A\n\nuno\n")
        File(dir, "canciones/B.txt").writeText("# B\nAutor: Ana\n\nprimera estrofa\n\nTipo: esto es letra\n")
        library = Library(dir).also { it.reload() }
        commands = Commands.create(library)
    }

    @AfterTest
    fun tearDown() { dir.deleteRecursively() }

    private fun saveList(name: String) {
        commands.handle(ClientMessage.PlanClear)
        commands.handle(ClientMessage.PlanAddSong("A"))
        assertNull(commands.handle(ClientMessage.PlanSave(name)))
    }

    @Test
    fun openingAListAdvancesTheRotationAndItIsSaved() {
        for (n in listOf("Dominical A", "Dominical B", "Dominical C")) saveList(n)
        val services = commands.services!!
        services.update { it.copy(services = listOf(WorshipService("Dominical", 7, "9:00", rotation = listOf("Dominical A", "Dominical B", "Dominical C")))) }
        val saved = commands.plan.saved.value
        assertEquals("Dominical A", services.config.value.services[0].nextList(saved))
        commands.handle(ClientMessage.PlanOpen("Dominical A"))
        assertEquals("Dominical B", services.config.value.services[0].nextList(saved))
        commands.handle(ClientMessage.PlanOpen("dominical b")) // sin importar mayúsculas
        assertEquals("Dominical C", services.config.value.services[0].nextList(saved))
        // Queda guardado en cultos.json (se lee igual al abrir Selah otra vez)
        val reopened = ServicesStore(File(dir, "cultos.json"))
        assertEquals("Dominical B", reopened.config.value.services[0].lastUsed)
        assertTrue("cultos.json" in Backups.INCLUDED) // entra en los respaldos
    }

    @Test
    fun planningTheWeekSavesEachServiceWithoutTouchingTheCurrentList() {
        // La lista de ahora (lo que está sonando) no se toca al planear
        saveList("Ahora")
        val before = commands.plan.state.value
        val domingo = WorshipService(
            "Domingo", 7, "9:00",
            steps = listOf(ServiceStep(StepKind.SONG, "Alabanza"), ServiceStep(StepKind.SONG, "Adoración"), ServiceStep(StepKind.BIBLE)),
        )
        val miercoles = WorshipService("Miércoles", 3, "7:00 p.m.", steps = listOf(ServiceStep(StepKind.SONG)))
        val services = commands.services!!
        services.update { it.copy(services = listOf(domingo, miercoles)) }

        // Jueves 26 nov 2026: la semana trae domingo 29 y miércoles 2 dic, en orden
        val thursday = LocalDate.of(2026, 11, 26)
        val week = upcomingServices(services.config.value, thursday)
        assertEquals(listOf(0 to LocalDate.of(2026, 11, 29), 1 to LocalDate.of(2026, 12, 2)), week)
        assertEquals("Domingo 29 nov", plannedListName(domingo, LocalDate.of(2026, 11, 29)))

        // Guardar con nombre, sin abrirla
        assertNull(commands.plan.saveItems("Domingo 29 nov", listOf(ClientMessage.PlanAddSong("B"), ClientMessage.PlanAddSong("A"))))
        assertEquals(before, commands.plan.state.value)
        assertEquals(listOf("B", "A"), commands.plan.savedItems("domingo 29 nov")!!.map { it.ref })
        // Si algo no sirve, no se guarda nada a medias
        assertEquals("Canción no encontrada", commands.plan.saveItems("Mala", listOf(ClientMessage.PlanAddSong("A"), ClientMessage.PlanAddSong("no-existe"))))
        assertNull(commands.plan.savedItems("Mala"))
        assertEquals("La lista está vacía", commands.plan.saveItems("Vacía", emptyList()))

        // Ese domingo le toca la planeada (aunque haya rotación); otro domingo, la rotación
        services.update { c -> c.copy(services = c.services.mapIndexed { i, s -> if (i == 0) s.copy(planned = mapOf("2026-11-29" to "Domingo 29 nov"), rotation = listOf("Ahora")) else s }) }
        val saved = commands.plan.saved.value
        assertEquals("Domingo · domingo 9:00" to "Domingo 29 nov", todaySuggestion(services.config.value, saved, LocalDateTime.of(2026, 11, 29, 8, 0)))
        assertEquals("Domingo · domingo 9:00" to "Ahora", todaySuggestion(services.config.value, saved, LocalDateTime.of(2026, 12, 6, 8, 0)))
        // Se recuerda en cultos.json
        assertEquals("Domingo 29 nov", ServicesStore(File(dir, "cultos.json")).config.value.services[0].planned["2026-11-29"])

        // La semana para la pestaña Lista: el domingo con su lista planeada y sus canciones; el miércoles sin planear
        val weekList = weekPlan(services.config.value, thursday, commands.plan.saved.value) { commands.plan.savedItems(it) }
        assertEquals(listOf("Domingo", "Miércoles"), weekList.map { it.name })
        assertEquals("domingo 29 nov", weekList[0].day)
        assertTrue(weekList[0].planned)
        assertEquals(listOf("B", "A"), weekList[0].items.map { it.ref })
        assertNull(weekList[1].list)
        assertTrue(weekList.none { it.today })
        assertTrue(weekPlan(services.config.value, LocalDate.of(2026, 11, 29), commands.plan.saved.value) { null }[0].today)

        // Al volver a abrir el planeado, cada paso recibe lo suyo (canción con canción, lectura con lectura)
        val items = commands.plan.savedItems("Domingo 29 nov")!!
        assertEquals(listOf("B", "A", null), matchStepsToItems(domingo.steps, items).map { it?.ref })
    }

    @Test
    fun onlyTheNewestPlannedDatesAreKept() {
        val store = ServicesStore(File(dir, "c3.json"))
        val many = (1..50).associate { "2026-%02d-%02d".format(1 + it / 28, 1 + it % 28) to "L$it" }
        store.update { it.copy(services = listOf(WorshipService("Domingo", 7, planned = many))) }
        val kept = store.config.value.services[0].planned
        assertEquals(ServicesStore.MAX_PLANNED, kept.size)
        assertEquals(many.keys.max(), kept.keys.max())
    }

    @Test
    fun todaysSuggestionForPhones() {
        val cfg = app.selah.shared.ServicesConfig(listOf(WorshipService("Dominical", 7, "9:00", rotation = listOf("X", "Y"), lastUsed = "X")))
        val sunday = LocalDateTime.of(2026, 11, 8, 8, 0) // domingo
        assertEquals("Dominical · domingo 9:00" to "Y", todaySuggestion(cfg, listOf("X", "Y"), sunday))
        assertEquals("" to null, todaySuggestion(cfg, listOf("X", "Y"), sunday.plusDays(1)))
        // Valores raros desde la ventana: se corrigen al guardar
        val store = ServicesStore(File(dir, "c2.json"))
        store.update { it.copy(repeatDays = -5, services = listOf(WorshipService("  Oración  ", day = 9, steps = listOf(ServiceStep(StepKind.BIBLE))))) }
        assertEquals(0, store.config.value.repeatDays)
        assertEquals("Oración", store.config.value.services[0].name)
        assertEquals(7, store.config.value.services[0].day)
    }

    @Test
    fun songTypeOnlyTouchesTheHeader() {
        library.setSongTags("B", listOf("Adoración", "adoración", "Navidad"))
        val text = File(dir, "canciones/B.txt").readText()
        assertTrue(text.startsWith("# B\nTipo: Adoración, Navidad\nAutor: Ana"))
        assertTrue("Tipo: esto es letra" in text) // la línea de la letra sigue ahí
        assertEquals(listOf("Adoración", "Navidad"), library.catalog.value.songs.first { it.id == "B" }.tags)
        library.setSongTags("B", emptyList())
        assertEquals(emptyList(), library.catalog.value.songs.first { it.id == "B" }.tags)
        assertTrue("Tipo: esto es letra" in File(dir, "canciones/B.txt").readText())
    }

    @Test
    fun lastSungTravelsWithTheSongs() {
        val songs = listOf(SongSummary("A", "A"), SongSummary("B", "B"))
        val out = withLastSung(songs, mapOf("A" to LocalDate.of(2026, 11, 2)))
        assertEquals("2026-11-02", out[0].lastSung)
        assertNull(out[1].lastSung)
    }
}
