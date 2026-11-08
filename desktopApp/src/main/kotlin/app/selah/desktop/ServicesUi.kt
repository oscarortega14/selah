package app.selah.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import app.selah.shared.ClientMessage
import app.selah.shared.DAY_NAMES
import app.selah.shared.ServiceStep
import app.selah.shared.ServicesConfig
import app.selah.shared.SongSummary
import app.selah.shared.StepKind
import app.selah.shared.WorshipService
import app.selah.shared.agoText
import app.selah.shared.normalizeKey
import app.selah.shared.stepName
import app.selah.shared.suggestSongs
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Lo de los cultos que necesitan varias pantallas del operador (lista, canciones, ventana de cultos).
 * Se reparte con [LocalWorship] para no pasarlo de mano en mano por cada pantalla.
 */
class Worship(
    val config: ServicesConfig,
    val update: ((ServicesConfig) -> ServicesConfig) -> Unit,
    val savedLists: List<String>,
    /** Canciones con la fecha en que se cantaron por última vez. */
    val songs: List<SongSummary>,
    val announcements: List<String>,
    val sermons: List<SongSummary>,
    /** Cambia la línea "Tipo:" de una canción; devuelve un error o null. */
    val setTags: (id: String, tags: List<String>) -> String?,
    val openServices: () -> Unit,
) {
    /** Tipos para elegir: los de siempre más los que ya usan las canciones. */
    val knownTags: List<String> by lazy {
        (DEFAULT_TAGS + songs.flatMap { it.tags }).distinctBy { normalizeKey(it) }
    }

    /** Días desde que se cantó, o null si nunca. */
    fun daysSince(s: SongSummary, today: LocalDate = LocalDate.now()): Int? =
        s.lastSung?.let { runCatching { ChronoUnit.DAYS.between(LocalDate.parse(it), today).toInt() }.getOrNull() }

    /** "Se cantó hace 6 días": aviso si fue dentro de los días configurados. */
    fun repeatWarning(s: SongSummary): String? {
        val d = daysSince(s) ?: return null
        return if (config.repeatDays > 0 && d in 0 until config.repeatDays) "«${s.title}» se cantó ${agoText(d)}" else null
    }

    companion object {
        val DEFAULT_TAGS = listOf("Alabanza", "Adoración")
    }
}

val LocalWorship = staticCompositionLocalOf<Worship?> { null }

/** Botón "Tipo ▾" de una canción: marca o quita Alabanza, Adoración u otro tipo. */
@Composable
fun SongTagsButton(song: SongSummary, worship: Worship) {
    var open by remember { mutableStateOf(false) }
    var other by remember { mutableStateOf("") }
    Box {
        TextButton({ open = true }, Modifier.hand(), contentPadding = androidx.compose.foundation.layout.PaddingValues(6.dp)) {
            Text("Tipo", maxLines = 1)
        }
        DropdownMenu(open, { open = false }) {
            for (tag in worship.knownTags) {
                val on = song.tags.any { normalizeKey(it) == normalizeKey(tag) }
                DropdownMenuItem(
                    text = { Text((if (on) "✓ " else "    ") + tag) },
                    onClick = {
                        val new = if (on) song.tags.filterNot { normalizeKey(it) == normalizeKey(tag) } else song.tags + tag
                        worship.setTags(song.id, new)
                        open = false
                    },
                    modifier = Modifier.hand(),
                )
            }
            HorizontalDivider()
            Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(other, { other = it.take(30) }, placeholder = { Text("Otro tipo") }, singleLine = true, modifier = Modifier.width(150.dp))
                TextButton({ if (other.isNotBlank()) { worship.setTags(song.id, song.tags + other.trim()); other = ""; open = false } }) { Text("Agregar") }
            }
        }
    }
}

/** Aviso arriba de la lista: el culto de hoy, la lista que le toca y preparar desde la plantilla. */
@Composable
fun TodayServiceBanner(worship: Worship, onOpenList: (String) -> Unit, onPrepare: (WorshipService) -> Unit) {
    val now = java.time.LocalDateTime.now()
    val service = worship.config.serviceFor(now.dayOfWeek.value, now.hour * 60 + now.minute)
    if (service == null) {
        TextButton(worship.openServices, Modifier.hand()) { Text(if (worship.config.services.isEmpty()) "Configurar los cultos de la iglesia…" else "Cultos…") }
        return
    }
    val next = service.nextList(worship.savedLists)
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Hoy: ${service.label}", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                TextButton(worship.openServices, Modifier.hand(), contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp)) { Text("Cultos…") }
            }
            if (next != null) Button({ onOpenList(next) }, Modifier.fillMaxWidth().hand()) { Text("Abrir «$next» (le toca hoy)") }
            if (service.steps.isNotEmpty()) OutlinedButton({ onPrepare(service) }, Modifier.fillMaxWidth().hand()) { Text("Preparar desde la plantilla…") }
        }
    }
}

/** Una elección del asistente para preparar el culto. */
private class Choice(val step: ServiceStep, val label: String) {
    var include by mutableStateOf(true)
    var songId by mutableStateOf<String?>(null)
    var text by mutableStateOf("")
}

/**
 * Asistente "Preparar desde la plantilla": un renglón por paso, con canciones sugeridas (las de ese tipo que
 * llevan más tiempo sin cantarse). Al crear, reemplaza la lista del culto actual.
 */
@Composable
fun PrepareServiceDialog(service: WorshipService, worship: Worship, send: (ClientMessage) -> Unit, onClose: () -> Unit) {
    val choices = remember(service) {
        val labels = service.stepLabels()
        val used = mutableSetOf<String>()
        service.steps.mapIndexed { i, step ->
            Choice(step, labels[i]).also { c ->
                if (step.kind == StepKind.SONG) {
                    c.songId = suggestSongs(worship.songs, step.tag, used).firstOrNull()?.id?.also { used += it }
                }
                if (step.kind == StepKind.SERMON) c.songId = worship.sermons.firstOrNull()?.id
                if (step.kind == StepKind.ANNOUNCEMENT) c.songId = worship.announcements.firstOrNull()
                if (c.songId == null && step.kind != StepKind.BIBLE) c.include = false
            }
        }
    }
    AlertDialog(
        onDismissRequest = onClose,
        modifier = Modifier.width(640.dp),
        title = { Text("Preparar: ${service.label}") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Selah propone las canciones de cada tipo que llevan más tiempo sin cantarse. Cambia lo que quieras; " +
                        "al crearla reemplaza la lista del culto actual (guárdala antes si la necesitas).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                for (c in choices) ChoiceRow(c, worship, choices)
            }
        },
        confirmButton = {
            Button({
                send(ClientMessage.PlanClear)
                for (c in choices.filter { it.include }) {
                    val id = c.songId
                    when (c.step.kind) {
                        StepKind.SONG -> id?.let { send(ClientMessage.PlanAddSong(it)) }
                        StepKind.BIBLE -> c.text.trim().takeIf { it.isNotEmpty() }?.let { send(ClientMessage.PlanAddBible(it)) }
                        StepKind.ANNOUNCEMENT -> id?.let { send(ClientMessage.PlanAddMedia(it)) }
                        StepKind.SERMON -> id?.let { send(ClientMessage.PlanAddSermon(it)) }
                    }
                }
                onClose()
            }) { Text("Crear la lista") }
        },
        dismissButton = { TextButton(onClose) { Text("Cancelar") } },
    )
}

@Composable
private fun ChoiceRow(c: Choice, worship: Worship, all: List<Choice>) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(c.include, { c.include = it })
        Text(c.label, Modifier.width(120.dp), fontWeight = FontWeight.Medium)
        Box(Modifier.weight(1f)) {
            when (c.step.kind) {
                StepKind.BIBLE -> OutlinedTextField(c.text, { c.text = it.take(80); c.include = true }, placeholder = { Text("Cita, ej. Salmos 23") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                StepKind.SONG -> {
                    val taken = all.filter { it !== c && it.include }.mapNotNull { it.songId }.toSet()
                    val options = suggestSongs(worship.songs, c.step.tag, taken)
                    val chosen = worship.songs.find { it.id == c.songId }
                    Picker(
                        shown = chosen?.let { s -> s.title + (worship.daysSince(s)?.let { " · ${agoText(it)}" } ?: " · nunca") } ?: "Elegir canción…",
                        warning = chosen?.let { worship.repeatWarning(it) },
                        options = options.map { s -> s.id to (s.title + (worship.daysSince(s)?.let { " · cantada ${agoText(it)}" } ?: " · nunca cantada")) },
                    ) { c.songId = it; c.include = true }
                }
                StepKind.ANNOUNCEMENT -> Picker(c.songId?.substringBeforeLast('.') ?: "Elegir anuncio…", null, worship.announcements.map { it to it.substringBeforeLast('.') }) { c.songId = it; c.include = true }
                StepKind.SERMON -> Picker(worship.sermons.find { it.id == c.songId }?.title ?: "Elegir predicación…", null, worship.sermons.map { it.id to it.title }) { c.songId = it; c.include = true }
            }
        }
    }
}

/** Botón que abre una lista para elegir (con buscador si es larga). */
@Composable
private fun Picker(shown: String, warning: String?, options: List<Pair<String, String>>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("") }
    Column {
        OutlinedButton({ open = true }, Modifier.fillMaxWidth().hand(), enabled = options.isNotEmpty()) {
            Text(if (options.isEmpty()) "No hay para elegir" else "$shown ▾", maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        warning?.let { Text("⚠ $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary) }
        DropdownMenu(open, { open = false; filter = "" }, Modifier.heightIn(max = 420.dp)) {
            if (options.size > 8) {
                OutlinedTextField(filter, { filter = it }, placeholder = { Text("Buscar") }, singleLine = true, modifier = Modifier.padding(horizontal = 8.dp).width(320.dp))
            }
            val q = normalizeKey(filter)
            for ((id, text) in options.filter { q.isEmpty() || q in normalizeKey(it.second) }.take(60)) {
                DropdownMenuItem(text = { Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis) }, onClick = { onPick(id); open = false; filter = "" }, modifier = Modifier.hand())
            }
        }
    }
}

/** Ventana "Cultos": cada culto con su día, hora, plantilla y listas en rotación, y el aviso de repetidas. */
@Composable
fun ServicesWindow(worship: Worship, onClose: () -> Unit) {
    val cfg = worship.config
    Window(onCloseRequest = onClose, title = "Cultos de la iglesia", state = rememberFittedWindowState(DpSize(860.dp, 760.dp))) {
        MaterialTheme(colorScheme = darkColorScheme()) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(
                        "Cada culto con su día y hora. La plantilla arma la lista con las canciones que llevan más tiempo sin " +
                            "cantarse; las listas en rotación se turnan (Selah propone la que toca). Todo se guarda solo.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Avisar si una canción se cantó en los últimos")
                        var days by remember { mutableStateOf(cfg.repeatDays.toString()) }
                        OutlinedTextField(
                            days, { v -> days = v.filter { it.isDigit() }.take(3); days.toIntOrNull()?.let { d -> worship.update { it.copy(repeatDays = d) } } },
                            singleLine = true, modifier = Modifier.width(80.dp),
                        )
                        Text("días (0 = no avisar)")
                    }
                    cfg.services.forEachIndexed { i, s ->
                        ServiceCard(s, worship, onChange = { new -> worship.update { c -> c.copy(services = c.services.toMutableList().also { it[i] = new }) } }) {
                            worship.update { c -> c.copy(services = c.services.filterIndexed { j, _ -> j != i }) }
                        }
                    }
                    Button({
                        worship.update { c -> c.copy(services = c.services + WorshipService("Nuevo culto", day = 7, time = "9:00", steps = DEFAULT_STEPS)) }
                    }, Modifier.hand()) { Text("+ Nuevo culto") }
                }
            }
        }
    }
}

/** Plantilla para un culto nuevo: 3 de alabanza, 3 de adoración, lectura, anuncio y predicación. */
private val DEFAULT_STEPS = List(3) { ServiceStep(StepKind.SONG, "Alabanza") } + List(3) { ServiceStep(StepKind.SONG, "Adoración") } +
    listOf(ServiceStep(StepKind.BIBLE), ServiceStep(StepKind.ANNOUNCEMENT), ServiceStep(StepKind.SERMON))

@Composable
private fun ServiceCard(s: WorshipService, worship: Worship, onChange: (WorshipService) -> Unit, onDelete: () -> Unit) {
    var confirmDelete by remember { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                var name by remember(s.name) { mutableStateOf(s.name) }
                OutlinedTextField(name, { name = it.take(60); onChange(s.copy(name = name)) }, label = { Text("Nombre") }, singleLine = true, modifier = Modifier.weight(1f))
                var time by remember(s.time) { mutableStateOf(s.time) }
                OutlinedTextField(time, { time = it.take(20); onChange(s.copy(time = time)) }, label = { Text("Hora (ej. 9:00 o 7:00 p.m.)") }, singleLine = true, modifier = Modifier.width(200.dp))
                if (confirmDelete) {
                    TextButton(onDelete) { Text("Sí, borrar", color = MaterialTheme.colorScheme.error) }
                    TextButton({ confirmDelete = false }) { Text("No") }
                } else TextButton({ confirmDelete = true }) { Text("Borrar") }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DAY_NAMES.forEachIndexed { i, d -> FilterChip(s.day == i + 1, { onChange(s.copy(day = i + 1)) }, { Text(d) }, Modifier.hand()) }
            }
            // Plantilla
            Text("Plantilla (el orden del culto)", style = MaterialTheme.typography.titleSmall)
            val labels = s.stepLabels()
            s.steps.forEachIndexed { i, step ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}. ${labels[i]}", Modifier.weight(1f))
                    TextButton({ onChange(s.copy(steps = s.steps.toMutableList().also { l -> l.add(i - 1, l.removeAt(i)) })) }, enabled = i > 0) { Text("▲") }
                    TextButton({ onChange(s.copy(steps = s.steps.toMutableList().also { l -> l.add(i + 1, l.removeAt(i)) })) }, enabled = i < s.steps.lastIndex) { Text("▼") }
                    TextButton({ onChange(s.copy(steps = s.steps.filterIndexed { j, _ -> j != i })) }) { Text("✕") }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (tag in worship.knownTags) OutlinedButton({ onChange(s.copy(steps = s.steps + ServiceStep(StepKind.SONG, tag))) }, Modifier.hand()) { Text("+ $tag") }
                OutlinedButton({ onChange(s.copy(steps = s.steps + ServiceStep(StepKind.SONG))) }, Modifier.hand()) { Text("+ Canción") }
                for (k in listOf(StepKind.BIBLE, StepKind.ANNOUNCEMENT, StepKind.SERMON)) {
                    OutlinedButton({ onChange(s.copy(steps = s.steps + ServiceStep(k))) }, Modifier.hand()) { Text("+ " + stepName(ServiceStep(k))) }
                }
            }
            // Rotación
            Text("Listas en rotación (se turnan en orden alfabético)", style = MaterialTheme.typography.titleSmall)
            if (worship.savedLists.isEmpty()) {
                Text("Todavía no hay listas guardadas. Arma una lista y toca \"Guardar…\" (ej. \"${s.name} A\").", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val next = s.nextList(worship.savedLists)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (list in worship.savedLists.sortedWith(String.CASE_INSENSITIVE_ORDER)) {
                    val on = list in s.rotation
                    FilterChip(on, {
                        val rot = (if (on) s.rotation - list else s.rotation + list).sortedWith(String.CASE_INSENSITIVE_ORDER)
                        onChange(s.copy(rotation = rot))
                    }, { Text(list + if (list == next) "  · le toca" else "") }, Modifier.hand())
                }
            }
        }
    }
}
