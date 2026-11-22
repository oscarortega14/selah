package app.selah.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

/** Elegir un fondo mirando su imagen (videos e imágenes de la carpeta de fondos). [onPick] null = el de siempre. */
@Composable
fun BackgroundPicker(library: Library, current: String?, title: String, onPick: (String?) -> Unit, onClose: () -> Unit) {
    val names = androidx.compose.runtime.remember { library.videos() + library.backgrounds() }
    androidx.compose.ui.window.DialogWindow(
        onCloseRequest = onClose, title = title,
        state = androidx.compose.ui.window.rememberDialogState(size = androidx.compose.ui.unit.DpSize(900.dp, 700.dp)),
    ) {
        androidx.compose.material3.MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme()) {
            androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    Text("Toca el fondo para esta canción. Los que tienen ▶ son videos: en la proyección se mueven.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    androidx.compose.material3.OutlinedButton({ onPick(null) }, Modifier.hand()) { Text((if (current == null) "✓ " else "") + "El de siempre (o el de su tipo)") }
                    androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                        androidx.compose.foundation.lazy.grid.GridCells.Adaptive(160.dp), Modifier.fillMaxSize(),
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
                    ) {
                        items(names.size) { i ->
                            val name = names[i]
                            val file = library.backgroundFile(name)
                            val shape = RoundedCornerShape(6.dp)
                            androidx.compose.foundation.layout.Column(Modifier.handClickable { onPick(name) }) {
                                Box(
                                    Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(shape)
                                        .border(if (name == current) 3.dp else 1.dp, if (name == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, shape),
                                ) {
                                    if (isVideoFile(name)) VideoTile(file)
                                    else rememberBackground(file)?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                                }
                                Text((if (name == current) "✓ " else "") + name, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}
