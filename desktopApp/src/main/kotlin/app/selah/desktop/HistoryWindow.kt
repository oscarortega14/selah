package app.selah.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import java.io.File
import java.time.LocalDate

/** Qué se cantó y leyó cada día, y las canciones más cantadas (para no repetir o para reportes). */
@Composable
fun HistoryWindow(history: History, song: (String) -> app.selah.shared.Song?, onClose: () -> Unit) {
    val entries by history.entries.collectAsState()
    var months by remember { mutableIntStateOf(3) }
    var message by remember { mutableStateOf<String?>(null) }
    Window(icon = appIcon, onCloseRequest = onClose, title = "Historial", state = rememberFittedWindowState(DpSize(980.dp, 700.dp))) {
        MaterialTheme(colorScheme = darkColorScheme()) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    // Por día, lo más reciente arriba
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Por día", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                            OutlinedButton({ message = exportHistory(history) }, enabled = entries.isNotEmpty()) { Text("Exportar a Excel (.csv)…") }
                        }
                        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                        if (entries.isEmpty()) {
                            Text(
                                "Todavía no hay nada. Cada canción y lectura que se proyecte queda anotada aquí (una vez por día).",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        val byDay = remember(entries) { entries.groupBy { it.date }.toSortedMap(compareByDescending { it }).toList() }
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            for ((day, list) in byDay) {
                                item(key = day.toString()) {
                                    Text(
                                        formatDay(day).replaceFirstChar { it.uppercase() },
                                        style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                                    )
                                }
                                items(list, key = { "$day|${it.kind}|${it.ref}" }) { e ->
                                    Row {
                                        Text(e.time, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(56.dp))
                                        Text(e.kind.label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(80.dp))
                                        Text(e.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                    // Ranking
                    Column(Modifier.width(360.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Más cantadas", style = MaterialTheme.typography.titleLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (m in listOf(1, 3, 12)) {
                                FilterChip(months == m, { months = m }, { Text(if (m == 12) "Último año" else if (m == 1) "Último mes" else "$m meses") })
                            }
                        }
                        val top = remember(entries, months) { history.topSongs(LocalDate.now().minusMonths(months.toLong())) }
                        // Reporte para la licencia CCLI del mismo periodo
                        val report = remember(entries, months) {
                            history.ccliReport(LocalDate.now().minusMonths(months.toLong()), LocalDate.now(), song)
                        }
                        OutlinedButton(
                            { message = exportCcli(report, months) }, Modifier.fillMaxWidth(), enabled = report.isNotEmpty(),
                        ) { Text("Reporte para CCLI (.csv)…") }
                        val missing = report.count { it.ccli.isEmpty() }
                        if (missing > 0) {
                            Text(
                                "$missing ${if (missing == 1) "canción no tiene" else "canciones no tienen"} número CCLI. " +
                                    "Agrégalo en la canción con una línea \"CCLI: 1234567\" debajo del título.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        HorizontalDivider()
                        if (top.isEmpty()) Text("Sin canciones en ese tiempo.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        LazyColumn {
                            items(top, key = { it.first }) { (title, count) ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                    Text(title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(if (count == 1) "1 vez" else "$count veces", fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Guarda el reporte CCLI (título, autor, número, veces, fechas) para copiarlo en el sitio de CCLI. */
private fun exportCcli(rows: List<CcliRow>, months: Int): String? {
    val dialog = java.awt.FileDialog(null as java.awt.Frame?, "Guardar reporte para CCLI", java.awt.FileDialog.SAVE).apply {
        file = "reporte-ccli-${LocalDate.now()}-${months}m.csv"
        isVisible = true
    }
    val name = dialog.file ?: return null
    val target = File(dialog.directory, if (name.endsWith(".csv", true)) name else "$name.csv")
    // Con BOM: Excel en español abre bien las tildes
    return runCatching { target.writeText("\uFEFF" + History.ccliCsv(rows), Charsets.UTF_8); "Reporte guardado en ${target.absolutePath}" }
        .getOrElse { "No pude guardar: ${it.message}" }
}

private fun exportHistory(history: History): String? {
    val dialog = java.awt.FileDialog(null as java.awt.Frame?, "Exportar historial", java.awt.FileDialog.SAVE).apply {
        file = "historial-selah.csv"
        isVisible = true
    }
    val name = dialog.file ?: return null
    val target = File(dialog.directory, if (name.endsWith(".csv", true)) name else "$name.csv")
    return runCatching { history.exportCsv(target); "Guardado en ${target.absolutePath}" }.getOrElse { "No pude guardar: ${it.message}" }
}
