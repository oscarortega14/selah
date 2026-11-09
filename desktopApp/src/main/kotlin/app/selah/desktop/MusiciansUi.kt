package app.selah.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import app.selah.shared.GENERAL_INSTRUMENT
import java.io.File

/** Lo que la ventana de operador necesita saber de los músicos (lo da Main). */
data class Musicians(
    val church: ChurchFeatures = ChurchFeatures(),
    val catalog: MusicCatalog = MusicCatalog(),
    val update: ((ChurchFeatures) -> ChurchFeatures) -> Unit = {},
    val songTitle: (String) -> String = { it },
    val music: MusicLibrary? = null,
    val open: () -> Unit = {},
) {
    fun hasSequence(songId: String?): Boolean = church.sequences && songId != null && songId in catalog.sequences
}

val LocalMusicians = compositionLocalOf { Musicians() }

/** "¿Qué usa tu iglesia?": interruptores, partituras, secuencias y salida de sonido. */
@Composable
fun MusiciansWindow(m: Musicians, onClose: () -> Unit) {
    Window(onCloseRequest = onClose, title = "¿Qué usa tu iglesia?", state = rememberFittedWindowState(DpSize(820.dp, 820.dp))) {
        MusiciansContent(m)
    }
}

/** El contenido de la ventana (aparte, para poder dibujarlo en las pruebas). */
@Composable
fun MusiciansContent(m: Musicians, devices: List<String> = remember { JavaSoundSequenceOutput.devices() }) {
        MaterialTheme(colorScheme = darkColorScheme()) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Letra y Biblia van siempre. Enciende solo lo que tu iglesia usa: lo apagado no aparece ni aquí ni en los celulares.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val c = m.church
                    FeatureSwitch("Click para los músicos", "Metrónomo en los celulares con audífonos (nunca en los parlantes) y letra que pasa sola.", c.click) { on -> m.update { it.copy(click = on) } }
                    FeatureSwitch("Secuencias", "Selah reproduce el audio de cada canción y la letra lo sigue sola.", c.sequences) { on -> m.update { it.copy(sequences = on) } }
                    FeatureSwitch("Partituras", "Cada músico ve en su celular la partitura de su instrumento de la canción en pantalla.", c.scores) { on -> m.update { it.copy(scores = on) } }
                    FeatureSwitch("Acordes", "Los músicos ven la letra con acordes en el celular y pueden cambiar el tono.", c.chords) { on -> m.update { it.copy(chords = on) } }
                    FeatureSwitch("Letra en el celular de la congregación", "Código QR en \"Conectar dispositivos\" para seguir la letra.", c.congregation) { on -> m.update { it.copy(congregation = on) } }

                    if (c.scores) { HorizontalDivider(); ScoresSection(m) }
                    if (c.sequences) { HorizontalDivider(); SequencesSection(m, devices) }
                    if (c.chords) {
                        HorizontalDivider()
                        Text("Acordes", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            "En el editor de la canción, escribe cada acorde entre corchetes justo antes de la sílaba: " +
                                "[G]Cuán grande es [C]Él. En el videobeam sale solo la letra; los músicos los ven en la app (pestaña Músicos) " +
                                "y en la vista de escenario.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
}

@Composable
private fun FeatureSwitch(title: String, detail: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(on, onChange, Modifier.hand())
    }
}

@Composable
private fun ScoresSection(m: Musicians) {
    var message by remember { mutableStateOf<String?>(null) }
    Text("Partituras", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Text(
        "PDF con el nombre de la canción y el instrumento: «Cuán grande es Él - Bajo.pdf» (o una carpeta por canción con " +
            "«Bajo.pdf», «Teclado.pdf»…). Sin instrumento («Cuán grande es Él.pdf») es para todos.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton({ m.music?.let { message = addFiles("Agregar partituras (PDF)", setOf("pdf")) { f -> it.addScores(f) } } }, Modifier.hand()) { Text("Agregar PDFs…") }
        TextButton({ m.music?.scoresDir?.let { d -> d.mkdirs(); runCatching { java.awt.Desktop.getDesktop().open(d) } } }, Modifier.hand()) { Text("Abrir carpeta") }
    }
    message?.let { Text(it, color = MaterialTheme.colorScheme.tertiary) }
    val bySong = m.catalog.scores.groupBy { it.songId }
    if (bySong.isEmpty()) Text("Todavía no hay partituras.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    bySong.forEach { (song, list) ->
        Text("• ${m.songTitle(song)}: " + list.joinToString(", ") { if (it.instrument == GENERAL_INSTRUMENT) "para todos" else it.instrument })
    }
    Orphans(m.catalog.scoreOrphans, "Estos PDF no corresponden a ninguna canción (revisa que el nombre sea igual al título):")
}

@Composable
private fun SequencesSection(m: Musicians, devices: List<String>) {
    var message by remember { mutableStateOf<String?>(null) }
    val c = m.church
    Text("Secuencias", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Text(
        "Un audio por canción, MP3 o WAV, con el mismo nombre de la canción: «Cuán grande es Él.mp3». Con la canción en " +
            "pantalla, «▶ Secuencia» la hace sonar. Para que la letra pase sola: graba los tiempos una vez (● Grabar tiempos) " +
            "mientras suena. Si la canción tiene «BPM:», los músicos también tienen el click en el celular.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton({ m.music?.let { message = addFiles("Agregar secuencias (MP3 o WAV)", MusicLibrary.SEQUENCE_EXTENSIONS) { f -> it.addSequences(f) } } }, Modifier.hand()) { Text("Agregar audios…") }
        TextButton({ m.music?.sequencesDir?.let { d -> d.mkdirs(); runCatching { java.awt.Desktop.getDesktop().open(d) } } }, Modifier.hand()) { Text("Abrir carpeta") }
    }
    message?.let { Text(it, color = MaterialTheme.colorScheme.tertiary) }

    // Salida de sonido: la tarjeta que va a la consola.
    var menu by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Salida de sonido:")
        Box {
            OutlinedButton({ menu = true }, Modifier.hand()) { Text(c.audioDevice ?: "La del sistema") }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text("La del sistema") }, { menu = false; m.update { it.copy(audioDevice = null) } })
                devices.forEach { d -> DropdownMenuItem({ Text(d) }, { menu = false; m.update { it.copy(audioDevice = d) } }) }
            }
        }
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(c.splitClick, { on -> m.update { it.copy(splitClick = on) } }, Modifier.hand())
            Column {
                Text("Click a la izquierda, música a la derecha", fontWeight = FontWeight.Bold)
                Text(
                    "Para la consola: el canal izquierdo (solo click, con un compás de cuenta) va a los audífonos de la banda y el " +
                        "derecho (la música) a los parlantes. Solo en canciones con «BPM:». Déjalo apagado si tu secuencia ya trae su click.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (m.catalog.sequences.isEmpty()) Text("Todavía no hay secuencias.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    m.catalog.sequences.forEach { (song, file) -> Text("• ${m.songTitle(song)}  ←  $file") }
    Orphans(m.catalog.sequenceOrphans, "Estos audios no corresponden a ninguna canción (revisa que el nombre sea igual al título):")
}

@Composable
private fun Orphans(files: List<String>, title: String) {
    if (files.isEmpty()) return
    Text(title, color = MaterialTheme.colorScheme.error)
    files.forEach { Text("   $it", color = MaterialTheme.colorScheme.error) }
}

/** Elegir archivos y copiarlos; devuelve el mensaje para mostrar. */
private fun addFiles(title: String, extensions: Set<String>, add: (List<File>) -> Int): String? {
    val dialog = java.awt.FileDialog(null as java.awt.Frame?, title, java.awt.FileDialog.LOAD).apply {
        isMultipleMode = true
        setFilenameFilter { _, name -> name.substringAfterLast('.', "").lowercase() in extensions }
        file = extensions.joinToString(";") { "*.$it" }
        isVisible = true
    }
    val files = dialog.files?.toList().orEmpty()
    if (files.isEmpty()) return null
    val n = runCatching { add(files) }.getOrElse { return "No pude agregar: ${it.message}" }
    return if (n == 0) "Esos archivos ya estaban" else "Agregados $n. Aparecen en unos segundos."
}
