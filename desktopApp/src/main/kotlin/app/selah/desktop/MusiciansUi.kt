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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import app.selah.shared.search
import java.io.File
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Lo que la ventana de operador necesita saber de los músicos (lo da Main). */
data class Musicians(
    val church: ChurchFeatures = ChurchFeatures(),
    val catalog: MusicCatalog = MusicCatalog(),
    val update: ((ChurchFeatures) -> ChurchFeatures) -> Unit = {},
    val songTitle: (String) -> String = { it },
    val music: MusicLibrary? = null,
    val open: () -> Unit = {},
    /** Abrir la ventana Equipo y cuántos celulares esperan que los aprueben. */
    val openTeam: () -> Unit = {},
    val teamWaiting: Int = 0,
    /** Canciones de la biblioteca (para asignarles un audio a mano). */
    val songs: List<app.selah.shared.SongSummary> = emptyList(),
    /** Elegir el audio de una canción (archivo, "ninguna", o null = el que coincida). Devuelve un error o null. */
    val chooseSequence: (songId: String, value: String?) -> String? = { _, _ -> null },
    /** Sugerir tiempos con la voz guía (null = no disponible). */
    val guide: GuideTimings? = null,
    /** Canciones con tiempos sugeridos que nadie ha revisado (ids). */
    val suggested: List<String> = emptyList(),
    val undoSuggestion: (songId: String) -> String? = { null },
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

/** Pestañas de la ventana: la de los interruptores y una por cada cosa encendida que tiene ajustes. */
enum class ChurchTab(val title: String) { FEATURES("Qué se usa"), SEQUENCES("Secuencias"), SCORES("Partituras"), CHORDS("Acordes") }

/** Las pestañas que se ven: lo apagado no tiene pestaña. */
fun churchTabs(c: ChurchFeatures): List<ChurchTab> = buildList {
    add(ChurchTab.FEATURES)
    if (c.sequences) add(ChurchTab.SEQUENCES)
    if (c.scores) add(ChurchTab.SCORES)
    if (c.chords) add(ChurchTab.CHORDS)
}

/** El contenido de la ventana (aparte, para poder dibujarlo en las pruebas). [start]: la pestaña abierta al comienzo. */
@Composable
fun MusiciansContent(m: Musicians, devices: List<String> = remember { JavaSoundSequenceOutput.devices() }, start: ChurchTab = ChurchTab.FEATURES) {
        MaterialTheme(colorScheme = darkColorScheme()) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                val c = m.church
                val tabs = churchTabs(c)
                var chosen by remember { mutableStateOf(start) }
                // Si se apaga lo que estaba abierto, se vuelve a la primera.
                val tab = chosen.takeIf { it in tabs } ?: ChurchTab.FEATURES
                Column(Modifier.fillMaxSize()) {
                    if (tabs.size > 1) TabRow(selectedTabIndex = tabs.indexOf(tab)) {
                        tabs.forEach { t ->
                            Tab(selected = t == tab, onClick = { chosen = t }, modifier = Modifier.hand()) {
                                Text(
                                    t.title, maxLines = 1, style = MaterialTheme.typography.labelLarge,
                                    color = if (t == tab) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 14.dp),
                                )
                            }
                        }
                    }
                    // Cada pestaña con su propio desplazamiento: al cambiar de pestaña se empieza arriba.
                    key(tab) {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            when (tab) {
                                ChurchTab.FEATURES -> FeaturesTab(m)
                                ChurchTab.SEQUENCES -> SequencesSection(m, devices)
                                ChurchTab.SCORES -> ScoresSection(m)
                                ChurchTab.CHORDS -> ChordsHelp()
                            }
                        }
                    }
                }
            }
        }
}

@Composable
private fun FeaturesTab(m: Musicians) {
    Text(
        "Letra y Biblia van siempre. Enciende solo lo que tu iglesia usa: lo apagado no aparece ni aquí ni en los celulares. " +
            "Lo que tiene ajustes aparece arriba como una pestaña.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val c = m.church
    FeatureSwitch("Click para los músicos", "Metrónomo en los celulares con audífonos (nunca en los parlantes) y letra que pasa sola.", c.click) { on -> m.update { it.copy(click = on) } }
    FeatureSwitch("Secuencias", "Selah reproduce el audio de cada canción y la letra lo sigue sola.", c.sequences) { on -> m.update { it.copy(sequences = on) } }
    FeatureSwitch("Partituras", "Cada músico ve en su celular la partitura de su instrumento de la canción en pantalla.", c.scores) { on -> m.update { it.copy(scores = on) } }
    FeatureSwitch("Acordes", "Los músicos ven la letra con acordes en el celular y pueden cambiar el tono.", c.chords) { on -> m.update { it.copy(chords = on) } }
    FeatureSwitch("Letra en el celular de la congregación", "Código QR en \"Conectar dispositivos\" para seguir la letra.", c.congregation) { on -> m.update { it.copy(congregation = on) } }
}

@Composable
private fun ChordsHelp() {
    Text(
        "En el editor de la canción, escribe cada acorde entre corchetes justo antes de la sílaba: " +
            "[G]Cuán grande es [C]Él. En el videobeam sale solo la letra; los músicos los ven en la app (pestaña Músicos) " +
            "y en la vista de escenario.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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
    Text(
        "Un audio por canción, MP3 o WAV, con el nombre de la canción (también sirven nombres como «Aclamad a Dios - 150 BPM - D.wav» " +
            "o «Salmo 23 No Bass.wav»). Con la canción en pantalla, «▶ Secuencia» la hace sonar. Si el audio trae el click a la " +
            "izquierda, Selah lo detecta y toma su tempo: los celulares suenan igual que los audífonos de la banda. Para que la " +
            "letra pase sola: graba los tiempos una vez (● Grabar tiempos) mientras suena.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton({ m.music?.let { message = addFiles("Agregar secuencias (MP3 o WAV)", MusicLibrary.SEQUENCE_EXTENSIONS) { f -> it.addSequences(f) } } }, Modifier.hand()) { Text("Agregar audios…") }
        TextButton({ m.music?.sequencesDir?.let { d -> d.mkdirs(); runCatching { java.awt.Desktop.getDesktop().open(d) } } }, Modifier.hand()) { Text("Abrir carpeta") }
    }
    message?.let { Text(it, color = MaterialTheme.colorScheme.tertiary) }

    // ¿Dónde suena? Si es en otro aparato, Selah solo sigue el tiempo (letra y partituras), sin sonido.
    Text("¿Dónde suena la secuencia?", fontWeight = FontWeight.Bold)
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.RadioButton(c.sequencePlays, { m.update { it.copy(sequencePlays = true) } }, Modifier.hand())
        Text("En este computador: Selah la reproduce")
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.RadioButton(!c.sequencePlays, { m.update { it.copy(sequencePlays = false) } }, Modifier.hand())
        Column {
            Text("En otro aparato (otro computador, celular, consola): Selah solo la sigue")
            Text(
                "Dale «▶ Secuencia» (aquí o en el celular del director) en el mismo momento en que arranca en el otro aparato. " +
                    "La letra y las partituras van solas; si se adelantan o atrasan, «◀ Antes / Después ▶» las corre.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (!c.sequencePlays) ListenSettings(m)
    if (c.sequencePlays) {
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
                        "derecho (la música) a los parlantes. Para audios que no traen click (y con tempo conocido); los que ya lo traen suenan tal cual.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    }
    var error by remember { mutableStateOf<String?>(null) }
    val choose: (String, String?) -> Unit = { id, v -> error = m.chooseSequence(id, v) }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    val cat = m.catalog
    if (cat.sequenceFiles.isEmpty()) Text("Todavía no hay secuencias.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (c.splitClick && cat.sequenceFiles.any { it.analysis?.clickLeft == true }) {
        Text(
            "Las secuencias que ya traen el click a la izquierda suenan tal cual (Selah no mezcla otro click encima).",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    GuideSection(m)

    // Las dudosas primero: se parecen a una canción solo por el comienzo del nombre.
    var dismissed by remember { mutableStateOf(setOf<String>()) }
    val doubtful = cat.toConfirm.filter { it.file !in dismissed }
    if (doubtful.isNotEmpty()) {
        Text("Revisa estas (${doubtful.size}): el nombre se parece a una canción, pero no igual", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.tertiary)
        doubtful.forEach { f ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("«${f.file}» ¿es «${m.songTitle(f.songId!!)}»?", Modifier.weight(1f))
                OutlinedButton({ choose(f.songId!!, f.file) }, Modifier.hand()) { Text("Sí") }
                TextButton({ dismissed = dismissed + f.file }, Modifier.hand()) { Text("No") }
            }
        }
    }

    // Cada canción con su audio, el tempo y si trae el click; si hay varias versiones, se elige.
    cat.sequences.entries.sortedBy { app.selah.shared.normalizeText(m.songTitle(it.key)) }.forEach { (song, file) ->
        val info = cat.info(file)
        val versions = cat.versionsOf(song)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Column(Modifier.weight(1f)) {
                Text(m.songTitle(song), fontWeight = FontWeight.Bold)
                Text(sequenceDetails(file, info), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            var menu by remember { mutableStateOf(false) }
            Box {
                TextButton({ menu = true }, Modifier.hand()) { Text(if (versions.size > 1) "Versión (${versions.size}) ▾" else "Cambiar ▾") }
                DropdownMenu(menu, { menu = false }) {
                    versions.forEach { v -> DropdownMenuItem({ Text((if (v.file == file) "✓ " else "") + v.file) }, { menu = false; choose(song, v.file) }) }
                    DropdownMenuItem({ Text("Ninguna (sin secuencia)") }, { menu = false; choose(song, MusicLibrary.NONE) })
                }
            }
        }
    }

    // Sin canción: asignarlos a mano.
    val orphans = cat.sequenceOrphans
    if (orphans.isNotEmpty()) {
        Text("Sin canción (${orphans.size}): asígnalos a mano, o agrega la canción a la biblioteca", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
        orphans.forEach { f -> AssignRow(f, m.songs) { id -> choose(id, f) } }
    }
}

/** Proponer los tiempos de la letra con la voz guía de las secuencias, y revisarlos. */
@Composable
private fun GuideSection(m: Musicians) {
    val guide = m.guide ?: return
    var status by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<GuideTimings.Result>>(emptyList()) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val pending = remember(m.catalog, m.suggested) { guide.pending() }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Tiempos con la voz guía", fontWeight = FontWeight.Bold)
            Text(
                "Selah escucha la voz guía de cada secuencia («Verso… 1, 2, 3, 4», «Coro», «Repetir», «Final») y propone el orden de " +
                    "las secciones y cuándo cambia cada diapositiva. Revísalo en un ensayo: si algo va mal, «● Grabar tiempos» lo corrige " +
                    "(y «Deshacer» vuelve todo como estaba). La primera vez descarga el reconocimiento de voz (40 MB).",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            androidx.compose.material3.Button({
                running = true; results = emptyList()
                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { guide.run(pending) { s -> status = s } }
                        .onSuccess { r -> results = r; status = "Listo: ${r.count { it.ok }} de ${r.size} con tiempos sugeridos. Revísalas en un ensayo." }
                        .onFailure { status = it.message ?: "No se pudo" }
                    running = false
                }
            }, Modifier.hand(), enabled = !running && pending.isNotEmpty()) {
                Text(if (pending.isEmpty()) "Todas las canciones con secuencia ya tienen tiempos" else "Sugerir tiempos (${pending.size} canciones sin tiempos)")
            }
            status?.let { Text(it, color = MaterialTheme.colorScheme.tertiary) }
            results.filter { !it.ok }.forEach { Text("✗ ${it.title}: ${it.message}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            if (m.suggested.isNotEmpty()) {
                Text("Sugeridas, sin revisar (${m.suggested.size}):", fontWeight = FontWeight.Bold)
                m.suggested.forEach { id ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("• ${m.songTitle(id)}", Modifier.weight(1f))
                        TextButton({ status = m.undoSuggestion(id) }, Modifier.hand()) { Text("Deshacer") }
                    }
                }
            }
        }
    }
}

/** Escuchar el click por una entrada de audio: arranca y se corrige solo. */
@Composable
private fun ListenSettings(m: Musicians) {
    val c = m.church
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(c.listenClick, { on -> m.update { it.copy(listenClick = on) } }, Modifier.hand())
                Column {
                    Text("Escuchar el click (arranca y se corrige solo)", fontWeight = FontWeight.Bold)
                    Text(
                        "Conecta el click que sale del otro aparato (o un envío de la consola) a una entrada de audio de este computador. " +
                            "Con «▶ Secuencia» Selah espera: cuando oye el click arranca solo, y mientras suena se mantiene alineado.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (c.listenClick) {
                val inputs = remember { LineClickInput.devices() }
                var menu by remember { mutableStateOf(false) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Entrada de audio:")
                    Box {
                        OutlinedButton({ menu = true }, Modifier.hand()) { Text(c.listenDevice ?: "La del sistema") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem({ Text("La del sistema") }, { menu = false; m.update { it.copy(listenDevice = null) } })
                            inputs.forEach { d -> DropdownMenuItem({ Text(d) }, { menu = false; m.update { it.copy(listenDevice = d) } }) }
                        }
                    }
                }
            }
        }
    }
}

/** "150 BPM · tono D · click a la izquierda · No Bass · archivo.wav" */
private fun sequenceDetails(file: String, info: SequenceFile?): String {
    val a = info?.analysis
    return listOfNotNull(
        (info?.name?.bpm ?: a?.bpm)?.let { "$it BPM" },
        info?.name?.key?.let { "tono $it" },
        when {
            a == null -> "escuchando…"
            a.clickLeft && a.tempoChanges -> "click a la izquierda, cambia de tempo (en los celulares no suena)"
            a.clickLeft -> "click a la izquierda" + (a.bpm?.let { m -> if (info.name.bpm != null && abs(m - info.name.bpm!!) > 2) " a $m" else "" } ?: "")
            else -> "sin click"
        },
        info?.name?.version?.takeIf { it.isNotEmpty() },
        file,
    ).joinToString(" · ")
}

/** Un audio sin canción: buscar la canción y asignárselo. */
@Composable
private fun AssignRow(file: String, songs: List<app.selah.shared.SongSummary>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf(app.selah.shared.parseSequenceName(file).title) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("   $file", Modifier.weight(1f))
            TextButton({ open = !open }, Modifier.hand()) { Text(if (open) "Cerrar" else "Asignar…") }
        }
        if (open) {
            androidx.compose.material3.OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text("Busca la canción") }, modifier = Modifier.fillMaxWidth().marksTyping())
            val hits = remember(query, songs) { songs.search(query).take(6) }
            if (hits.isEmpty()) Text("Ninguna canción con eso. Prueba con otra palabra.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            hits.forEach { s -> TextButton({ onPick(s.id); open = false }, Modifier.hand()) { Text("→ ${s.title}") } }
        }
    }
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
