package app.selah.desktop

import app.selah.shared.cleanListName
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.selah.shared.ClientMessage
import app.selah.shared.PresentationState
import app.selah.shared.search
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.material3.TabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.AlertDialog
import app.selah.shared.BibleReference
import app.selah.shared.BibleHit
import app.selah.shared.Bible
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import app.selah.shared.ServicePlanState
import app.selah.shared.PlanKind
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.PaddingValues
import kotlinx.coroutines.delay

@Composable
fun OperatorScreen(
    state: PresentationState,
    catalog: Catalog,
    plan: ServicePlanState,
    savedLists: List<String> = emptyList(),
    onDeleteSavedList: (String) -> Unit = {},
    serverError: String?,
    projector: ProjectorControl,
    onCommand: (ClientMessage) -> String?,
    onReload: () -> Unit,
    onOpenFolder: () -> Unit,
    onEdit: (songId: String?) -> Unit,
    onEditSermon: (String?) -> Unit = {},
    onOpenBackgrounds: () -> Unit,
    onOpenConnect: () -> Unit,
    onOpenHistory: () -> Unit = {},
    onOpenWelcome: () -> Unit = {},
    onOpenBackups: () -> Unit = {},
    /** Versión nueva disponible (null = ninguna). */
    updateVersion: String? = null,
    onOpenUpdate: () -> Unit = {},
    /** Última vez que se cantó cada canción (id → fecha). */
    lastSung: Map<String, java.time.LocalDate> = emptyMap(),
    searchBible: (String) -> Pair<List<BibleHit>, Int>?,
    /** Buscar canciones por título o letra. */
    searchSongs: (String) -> List<app.selah.shared.SongHit> = { emptyList() },
    bible: Bible? = null,
    onSelectBible: (String) -> Unit = {},
    onSelectSecondBible: (String?) -> Unit = {},
    /** Abrir la ventana para descargar o agregar Biblias. */
    onOpenBibles: () -> Unit = {},
    firewallOk: Boolean?,
    onImport: () -> String?,
    onExport: () -> String?,
    onAddAnnouncements: () -> String? = { null },
    onOpenAnnouncements: () -> Unit = {},
    onAddPresentations: () -> String? = { null },
    onOpenPresentations: () -> Unit = {},
    /** Cambia cuando termina de convertirse una presentación (para refrescar las miniaturas). */
    presentationsReady: Int = 0,
) {
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(message) { if (message != null) { delay(6000); message = null } }
    val send: (ClientMessage) -> Unit = { m -> message = onCommand(m) }
    var helpOpen by remember { mutableStateOf(false) }

    val focusManager = LocalFocusManager.current
    Surface(
        Modifier.fillMaxSize().onPreviewKeyEvent {
            // Esc saca el foco del campo de texto para que vuelvan a funcionar los atajos.
            if (it.type == KeyEventType.KeyDown && it.key == Key.Escape && Typing.active) {
                focusManager.clearFocus(); Typing.active = false; true
            } else false
        },
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(Modifier.fillMaxSize()) {
            TopBar(
                message = serverError ?: message,
                projector = projector,
                connectAttention = firewallOk == false,
                onOpenBackgrounds = onOpenBackgrounds,
                onOpenConnect = onOpenConnect,
                onOpenHistory = onOpenHistory,
                onOpenWelcome = onOpenWelcome,
                onOpenBackups = onOpenBackups,
                updateVersion = updateVersion,
                onOpenUpdate = onOpenUpdate,
                onHelp = { helpOpen = true },
            )
            HorizontalDivider()
            Row(Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LeftPanel(
                    catalog, lastSung, plan, savedLists, onDeleteSavedList, Modifier.width(350.dp).fillMaxHeight(), send, onReload, onOpenFolder, onEdit, searchBible, bible, onSelectBible, onSelectSecondBible,
                    onImport = { onImport()?.let { message = it } }, onExport = { onExport()?.let { message = it } },
                    onOpenBibles = onOpenBibles,
                    currentMedia = state.media,
                    onAddAnnouncements = { onAddAnnouncements()?.let { message = it } },
                    onOpenAnnouncements = onOpenAnnouncements,
                    currentPresentation = state.presentation,
                    onAddPresentations = { onAddPresentations()?.let { message = it } },
                    onOpenPresentations = onOpenPresentations,
                    presentationsReady = presentationsReady,
                    onEditSermon = onEditSermon,
                    searchSongs = searchSongs,
                )
                SlidesPanel(state, Modifier.weight(1f).fillMaxHeight(), send) { id -> if (state.sermon) onEditSermon(id) else onEdit(id) }
                LivePanel(state, Modifier.width(320.dp).fillMaxHeight(), send)
            }
        }
        if (helpOpen) {
            AlertDialog(
                onDismissRequest = { helpOpen = false },
                title = { Text("Atajos de teclado") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for ((keys, what) in SHORTCUTS) {
                            Row { Text(keys, fontWeight = FontWeight.Bold, modifier = Modifier.width(170.dp)); Text(what) }
                        }
                    }
                },
                confirmButton = { TextButton({ helpOpen = false }) { Text("Entendido") } },
                dismissButton = { TextButton({ helpOpen = false; onOpenWelcome() }) { Text("Ver la guía de inicio") } },
            )
        }
    }
}

/** Barra superior: lo que se usa de vez en cuando, fuera del camino durante el culto. */
@Composable
private fun TopBar(
    message: String?,
    projector: ProjectorControl,
    connectAttention: Boolean,
    onOpenBackgrounds: () -> Unit,
    onOpenConnect: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenWelcome: () -> Unit,
    onOpenBackups: () -> Unit,
    updateVersion: String?,
    onOpenUpdate: () -> Unit,
    onHelp: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Selah", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            message.orEmpty(),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
        )
        updateVersion?.let { v ->
            Button(onOpenUpdate, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)) {
                Text("Nueva versión $v")
            }
        }
        OutlinedButton(onOpenHistory) { Text("Historial") }
        OutlinedButton(onOpenBackgrounds) { Text("Fondos y letra") }
        OutlinedButton(onOpenConnect) {
            if (connectAttention) Text("● ", color = MaterialTheme.colorScheme.error)
            Text("Conectar dispositivos")
        }
        ProjectorMenu(projector)
        HelpMenu(onHelp, onOpenWelcome, onOpenBackups)
    }
}

/** Izquierda: Canciones · Biblia · Lista del culto, en pestañas. */
@Composable
private fun LeftPanel(
    catalog: Catalog,
    lastSung: Map<String, java.time.LocalDate>,
    plan: ServicePlanState,
    savedLists: List<String>,
    onDeleteSavedList: (String) -> Unit,
    modifier: Modifier,
    send: (ClientMessage) -> Unit,
    onReload: () -> Unit,
    onOpenFolder: () -> Unit,
    onEdit: (songId: String?) -> Unit,
    searchBible: (String) -> Pair<List<BibleHit>, Int>?,
    bible: Bible?,
    onSelectBible: (String) -> Unit,
    onSelectSecondBible: (String?) -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onOpenBibles: () -> Unit,
    currentMedia: String?,
    onAddAnnouncements: () -> Unit,
    onOpenAnnouncements: () -> Unit,
    currentPresentation: String?,
    onAddPresentations: () -> Unit,
    onOpenPresentations: () -> Unit,
    presentationsReady: Int,
    onEditSermon: (String?) -> Unit,
    searchSongs: (String) -> List<app.selah.shared.SongHit>,
) {
    var tab by remember { mutableIntStateOf(0) }
    val focusManager = LocalFocusManager.current
    // Al proyectar algo, el foco sale del campo para que las flechas vuelvan a mover la letra.
    val present: (ClientMessage) -> Unit = { m -> send(m); focusManager.clearFocus(); Typing.active = false }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TabRow(selectedTabIndex = tab) {
            val titles = listOf("Canciones", "Biblia", "Anuncios", "Lista" + if (plan.items.isNotEmpty()) " (${plan.items.size})" else "")
            titles.forEachIndexed { i, t ->
                // Contenido propio (sin el margen grande de Tab) para que quepan las 4 pestañas.
                Tab(selected = tab == i, onClick = { tab = i }, modifier = Modifier.hand()) {
                    Text(
                        t, maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelLarge,
                        color = if (tab == i) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 2.dp, vertical = 14.dp),
                    )
                }
            }
        }
        when (tab) {
            0 -> SongsTab(catalog, lastSung, Modifier.weight(1f), send, present, onReload, onOpenFolder, onEdit, onImport, onExport, onEditSermon, searchSongs)
            1 -> BibleTab(catalog, bible, onSelectBible, onSelectSecondBible, onOpenBibles, Modifier.weight(1f), send, present, searchBible)
            2 -> AnnouncementsTab(
                catalog.announcements, currentMedia, Modifier.weight(1f), send, present, onAddAnnouncements, onOpenAnnouncements,
                catalog.presentations, currentPresentation, onAddPresentations, onOpenPresentations, presentationsReady,
            )
            else -> PlanPanel(plan, savedLists, onDeleteSavedList, Modifier.weight(1f).fillMaxWidth(), send)
        }
    }
}

@Composable
private fun SongsTab(
    catalog: Catalog,
    lastSung: Map<String, java.time.LocalDate>,
    modifier: Modifier,
    send: (ClientMessage) -> Unit,
    present: (ClientMessage) -> Unit,
    onReload: () -> Unit,
    onOpenFolder: () -> Unit,
    onEdit: (songId: String?) -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onEditSermon: (String?) -> Unit,
    searchSongs: (String) -> List<app.selah.shared.SongHit>,
) {
    var query by remember { mutableStateOf("") }
    var sermons by remember { mutableStateOf(false) }
    // Canciones: por título o por cualquier frase de la letra. Predicaciones: por título.
    val results = remember(query, catalog, sermons) {
        if (sermons) catalog.sermons.search(query).map { app.selah.shared.SongHit(it.id, it.title, it.firstLine) }
        else if (query.isBlank()) catalog.songs.map { app.selah.shared.SongHit(it.id, it.title, it.firstLine) }
        else searchSongs(query)
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(!sermons, { sermons = false }, { Text("Canciones (${catalog.songs.size})") })
            FilterChip(sermons, { sermons = true }, { Text("Predicaciones (${catalog.sermons.size})") })
        }
        OutlinedTextField(
            query, { query = it },
            label = { Text(if (sermons) "Buscar predicación" else "Buscar por título o letra") },
            singleLine = true, modifier = Modifier.fillMaxWidth().marksTyping(),
        )
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(results, key = { it.id }) { song ->
                Row(
                    Modifier.fillMaxWidth().handClickable {
                        present(if (sermons) ClientMessage.ShowSermon(song.id) else ClientMessage.SelectSong(song.id))
                    }.padding(start = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                        Text(song.title, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        // "Cantada: hace 3 días" ayuda a no repetir
                        Text(
                            if (song.snippet.isNotEmpty()) "…${song.snippet}…"
                            else (if (sermons) "" else lastSung[song.id]?.let { "Cantada: ${formatAgo(it)} · " }.orEmpty()) + song.firstLine, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(
                        { send(if (sermons) ClientMessage.PlanAddSermon(song.id) else ClientMessage.PlanAddSong(song.id)) },
                        contentPadding = PaddingValues(6.dp),
                    ) { Text("+ Lista") }
                    TextButton({ if (sermons) onEditSermon(song.id) else onEdit(song.id) }, contentPadding = PaddingValues(6.dp)) { Text("Editar") }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (sermons) {
                Button({ onEditSermon(null) }, Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) { Text("+ Nueva predicación") }
            } else {
                Button({ onEdit(null) }, Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) { Text("+ Nueva") }
                OutlinedButton(onImport, Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) { Text("Importar…") }
                OutlinedButton(onExport, Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) { Text("Exportar…") }
            }
        }
        Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
            TextButton(onReload) { Text("Recargar") }
            TextButton(onOpenFolder) { Text("Abrir carpeta") }
        }
    }
}

@Composable
private fun BibleTab(
    catalog: Catalog,
    bible: Bible?,
    onSelectBible: (String) -> Unit,
    onSelectSecondBible: (String?) -> Unit,
    onOpenBibles: () -> Unit,
    modifier: Modifier,
    send: (ClientMessage) -> Unit,
    present: (ClientMessage) -> Unit,
    searchBible: (String) -> Pair<List<BibleHit>, Int>?,
) {
    var reference by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<Pair<List<BibleHit>, Int>?>(null) }
    val showOrSearch = {
        if (BibleReference.parseAll(reference) != null) present(ClientMessage.ShowBible(reference))
        else hits = searchBible(reference)
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BibleChooser(catalog, onSelectBible, onSelectSecondBible, onOpenBibles)
        OutlinedTextField(
            reference, { reference = it },
            label = { Text("Cita o frase (ej. Juan 3:16 · amó Dios)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().marksTyping(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { showOrSearch() }),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button({ present(ClientMessage.ShowBible(reference)) }, Modifier.weight(1f), enabled = reference.isNotBlank(), contentPadding = PaddingValues(6.dp)) {
                Text("Mostrar")
            }
            OutlinedButton({ hits = searchBible(reference) }, Modifier.weight(1f), enabled = reference.isNotBlank(), contentPadding = PaddingValues(6.dp)) {
                Text("Buscar frase")
            }
            OutlinedButton({ send(ClientMessage.PlanAddBible(reference)) }, Modifier.weight(1f), enabled = reference.isNotBlank(), contentPadding = PaddingValues(6.dp)) {
                Text("+ Lista")
            }
        }
        hits?.let { (list, total) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (total == 0) "Sin resultados" else "$total resultados" + if (total > list.size) " (primeros ${list.size})" else "",
                    style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                TextButton({ hits = null }, contentPadding = PaddingValues(6.dp)) { Text("✕ Ver libros") }
            }
        }
        if (hits == null && bible != null) {
            BibleBrowser(bible, Modifier.weight(1f).fillMaxWidth(), send, present)
        } else LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(hits?.first.orEmpty(), key = { it.reference }) { hit ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).handClickable { present(ClientMessage.ShowBible(hit.reference)) }.padding(vertical = 6.dp, horizontal = 4.dp)) {
                        Text(hit.reference, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
                        Text(hit.text, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton({ send(ClientMessage.PlanAddBible(hit.reference)) }, contentPadding = PaddingValues(6.dp)) { Text("+ Lista") }
                }
            }
        }
        if (hits == null && bible == null) {
            Text(
                "Escribe una cita (Juan 3:16, Sal 23, 1 Co 13:4-7) y presiona Enter para mostrarla, o unas palabras para buscarlas en toda la Biblia.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SlidesPanel(
    state: PresentationState,
    modifier: Modifier,
    send: (ClientMessage) -> Unit,
    onEdit: (songId: String?) -> Unit,
) {
    val listState = rememberLazyListState()
    val look = LocalLook.current
    LaunchedEffect(state.index, state.title) { if (state.index >= 0) listState.animateScrollToItem(state.index) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                state.title.ifEmpty { "Elige una canción o una cita" },
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
            state.songId?.let { id -> OutlinedButton({ onEdit(id) }) { Text(if (state.sermon) "Editar predicación" else "Editar canción") } }
        }
        SectionJumps(state, send)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(state.slides) { i, slide ->
                val selected = i == state.index
                Card(
                    Modifier.fillMaxWidth().handClickable { send(ClientMessage.GoTo(i)) },
                    colors = CardDefaults.cardColors(
                        containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    ),
                    border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                ) {
                    val presentation = state.presentation
                    if (presentation != null) {
                        // Presentación: la miniatura de cada diapositiva
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            val thumb = rememberBackground(remember(presentation, i) { look.presentationThumb(presentation, i) })
                            Box(Modifier.width(192.dp).aspectRatio(16f / 9f).background(Color.Black), contentAlignment = Alignment.Center) {
                                if (thumb != null) Image(thumb, contentDescription = slide.text, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                            }
                            Text(slide.text, style = MaterialTheme.typography.bodyLarge)
                        }
                    } else Column(Modifier.padding(14.dp)) {
                        if (slide.label.isNotEmpty()) {
                            Text(slide.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        }
                        Text(slide.text, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}

/** Derecha: solo lo "en vivo". Los avisos van plegados para no estorbar. */
@Composable
private fun LivePanel(state: PresentationState, modifier: Modifier, send: (ClientMessage) -> Unit) {
    var avisosOpen by remember { mutableStateOf(false) }
    val avisosActivos = listOfNotNull(
        "alerta".takeIf { state.alert.isNotEmpty() },
        "cuenta regresiva".takeIf { state.countdownEnd != null },
        "mensaje".takeIf { state.stageMessage.isNotEmpty() },
        "tiempo del predicador".takeIf { state.timerStart != null },
        "nombre en OBS".takeIf { state.lowerThird.isNotEmpty() },
    )
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("En pantalla", style = MaterialTheme.typography.titleMedium)
        Surface(Modifier.fillMaxWidth().aspectRatio(16f / 9f), shape = MaterialTheme.shapes.small) {
            ProjectorScreen(state, look = LocalLook.current.copy(preview = true))
        }
        state.next?.let { next ->
            Column {
                Text("SIGUE" + if (next.label.isNotEmpty() && !state.isBible) " · ${next.label}" else "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(next.text, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ send(ClientMessage.Prev) }, Modifier.weight(1f).height(56.dp)) { Text("◀", style = MaterialTheme.typography.titleLarge) }
            Button({ send(ClientMessage.Next) }, Modifier.weight(2f).height(56.dp)) { Text("Siguiente ▶", style = MaterialTheme.typography.titleMedium) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ send(ClientMessage.SetBlank(!state.blank)) }, Modifier.weight(1f)) {
                Text(if (state.blank) "Mostrar" else "Negro (B)")
            }
            OutlinedButton({ send(ClientMessage.ShowLogo(!state.logo)) }, Modifier.weight(1f)) {
                Text(if (state.logo) "Quitar logo" else "Logo (L)")
            }
        }
        HorizontalDivider()
        Row(
            Modifier.fillMaxWidth().handClickable { avisosOpen = !avisosOpen }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (avisosOpen) "▾ " else "▸ ", style = MaterialTheme.typography.titleMedium)
            Text("Avisos", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (avisosActivos.isNotEmpty()) {
                Text("● " + avisosActivos.joinToString(", "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
        if (avisosOpen) {
            AlertBox(state.alert, send)
            HorizontalDivider()
            CountdownBox(state, send)
            HorizontalDivider()
            PreacherTimerBox(state, send)
            HorizontalDivider()
            LowerThirdBox(state, send)
            HorizontalDivider()
            StageMessageBox(state.stageMessage, send)
        }
    }
}

/** Botones para saltar a una sección (Coro, Pregón 1…). Se resalta la sección actual. */
@Composable
private fun SectionJumps(state: PresentationState, send: (ClientMessage) -> Unit) {
    val labels = state.sectionLabels
    if (labels.isEmpty()) return
    val current = state.currentLabel
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(labels) { label ->
            FilterChip(
                selected = label == current,
                onClick = { state.jumpTarget(label)?.let { send(ClientMessage.GoTo(it)) } },
                label = { Text(label) },
            )
        }
    }
}

@Composable
private fun PlanPanel(
    plan: ServicePlanState,
    savedLists: List<String>,
    onDeleteSaved: (String) -> Unit,
    modifier: Modifier,
    send: (ClientMessage) -> Unit,
) {
    var saving by remember { mutableStateOf(false) }
    var opening by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Orden del culto", style = MaterialTheme.typography.titleSmall)
                if (plan.name.isNotEmpty()) {
                    Text(plan.name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (plan.items.isNotEmpty()) TextButton({ send(ClientMessage.PlanClear) }) { Text("Vaciar") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton({ saving = true }, Modifier.weight(1f), enabled = plan.items.isNotEmpty(), contentPadding = PaddingValues(6.dp)) { Text("Guardar…") }
            OutlinedButton({ opening = true }, Modifier.weight(1f), enabled = savedLists.isNotEmpty(), contentPadding = PaddingValues(6.dp)) {
                Text("Abrir… (${savedLists.size})")
            }
        }
        if (saving) SavePlanDialog(plan.name.ifEmpty { suggestedListName() }, savedLists, { saving = false }) { name -> saving = false; send(ClientMessage.PlanSave(name)) }
        if (opening) OpenPlanDialog(savedLists, plan.items.isNotEmpty(), onDeleteSaved, { opening = false }) { name -> opening = false; send(ClientMessage.PlanOpen(name)) }
        Button({ send(ClientMessage.PlanNext) }, Modifier.fillMaxWidth(), enabled = plan.current < plan.items.lastIndex) {
            Text("Siguiente elemento ▶")
        }
        if (plan.items.isEmpty()) {
            Text(
                "Vacía. Usa \"+ Lista\" en las canciones o \"+ A la lista\" en la Biblia para preparar el culto.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(plan.items) { i, item ->
                val selected = i == plan.current
                Card(
                    Modifier.fillMaxWidth().handClickable { send(ClientMessage.PlanGo(i)) },
                    colors = CardDefaults.cardColors(
                        containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Row(Modifier.padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                            Text("${i + 1}. ${item.title}", maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                            Text(
                                when (item.kind) { PlanKind.BIBLE -> "Lectura"; PlanKind.MEDIA -> "Anuncio"; PlanKind.SERMON -> "Predicación"; PlanKind.PRESENTATION -> "Presentación"; else -> "Canción" },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton({ send(ClientMessage.PlanMove(i, i - 1)) }, enabled = i > 0, contentPadding = PaddingValues(4.dp)) { Text("▲") }
                        TextButton({ send(ClientMessage.PlanMove(i, i + 1)) }, enabled = i < plan.items.lastIndex, contentPadding = PaddingValues(4.dp)) { Text("▼") }
                        TextButton({ send(ClientMessage.PlanRemove(i)) }, contentPadding = PaddingValues(4.dp)) { Text("✕") }
                    }
                }
            }
        }
    }
}

/** Mensaje para los músicos en la vista de escenario. */
@Composable
private fun StageMessageBox(current: String, send: (ClientMessage) -> Unit) {
    var text by remember { mutableStateOf("") }
    Text("Mensaje al escenario", style = MaterialTheme.typography.titleSmall)
    OutlinedTextField(
        text, { text = it },
        placeholder = { Text("Ej. Repetir coro") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().marksTyping(),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { send(ClientMessage.StageMessage(text)) }),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button({ send(ClientMessage.StageMessage(text)) }, Modifier.weight(1f)) { Text("Enviar") }
        OutlinedButton({ text = ""; send(ClientMessage.StageMessage("")) }, enabled = current.isNotEmpty()) { Text("Borrar") }
    }
    if (current.isNotEmpty()) Text("En escenario: $current", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
}

/** Aviso en una franja abajo del videobeam (ej. "Se busca al dueño del carro ABC123"). */
@Composable
private fun AlertBox(current: String, send: (ClientMessage) -> Unit) {
    var text by remember { mutableStateOf("") }
    Text("Alerta en pantalla", style = MaterialTheme.typography.titleSmall)
    OutlinedTextField(
        text, { text = it },
        placeholder = { Text("Ej. Se busca al dueño del carro ABC123") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().marksTyping(),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { send(ClientMessage.SetAlert(text)) }),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button({ send(ClientMessage.SetAlert(text)) }, Modifier.weight(1f), enabled = text.isNotBlank()) { Text("Mostrar") }
        OutlinedButton({ send(ClientMessage.SetAlert("")) }, enabled = current.isNotEmpty()) { Text("Quitar") }
    }
    if (current.isNotEmpty()) Text("En pantalla: $current", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
}

/** Cuenta regresiva en el videobeam (ej. "Comenzamos en 05:00"). */
@Composable
private fun CountdownBox(state: PresentationState, send: (ClientMessage) -> Unit) {
    var minutes by remember { mutableStateOf("5") }
    var text by remember { mutableStateOf("Comenzamos en") }
    val running = state.countdownEnd != null && (state.countdownEnd ?: 0) > System.currentTimeMillis()
    Text("Cuenta regresiva", style = MaterialTheme.typography.titleSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            minutes, { v -> minutes = v.filter { it.isDigit() }.take(3) },
            label = { Text("Min.") }, singleLine = true, modifier = Modifier.width(80.dp).marksTyping(),
        )
        OutlinedTextField(text, { text = it }, label = { Text("Texto") }, singleLine = true, modifier = Modifier.weight(1f).marksTyping())
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            { send(ClientMessage.StartCountdown((minutes.toIntOrNull() ?: 0) * 60, text)) },
            Modifier.weight(1f),
            enabled = (minutes.toIntOrNull() ?: 0) > 0,
        ) { Text(if (running) "Reiniciar" else "Iniciar") }
        OutlinedButton({ send(ClientMessage.StopCountdown) }, enabled = state.countdownEnd != null) { Text("Detener") }
    }
}

/** Lo que el operador necesita para elegir y encender/apagar la proyección. */
data class ProjectorControl(
    val on: Boolean,
    val screens: List<ScreenInfo>,
    /** Pantalla en uso; null = en una ventana. */
    val current: ScreenInfo?,
    val onTurnOn: () -> Unit,
    val onTurnOff: () -> Unit,
    /** id de pantalla, o Settings.WINDOWED. */
    val onChoose: (String) -> Unit,
)

@Composable
private fun ProjectorMenu(p: ProjectorControl) {
    var open by remember { mutableStateOf(false) }
    Box {
        if (p.on) {
            FilterChip(
                selected = true,
                onClick = { open = true },
                label = { Text("Proyección: " + (p.current?.let { "Pantalla ${it.number}" } ?: "en ventana") + "  ▾") },
            )
        } else {
            Button(
                p.onTurnOn,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) { Text("Proyección apagada · Encender (F5)") }
        }
        DropdownMenu(open, { open = false }) {
            Text(
                "¿Dónde se proyecta?", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            for (screen in p.screens) {
                DropdownMenuItem(
                    text = { Text((if (screen == p.current) "✓ " else "    ") + screen.label) },
                    onClick = { open = false; p.onChoose(screen.id) },
                    modifier = Modifier.hand(),
                )
            }
            DropdownMenuItem(
                text = { Text((if (p.current == null) "✓ " else "    ") + "En una ventana (para probar)") },
                onClick = { open = false; p.onChoose(Settings.WINDOWED) },
                modifier = Modifier.hand(),
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Apagar proyección", color = MaterialTheme.colorScheme.error) },
                onClick = { open = false; p.onTurnOff() },
                modifier = Modifier.hand(),
            )
        }
    }
}

/** Traducción en uso y la segunda (opcional), con menús para cambiarlas, y los créditos que pide la licencia. */
@Composable
private fun BibleChooser(catalog: Catalog, onSelect: (String) -> Unit, onSelectSecond: (String?) -> Unit, onOpenBibles: () -> Unit) {
    Column {
        // Si los nombres son largos, la segunda traducción baja a otra línea en vez de cortarse.
        androidx.compose.foundation.layout.FlowRow(itemVerticalAlignment = Alignment.CenterVertically) {
            ChoiceMenu("Traducción", catalog.bibleName ?: "ninguna", catalog.bibleNames, catalog.bibleName) { it?.let(onSelect) }
            if (catalog.bibleNames.size > 1) {
                ChoiceMenu(
                    "+ segunda", catalog.secondBibleName ?: "ninguna",
                    listOf<String?>(null) + catalog.bibleNames.filter { it != catalog.bibleName }, catalog.secondBibleName,
                ) { onSelectSecond(it) }
            }
        }
        // En su propia línea: junto a las dos traducciones no cabía (en Windows salía letra por letra).
        TextButton(onOpenBibles, Modifier.hand(), contentPadding = PaddingValues(horizontal = 6.dp)) {
            Text("＋ Más Biblias…", style = MaterialTheme.typography.bodySmall, maxLines = 1, softWrap = false)
        }
        (catalog.bibleError ?: listOfNotNull(Library.BIBLE_NOTICES[catalog.bibleName], Library.BIBLE_NOTICES[catalog.secondBibleName]).distinct().joinToString(" · ").ifEmpty { null })?.let {
            Text(
                it, style = MaterialTheme.typography.labelSmall,
                color = if (catalog.bibleError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

@Composable
private fun ChoiceMenu(title: String, shown: String, options: List<String?>, current: String?, onPick: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton({ open = true }, enabled = options.size > 1, contentPadding = PaddingValues(horizontal = 4.dp)) {
            Text("$title: $shown" + if (options.size > 1) " ▾" else "", style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }
        DropdownMenu(open, { open = false }) {
            for (o in options) {
                DropdownMenuItem(
                    text = { Text((if (o == current) "✓ " else "    ") + (o ?: "Ninguna (solo una)")) },
                    onClick = { open = false; if (o != current) onPick(o) },
                    modifier = Modifier.hand(),
                )
            }
        }
    }
}

@Composable
private fun SavePlanDialog(initial: String, saved: List<String>, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    val clean = cleanListName(name)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Guardar la lista del culto") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Nombre") }, singleLine = true, modifier = Modifier.fillMaxWidth().marksTyping())
                if (clean != null && clean in saved) {
                    Text("Ya hay una lista con ese nombre: se reemplaza.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                }
            }
        },
        confirmButton = { Button({ clean?.let(onSave) }, enabled = clean != null) { Text("Guardar") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancelar") } },
    )
}

@Composable
private fun OpenPlanDialog(
    saved: List<String>,
    replacesCurrent: Boolean,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
    onOpen: (String) -> Unit,
) {
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Abrir una lista guardada") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (replacesCurrent) {
                    Text("La lista actual se reemplaza (guárdala antes si la quieres conservar).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(saved, key = { it }) { name ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(name, Modifier.weight(1f).handClickable { onOpen(name) }.padding(vertical = 10.dp, horizontal = 4.dp))
                            if (confirmDelete == name) {
                                TextButton({ confirmDelete = null; onDelete(name) }) { Text("Eliminar", color = MaterialTheme.colorScheme.error) }
                            } else {
                                TextButton({ confirmDelete = name }, contentPadding = PaddingValues(4.dp)) { Text("✕") }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Cerrar") } },
    )
}

/** "?": atajos, la guía de inicio y los respaldos. */
@Composable
private fun HelpMenu(onShortcuts: () -> Unit, onWelcome: () -> Unit, onBackups: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton({ open = true }) { Text("?", style = MaterialTheme.typography.titleMedium) }
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem({ Text("Atajos de teclado") }, { open = false; onShortcuts() }, Modifier.hand())
            DropdownMenuItem({ Text("Guía de inicio") }, { open = false; onWelcome() }, Modifier.hand())
            DropdownMenuItem({ Text("Respaldos…") }, { open = false; onBackups() }, Modifier.hand())
            DropdownMenuItem(
                { Text("Ver el registro (si algo falló)") },
                { open = false; Log.current?.takeIf { it.isFile }?.let { f -> runCatching { java.awt.Desktop.getDesktop().open(f) } } },
                Modifier.hand(),
            )
            HorizontalDivider()
            Text("Selah ${Updater.currentVersion}" + if (Updater.fromStore) " · Microsoft Store" else "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
        }
    }
}

/** Tiempo del predicador: lo ve solo él (y los músicos) en la vista de escenario. */
@Composable
private fun PreacherTimerBox(state: PresentationState, send: (ClientMessage) -> Unit) {
    var minutes by remember { mutableStateOf("30") }
    val now by produceState(System.currentTimeMillis(), state.timerStart) {
        while (state.timerStart != null) { value = System.currentTimeMillis(); kotlinx.coroutines.delay(500) }
    }
    Text("Tiempo del predicador", style = MaterialTheme.typography.titleSmall)
    Text("Se ve solo en la vista de escenario, no en el videobeam.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            minutes, { v -> minutes = v.filter { it.isDigit() }.take(3) },
            label = { Text("Min.") }, singleLine = true, modifier = Modifier.width(80.dp).marksTyping(),
        )
        Button({ send(ClientMessage.StartTimer(minutes.toIntOrNull() ?: 0)) }, Modifier.weight(1f)) {
            Text(if (state.timerStart != null) "Reiniciar" else "Iniciar")
        }
        OutlinedButton({ send(ClientMessage.StopTimer) }, enabled = state.timerStart != null) { Text("Detener") }
    }
    state.timerElapsed(now)?.let { elapsed ->
        val left = state.timerLeft(now)
        Text(
            "Lleva ${app.selah.shared.formatClock(elapsed)}" + when {
                left == null -> ""
                left >= 0 -> " · quedan ${app.selah.shared.formatClock(left)}"
                else -> " · se pasó ${app.selah.shared.formatClock(-left)}"
            },
            color = if (left != null && left < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
    }
}

/** Nombre en la transmisión (OBS): "Pastor Juan Pérez · Predicación". No sale en el videobeam. */
@Composable
private fun LowerThirdBox(state: PresentationState, send: (ClientMessage) -> Unit) {
    var title by remember { mutableStateOf("") }
    var subtitle by remember { mutableStateOf("") }
    Text("Nombre en la transmisión (OBS)", style = MaterialTheme.typography.titleSmall)
    Text("Sale solo en OBS, no en el videobeam.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedTextField(title, { title = it }, label = { Text("Nombre (ej. Pastor Juan Pérez)") }, singleLine = true, modifier = Modifier.fillMaxWidth().marksTyping())
    OutlinedTextField(subtitle, { subtitle = it }, label = { Text("Debajo (ej. Predicación)") }, singleLine = true, modifier = Modifier.fillMaxWidth().marksTyping())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button({ send(ClientMessage.SetLowerThird(title, subtitle)) }, Modifier.weight(1f), enabled = title.isNotBlank()) { Text("Mostrar") }
        OutlinedButton({ send(ClientMessage.SetLowerThird("", "")) }, enabled = state.lowerThird.isNotEmpty()) { Text("Quitar") }
    }
    if (state.lowerThird.isNotEmpty()) {
        Text("En OBS: ${state.lowerThird}" + if (state.lowerThirdSub.isNotEmpty()) " · ${state.lowerThirdSub}" else "", color = MaterialTheme.colorScheme.primary)
    }
}
