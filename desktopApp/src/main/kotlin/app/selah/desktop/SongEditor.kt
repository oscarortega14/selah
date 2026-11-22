package app.selah.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import app.selah.shared.SongParser
import app.selah.shared.sections

private val NEW_SONG_TEMPLATE = """
    # Título de la canción

    Primera estrofa, línea 1
    Primera estrofa, línea 2

    Coro:
    Línea del coro
    Línea del coro
""".trimIndent() + "\n"

private val NEW_SERMON_TEMPLATE = """
    # Título de la predicación

    Introducción:
    Una idea para empezar

    Biblia: Juan 3:16

    Punto 1:
    Primera idea principal

    Punto 2:
    Segunda idea principal
""".trimIndent() + "\n"

/** Qué se está editando: [songId] null = nueva. [sermon]: una predicación en vez de una canción. */
data class EditRequest(val songId: String?, val sermon: Boolean = false)

@Composable
fun SongEditorWindow(
    request: EditRequest,
    library: Library,
    presenter: Presenter,
    onClose: () -> Unit,
) {
    val songId = request.songId
    val sermon = request.sermon
    val original = remember(request) { songId?.let { library.rawText(it, sermon) } ?: if (sermon) NEW_SERMON_TEMPLATE else NEW_SONG_TEMPLATE }
    var value by remember(request) { mutableStateOf(TextFieldValue(original, TextRange(original.length))) }
    var error by remember(request) { mutableStateOf<String?>(null) }
    var confirmDiscard by remember(request) { mutableStateOf(false) }
    var confirmDelete by remember(request) { mutableStateOf(false) }
    val dirty = value.text != original
    val preview = remember(value.text) { SongParser.parse("vista", value.text, "(sin título)") }
    // Predicación: la vista previa ya trae los versículos de "Biblia: …" y avisa si una cita no existe.
    val expanded = remember(value.text, sermon) { if (sermon) app.selah.shared.Sermon.expand(preview, library.bible) else null }

    fun save() {
        try {
            val newId = library.save(songId, value.text, sermon)
            if (sermon) presenter.sermonSaved(songId, newId) else presenter.songSaved(songId, newId)
            onClose()
        } catch (e: IllegalArgumentException) {
            error = e.message
        } catch (e: java.io.IOException) {
            error = "No pude guardar el archivo: ${e.message}"
        }
    }

    Window(
        onCloseRequest = { if (dirty) confirmDiscard = true else onClose() },
        title = when {
            songId == null && sermon -> "Nueva predicación"
            songId == null -> "Nueva canción"
            else -> "Editar: ${preview.title}"
        },
        state = rememberFittedWindowState(DpSize(1150.dp, 780.dp)),
        onPreviewKeyEvent = {
            if (it.type == KeyEventType.KeyDown && it.isCtrlPressed && it.key == Key.S) { save(); true } else false
        },
    ) {
        MaterialTheme(colorScheme = darkColorScheme()) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(::save) { Text("Guardar (Ctrl+S)") }
                        OutlinedButton({ if (dirty) confirmDiscard = true else onClose() }) { Text("Cancelar") }
                        Spacer(Modifier.width(16.dp))
                        if (sermon) {
                            TextButton({ value = value.insertBlock("Punto ${nextNumber(value.text, "Punto")}:\n") }) { Text("+ Punto") }
                            TextButton({ value = value.insertBlock("Biblia: ") }) { Text("+ Versículo") }
                            TextButton({ value = value.insertBlock("") }) { Text("+ Nueva diapositiva") }
                        } else {
                            TextButton({ value = value.insertBlock("Coro:\n") }) { Text("+ Coro") }
                            TextButton({ value = value.insertBlock("Pregón ${nextNumber(value.text, "Pregón")}:\n") }) { Text("+ Pregón") }
                            TextButton({ value = value.insertBlock("Puente:\n") }) { Text("+ Puente") }
                            TextButton({ value = value.insertBlock("") }) { Text("+ Nueva diapositiva") }
                            TextButton({ value = value.withOrderLine() }) { Text("+ Orden") }
                            // El fondo de esta canción (línea "Fondo:"), sin tener que proyectarla.
                            Box {
                                var menu by remember { mutableStateOf(false) }
                                val current = remember(value.text) { SongParser.parse("x", value.text).background }
                                TextButton({ menu = true }) { Text("Fondo: " + (current ?: "el de siempre") + " ▾", maxLines = 1) }
                                androidx.compose.material3.DropdownMenu(menu, { menu = false }) {
                                    androidx.compose.material3.DropdownMenuItem({ Text("El de siempre (o el de su tipo)") }, { menu = false; value = value.withBackground(null) })
                                    (library.videos() + library.backgrounds()).forEach { name ->
                                        androidx.compose.material3.DropdownMenuItem({ Text((if (name == current) "✓ " else "") + name) }, { menu = false; value = value.withBackground(name) })
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        if (songId != null) {
                            OutlinedButton(
                                { confirmDelete = true },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                            ) { Text("Eliminar") }
                        }
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

                    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = value,
                            onValueChange = { value = it; error = null },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 15.sp),
                        )
                        Preview(
                            preview.title, expanded?.slides ?: preview.slides,
                            expanded?.problems ?: preview.unknownOrder.map { "En \"Orden:\" no existe: $it" },
                            Modifier.width(400.dp).fillMaxHeight(),
                        )
                    }
                    Text(
                        if (sermon) {
                            "Formato: la primera línea es \"# Título\" (sale como portada). Una línea en blanco separa diapositivas. " +
                                "\"Punto 1:\" al inicio de un bloque sale como encabezado. Un bloque \"Biblia: Juan 10:11\" se reemplaza por los versículos."
                        } else {
                            "Formato: la primera línea es \"# Título\". Una línea en blanco separa diapositivas. " +
                                "\"Coro:\", \"Pregón 1:\"… al inicio de un bloque crean una sección para saltar rápido. " +
                                "\"Orden: Inicio, Coro, Pregón 1, Coro\" (debajo del título) repite secciones al avanzar. " +
                                "Acordes: \"[G]Cuán grande es [C]Él\" (en el videobeam sale solo la letra; los músicos los ven en la vista de escenario)."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (confirmDiscard) {
                    AlertDialog(
                        onDismissRequest = { confirmDiscard = false },
                        title = { Text("¿Descartar los cambios?") },
                        text = { Text("Lo que escribiste no se ha guardado.") },
                        confirmButton = { TextButton({ confirmDiscard = false; onClose() }) { Text("Descartar") } },
                        dismissButton = { TextButton({ confirmDiscard = false }) { Text("Seguir editando") } },
                    )
                }
                if (confirmDelete && songId != null) {
                    AlertDialog(
                        onDismissRequest = { confirmDelete = false },
                        title = { Text("¿Eliminar \"${preview.title}\"?") },
                        text = { Text("Se borra el archivo $songId.txt de la carpeta de ${if (sermon) "predicaciones" else "canciones"}.") },
                        confirmButton = {
                            TextButton({ confirmDelete = false; library.delete(songId, sermon); onClose() }) {
                                Text("Eliminar", color = MaterialTheme.colorScheme.error)
                            }
                        },
                        dismissButton = { TextButton({ confirmDelete = false }) { Text("No") } },
                    )
                }
            }
        }
    }
}

@Composable
private fun Preview(title: String, slides: List<app.selah.shared.Slide>, problems: List<String>, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (problems.isNotEmpty()) {
            Text(
                problems.joinToString("\n"),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        val sections = slides.sections()
        Text(
            "${slides.size} diapositivas" + if (sections.isNotEmpty()) " · secciones: " + sections.map { it.label }.distinct().joinToString(" · ") else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(slides) { i, slide ->
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Column(Modifier.padding(10.dp)) {
                        Text(
                            "${i + 1}" + if (slide.label.isNotEmpty()) " · ${slide.label}" else "",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(slide.text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

/** Inserta en el cursor empezando un bloque nuevo (deja una línea en blanco antes si hace falta). */
internal fun TextFieldValue.insertBlock(snippet: String): TextFieldValue {
    val start = selection.min
    val before = text.substring(0, start)
    val gap = when {
        before.isEmpty() || before.endsWith("\n\n") -> ""
        before.endsWith("\n") -> "\n"
        else -> "\n\n"
    }
    val insert = gap + snippet
    val newText = before + insert + text.substring(selection.max)
    return TextFieldValue(newText, TextRange(start + insert.length))
}

/** Siguiente número libre para una etiqueta numerada ("Pregón 3" -> 4). */
internal fun nextNumber(text: String, label: String): Int {
    val rx = Regex("""^\s*${Regex.escape(label)}\s+(\d+)\s*:""", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))
    return (rx.findAll(text).maxOfOrNull { it.groupValues[1].toInt() } ?: 0) + 1
}

/** Agrega (si falta) una línea "Orden:" debajo del título con las secciones actuales. */
/** Pone (o quita, con null) la línea "Fondo:" justo debajo del título. */
internal fun TextFieldValue.withBackground(name: String?): TextFieldValue {
    val lines = text.split('\n').filterNot { Regex("""^\s*fondo\s*:.*$""", RegexOption.IGNORE_CASE).matches(it) }.toMutableList()
    if (name != null) lines.add(lines.indexOfFirst { it.trimStart().startsWith("#") } + 1, "Fondo: $name")
    val newText = lines.joinToString("\n")
    return TextFieldValue(newText, TextRange(minOf(selection.start, newText.length)))
}

internal fun TextFieldValue.withOrderLine(): TextFieldValue {
    if (text.lineSequence().any { it.trim().startsWith("Orden", ignoreCase = true) && it.contains(':') }) return this
    val labels = SongParser.parse("x", text).slides.sections().map { it.label }.distinct()
    val line = "Orden: " + labels.ifEmpty { listOf("Inicio") }.joinToString(", ")
    val lines = text.split('\n').toMutableList()
    val titleAt = lines.indexOfFirst { it.trimStart().startsWith("#") }
    lines.add(titleAt + 1, line)
    val newText = lines.joinToString("\n")
    return TextFieldValue(newText, TextRange(newText.length))
}
