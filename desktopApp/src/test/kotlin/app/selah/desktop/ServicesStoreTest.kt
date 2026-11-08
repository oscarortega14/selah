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
