package app.selah.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/** Buscar y descargar fondos de video de Pixabay a Selah/fondos. */
@Composable
fun PixabayWindow(library: Library, themes: ThemeStore, client: PixabayClient, onDownloaded: () -> Unit, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val theme by themes.theme.collectAsState()
    var query by remember { mutableStateOf("partículas") }
    var onlyBackgrounds by remember { mutableStateOf(true) }
    var results by remember { mutableStateOf<PixabayPage?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val progress = remember { mutableStateMapOf<Long, Float>() }
    val downloaded = remember { mutableStateMapOf<Long, Boolean>() }

    fun search(q: String = query) {
        if (q.isBlank()) return
        query = q
        loading = true
        error = null
        scope.launch {
            try {
                results = withContext(Dispatchers.IO) { client.search(q, onlyBackgrounds) }
            } catch (e: Exception) {
                error = "No se pudo buscar: ${e.message ?: "sin conexión a internet"}"
            } finally {
                loading = false
            }
        }
    }

    fun download(v: PixabayVideo) {
        val target = java.io.File(library.backgroundsDir, v.fileName)
        progress[v.id] = 0f
        scope.launch {
            try {
                withContext(Dispatchers.IO) { client.download(v, target) { p -> progress[v.id] = p } }
                downloaded[v.id] = true
                onDownloaded()
            } catch (e: Exception) {
                error = "No se pudo descargar: ${e.message}"
            } finally {
                progress.remove(v.id)
            }
        }
    }

    // Primera búsqueda al abrir
    androidx.compose.runtime.LaunchedEffect(Unit) { if (client.configured) search() }

    Window(icon = appIcon, onCloseRequest = onClose, title = "Buscar fondos en internet", state = rememberFittedWindowState(DpSize(1200.dp, 800.dp))) {
        MaterialTheme(colorScheme = darkColorScheme()) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (!client.configured) {
                        Text("Esta versión de Selah no tiene configurada la búsqueda en Pixabay.", color = MaterialTheme.colorScheme.error)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            query, { query = it },
                            label = { Text("¿Qué fondo buscas?") },
                            singleLine = true,
                            modifier = Modifier.weight(1f).marksTyping(),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { search() }),
                        )
                        Button({ search() }, enabled = client.configured && !loading) { Text("Buscar") }
                        Checkbox(onlyBackgrounds, { onlyBackgrounds = it })
                        Text("Solo fondos\n(sin personas)", style = MaterialTheme.typography.bodySmall)
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(PixabayClient.SUGGESTIONS) { s -> AssistChip(onClick = { search(s) }, label = { Text(s) }) }
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    results?.let { r ->
                        Text(
                            if (r.videos.isEmpty()) "Sin resultados para «$query»" else "${r.total} videos para «$query» (se muestran ${r.videos.size})",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    LazyVerticalGrid(
                        GridCells.Adaptive(260.dp),
                        Modifier.weight(1f).fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(results?.videos.orEmpty(), key = { it.id }) { v ->
                            val file = java.io.File(library.backgroundsDir, v.fileName)
                            val isDownloaded = downloaded[v.id] == true || file.exists()
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Box(
                                    Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp)).background(Color(0xFF1B1B22)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    val thumb = rememberRemoteImage(v.thumbnail)
                                    if (thumb != null) Image(thumb, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                    Text(
                                        "${v.duration} s",
                                        Modifier.align(Alignment.BottomEnd).background(Color(0x99000000)).padding(horizontal = 6.dp, vertical = 2.dp),
                                        style = MaterialTheme.typography.labelSmall, color = Color.White,
                                    )
                                }
                                Text(v.tags, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val p = progress[v.id]
                                when {
                                    p != null -> LinearProgressIndicator({ p }, Modifier.fillMaxWidth().padding(vertical = 8.dp))
                                    isDownloaded -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Button({ themes.update { it.copy(image = v.fileName) } }, Modifier.weight(1f), enabled = theme.image != v.fileName) {
                                            Text(if (theme.image == v.fileName) "✓ En uso" else "Usar de fondo")
                                        }
                                    }
                                    else -> OutlinedButton({ download(v) }, Modifier.fillMaxWidth()) {
                                        Text("Descargar (${"%.1f".format(v.sizeBytes / 1_048_576.0)} MB)")
                                    }
                                }
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Videos de Pixabay: uso libre y gratuito, también en transmisiones; no requieren dar crédito. " +
                                "Se guardan en la carpeta de fondos y aparecen en \"Videos en bucle\".",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton({ runCatching { Desktop.getDesktop().browse(URI("https://pixabay.com/service/license-summary/")) } }) {
                            Text("Licencia de Pixabay")
                        }
                    }
                }
            }
        }
    }
}

private val remoteImages = ConcurrentHashMap<String, ImageBitmap>()
private val imageHttp by lazy { java.net.http.HttpClient.newBuilder().followRedirects(java.net.http.HttpClient.Redirect.NORMAL).build() }

/** Miniatura desde internet (se descarga una vez y queda en memoria). */
@Composable
private fun rememberRemoteImage(url: String): ImageBitmap? {
    val image by produceState(remoteImages[url], url) {
        if (value != null || url.isBlank()) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = imageHttp.send(
                    java.net.http.HttpRequest.newBuilder(URI(url)).GET().build(),
                    java.net.http.HttpResponse.BodyHandlers.ofByteArray(),
                ).body()
                org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap().also { remoteImages[url] = it }
            }.getOrNull()
        }
    }
    return image
}
