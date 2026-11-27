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
import androidx.compose.ui.test.performScrollTo
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
            // Si está fuera de la vista (lista larga), primero se desplaza hasta ahí, como haría una persona.
            fun SemanticsNodeInteraction.tap() = also { runCatching { performScrollTo() } }.performClick().also { waitForIdle() }

            // 1) La pestaña Lista: "En curso" compacto y "Esta semana" con un culto por tarjeta
            onNode(androidx.compose.ui.test.hasText("Lista") and
                androidx.compose.ui.test.SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Tab)).tap()
            shot("1-lista")
            onNodeWithText("Esta semana").assertExists()
            onNodeWithText("En curso").assertExists()
            // El orden completo no se ve hasta pedirlo
            assertEquals(0, onAllNodesWithText(firstSongs[0].title).fetchSemanticsNodes().size)

            val week = upcomingServices(services.config.value, LocalDate.now())
            val (svcIndex, date) = week.first()
            val svc = services.config.value.services[svcIndex]

            // 2) Entrar al primer culto y elegir sus canciones con la plantilla
            onNodeWithText(svc.name).tap()
            shot("2-culto-abierto")
            onNodeWithText("Elegir canciones…").tap()
            shot("3-plantilla")
            onAllNodesWithText("▾", substring = true)[0].tap()
            shot("4-select")
            val option = library.catalog.value.songs.filter { s -> svc.steps.first().tag.let { t -> s.tags.isEmpty() || s.tags.any { it.equals(t, true) } } }
                .map { it.title }.first { t -> onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty() }
            onAllNodesWithText(option, substring = true).let { it[it.fetchSemanticsNodes().size - 1] }.tap()
            onNodeWithText("Guardar").tap()
            shot("5-guardada")

            val listName = plannedListName(svc, date)
            val items = assertNotNull(commands.plan.savedItems(listName))
            val songCount = svc.steps.count { it.kind == app.selah.shared.StepKind.SONG }
            assertEquals(songCount, items.count { it.kind == PlanKind.SONG })
            assertTrue(items.first().title.startsWith(option.substringBefore(" ·")))
            assertEquals(listName, services.config.value.services[svcIndex].planned[date.toString()])
            assertEquals(planBefore.items, commands.plan.state.value.items)
            assertEquals(items.size, items.map { it.ref }.distinct().size)
            // La tarjeta ya muestra sus canciones y el botón para cambiarlas
            onNodeWithText("$songCount canciones").assertExists()
            onNodeWithText("Cambiar canciones…").assertExists()
            onNodeWithText("1. ${items.first().title}").assertExists()

            // 3) El segundo culto no repite las del primero
            if (week.size > 1) {
                val (j, d2) = week[1]
                onNodeWithText(svc.name).tap() // cerrar el primero
                onNodeWithText(services.config.value.services[j].name).tap()
                onNodeWithText("Elegir canciones…").tap()
                onNodeWithText("Guardar").tap()
                val second = assertNotNull(commands.plan.savedItems(plannedListName(services.config.value.services[j], d2)))
                assertTrue(second.filter { it.kind == PlanKind.SONG }.none { s -> items.any { it.kind == PlanKind.SONG && it.ref == s.ref } }, "El segundo culto repitió canciones del primero")
                shot("6-dos-cultos")
                onNodeWithText(svc.name).tap()
            }

            // 4) "Proyectar" una canción del culto sin abrirlo
            onAllNodesWithText("Proyectar")[1].tap()
            assertEquals(items[1].title, commands.presenter.state.value.title)
            assertEquals(planBefore.items, commands.plan.state.value.items)

            // 5) "Abrir este culto": queda En curso, compacto, con "Empezar"
            onNodeWithText("Abrir este culto").tap()
            shot("7-en-curso")
            assertEquals(listName, commands.plan.state.value.name)
            onNodeWithText(listName).assertExists()
            onNodeWithText("Abierto · ${items.size} elementos").assertExists()
            onNodeWithText("Ver el orden (${items.size})").tap()
            shot("8-orden")
            onNodeWithText("Ocultar el orden").assertExists()
        }
    }
}
