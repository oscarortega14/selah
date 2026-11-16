package app.selah.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import app.selah.shared.ClickState
import app.selah.shared.ClientMessage
import app.selah.shared.Role
import app.selah.shared.ServicePlanState
import app.selah.shared.Theme
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * Capturas del PC para el asistente de inicio, dibujadas con una biblioteca de ejemplo (sin canciones de nadie):
 *   SELAH_GUIA_DIR=/ruta/Selah SELAH_GUIA_OUT=/ruta/salida ./gradlew :desktopApp:test --tests '*GuideImagesTest*'
 */
class GuideImagesTest {
    @Test
    fun render() {
        val dir = System.getenv("SELAH_GUIA_DIR")?.let(::File) ?: return
        val out = File(System.getenv("SELAH_GUIA_OUT") ?: return).also { it.mkdirs() }
        val library = Library(dir).also { it.reload() }
        val music = MusicLibrary(dir).also { it.refresh(library.catalog.value.songs) }
        val commands = Commands.create(library)
        commands.handle(ClientMessage.SelectSong("Gracia admirable"))
        commands.handle(ClientMessage.Next)
        commands.handle(ClientMessage.PlanAddSong("Gracia admirable"))
        commands.handle(ClientMessage.PlanAddSong("Luz en el camino"))
        commands.handle(ClientMessage.PlanAddBible("Salmo 23:1"))
        val state = commands.presenter.state.value
        val withClick = state.copy(click = ClickState(120, 4, System.currentTimeMillis() - 10_000, auto = true, hasTiming = true))
        fun save(name: String, w: Int, h: Int, density: Float = 1f, content: @androidx.compose.runtime.Composable () -> Unit) {
            ImageComposeScene(w, h, Density(density)) { content() }.use { scene ->
                File(out, "$name.png").writeBytes(scene.render(0).encodeToData(EncodedImageFormat.PNG)!!.bytes)
            }
        }
        val look = Look(Theme())
        val projector = ProjectorControl(true, emptyList(), null, {}, {}, {})
        val musicians = Musicians(church = ChurchFeatures.ALL, catalog = music.catalog.value, songTitle = { library.song(it)?.title ?: it }, songs = library.catalog.value.songs)
        save("pc-operador", 1440, 820) {
            ProvideLook(look) {
                CompositionLocalProvider(LocalMusicians provides musicians) {
                    androidx.compose.material3.MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme()) {
                        OperatorScreen(
                            state = withClick, catalog = library.catalog.value, plan = commands.plan.state.value, serverError = null,
                            projector = projector, onCommand = { null }, onReload = {}, onOpenFolder = {}, onEdit = {}, onOpenBackgrounds = {},
                            onOpenConnect = {}, searchBible = { null }, firewallOk = true, onImport = { null }, onExport = { null },
                        )
                    }
                }
            }
        }
        save("pc-proyeccion", 1280, 720) { ProjectorScreen(state, look = Look(Theme(), frozenTime = 2f)) }
        save("pc-que-usa", 820, 760) { MusiciansContent(musicians.copy(church = ChurchFeatures(click = true, sequences = true, scores = true, congregation = true)), devices = emptyList()) }
        val teamDir = kotlin.io.path.createTempDirectory("equipo").toFile()
        val team = TeamStore(File(teamDir, "equipo.json"))
        team.hello("a".repeat(64), "Celular de Pedro", "192.168.1.30:51234")
        team.hello("b".repeat(64), "Ana", "x"); team.approve("b".repeat(64), Role.DIRECTOR, "Piano")
        team.hello("c".repeat(64), "Luis", "x"); team.approve("c".repeat(64), Role.MUSICIAN, "Bajo")
        save("pc-equipo", 760, 640) { TeamContent(team, listOf("Bajo", "Piano")) }
        teamDir.deleteRecursively()
        ImageIO.write(Congregation.qrAnnouncement("http://192.168.1.20:8765/letra"), "png", File(out, "pc-qr.png"))
    }
}
