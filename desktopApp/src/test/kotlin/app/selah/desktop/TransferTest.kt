package app.selah.desktop

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Llevar Selah de un computador a otro con un .zip. */
class TransferTest {
    private lateinit var root: File
    private lateinit var mac: File
    private lateinit var church: File

    @BeforeTest
    fun setUp() {
        root = createTempDirectory("selah-llevar").toFile()
        mac = File(root, "mac/Selah"); church = File(root, "iglesia/Selah")
        fun put(base: File, path: String, text: String) = File(base, path).apply { parentFile.mkdirs(); writeText(text) }
        put(mac, "canciones/Luz.txt", "# Luz\nTiempos: 0 8\n\nuno\n\ndos\n")
        put(mac, "canciones/Nueva.txt", "# Nueva\n\nhola\n")
        put(mac, "partituras/Luz - Bajo.pdf", "pdf")
        put(mac, "secuencias/Luz.mp3", "x".repeat(5000))
        put(mac, "secuencias/.analisis.json", "{}")
        put(mac, "tema.json", "{}")
        put(mac, "biblia/Mi Biblia.json", "{}")
        put(mac, "listas/Domingo.json", "[]")
        // Lo propio de la Mac: no viaja
        put(mac, "ajustes.json", "{\"pin\":\"1234\"}")
        put(mac, "equipo.json", "{}")
        put(mac, "modelos/whisper.bin", "m")
        put(mac, "respaldos/r.zip", "r")
        // En la iglesia ya había una versión vieja de Luz, una canción propia y sus ajustes
        put(church, "canciones/Luz.txt", "# Luz\n\nuno\n")
        put(church, "canciones/Solo de la iglesia.txt", "# Solo\n\nx\n")
        put(church, "ajustes.json", "{\"pin\":\"9999\"}")
    }

    @AfterTest
    fun tearDown() { root.deleteRecursively() }

    @Test
    fun llevaLoElegidoYNadaPropioDelComputador() {
        val zip = File(root, "llevar.zip")
        val n = Transfer(mac).export(zip, setOf("canciones", "cultos", "partituras", "diseno", "biblias"))
        assertEquals(6, n)
        val names = ZipFile(zip).use { z -> z.entries().asSequence().map { it.name }.toSet() }
        assertTrue("canciones/Luz.txt" in names && "partituras/Luz - Bajo.pdf" in names && "biblia/Mi Biblia.json" in names)
        // No se eligieron las secuencias; lo de cada computador nunca va
        assertFalse(names.any { it.startsWith("secuencias/") || it == "ajustes.json" || it == "equipo.json" || it.startsWith("modelos/") || it.startsWith("respaldos/") })

        val t = Transfer(church)
        val what = assertNotNull(t.inspect(zip))
        assertEquals(2, what["canciones"]!!.files)
        assertNull(what["secuencias"])
        assertEquals(6, t.import(zip))
        // La canción que cambió en la Mac reemplaza a la vieja; la propia de la iglesia y sus ajustes quedan
        assertEquals("# Luz\nTiempos: 0 8\n\nuno\n\ndos\n", File(church, "canciones/Luz.txt").readText())
        assertTrue(File(church, "canciones/Nueva.txt").isFile)
        assertTrue(File(church, "canciones/Solo de la iglesia.txt").isFile)
        assertEquals("{\"pin\":\"9999\"}", File(church, "ajustes.json").readText())
        // Otra vez: ya está todo igual, no copia nada
        assertEquals(0, t.import(zip))
    }

    @Test
    fun conLasSecuenciasYSinArchivosOcultos() {
        val zip = File(root, "todo.zip")
        Transfer(mac).export(zip, Transfer.PARTS.map { it.key }.toSet())
        val names = ZipFile(zip).use { z -> z.entries().asSequence().map { it.name }.toSet() }
        assertTrue("secuencias/Luz.mp3" in names)
        assertFalse("secuencias/.analisis.json" in names) // se vuelve a calcular allá
        assertEquals(5000, Transfer(mac).sizes()["secuencias"]!!.bytes)
    }

    @Test
    fun unZipCualquieraOMaliciosoNoEntra() {
        // Sin la marca: no es de "Llevar a otro computador"
        val plain = File(root, "otro.zip")
        ZipOutputStream(plain.outputStream()).use { z -> z.putNextEntry(ZipEntry("canciones/A.txt")); z.write("a".toByteArray()); z.closeEntry() }
        assertNull(Transfer(church).inspect(plain))
        assertFailsWith<IllegalArgumentException> { Transfer(church).import(plain) }
        // Con la marca, pero intentando salirse de la carpeta o tocar los ajustes
        val bad = File(root, "malo.zip")
        ZipOutputStream(bad.outputStream()).use { z ->
            for ((name, text) in listOf(Transfer.MARK to "x", "canciones/../ajustes.json" to "{}", "../fuera.txt" to "x", "ajustes.json" to "{}", "canciones/Bien.txt" to "# Bien\n\nok\n")) {
                z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry()
            }
        }
        assertEquals(1, Transfer(church).import(bad))
        assertTrue(File(church, "canciones/Bien.txt").isFile)
        assertEquals("{\"pin\":\"9999\"}", File(church, "ajustes.json").readText())
        assertFalse(File(church.parentFile, "fuera.txt").exists())
    }
}

class TransferProgressTest {
    @Test
    fun cuantoVaYCuantoFalta() {
        val w = Work("Armando el .zip", started = 0)
        val a = w.at(500, 1000, "secuencias/A.wav")
        assertEquals(0.5f, a.fraction)
        assertEquals("secuencias/A.wav", a.file)
        // Muy seguido: no cambia la pantalla (salvo al terminar)
        assertTrue(w.at(600, 1000, "secuencias/B.wav") === w)
        assertEquals(1f, w.at(1000, 1000, "fin").fraction)
        // A los 60 s lleva la mitad: falta más o menos otro tanto
        assertEquals("falta como 1 minuto", a.copy(started = 0).remaining(now = 70_000))
        assertEquals("faltan como 3 minutos", a.copy(started = 0).remaining(now = 180_000))
        assertEquals("falta menos de un minuto", a.copy(started = 0).remaining(now = 20_000))
        assertNull(a.copy(started = 0).remaining(now = 1_000)) // muy pronto para calcular
    }
}
