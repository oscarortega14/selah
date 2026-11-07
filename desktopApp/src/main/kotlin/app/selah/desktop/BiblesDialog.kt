package app.selah.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
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
import kotlinx.coroutines.withContext

/** Biblias: descargar traducciones libres, agregar una desde un archivo, usar o quitar las agregadas. */
@Composable
fun BiblesDialog(
    library: Library,
    catalog: Catalog,
    onUse: (String) -> Unit,
    /** Recordar la traducción en uso (para que una agregada no la reemplace al volver a abrir Selah). */
    onKeep: (String) -> Unit,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<Pair<String, Boolean>?>(null) } // (texto, ¿es error?)
    // catalog.bibleNames cambia al agregar o quitar una: con eso se vuelve a pintar la lista.
    val installed = remember(catalog.bibleNames) { library.userBibleNames }

    fun work(label: String, success: String? = null, action: () -> String?) {
        busy = label
        message = null
        catalog.bibleName?.let(onKeep)
        scope.launch {
            val error = withContext(Dispatchers.IO) { runCatching(action).getOrElse { it.message ?: "Algo salió mal" } }
            busy = null
            message = error?.let { it to true } ?: success?.let { it to false }
        }
    }

    AlertDialog(
        onDismissRequest = { if (busy == null) onClose() },
        modifier = Modifier.width(680.dp),
        title = { Text("Biblias") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Quedan guardadas en este computador y se usan sin internet. Los celulares y tablets las ven solas " +
                        "(Biblia → Traducción). También puedes mostrar dos traducciones a la vez.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({
                        val file = chooseBibleFile() ?: return@OutlinedButton
                        busy = "archivo"
                        message = null
                        catalog.bibleName?.let(onKeep)
                        scope.launch {
                            val result = withContext(Dispatchers.IO) { runCatching { BibleDownloads.addFromFile(library, file) } }
                            busy = null
                            message = result.fold({ "Lista: \"$it\". Elígela en Traducción." to false }, { (it.message ?: "No pude leerla") to true })
                        }
                    }, enabled = busy == null) { Text("Agregar desde un archivo…") }
                    Text(
                        ".txt (VPL de eBible.org), .xml (Zefania), .json o .zip",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                busy?.let {
                    val text = when {
                        it == "archivo" -> "Leyendo el archivo…"
                        it.startsWith("quitar:") -> "Quitando ${it.removePrefix("quitar:")}…"
                        else -> "Descargando $it… (unos 5 MB)"
                    }
                    Text(text, color = MaterialTheme.colorScheme.primary)
                }
                message?.let { (text, isError) ->
                    Text(text, color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                }
                HorizontalDivider()
                LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val added = installed.filter { name -> BibleDownloads.CATALOG.none { it.title == name } }.sorted()
                    if (added.isNotEmpty()) {
                        item { Section("Agregadas desde un archivo") }
                        items(added, key = { "propia:$it" }) { name ->
                            BibleRow(name, null, null, inUse = name == catalog.bibleName) {
                                InstalledButtons(name, catalog, busy == null, onUse) { work("quitar:$name") { BibleDownloads.remove(library, name); null } }
                            }
                        }
                    }
                    for ((language, bibles) in BibleDownloads.CATALOG.groupBy { it.language }) {
                        item(key = "idioma:$language") { Section("Descargar gratis · $language") }
                        items(bibles, key = { it.id }) { b ->
                            BibleRow(b.title, b.description, b.credit, inUse = b.title == catalog.bibleName) {
                                if (b.title in installed) {
                                    InstalledButtons(b.title, catalog, busy == null, onUse) { work("quitar:${b.title}") { BibleDownloads.remove(library, b.title); null } }
                                } else {
                                    Button({
                                        work(b.title, "Lista: \"${b.title}\". Pulsa Usar, o elígela en Traducción.") { BibleDownloads.download(library, b) }
                                    }, enabled = busy == null) { Text(if (busy == b.title) "Descargando…" else "Descargar") }
                                }
                            }
                        }
                    }
                    item {
                        Text(
                            "¿Y la Reina-Valera 1960, la NVI o la NTV? Tienen derechos de autor de sus editoriales, así que Selah no puede " +
                                "traerlas ni descargarlas. Si tu iglesia tiene permiso y un archivo de una de ellas, agrégalo desde un archivo.",
                            Modifier.padding(top = 12.dp),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClose, enabled = busy == null) { Text("Cerrar") } },
    )
}

@Composable
private fun Section(title: String) {
    Text(title, Modifier.padding(top = 10.dp, bottom = 2.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun BibleRow(title: String, description: String?, credit: String?, inUse: Boolean, actions: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title + if (inUse) "  · en uso" else "", fontWeight = FontWeight.Medium)
            description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            credit?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        actions()
    }
}

@Composable
private fun InstalledButtons(name: String, catalog: Catalog, enabled: Boolean, onUse: (String) -> Unit, onRemove: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("✓ Instalada", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        if (name != catalog.bibleName) TextButton({ onUse(name) }, enabled = enabled) { Text("Usar") }
        if (confirm) {
            TextButton({ confirm = false; onRemove() }, enabled = enabled) { Text("Sí, quitar", color = MaterialTheme.colorScheme.error) }
            TextButton({ confirm = false }) { Text("No") }
        } else {
            TextButton({ confirm = true }, enabled = enabled) { Text("Quitar") }
        }
    }
}

/** Elegir un archivo de Biblia (.txt, .xml, .json o .zip). */
private fun chooseBibleFile(): java.io.File? {
    val dialog = java.awt.FileDialog(null as java.awt.Frame?, "Agregar Biblia: .txt (VPL), .xml (Zefania), .json o .zip", java.awt.FileDialog.LOAD).apply {
        setFilenameFilter { _, name -> name.substringAfterLast('.', "").lowercase() in Library.BIBLE_EXTENSIONS + "zip" }
        file = "*.txt;*.xml;*.json;*.zip"
        isVisible = true
    }
    return dialog.files?.firstOrNull()
}
