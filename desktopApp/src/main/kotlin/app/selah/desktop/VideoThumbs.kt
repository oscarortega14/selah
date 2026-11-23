package app.selah.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jcodec.api.FrameGrab
import org.jcodec.common.io.NIOUtils
import org.jcodec.scale.AWTUtil
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Una imagen de cada video de fondo, para reconocerlo al elegirlo ("Holyrics 017.mp4" no dice nada). Se saca del
 * segundo 1 con un decodificador en Java (no hace falta VLC) y se guarda en fondos/.miniaturas.
 */
object VideoThumbs {
    private const val WIDTH = 384

    fun dir(backgroundsDir: File) = File(backgroundsDir, ".miniaturas")

    /** La miniatura de [video] (la hace si falta o si el video cambió), o null si no se pudo leer. */
    @Synchronized
    fun of(video: File): File? {
        val out = File(dir(video.parentFile), video.name + ".jpg")
        if (out.isFile && out.lastModified() >= video.lastModified()) return out
        val frame = runCatching {
            NIOUtils.readableChannel(video).use { ch ->
                val g = FrameGrab.createFrameGrab(ch)
                runCatching { g.seekToSecondPrecise(1.0) }
                g.nativeFrame?.let { AWTUtil.toBufferedImage(it) }
            }
        }.onFailure { Log.warn("No pude sacar la imagen de ${video.name}: ${it.message}") }.getOrNull() ?: return null
        val h = (WIDTH * frame.height / frame.width.coerceAtLeast(1)).coerceAtLeast(1)
        val small = BufferedImage(WIDTH, h, BufferedImage.TYPE_INT_RGB)
        small.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(frame, 0, 0, WIDTH, h, null); dispose()
        }
        out.parentFile.mkdirs()
        val tmp = File(out.parentFile, ".${out.name}.tmp")
        ImageIO.write(small, "jpg", tmp)
        if (!tmp.renameTo(out)) { tmp.copyTo(out, overwrite = true); tmp.delete() }
        return out
    }
}

/** El cuadro de un video de fondo: su imagen (o un fondo oscuro mientras se prepara) con un ▶ pequeño. */
@Composable
fun VideoTile(video: File?) {
    val thumb by produceState<File?>(null, video) { value = video?.let { withContext(Dispatchers.IO) { VideoThumbs.of(it) } } }
    val image = rememberBackground(thumb)
    Box(Modifier.fillMaxSize().background(Color(0xFF1B1B22)), contentAlignment = Alignment.Center) {
        if (image != null) Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Text("▶", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
        if (image != null) Text(
            "▶", Modifier.align(Alignment.BottomEnd).padding(4.dp).background(Color(0x99000000), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp),
            color = Color.White, style = MaterialTheme.typography.labelSmall,
        )
    }
}

/**
 * Elegir un fondo mirando su imagen (videos e imágenes de la carpeta de fondos). Tocar uno solo lo marca; se aplica con
 * "Aceptar". [onPick] null = el de siempre.
 */
@Composable
fun BackgroundPicker(
    library: Library,
    current: String?,
    title: String,
    onPick: (String?) -> Unit,
    onClose: () -> Unit,
    /** La letra de la canción (para la vista previa) y sus tipos (para mostrar el fondo de su tipo si no tiene uno propio). */
    slides: List<app.selah.shared.Slide> = emptyList(),
    tags: List<String> = emptyList(),
) {
    val names = androidx.compose.runtime.remember { library.videos() + library.backgrounds() }
    // "" = el de siempre; null no se usa (siempre hay algo marcado).
    var marked by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(current ?: "") }
    var slideIndex by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0) }
    val parentLook = LocalLook.current
    // En la vista previa, un video se ve con su imagen (moverlo aquí no hace falta y en Mac puede faltar VLC).
    val look = parentLook.copy(
        preview = true,
        songTags = { tags },
        backgroundFile = { n ->
            val f = library.backgroundFile(n)
            if (f != null && isVideoFile(n)) File(VideoThumbs.dir(f.parentFile), f.name + ".jpg").takeIf { it.isFile } else f
        },
    )
    androidx.compose.ui.window.DialogWindow(icon = appIcon, 
        onCloseRequest = onClose, title = title,
        onPreviewKeyEvent = { e -> if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && e.key == androidx.compose.ui.input.key.Key.Escape) { onClose(); true } else false },
        state = androidx.compose.ui.window.rememberDialogState(size = androidx.compose.ui.unit.DpSize(1300.dp, 760.dp)),
    ) {
        androidx.compose.material3.MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme()) {
            androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    Text("Elige el fondo y dale «Aceptar». Los que tienen ▶ son videos: en la proyección se mueven.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    androidx.compose.material3.FilterChip(marked == "", { marked = "" }, { Text("El de siempre (o el de su tipo)") })
                    androidx.compose.foundation.layout.Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp)) {
                    androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                        androidx.compose.foundation.lazy.grid.GridCells.Adaptive(165.dp), Modifier.weight(1f).fillMaxSize(),
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
                    ) {
                        items(names.size) { i ->
                            val name = names[i]
                            val file = library.backgroundFile(name)
                            val shape = RoundedCornerShape(6.dp)
                            val on = name == marked
                            androidx.compose.foundation.layout.Column(Modifier.handClickable { marked = name }) {
                                Box(
                                    Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(shape)
                                        .border(if (on) 4.dp else 1.dp, if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, shape),
                                ) {
                                    if (isVideoFile(name)) VideoTile(file)
                                    else rememberBackground(file)?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                                }
                                Text(
                                    name.substringBeforeLast('.'), style = MaterialTheme.typography.labelMedium, maxLines = 2,
                                    color = if (on) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Unspecified,
                                    fontWeight = if (on) androidx.compose.ui.text.font.FontWeight.Bold else null,
                                )
                            }
                        }
                    }
                    // --- Vista previa: cambia apenas se marca otro fondo ---
                    androidx.compose.foundation.layout.Column(Modifier.width(440.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                        Text("Vista previa", style = MaterialTheme.typography.titleMedium)
                        val shown = slides.ifEmpty { listOf(app.selah.shared.Slide(text = "Cuán grande es Él")) }
                        val idx = slideIndex.coerceIn(0, shown.lastIndex)
                        val state = app.selah.shared.PresentationState(title = title, slides = shown, index = idx, songId = "vista", background = marked.ifEmpty { null })
                        androidx.compose.material3.Surface(Modifier.fillMaxWidth().aspectRatio(16f / 9f), shape = RoundedCornerShape(6.dp)) {
                            ProjectorScreen(state, look = look)
                        }
                        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.TextButton({ slideIndex = idx - 1 }, Modifier.hand(), enabled = idx > 0) { Text("◀ Anterior") }
                            Text("Diapositiva ${idx + 1} de ${shown.size}", Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = MaterialTheme.typography.bodySmall)
                            androidx.compose.material3.TextButton({ slideIndex = idx + 1 }, Modifier.hand(), enabled = idx < shown.lastIndex) { Text("Siguiente ▶") }
                        }
                        if (marked.isNotEmpty() && isVideoFile(marked)) Text(
                            "Es un video: en la proyección se mueve (aquí se ve quieto).",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    }
                    androidx.compose.foundation.layout.Row(
                        Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "Elegido: " + (marked.ifEmpty { null }?.substringBeforeLast('.') ?: "el de siempre"),
                            Modifier.weight(1f), maxLines = 1, color = MaterialTheme.colorScheme.primary,
                        )
                        androidx.compose.material3.OutlinedButton(onClose, Modifier.hand()) { Text("Cancelar") }
                        androidx.compose.material3.Button({ onPick(marked.ifEmpty { null }) }, Modifier.hand()) { Text("Aceptar") }
                    }
                }
            }
        }
    }
}
