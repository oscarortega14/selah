package app.selah.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import app.selah.shared.PlanKind
import app.selah.shared.Theme
import java.io.File
import java.time.LocalDate
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * "Planificar la semana" tocando la interfaz de verdad (clics en la ventana del operador), con capturas.
 * Usa una COPIA de una carpeta Selah (escribe listas y cultos.json en ella):
 *   SELAH_SEMANA_DIR=/copia/Selah SELAH_SEMANA_OUT=/ruta/capturas ./gradlew :desktopApp:test --tests '*WeekPlanUiTest*'
 */
@OptIn(ExperimentalTestApi::class)
class WeekPlanUiTest {
    @Test
    fun planTheWeekByClicking() {
        val dir = System.getenv("SELAH_SEMANA_DIR")?.let(::File) ?: return
        val out = File(System.getenv("SELAH_SEMANA_OUT") ?: return).also { it.mkdirs() }
        val library = Library(dir).also { it.reload() }
        val commands = Commands.create(library)
        val services = commands.services!!
        // Una lista de ahora (lo que estaría sonando): planear no la debe tocar.
        val firstSongs = library.catalog.value.songs.take(2)
        firstSongs.forEach { commands.handle(app.selah.shared.ClientMessage.PlanAddSong(it.id)) }
        val planBefore = commands.plan.state.value

        runDesktopComposeUiTest(1440, 900) {
            setContent {
                val plan by commands.plan.state.collectAsState()
                val saved by commands.plan.saved.collectAsState()
                val cfg by services.config.collectAsState()
                val catalog by library.catalog.collectAsState()
                val worship = Worship(
                    config = cfg, update = { f -> services.update(f) }, savedLists = saved, songs = catalog.songs,
                    announcements = catalog.announcements, sermons = catalog.sermons, setTags = { _, _ -> null }, openServices = {},
                    saveList = { name, adds -> commands.plan.saveItems(name, adds) },
                    savedItems = { name -> commands.plan.savedItems(name) },
                )
                ProvideLook(Look(Theme())) {
                    CompositionLocalProvider(LocalWorship provides worship, LocalMusicians provides Musicians()) {
                        MaterialTheme(colorScheme = darkColorScheme()) {
                            OperatorScreen(
                                state = commands.presenter.state.collectAsState().value, catalog = catalog, plan = plan, savedLists = saved,
                                serverError = null, projector = ProjectorControl(false, emptyList(), null, {}, {}, {}),
                                onCommand = { commands.handle(it) }, onReload = {}, onOpenFolder = {}, onEdit = {}, onOpenBackgrounds = {},
                                onOpenConnect = {}, searchBible = { null }, firewallOk = true, onImport = { null }, onExport = { null },
                            )
                        }
                    }
                }
            }
            fun shot(name: String) {
                waitForIdle()
                // La ventana y los diálogos encima (cada uno es una raíz).
                onAllNodes(isRoot()).fetchSemanticsNodes().indices.forEach { i ->
                    val img = onAllNodes(isRoot())[i].captureToImage().toAwtImage()
                    ImageIO.write(img, "png", File(out, if (i == 0) "$name.png" else "$name-$i.png"))
                }
            }
            fun SemanticsNodeInteraction.tap() = performClick().also { waitForIdle() }

            // 1) La pestaña Lista con el botón nuevo
            onNode(androidx.compose.ui.test.hasText("Lista", substring = true) and
                androidx.compose.ui.test.SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Tab)).tap()
            shot("1-lista")
            onNodeWithText("Planificar la semana…").tap()
            shot("2-semana")
            assertEquals(2, onAllNodesWithText("Sin planear").fetchSemanticsNodes().size)

            // 2) El primer culto que viene: elegir canciones con la plantilla
            onAllNodesWithText("Elegir canciones…")[0].tap()
            shot("3-plantilla")
            // Cambiar la primera canción por otra con el select
            val firstPick = onAllNodesWithText("▾", substring = true)[0]
            firstPick.tap()
            shot("4-select")
            val week = upcomingServices(services.config.value, LocalDate.now())
            val (svcIndex, date) = week.first()
            val svc = services.config.value.services[svcIndex]
            val option = library.catalog.value.songs.filter { s -> svc.steps.first().tag.let { t -> s.tags.isEmpty() || s.tags.any { it.equals(t, true) } } }
                .map { it.title }.first { t -> onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty() }
            onAllNodesWithText(option, substring = true).fetchSemanticsNodes().size.let { assertTrue(it >= 1) }
            onAllNodesWithText(option, substring = true).let { it[it.fetchSemanticsNodes().size - 1] }.tap()
            shot("5-elegida")
            onNodeWithText("Guardar").tap()
            shot("6-guardada")

            // Quedó guardada con nombre, enlazada a ese día, y la lista de ahora no cambió
            val listName = plannedListName(svc, date)
            onNodeWithText("✓ Lista: «$listName»").assertExists()
            val items = assertNotNull(commands.plan.savedItems(listName))
            assertEquals(svc.steps.count { it.kind == app.selah.shared.StepKind.SONG }, items.count { it.kind == PlanKind.SONG })
            assertTrue(items.first().title.startsWith(option.substringBefore(" ·")))
            assertEquals(listName, services.config.value.services[svcIndex].planned[date.toString()])
            assertEquals(planBefore.items, commands.plan.state.value.items)
            // Sin canciones repetidas dentro del culto
            assertEquals(items.size, items.map { it.ref }.distinct().size)

            // 3) El segundo culto no repite las del primero
            if (week.size > 1) {
                onAllNodesWithText("Elegir canciones…")[0].tap()
                onNodeWithText("Guardar").tap()
                val (j, d2) = week[1]
                val second = assertNotNull(commands.plan.savedItems(plannedListName(services.config.value.services[j], d2)))
                assertTrue(second.filter { it.kind == PlanKind.SONG }.none { s -> items.any { it.kind == PlanKind.SONG && it.ref == s.ref } }, "El segundo culto repitió canciones del primero")
                shot("7-dos-cultos")
            }

            // 4) "Cambiar…" abre lo que ya tenía
            onAllNodesWithText("Cambiar…")[0].tap()
            shot("8-cambiar")
            onNodeWithText(option, substring = true).assertExists()
            onNodeWithText("Cancelar").tap()

            // 5) "Abrir ahora" la pone como lista del culto
            onAllNodesWithText("Abrir ahora")[0].tap()
            shot("9-abierta")
            assertEquals(listName, commands.plan.state.value.name)
        }
    }
}
