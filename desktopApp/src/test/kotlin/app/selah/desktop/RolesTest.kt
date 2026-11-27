package app.selah.desktop

import app.selah.shared.ClientMessage
import app.selah.shared.Role
import app.selah.shared.ServerMessage
import app.selah.shared.WS_PATH
import app.selah.shared.decodeServerMessage
import app.selah.shared.encode
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.common.PDRectangle
import java.io.File
import java.net.ServerSocket
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Papeles del equipo: el PC rechaza lo que el papel no permite, nadie se salta el suyo. */
class RolesTest {
    private lateinit var dir: File
    private lateinit var library: Library
    private lateinit var team: TeamStore
    private lateinit var music: MusicLibrary
    private lateinit var server: RemoteServer
    private val church = MutableStateFlow(ChurchFeatures.ALL)
    private lateinit var commands: Commands
    private val click get() = commands.presenter.state.value.click
    private val port = ServerSocket(0).use { it.localPort }
    private val juan = "juan-celular-0123456789abcdef"
    private val ana = "ana-celular-0123456789abcdef"

    @BeforeTest
    fun setUp() {
        dir = createTempDirectory("selah-papeles").toFile()
        File(dir, "canciones").mkdirs()
        File(dir, "canciones/A.txt").writeText("# A\nBPM: 120\n\nuno\n\ndos\n")
        library = Library(dir).also { it.reload() }
        music = MusicLibrary(dir)
        music.scoresDir.mkdirs()
        for (i in listOf("Bajo", "Piano")) PDDocument().use { d -> d.addPage(PDPage(PDRectangle.A4)); d.save(File(music.scoresDir, "A - $i.pdf")) }
        music.refresh(library.catalog.value.songs)
        team = TeamStore(File(dir, "equipo.json"))
        commands = Commands.create(library)
        server = RemoteServer(commands, library, port, pin = MutableStateFlow("4321"), church = church, music = music, team = team)
        server.start()
    }

    @AfterTest
    fun tearDown() { server.stop(); dir.deleteRecursively() }

    private suspend fun DefaultClientWebSocketSession.next(): ServerMessage = decodeServerMessage((incoming.receive() as Frame.Text).readText())
    private suspend fun DefaultClientWebSocketSession.until(f: (ServerMessage) -> Boolean): ServerMessage { while (true) { val m = next(); if (f(m)) return m } }
    private suspend fun DefaultClientWebSocketSession.say(m: ClientMessage) = send(Frame.Text(m.encode()))
    /** Lo que responde el PC a una orden: un error, un aviso de clave, o null si la hizo (llega el estado). */
    private suspend fun DefaultClientWebSocketSession.refused(m: ClientMessage): String? {
        // Lo que había llegado antes no cuenta.
        while (!incoming.isEmpty) incoming.tryReceive()
        say(m)
        return withTimeoutOrNull(1_000) {
            when (val r = until { it is ServerMessage.Error || (it is ServerMessage.Auth && it.message != null) }) {
                is ServerMessage.Error -> r.message
                is ServerMessage.Auth -> r.message
                else -> null
            }
        }
    }

    @Test
    fun onlyTheDirectorGivesTheClickWhenTheChurchWantsIt() = runBlocking {
        church.value = ChurchFeatures.ALL.copy(clickByDirector = true)
        val client = HttpClient(CIO) { install(WebSockets) }
        withTimeout(20_000) {
            // Audiovisuales, con la clave: la letra automática sí, el click que suena no.
            client.webSocket(host = "127.0.0.1", port = port, path = WS_PATH) {
                say(ClientMessage.Login("4321"))
                until { it is ServerMessage.Auth && it.ok }
                assertNull(refused(ClientMessage.SelectSong("A")))
                assertEquals(RemoteServer.DIRECTOR_CLICK, refused(ClientMessage.StartClick(90)))
                assertNull(refused(ClientMessage.StartClick(lyricsOnly = true)))
                assertTrue(click!!.lyricsOnly)
                assertFalse(click!!.audible) // no suena en ningún celular
                assertNull(refused(ClientMessage.SetTempo(118))) // es solo la letra: se puede ajustar
            }
            // El director da el click encima de la letra sola; la letra sigue con su click.
            client.webSocket(host = "127.0.0.1", port = port, path = WS_PATH) {
                say(ClientMessage.Hello(deviceId = ana, deviceName = "Ana"))
                team.approve(TeamStore.hash(ana), Role.DIRECTOR, null)
                until { it is ServerMessage.Auth && it.role == Role.DIRECTOR }
                assertNull(refused(ClientMessage.StartClick(100)))
                assertTrue(click!!.audible)
                assertFalse(click!!.lyricsOnly)
                assertEquals(100, click!!.bpm)
            }
            // Con el click del director andando, audiovisuales no lo cambia ni lo para, ni lo tapa con la letra sola.
            client.webSocket(host = "127.0.0.1", port = port, path = WS_PATH) {
                say(ClientMessage.Login("4321"))
                until { it is ServerMessage.Auth && it.ok }
                assertEquals(RemoteServer.DIRECTOR_CLICK, refused(ClientMessage.SetTempo(90)))
                assertEquals(RemoteServer.DIRECTOR_CLICK, refused(ClientMessage.StopClick))
                assertEquals(RemoteServer.DIRECTOR_CLICK, refused(ClientMessage.StartClick(lyricsOnly = true)))
                // Pero sí acomoda la letra
                assertNull(refused(ClientMessage.Next))
            }
        }
        client.close()
    }

    @Test
    fun eachRoleDoesOnlyWhatItCan() = runBlocking {
        val client = HttpClient(CIO) { install(WebSockets) }
        withTimeout(20_000) {
            client.webSocket(host = "127.0.0.1", port = port, path = WS_PATH) {
                say(ClientMessage.Hello(deviceId = juan, deviceName = "Celular de Juan"))
                // Nuevo: espera que lo aprueben; mientras, solo mira
                val waiting = until { it is ServerMessage.Auth && it.pending } as ServerMessage.Auth
                assertEquals(Role.VIEWER, waiting.role)
                assertEquals("Celular de Juan", team.pending.value.single().name)
                assertTrue(refused(ClientMessage.SelectSong("A"))!!.contains("Esperando"))

                // Músico (bajo): ve todas las partituras, no cambia nada
                team.approve(TeamStore.hash(juan), Role.MUSICIAN, "Bajo")
                val m = until { it is ServerMessage.Auth && it.role == Role.MUSICIAN } as ServerMessage.Auth
                assertEquals("Bajo", m.instrument)
                assertFalse(m.pending)
                val lib = until { it is ServerMessage.Library && it.scores.isNotEmpty() } as ServerMessage.Library
                assertEquals(listOf("Bajo", "Piano"), lib.scores.map { it.instrument })
                assertEquals("Tu papel (Músico) no permite eso", refused(ClientMessage.SelectSong("A")))
                assertEquals("Tu papel (Músico) no permite eso", refused(ClientMessage.StartClick(90)))
                assertEquals("Tu papel (Músico) no permite eso", refused(ClientMessage.PlanClear))
                // Pero mira cualquier canción por su cuenta: le llega solo a él y el videobeam no cambia
                val shownBefore = commands.presenter.state.value
                say(ClientMessage.SongView("A"))
                val seen = until { it is ServerMessage.SongView } as ServerMessage.SongView
                assertEquals("A", seen.title)
                assertEquals(listOf("uno", "dos"), seen.slides.map { it.text })
                assertEquals(shownBefore, commands.presenter.state.value)
                say(ClientMessage.SongView("no-existe"))
                assertTrue((until { it is ServerMessage.SongView } as ServerMessage.SongView).slides.isEmpty())
                assertTrue(app.selah.shared.FEATURE_SONG_VIEW in lib.features)
                // Esta app no dijo que entiende la semana: no le llega
                assertNull(withTimeoutOrNull(800) { until { it is ServerMessage.Week } })

                // Director: click sí, la letra no
                team.update(TeamStore.hash(juan)) { it.copy(role = Role.DIRECTOR) }
                until { it is ServerMessage.Auth && it.role == Role.DIRECTOR }
                assertNull(refused(ClientMessage.StartClick(90)))
                assertNull(refused(ClientMessage.StageMessage("Repetir coro")))
                assertEquals("Tu papel (Director de alabanza) no permite eso", refused(ClientMessage.Next))
                assertEquals("Tu papel (Director de alabanza) no permite eso", refused(ClientMessage.SetLowerThird("Pastor")))

                // Con la clave: operador
                say(ClientMessage.Login("4321"))
                until { it is ServerMessage.Auth && it.role == Role.OPERATOR && it.ok }
                assertNull(refused(ClientMessage.SelectSong("A")))

                // Quitado del equipo (y sin la clave en otra conexión): vuelve a pedir entrar
            }
            // Un celular nuevo que trae la clave (enlace del QR): operador, no "esperando"
            client.webSocket(host = "127.0.0.1", port = port, path = WS_PATH) {
                say(ClientMessage.Hello(deviceId = "nuevo-celular-0123456789abcdef", deviceName = "Operador"))
                until { it is ServerMessage.Auth && it.pending }
                say(ClientMessage.Login("4321"))
                val a = until { it is ServerMessage.Auth && it.ok } as ServerMessage.Auth
                assertFalse(a.pending)
                assertEquals(Role.OPERATOR, a.role)
            }
            // Una app vieja (sin código): como antes, la clave controla todo
            client.webSocket(host = "127.0.0.1", port = port, path = WS_PATH) {
                say(ClientMessage.Hello())
                assertEquals("Escribe la clave para controlar", refused(ClientMessage.Next))
                say(ClientMessage.Login("4321"))
                until { it is ServerMessage.Auth && it.ok }
                assertNull(refused(ClientMessage.Next))
            }
        }
        client.close()
    }

    @Test
    fun theWeekGoesToNewAppsOnly() = runBlocking {
        commands.services!!.update { it.copy(services = listOf(app.selah.shared.WorshipService("Diario", java.time.LocalDate.now().dayOfWeek.value, "9:00"))) }
        val client = HttpClient(CIO) { install(WebSockets) }
        withTimeout(10_000) {
            client.webSocket(host = "127.0.0.1", port = port, path = WS_PATH) {
                say(ClientMessage.Hello(listOf(app.selah.shared.FEATURE_WEEK), deviceId = juan, deviceName = "Juan"))
                val week = until { it is ServerMessage.Week } as ServerMessage.Week
                assertEquals("Diario", week.services.first().name)
                assertTrue(week.services.first().today)
            }
        }
        client.close()
    }

    @Test
    fun scoresOnlyForTheTeamsMusicians() = runBlocking {
        val http = HttpClient(CIO)
        val url = "http://127.0.0.1:$port/partitura/paginas?nombre=A%20-%20Piano.pdf"
        assertEquals(HttpStatusCode.Forbidden, http.get(url).status)
        assertEquals(HttpStatusCode.Forbidden, http.get(url) { header(RemoteServer.DEVICE_HEADER, ana) }.status) // no aprobada
        team.hello(TeamStore.hash(ana), "Ana", "x")
        team.approve(TeamStore.hash(ana), Role.MEDIA, null)
        assertEquals(HttpStatusCode.Forbidden, http.get(url) { header(RemoteServer.DEVICE_HEADER, ana) }.status) // medios no
        team.update(TeamStore.hash(ana)) { it.copy(role = Role.MUSICIAN, instrument = "Bajo") }
        assertEquals(HttpStatusCode.OK, http.get(url) { header(RemoteServer.DEVICE_HEADER, ana) }.status) // la de piano también
        assertEquals(HttpStatusCode.OK, http.get(url) { header("X-Proyecta-Pin", "4321") }.status)
        http.close()
    }

    @Test
    fun theTeamFileKeepsNoSecrets() {
        team.hello(TeamStore.hash(juan), "Juan\n<script>", "1.2.3.4")
        team.approve(TeamStore.hash(juan), Role.DIRECTOR, "Piano")
        val text = File(dir, "equipo.json").readText()
        assertFalse(juan in text)
        assertTrue(TeamStore.hash(juan) in text)
        assertEquals(Role.DIRECTOR, TeamStore(File(dir, "equipo.json")).member(TeamStore.hash(juan))?.role)
        assertFalse(TeamStore.validId("corto"))
        assertFalse(TeamStore.validId("x".repeat(200)))
        assertFalse(TeamStore.validId("con espacios 0123456789"))
        assertEquals("Juan<script>", TeamStore.cleanName("Juan\n<script>"))
        // Nadie llena la lista de espera inventando códigos
        repeat(50) { team.hello(TeamStore.hash("falso-$it-0123456789abcdef"), "x", "y") }
        assertEquals(TeamStore.MAX_PENDING, team.pending.value.size)
    }
}
