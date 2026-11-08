package app.selah.desktop

import app.selah.shared.ClientMessage
import app.selah.shared.PresentationState
import app.selah.shared.Slide
import app.selah.shared.encode
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.ServerSocket
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Letra en los celulares de la congregación: solo lectura y solo lo que se ve en el videobeam. */
class CongregationTest {
    private lateinit var dir: File
    private lateinit var library: Library
    private lateinit var commands: Commands
    private lateinit var server: RemoteServer
    private val port = ServerSocket(0).use { it.localPort }

    @BeforeTest
    fun setUp() {
        dir = createTempDirectory("selah-letra").toFile()
        File(dir, "canciones").mkdirs()
        File(dir, "canciones/b.txt").writeText("# Beta\n\nuno\n\nCoro:\ndos\n")
        library = Library(dir).also { it.reload() }
        commands = Commands.create(library)
        server = RemoteServer(commands, library, port).also { it.start() }
    }

    @AfterTest
    fun tearDown() { server.stop(); dir.deleteRecursively() }

    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.content

    @Test
    fun onlyWhatIsOnTheProjectorNeverStageMessagesOrTimers() {
        val song = PresentationState(
            "Beta", listOf(Slide("Coro", "dos")), 0,
            stageMessage = "Repetir coro", lowerThird = "Pastor Juan", lowerThirdSub = "Predicación", timerStart = 5, timerMinutes = 30,
        )
        val v = Congregation.view(song, 1)
        assertTrue(v["visible"]!!.jsonPrimitive.boolean)
        assertEquals("dos", v.str("text"))
        assertEquals("", v.str("ref")) // en una canción la etiqueta no se muestra
        val json = v.toString()
        for (secret in listOf("Repetir coro", "Pastor Juan", "Predicación", "timer", "stage", "lowerThird")) assertFalse(secret in json, "se filtró: $secret")

        val verse = PresentationState("Juan 3:16", listOf(Slide("Juan 3:16", "Porque de tal manera", text2 = "For God so loved")), 0, isBible = true)
        val vv = Congregation.view(verse, 1)
        assertEquals("Juan 3:16", vv.str("ref"))
        assertEquals("For God so loved", vv.str("text2"))

        // Sin letra en el videobeam: tampoco en el celular
        for (s in listOf(song.copy(blank = true), song.copy(logo = true), song.copy(media = "a.jpg"), song.copy(presentation = "p.pdf"), PresentationState())) {
            val x = Congregation.view(s, 1)
            assertFalse(x["visible"]!!.jsonPrimitive.boolean)
            assertEquals("", x.str("text"))
        }
        // La cuenta regresiva y las alertas sí (se ven en el videobeam)
        val c = Congregation.view(song.copy(blank = true, countdownEnd = 99, countdownText = "Comenzamos en", alert = "Carro ABC"), 1)
        assertEquals("99", c.str("countdownEnd"))
        assertEquals("Carro ABC", c.str("alert"))
    }

    @Test
    fun followsTheProjectorAndCannotControlIt() = runBlocking {
        val client = HttpClient(CIO) { install(WebSockets) }
        withTimeout(10_000) {
            client.webSocket(host = "127.0.0.1", port = port, path = Congregation.WS_PATH) {
                suspend fun next(): JsonObject = Json.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject
                assertFalse(next()["visible"]!!.jsonPrimitive.boolean) // al conectar: nada en pantalla
                commands.handle(ClientMessage.SelectSong("b"))
                assertEquals("uno", next().str("text"))
                // Lo que mande la página se ignora: no cambia lo que se proyecta, aunque no haya clave.
                send(Frame.Text(ClientMessage.Next.encode()))
                send(Frame.Text(ClientMessage.SetBlank(true).encode()))
                kotlinx.coroutines.delay(300)
                assertEquals(0, commands.presenter.state.value.index)
                assertFalse(commands.presenter.state.value.blank)
                // Un mensaje al escenario no le llega (ni manda nada nuevo)
                commands.handle(ClientMessage.StageMessage("Repetir coro"))
                commands.handle(ClientMessage.Next)
                val after = next()
                assertEquals("dos", after.str("text"))
                assertFalse("Repetir coro" in after.toString())
                assertEquals(1, server.congregationViewers)
            }
        }
        // La dirección corta lleva a la página
        val page = client.get("http://127.0.0.1:$port${Congregation.PATH}").bodyAsText()
        assertTrue("/ws-letra" in page)
        client.close()
        kotlinx.coroutines.delay(300)
        assertEquals(0, server.congregationViewers)
    }

    @Test
    fun qrImageForAnnouncements() {
        val name = Congregation.saveQrAnnouncement(library, "http://192.168.1.20:8765/letra")
        val f = File(dir, "anuncios/$name")
        val img = ImageIO.read(f)
        assertEquals(1920, img.width)
        assertEquals(1080, img.height)
        assertTrue(name in library.catalog.value.announcements) // ya aparece en Anuncios (y entra al carrusel)
        // Se reemplaza al crearla otra vez (la dirección del PC pudo cambiar), no se duplica
        Congregation.saveQrAnnouncement(library, "http://192.168.1.21:8765/letra")
        assertEquals(1, library.catalog.value.announcements.count { it.startsWith("Letra en tu celular") })
    }
}
