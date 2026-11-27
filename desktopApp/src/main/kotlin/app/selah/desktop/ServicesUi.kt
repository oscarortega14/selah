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
import app.selah.shared.PlanItem
import app.selah.shared.PlanKind
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
    /** Guarda una lista con nombre sin tocar la de ahora (órdenes "+ Lista"); devuelve un error o null. */
    val saveList: (name: String, adds: List<ClientMessage>) -> String? = { _, _ -> "No disponible" },
    /** Lo que tiene una lista guardada (null si no existe). */
    val savedItems: (name: String) -> List<PlanItem>? = { null },
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
/** Las órdenes "+ Lista" de lo elegido, en el orden de la plantilla. */
private fun List<Choice>.adds(): List<ClientMessage> = filter { it.include }.mapNotNull { c ->
    val id = c.songId
    when (c.step.kind) {
        StepKind.SONG -> id?.let { ClientMessage.PlanAddSong(it) }
        StepKind.BIBLE -> c.text.trim().takeIf { it.isNotEmpty() }?.let { ClientMessage.PlanAddBible(it) }
        StepKind.ANNOUNCEMENT -> id?.let { ClientMessage.PlanAddMedia(it) }
        StepKind.SERMON -> id?.let { ClientMessage.PlanAddSermon(it) }
    }
}

/**
 * Qué elemento de una lista ya planeada va en cada paso de la plantilla: en orden, el siguiente del mismo tipo
 * (canción con canción, lectura con lectura…). null = ese paso no tiene nada.
 */
fun matchStepsToItems(steps: List<ServiceStep>, items: List<PlanItem>): List<PlanItem?> {
    val left = items.toMutableList()
    return steps.map { step ->
        val kind = when (step.kind) {
            StepKind.SONG -> PlanKind.SONG
            StepKind.BIBLE -> PlanKind.BIBLE
            StepKind.ANNOUNCEMENT -> PlanKind.MEDIA
            StepKind.SERMON -> PlanKind.SERMON
        }
        left.firstOrNull { it.kind == kind }?.also { left.remove(it) }
    }
}

/** Nombre de la lista planeada: "Domingo 29 nov". */
fun plannedListName(service: WorshipService, date: LocalDate): String = "${service.name} ${date.dayOfMonth} ${MONTHS[date.monthValue - 1]}"

/**
 * Asistente de la plantilla: un renglón por paso, con selects y canciones sugeridas (las de ese tipo que llevan
 * más tiempo sin cantarse, sin repetir las de [exclude]). Sin [date] arma la lista de ahora (reemplaza la actual);
 * con [date] la guarda con nombre para ese día ("Planificar la semana") sin tocar la de ahora.
 */
@Composable
fun PrepareServiceDialog(
    service: WorshipService,
    worship: Worship,
    send: (ClientMessage) -> Unit,
    date: LocalDate? = null,
    exclude: Set<String> = emptySet(),
    onSaved: (listName: String) -> Unit = {},
    onClose: () -> Unit,
) {
    val existingName = date?.let { service.planned[it.toString()] }
    var name by remember(service, date) { mutableStateOf(existingName ?: date?.let { plannedListName(service, it) }.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    val choices = remember(service, date) {
        val labels = service.stepLabels()
        val existing = existingName?.let(worship.savedItems)?.let { matchStepsToItems(service.steps, it) }
        val used = exclude.toMutableSet()
        service.steps.mapIndexed { i, step ->
            Choice(step, labels[i]).also { c ->
                if (existing != null) {
                    // Ya estaba planeado: se abre con lo que tenía, para cambiar solo lo que haga falta.
                    val item = existing[i]
                    if (step.kind == StepKind.BIBLE) c.text = item?.ref.orEmpty() else c.songId = item?.ref
                    c.include = item != null
                    c.songId?.let { used += it }
                    return@also
                }
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
        title = { Text(if (date == null) "Preparar: ${service.label}" else "${service.name} · ${dayText(date)}") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    if (date == null) "Selah propone las canciones de cada tipo que llevan más tiempo sin cantarse. Cambia lo que quieras; " +
                        "al crearla reemplaza la lista del culto actual (guárdala antes si la necesitas)."
                    else "Elige qué va en cada parte del culto. Selah propone las que llevan más tiempo sin cantarse y no repite " +
                        "las de los otros cultos de la semana. Se guarda como lista y ese día aparece lista para abrir.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (date != null) {
                    OutlinedTextField(name, { name = it.take(60); error = null }, label = { Text("Nombre de la lista") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                for (c in choices) ChoiceRow(c, worship, choices)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button({
                val adds = choices.adds()
                if (date == null) {
                    send(ClientMessage.PlanClear)
                    adds.forEach(send)
                    onClose()
                } else {
                    error = worship.saveList(name, adds)
                    if (error == null) { onSaved(app.selah.shared.cleanListName(name) ?: name); onClose() }
                }
            }, Modifier.hand()) { Text(if (date == null) "Crear la lista" else "Guardar") }
        },
        dismissButton = { TextButton(onClose) { Text("Cancelar") } },
    )
}

/**
 * Elegir las canciones de un culto ([index] en la configuración) para la fecha [date]: su plantilla con selects.
 * Se guarda como la lista de ese día; no propone las que ya están en los otros cultos de la semana.
 */
@Composable
fun ChooseSongsDialog(worship: Worship, index: Int, date: LocalDate, send: (ClientMessage) -> Unit, onClose: () -> Unit) {
    val cfg = worship.config
    val s = cfg.services.getOrNull(index)
    if (s == null) { onClose(); return }
    val others = upcomingServices(cfg, LocalDate.now()).filter { it != (index to date) }.flatMap { (j, d) ->
        cfg.services[j].planned[d.toString()]?.let(worship.savedItems).orEmpty().filter { it.kind == PlanKind.SONG }.map { it.ref }
    }.toSet()
    PrepareServiceDialog(
        s, worship, send, date = date, exclude = others,
        onSaved = { list ->
            worship.update { c ->
                c.copy(services = c.services.mapIndexed { k, svc -> if (k == index) svc.copy(planned = svc.planned + (date.toString() to list)) else svc })
            }
        },
        onClose = onClose,
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
    Window(icon = appIcon, onCloseRequest = onClose, title = "Cultos de la iglesia", state = rememberFittedWindowState(DpSize(860.dp, 760.dp))) {
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
                Text(
                    "Todavía no hay listas guardadas. Lo más fácil: en la ventana principal, pestaña Lista, toca " +
                        "\"Planificar la semana…\" y elige las canciones de cada culto con esta plantilla. " +
                        "Si prefieres listas fijas que se turnen, guárdalas (ej. \"${s.name} A\") y márcalas aquí.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
