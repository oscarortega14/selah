package app.selah.desktop

import app.selah.shared.ClientMessage
import app.selah.shared.ServerMessage
import app.selah.shared.ServicePlanState
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import app.selah.shared.WS_PATH
import app.selah.shared.decodeServerMessage
import app.selah.shared.encode
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.net.ServerSocket
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ServerTest {
    private lateinit var dir: File
    private lateinit var library: Library
    private lateinit var presenter: Presenter
    private lateinit var server: RemoteServer
    private val port = ServerSocket(0).use { it.localPort }

    @BeforeTest
    fun setUp() {
        dir = createTempDirectory("selah").toFile()
        File(dir, "canciones").mkdirs()
        File(dir, "canciones/b.txt").writeText("# Beta\n\nuno\n\nCoro:\ndos\n")
        // archivo guardado en ANSI (Windows-1252) con tildes
        File(dir, "canciones/a.txt").writeBytes("# Canción Alfa\n\nAquí está\n".toByteArray(charset("windows-1252")))
        File(dir, "biblia").mkdirs()
        File(dir, "biblia/rv.json").writeText(
            """{"name":"Test","books":[{"name":"Juan","chapters":[["a","b","c"]]}]}""",
        )
        library = Library(dir).also { it.reload() }
        val commands = Commands.create(library)
        presenter = commands.presenter
        server = RemoteServer(commands, library, port).also { it.start() }
    }

    @AfterTest
    fun tearDown() {
        server.stop()
        dir.deleteRecursively()
    }

    @Test
    fun libraryLoadsUtf8AndAnsiSortedByTitle() {
        val songs = library.catalog.value.songs
        assertEquals(listOf("Beta", "Canción Alfa"), songs.map { it.title })
        assertEquals("Aquí está", songs[1].firstLine)
        assertEquals("rv", library.catalog.value.bibleName) // una Biblia .json se llama como su archivo
    }

    @Test
    fun remoteControlsThePresentation() = runBlocking {
        val client = HttpClient(CIO) { install(WebSockets) }
        withTimeout(10_000) {
            client.webSocket(host = "127.0.0.1", port = port, path = WS_PATH) {
                suspend fun next(): ServerMessage = decodeServerMessage((incoming.receive() as Frame.Text).readText())
                suspend fun nextState(): app.selah.shared.PresentationState {
                    while (true) {
                        val m = next()
                        if (m is ServerMessage.State) return m.state
                    }
                }

                // al conectar llegan biblioteca, lista del culto, estado y permiso (en cualquier orden)
                val first = List(4) { next() }
                assertEquals(ServerMessage.Auth(required = false, ok = true), first.filterIsInstance<ServerMessage.Auth>().single())
                val lib = first.filterIsInstance<ServerMessage.Library>().single()
                assertEquals(2, lib.songs.size)
                assertNotNull(first.filterIsInstance<ServerMessage.State>().singleOrNull())
                assertNotNull(first.filterIsInstance<ServerMessage.Plan>().singleOrNull())

                send(Frame.Text(ClientMessage.SelectSong("b").encode()))
                var s = nextState()
                assertEquals("Beta", s.title)
                assertEquals(0, s.index)

                send(Frame.Text(ClientMessage.Next.encode()))
                s = nextState()
                assertEquals(1, s.index)
                assertEquals("Coro", s.current?.label)

                send(Frame.Text(ClientMessage.ShowBible("jn 1:2-3").encode()))
                s = nextState()
                assertTrue(s.isBible)
                assertEquals("Juan 1:2-3", s.title)
                assertEquals(listOf("b", "c"), s.slides.map { it.text })

                send(Frame.Text(ClientMessage.ShowBible("juan 9").encode()))
                val err = next()
                assertTrue(err is ServerMessage.Error && "capítulo" in err.message)

                // lista del culto: agregar, ir al elemento y pasar al siguiente
                suspend fun nextPlan(): ServicePlanState {
                    while (true) { val m = next(); if (m is ServerMessage.Plan) return m.plan }
                }
                send(Frame.Text(ClientMessage.PlanAddSong("b").encode()))
                assertEquals(1, nextPlan().items.size)
                send(Frame.Text(ClientMessage.PlanAddBible("jn 1:1").encode()))
                assertEquals(listOf("Beta", "Juan 1:1"), nextPlan().items.map { it.title })
                send(Frame.Text(ClientMessage.PlanGo(0).encode()))
                assertEquals("Beta", nextState().title)
                send(Frame.Text(ClientMessage.PlanNext.encode()))
                assertEquals("Juan 1:1", nextState().title)

                send(Frame.Text(ClientMessage.StageMessage("  Repetir coro ").encode()))
                assertEquals("Repetir coro", nextState().stageMessage)
            }
        }
        client.close()
    }

    @Test
    fun servesTheWebPages() = runBlocking {
        val client = HttpClient(CIO)
        for ((path, marker) in listOf(
            "/" to "Control remoto", "/escenario.html" to "Escenario", "/obs.html" to "OBS", "/selah.js" to "const Selah",
            "/manifest.json" to "\"display\": \"standalone\"", "/manifest-escenario.json" to "Escenario", "/info" to "\"port\"",
        )) {
            val r = client.get("http://127.0.0.1:$port$path")
            assertEquals(HttpStatusCode.OK, r.status, path)
            assertTrue(marker in r.bodyAsText(), path)
        }
        // los íconos de la app web existen y son PNG
        for (icon in listOf("icon-192.png", "icon-512.png", "icon-maskable-512.png", "apple-touch-icon.png", "favicon.png")) {
            val r = client.get("http://127.0.0.1:$port/icons/$icon")
            assertEquals(HttpStatusCode.OK, r.status, icon)
            assertEquals("image/png", r.headers["Content-Type"]?.substringBefore(';'), icon)
        }
        client.close()
    }
}
