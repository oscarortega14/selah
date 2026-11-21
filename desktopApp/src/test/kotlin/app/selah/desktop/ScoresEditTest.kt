package app.selah.desktop

import app.selah.shared.GENERAL_INSTRUMENT
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/** Partituras: cambiar de canción o instrumento y borrar, desde la pestaña Partituras. */
class ScoresEditTest {
    private lateinit var dir: File
    private lateinit var library: Library
    private lateinit var music: MusicLibrary

    @BeforeTest
    fun setUp() {
        dir = createTempDirectory("selah-partituras").toFile()
        File(dir, "canciones").mkdirs()
        File(dir, "canciones/Luz del camino.txt").writeText("# Luz del camino\n\nuno\n")
        File(dir, "canciones/Sendero.txt").writeText("# Sendero\n\ndos\n")
        library = Library(dir).also { it.reload() }
        music = MusicLibrary(dir)
        music.scoresDir.mkdirs()
        File(music.scoresDir, "Luz del camino.pdf").writeText("pdf")
        File(music.scoresDir, "Escaneo 004.pdf").writeText("pdf")
        File(music.scoresDir, "Sendero").mkdirs()
        File(music.scoresDir, "Sendero/Bajo.pdf").writeText("pdf")
        refresh()
    }

    @AfterTest
    fun tearDown() {
        // Selah prepara las imágenes de las partituras en segundo plano: si todavía escribe, se espera un poco y se reintenta.
        repeat(20) { if (runCatching { dir.deleteRecursively() }.getOrDefault(false)) return; Thread.sleep(100) }
    }

    private fun refresh() = music.refresh(library.catalog.value.songs)
    private val scores get() = music.catalog.value.scores

    @Test
    fun cambiarDeCancionEInstrumento() {
        assertEquals(listOf("Escaneo 004.pdf"), music.catalog.value.scoreOrphans)
        // El escaneo sin nombre pasa a ser el piano de "Sendero"
        assertNull(music.reassignScore("Escaneo 004.pdf", "Sendero", "Piano"))
        refresh()
        assertTrue(music.catalog.value.scoreOrphans.isEmpty())
        assertNotNull(scores.firstOrNull { it.songId == "Sendero" && it.instrument == "Piano" && it.file == "Sendero - Piano.pdf" })
        // La del bajo, de su carpeta a "para todos": la carpeta vacía se quita
        assertNull(music.reassignScore("Sendero/Bajo.pdf", "Sendero", ""))
        refresh()
        assertNotNull(scores.firstOrNull { it.songId == "Sendero" && it.instrument == GENERAL_INSTRUMENT })
        assertFalse(File(music.scoresDir, "Sendero").exists())
        // No pisa otra partitura
        assertTrue(music.reassignScore("Sendero - Piano.pdf", "Sendero", null)!!.startsWith("Ya hay una partitura"))
        assertTrue(File(music.scoresDir, "Sendero - Piano.pdf").isFile)
        // El instrumento no puede inventar rutas
        assertTrue(music.reassignScore("Sendero - Piano.pdf", "Sendero", "../x")!!.contains("«/»"))
        assertEquals("No encontré esa partitura", music.reassignScore("../afuera.pdf", "Sendero", null))
    }

    @Test
    fun agregarOtroInstrumento() {
        val pdf = File(dir, "escaneo bajo.pdf").apply { writeText("pdf") }
        assertNull(music.addScoreFor(pdf, "Luz del camino", "Bajo"))
        refresh()
        assertEquals(listOf("Bajo", GENERAL_INSTRUMENT), scores.filter { it.songId == "Luz del camino" }.map { it.instrument })
        assertTrue(pdf.isFile) // se copia, el original queda
        assertTrue(music.addScoreFor(pdf, "Luz del camino", "bajo")!!.startsWith("Ya hay"))
        assertEquals("Elige un archivo PDF", music.addScoreFor(File(dir, "nada.pdf"), "Luz del camino", "Piano"))
        assertTrue(music.addScoreFor(pdf, "Luz del camino", "../Piano")!!.contains("«/»"))
    }

    @Test
    fun borrar() {
        assertNull(music.deleteScore("Luz del camino.pdf"))
        refresh()
        assertTrue(scores.none { it.songId == "Luz del camino" })
        assertFalse(File(music.scoresDir, "Luz del camino.pdf").exists())
        assertEquals("No encontré esa partitura", music.deleteScore("Luz del camino.pdf"))
    }
}
