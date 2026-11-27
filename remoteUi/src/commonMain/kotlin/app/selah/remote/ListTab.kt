package app.selah.remote

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.selah.shared.ClientMessage
import app.selah.shared.PlanItem
import app.selah.shared.PlanKind
import app.selah.shared.PresentationState
import app.selah.shared.ScoreRef
import app.selah.shared.ServerMessage
import app.selah.shared.ServicePlanState
import app.selah.shared.WeekService
import app.selah.shared.kindLabel
import app.selah.shared.showMessage

/** Qué tiene abierto la pestaña Lista: la semana, un culto, o una canción de ese culto. */
private const val NO_SONG = -1

/**
 * Pestaña Lista: el culto en curso (compacto, con "Siguiente"), los cultos de la semana y, al entrar a uno, su orden.
 * Tocar una canción la abre para mirarla (partitura, acordes o letra) SIN cambiar el videobeam; "Proyectar esta" la pone.
 */
@Composable
internal fun ListTab(
    client: RemoteClient,
    plan: ServicePlanState,
    saved: List<String>,
    stageMessage: String,
    send: (ClientMessage) -> Unit,
    todayService: Pair<String, String?>,
    week: List<WeekService>,
    features: List<String>,
    scores: List<ScoreRef>,
    instrument: String?,
    onInstrument: (String?) -> Unit,
    prefs: KeyValueStore?,
    songView: ServerMessage.SongView?,
    onView: ((String) -> Unit)?,
    onPlayed: () -> Unit,
) {
    var openKey by rememberSaveable { mutableStateOf<String?>(null) }
    var songIndex by rememberSaveable { mutableStateOf(NO_SONG) }
    val open = week.firstOrNull { it.key == openKey }
    val song = open?.items?.getOrNull(songIndex)?.takeIf { it.kind == PlanKind.SONG }
    when {
        open != null && song != null && onView != null -> SongFromService(
            client, open, song, features, scores, instrument, onInstrument, prefs, songView, onView,
            onBack = { songIndex = NO_SONG },
            onProject = { send(song.showMessage()); onPlayed() },
        )
        open != null -> ServiceDetail(
            open, isOpen = open.list != null && open.list.equals(plan.name, ignoreCase = true), canView = onView != null,
            onBack = { openKey = null; songIndex = NO_SONG },
            onSong = { songIndex = it },
            onProject = { item -> send(item.showMessage()); onPlayed() },
            onOpenAll = { open.list?.let { send(ClientMessage.PlanOpen(it)) } },
        )
        else -> WeekOverview(plan, saved, stageMessage, send, todayService, week, onService = { openKey = it.key; songIndex = NO_SONG }, onPlayed = onPlayed)
    }
}

private val WeekService.key get() = "$date|$name"

@Composable
private fun WeekOverview(
    plan: ServicePlanState,
    saved: List<String>,
    stageMessage: String,
    send: (ClientMessage) -> Unit,
    todayService: Pair<String, String?>,
    week: List<WeekService>,
    onService: (WeekService) -> Unit,
    onPlayed: () -> Unit,
) {
    var msg by rememberSaveable { mutableStateOf("") }
    var saving by rememberSaveable { mutableStateOf(false) }
    var opening by rememberSaveable { mutableStateOf(false) }
    var showOrder by rememberSaveable { mutableStateOf(false) }
    if (saving) SavePlanDialog(plan.name.ifEmpty { suggestedListName() }, { saving = false }) { name -> saving = false; send(ClientMessage.PlanSave(name)) }
    if (opening) OpenPlanDialog(saved, { opening = false }) { name -> opening = false; send(ClientMessage.PlanOpen(name)) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // El culto abierto: compacto; el orden completo solo si se pide.
        if (plan.items.isNotEmpty()) item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("En curso", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(plan.name.ifEmpty { "Lista sin guardar" }, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    plan.items.getOrNull(plan.current)?.let { Text("Ahora: ${plan.current + 1}. ${it.title}", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    plan.items.getOrNull(plan.current + 1)?.let {
                        Text("Sigue: ${plan.current + 2}. ${it.title}", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Button({ send(ClientMessage.PlanNext); onPlayed() }, Modifier.fillMaxWidth(), enabled = plan.current < plan.items.lastIndex) {
                        Text(if (plan.current < 0) "Empezar ▶" else "Siguiente ▶")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton({ showOrder = !showOrder }) { Text(if (showOrder) "Ocultar el orden" else "Ver el orden (${plan.items.size})") }
                        TextButton({ saving = true }) { Text("Guardar") }
                    }
                }
            }
        }
        if (plan.items.isNotEmpty() && showOrder) itemsIndexed(plan.items) { i, item ->
            val selected = i == plan.current
            Card(
                Modifier.fillMaxWidth().clickable { send(ClientMessage.PlanGo(i)); onPlayed() },
                colors = CardDefaults.cardColors(
                    containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                        Text("${i + 1}. ${item.title}", fontSize = 17.sp, fontWeight = FontWeight.Medium)
                        Text(item.kindLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton({ send(ClientMessage.PlanMove(i, i - 1)) }, enabled = i > 0) { Text("▲") }
                    TextButton({ send(ClientMessage.PlanMove(i, i + 1)) }, enabled = i < plan.items.lastIndex) { Text("▼") }
                    TextButton({ send(ClientMessage.PlanRemove(i)) }) { Text("✕") }
                }
            }
        }

        // Esta semana (PC nuevo); con un PC viejo, el aviso de hoy como antes.
        if (week.isNotEmpty()) {
            item { Text("Esta semana", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
            for (w in week) item(key = w.key) { WeekRow(w, isOpen = w.list != null && w.list.equals(plan.name, ignoreCase = true)) { onService(w) } }
        } else {
            val (service, list) = todayService
            if (service.isNotEmpty()) item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Hoy: $service", fontWeight = FontWeight.Medium)
                        if (list != null && list != plan.name) Button({ send(ClientMessage.PlanOpen(list)) }, Modifier.fillMaxWidth()) { Text("Abrir «$list» (le toca hoy)") }
                    }
                }
            }
        }
        item {
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ opening = true }, Modifier.weight(1f), enabled = saved.isNotEmpty()) { Text("Otras listas (${saved.size})") }
            }
            if (plan.items.isEmpty() && week.isEmpty()) Text(
                "Vacía. Agrega canciones con \"+\" o citas desde Biblia.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        item {
            Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Mensaje al escenario", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    msg, { msg = it },
                    placeholder = { Text("Ej. Repetir coro · 2 minutos") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send(ClientMessage.StageMessage(msg)) }),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ send(ClientMessage.StageMessage(msg)) }, Modifier.weight(1f)) { Text("Enviar") }
                    OutlinedButton({ msg = ""; send(ClientMessage.StageMessage("")) }) { Text("Borrar") }
                }
                if (stageMessage.isNotEmpty()) Text("En escenario: $stageMessage", color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/** Un culto de la semana en la lista: nombre, día, cuántas canciones; se toca para entrar. */
@Composable
private fun WeekRow(w: WeekService, isOpen: Boolean, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = if (w.today) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text((if (w.today) "Hoy · " else "") + w.name, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(w.day + if (w.time.isBlank()) "" else " · ${w.time}", fontSize = 14.sp)
                Text(
                    when {
                        w.list == null -> "Sin planear"
                        isOpen -> "Abierto ahora"
                        else -> "${w.items.count { it.kind == PlanKind.SONG }} canciones"
                    },
                    fontSize = 13.sp,
                    color = if (w.list == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                )
            }
            Text("›", fontSize = 24.sp)
        }
    }
}

/** Un culto por dentro: su orden. Cada canción se abre para mirarla; "Proyectar" la pone en pantalla. */
@Composable
private fun ServiceDetail(
    w: WeekService,
    isOpen: Boolean,
    canView: Boolean,
    onBack: () -> Unit,
    onSong: (Int) -> Unit,
    onProject: (PlanItem) -> Unit,
    onOpenAll: () -> Unit,
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            TextButton(onBack) { Text("‹ Esta semana") }
            Text((if (w.today) "Hoy · " else "") + w.name, style = MaterialTheme.typography.titleLarge)
            Text(w.day + if (w.time.isBlank()) "" else " · ${w.time}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (w.list != null && !isOpen) Button(onOpenAll, Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Abrir todo el culto") }
            else if (isOpen) Text("Este culto está abierto (En curso).", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
            if (w.list == null) Text("Todavía no tiene canciones: se eligen en el computador (pestaña Lista).", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            else if (canView) Text("Toca una canción para ver su partitura o letra sin ponerla en pantalla.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        }
        itemsIndexed(w.items) { i, item ->
            val isSong = item.kind == PlanKind.SONG
            Card(
                Modifier.fillMaxWidth().clickable(enabled = isSong && canView) { onSong(i) },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                        Text("${i + 1}. ${item.title}", fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(item.kindLabel, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton({ onProject(item) }) { Text("Proyectar") }
                }
            }
        }
    }
}

/** Una canción del culto para mirar (la misma vista del músico) con "Proyectar esta". */
@Composable
private fun SongFromService(
    client: RemoteClient,
    w: WeekService,
    item: PlanItem,
    features: List<String>,
    scores: List<ScoreRef>,
    instrument: String?,
    onInstrument: (String?) -> Unit,
    prefs: KeyValueStore?,
    songView: ServerMessage.SongView?,
    onView: (String) -> Unit,
    onBack: () -> Unit,
    onProject: () -> Unit,
) {
    LaunchedEffect(item.ref, songView == null) { if (songView?.id != item.ref) onView(item.ref) }
    val shown = musicianShown(PresentationState(), item.ref, songView)
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onBack) { Text("‹ ${w.name}", maxLines = 1, overflow = TextOverflow.Ellipsis) }
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            Button(onProject) { Text("Proyectar esta") }
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            MusicSheet(
                client, shown ?: PresentationState(title = item.title), features, scores, instrument, onInstrument, prefs,
                suggestedInstrument = null, wide = maxWidth >= WIDE,
                loading = shown == null, missing = shown != null && shown.slides.isEmpty(),
            )
        }
    }
}
