package app.selah.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** "Llevar a otro computador": armar un .zip con lo elegido, o traer lo de un .zip a este computador. */
@Composable
fun TransferDialog(transfer: Transfer, backups: Backups, onImported: () -> Unit, onClose: () -> Unit, bring: Boolean = false) {
    val sizes = remember { transfer.sizes() }
    var chosen by remember { mutableStateOf(Transfer.PARTS.filter { it.default && (sizes[it.key]?.files ?: 0) > 0 }.map { it.key }.toSet()) }
    var busy by remember { mutableStateOf(false) }
    // Cómo va: bytes hechos, total, archivo actual y cuándo empezó (para calcular cuánto falta).
    var work by remember { mutableStateOf<Work?>(null) }
    // Si lo que avanza es "Traer" (su barra va junto a su botón) o "Crear el .zip".
    var importing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    // El .zip elegido para traer, con lo que trae.
    var incoming by remember { mutableStateOf<Pair<File, Map<String, Transfer.Summary>>?>(null) }
    val scope = rememberCoroutineScope()
    val total = Transfer.PARTS.filter { it.key in chosen }.sumOf { sizes[it.key]?.bytes ?: 0 }

    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text(if (bring) "Traer de otro computador" else "Llevar a otro computador") },
        text = {
            Column(Modifier.width(560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    if (bring) "Abre el .zip que hiciste en el otro computador con «? → Llevar a otro computador». Lo que ya está igual " +
                        "aquí no se toca, lo que cambió se reemplaza, y antes Selah hace un respaldo de lo que hay."
                    else "Prepara todo aquí y llévalo al computador de la iglesia: Selah arma un .zip, y en el otro computador lo abres con " +
                        "«? → Traer de otro computador». Lo propio de cada computador (pantallas, clave, celulares aprobados, sonido) no viaja.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!bring) {
                Text("¿Qué llevas?", fontWeight = FontWeight.Bold)
                for (p in Transfer.PARTS) {
                    val s = sizes[p.key] ?: Transfer.Summary(0, 0)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(p.key in chosen, { on -> chosen = if (on) chosen + p.key else chosen - p.key }, Modifier.hand(), enabled = !busy && s.files > 0)
                        Column(Modifier.weight(1f)) {
                            Text(p.label + "  ·  " + if (s.files == 0) "nada" else "${s.files} archivos, ${size(s.bytes)}")
                            if (p.note.isNotEmpty()) Text(p.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Button({
                    val name = "Selah para llevar " + SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date()) + ".zip"
                    val target = chooseSave(name) ?: return@Button
                    busy = true; message = null; work = Work("Armando el .zip")
                    scope.launch(Dispatchers.IO) {
                        val w = work!!
                        runCatching { transfer.export(target, chosen) { d, t, f -> work = w.at(d, t, f) } }
                            .onSuccess { n -> message = "Listo: «${target.name}» (${n} archivos, ${size(target.length())}). Cópialo al otro computador (memoria USB, Drive…) y ábrelo allá con «? → Traer de otro computador»." }
                            .onFailure { Log.error("No pude armar el .zip", it); message = "No pude armar el .zip: ${it.message}" }
                        busy = false; work = null
                    }
                }, Modifier.hand(), enabled = !busy && chosen.isNotEmpty()) { Text("Crear el .zip (${size(total)})…") }
                work?.takeIf { !importing }?.let { WorkProgress(it) }
                }

                if (bring) {
                val inc = incoming
                if (inc == null) {
                    OutlinedButton({
                        val f = chooseOpen() ?: return@OutlinedButton
                        val what = transfer.inspect(f)
                        if (what == null) message = "«${f.name}» no es un .zip hecho con «Llevar a otro computador»." else { incoming = f to what; message = null }
                    }, Modifier.hand(), enabled = !busy) { Text("Abrir un .zip…") }
                } else {
                    Text("«${inc.first.name}» trae:")
                    for (p in Transfer.PARTS) inc.second[p.key]?.let { s -> Text("• ${p.label}: ${s.files} archivos, ${size(s.bytes)}") }
                    Text(
                        "Lo que ya está igual aquí no se toca; lo que cambió se reemplaza por lo del .zip. Antes, Selah hace un respaldo " +
                            "de las canciones y listas de este computador (? → Respaldos).",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button({
                            busy = true; message = null; importing = true
                            work = Work("Primero, un respaldo de lo que hay en este computador")
                            scope.launch(Dispatchers.IO) {
                                runCatching {
                                    backups.create()
                                    val w = Work("Trayendo").also { work = it }
                                    transfer.import(inc.first) { d, t, f -> work = w.at(d, t, f) }
                                }
                                    .onSuccess { n -> onImported(); message = if (n == 0) "Ya estaba todo igual: no hubo nada que traer." else "Listo: $n archivos traídos." ; incoming = null }
                                    .onFailure { Log.error("No pude traer el .zip", it); message = "No pude traerlo: ${it.message}" }
                                busy = false; work = null; importing = false
                            }
                        }, Modifier.hand(), enabled = !busy) { Text("Traer") }
                        TextButton({ incoming = null }, Modifier.hand(), enabled = !busy) { Text("Elegir otro") }
                    }
                    work?.takeIf { importing }?.let { WorkProgress(it) }
                }
                }
                message?.let { Text(it, color = MaterialTheme.colorScheme.tertiary) }
            }
        },
        confirmButton = { TextButton(onClose, Modifier.hand(), enabled = !busy) { Text("Cerrar") } },
    )
}

/** Lo que se está haciendo y cómo va. [done]/[total] en bytes; [file] el archivo actual. */
internal data class Work(val what: String, val done: Long = 0, val total: Long = 1, val file: String = "", val started: Long = System.currentTimeMillis()) {
    private var lastShown = 0L
    /** El nuevo avance, pero a lo sumo 5 veces por segundo (cada 64 KB sería demasiado para la pantalla). */
    fun at(d: Long, t: Long, f: String): Work {
        val now = System.currentTimeMillis()
        if (now - lastShown < 200 && d < t) return this
        lastShown = now
        return copy(done = d, total = t.coerceAtLeast(1), file = f).also { it.lastShown = now }
    }
    val fraction get() = (done.toDouble() / total).toFloat().coerceIn(0f, 1f)
    /** "faltan unos 3 min", cuando ya hay con qué calcular. */
    fun remaining(now: Long = System.currentTimeMillis()): String? {
        val secs = (now - started) / 1000.0
        if (secs < 3 || done <= 0) return null
        val left = (total - done) / (done / secs)
        return when {
            left < 60 -> "falta menos de un minuto"
            Math.round(left / 60) == 1L -> "falta como 1 minuto"
            else -> "faltan como ${Math.round(left / 60)} minutos"
        }
    }
}

@Composable
private fun WorkProgress(w: Work) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LinearProgressIndicator({ w.fraction }, Modifier.fillMaxWidth())
        Text(
            "${w.what}… ${Math.round(w.fraction * 100)} % · ${size(w.done)} de ${size(w.total)}" + (w.remaining()?.let { " · $it" } ?: ""),
            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary,
        )
        if (w.file.isNotEmpty()) Text(w.file, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

private fun size(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
    else -> "%.0f KB".format(bytes / 1024.0).let { if (bytes in 1..1023) "1 KB" else it }
}

private fun chooseSave(name: String): File? {
    val d = java.awt.FileDialog(null as java.awt.Frame?, "Guardar el .zip para llevar", java.awt.FileDialog.SAVE).apply { file = name; isVisible = true }
    val f = d.file ?: return null
    return File(d.directory, if (f.endsWith(".zip", ignoreCase = true)) f else "$f.zip")
}

private fun chooseOpen(): File? {
    val d = java.awt.FileDialog(null as java.awt.Frame?, "Abrir el .zip que trajiste", java.awt.FileDialog.LOAD).apply {
        setFilenameFilter { _, n -> n.endsWith(".zip", ignoreCase = true) }
        file = "*.zip"
        isVisible = true
    }
    return d.files?.firstOrNull()
}
