package app.selah.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** "Hay una versión nueva": descargar e instalar, más tarde, u omitir esa versión. */
@Composable
fun UpdateDialog(
    release: Release,
    onLater: () -> Unit,
    onSkip: () -> Unit,
    /** Llamar para cerrar Selah cuando el instalador ya arrancó. */
    onExitForInstall: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf<Float?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val busy = progress != null && error == null
    AlertDialog(
        onDismissRequest = { if (!busy) onLater() },
        modifier = Modifier.width(560.dp),
        title = { Text("Hay una versión nueva: ${release.version}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Tienes la ${Updater.currentVersion}. Selah se cierra unos segundos mientras se instala y vuelve a abrir solo. Mejor hazlo fuera del culto.")
                if (release.notes.isNotBlank()) {
                    Text(
                        release.notes, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
                    )
                }
                progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (!Updater.canInstall) {
                    Text(
                        if (System.getProperty("os.name").startsWith("Mac")) "En Mac: descarga el .dmg nuevo desde GitHub y arrástralo a Aplicaciones (reemplaza al anterior)."
                        else "Esta copia no está instalada con el instalador (.msi): descarga la nueva desde GitHub.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                {
                    error = null
                    progress = 0f
                    scope.launch {
                        runCatching { Updater.download(release) { progress = it } }
                            .onSuccess { msi ->
                                runCatching { Updater.installAndRestart(msi) }
                                    .onSuccess { onExitForInstall() }
                                    .onFailure { error = "No pude abrir el instalador: ${it.message}" }
                            }
                            .onFailure { error = it.message ?: "No se pudo descargar"; progress = null }
                    }
                },
                enabled = !busy && Updater.canInstall,
            ) { Text(if (busy) "Descargando…" else "Actualizar ahora") }
        },
        dismissButton = {
            Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                TextButton(onLater, enabled = !busy) { Text("Más tarde") }
                TextButton(onSkip, enabled = !busy) { Text("No avisar de esta versión") }
            }
        },
    )
}
