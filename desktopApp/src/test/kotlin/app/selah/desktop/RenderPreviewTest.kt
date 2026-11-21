package app.selah.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import app.selah.shared.PresentationState
import app.selah.shared.Slide
import app.selah.shared.Theme
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

/**
 * Dibuja la proyección fuera de pantalla y guarda PNGs para revisarlos a ojo.
 * Se omite si no se indica carpeta:
 *   SELAH_RENDER_DIR=/tmp/out SELAH_BG=/ruta/fondo.png ./gradlew :desktopApp:test --tests '*RenderPreviewTest*'
 */
class RenderPreviewTest {
    @Test
    fun renderPreviews() {
        val outDir = System.getenv("SELAH_RENDER_DIR")?.let(::File) ?: return
        outDir.mkdirs()
        val bg = System.getenv("SELAH_BG")?.let(::File)
        bg?.let { BackgroundImages.load(it) } // precargar para que se vea en el primer cuadro

        val song = PresentationState(
            "Alabad a Jehová",
            listOf(Slide("Coro", "Alabad a Jehová con todo tu corazón\nViviré para Él, Señor, cantando salmos de gratitud")),
            index = 0,
        )
        val verse = PresentationState("Juan 3:16", listOf(Slide("Juan 3:16", "Porque de tal manera amó Dios al mundo, que ha dado á su Hijo unigénito, para que todo aquel que en él cree, no se pierda, mas tenga vida eterna.")), 0, isBible = true)

        val cases = listOf(
            "1-negro" to (song to Theme(builtin = null)),
            "2-azul-noche" to (song to Theme.PRESETS[1].second),
            "3-imagen" to (song to Theme(image = "bg", dim = 0.35f)),
            "4-imagen-crema-biblia" to (verse to Theme(image = "bg", dim = 0.5f, textColor = "#FFF4D6")),
            "5-letra-pequena-sin-sombra" to (song to Theme.PRESETS[2].second.copy(fontScale = 0.7f, shadow = false)),
            "6-cuenta-regresiva" to (song.copy(countdownEnd = System.currentTimeMillis() + 300_000, countdownText = "Comenzamos en") to Theme(image = "bg")),
            "7-logo" to (song.copy(logo = true) to Theme(image = "bg", logo = "bg")),
            "9-biblia-con-version" to (PresentationState("Josué 1:9", listOf(Slide("Josué 1:9", "Esta es mi orden: sé fuerte y valiente. No tengas miedo ni te desanimes, porque el Señor tu Dios está contigo dondequiera que vayas.")), 0, isBible = true, version = "NTV") to Theme.PRESETS[1].second),
            "8-alerta" to (song.copy(alert = "Se busca al dueño del carro de placas ABC123, favor acercarse a la entrada") to Theme.PRESETS[1].second),
        )
        // "¿Qué usa tu iglesia?" con todo encendido, partituras y secuencias.
        val musicians = Musicians(
            church = ChurchFeatures.ALL.copy(splitClick = true, clickByDirector = true),
            catalog = MusicCatalog(
                scores = listOf(app.selah.shared.ScoreRef("Gracia admirable", "Bajo", "a"), app.selah.shared.ScoreRef("Gracia admirable", "Teclado", "b"), app.selah.shared.ScoreRef("Sublime gracia", app.selah.shared.GENERAL_INSTRUMENT, "c")),
                scoreOrphans = listOf("Cancion mal escrita - Bajo.pdf"),
                sequences = mapOf("Gracia admirable" to "Gracia admirable.mp3"),
            ),
            songs = listOf(
                app.selah.shared.SongSummary("Gracia admirable", "Gracia admirable"),
                app.selah.shared.SongSummary("Luz del camino", "Luz del camino"),
                app.selah.shared.SongSummary("Sublime gracia", "Sublime gracia"),
            ),
            suggested = listOf("Luz del camino"),
            recording = RecordingTimings(Library(kotlin.io.path.createTempDirectory("vista").toFile()), WhisperModelFile(File("."))),
            autoState = { id -> mapOf("Gracia admirable" to "72 BPM · con tiempos", "Luz del camino" to "110 BPM · tiempos sugeridos")[id] },
        )
        // Una imagen por pestaña: los interruptores, y los ajustes de cada cosa encendida.
        for (tab in churchTabs(musicians.church)) {
            ImageComposeScene(820, 820, Density(1f)) { MusiciansContent(musicians, devices = listOf("Altavoces del MacBook Pro", "Tarjeta USB"), start = tab) }.use { scene ->
                File(outDir, "10-que-usa-tu-iglesia-${tab.name.lowercase()}.png").writeBytes(scene.render(0).encodeToData(EncodedImageFormat.PNG)!!.bytes)
            }
        }
        // Partituras con muchas canciones: abecedario y acordeón.
        val many = musicians.copy(
            catalog = musicians.catalog.copy(
                scores = listOf("Aclamad", "Alabad a Jehová", "Bendito sea", "Cuán grande es Él", "Él es mi rey", "Grande y fuerte", "Salmo 23").flatMap { s ->
                    listOf(app.selah.shared.GENERAL_INSTRUMENT, "Bajo", "Piano", "Trompeta 1").map { app.selah.shared.ScoreRef(s, it, "$s - $it.pdf") }
                },
                scoreOrphans = listOf("Jesús salva - Bajo.pdf", "Jesús salva - Piano.pdf", "Glorifícate - Trompeta 1.pdf"),
            ),
        )
        ImageComposeScene(820, 820, Density(1f)) { MusiciansContent(many, devices = emptyList(), start = ChurchTab.SCORES) }.use { scene ->
            File(outDir, "10-que-usa-tu-iglesia-partituras-muchas.png").writeBytes(scene.render(0).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
        // Escuchando la grabación de una canción: la rueda junto a ella.
        ImageComposeScene(820, 820, Density(1f)) { MusiciansContent(musicians, devices = emptyList(), start = ChurchTab.AUTO, busy = "Sublime gracia" to "Escuchando la letra…") }.use { scene ->
            File(outDir, "10-que-usa-tu-iglesia-auto-escuchando.png").writeBytes(scene.render(500).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
        // Ventana "Equipo": uno pidiendo entrar y el equipo aprobado.
        val teamDir = kotlin.io.path.createTempDirectory("equipo").toFile()
        val team = TeamStore(File(teamDir, "equipo.json"))
        team.hello("a".repeat(64), "Celular de Juan", "192.168.1.30:51234")
        team.hello("b".repeat(64), "Ana", "x"); team.approve("b".repeat(64), app.selah.shared.Role.DIRECTOR, "Piano")
        team.hello("c".repeat(64), "Pedro", "x"); team.approve("c".repeat(64), app.selah.shared.Role.MUSICIAN, "Bajo")
        team.hello("d".repeat(64), "Pastor Luis", "x"); team.approve("d".repeat(64), app.selah.shared.Role.PREACHER, null)
        ImageComposeScene(760, 1100, Density(1f)) { TeamContent(team, listOf("Bajo", "Piano")) }.use { scene ->
            File(outDir, "11-equipo.png").writeBytes(scene.render(0).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
        teamDir.deleteRecursively()
        // El caso reportado: ventana de proyección de ~1180x630 con palabras largas al final.
        val reported = PresentationState("A", listOf(Slide("Pregón 3", "Con la fe en Jesucristo tengo el poder\nUn poder como ninguno, tesoro invaluable")), 0)
        ImageComposeScene(1180, 630, Density(1.25f)) { ProjectorScreen(reported, look = Look(Theme(builtin = null))) }.use { scene ->
            File(outDir, "9-caso-reportado.png").writeBytes(scene.render(0).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
        // Los fondos incluidos, con letra encima (como se verían en el videobeam).
        for ((key, _) in Theme.BUILTINS) {
            // dos instantes de la animación, para ver que se mueve
            for (t in listOf(0f, 4f)) {
                ImageComposeScene(1280, 720, Density(1f)) { ProjectorScreen(song, look = Look(Theme(builtin = key), frozenTime = t)) }.use { scene ->
                    File(outDir, "fondo-$key-t${t.toInt()}.png").writeBytes(scene.render(0).encodeToData(EncodedImageFormat.PNG)!!.bytes)
                }
            }
        }
        for ((name, pair) in cases) {
            val (state, theme) = pair
            val look = Look(theme, backgroundFile = { bg })
            ImageComposeScene(1280, 720, Density(1f)) { ProjectorScreen(state, look = look) }.use { scene ->
                scene.render(0)
                val png = scene.render(100_000_000).encodeToData(EncodedImageFormat.PNG)!!.bytes
                File(outDir, "$name.png").writeBytes(png)
            }
        }
    }
}
