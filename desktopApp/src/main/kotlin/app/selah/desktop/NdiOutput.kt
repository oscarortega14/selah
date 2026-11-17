package app.selah.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.geometry.Offset
import app.selah.shared.PresentationState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import java.util.concurrent.ConcurrentHashMap

/**
 * Las fuentes NDI de Selah. Cada una se dibuja en su propio hilo (fuera de la ventana) y solo
 * mientras algún programa la está recibiendo: si nadie la mira, no gasta procesador.
 *
 * Dibujar 1920×1080 sin la tarjeta de video cuesta ~0,2 s por cuadro, así que solo se vuelve a
 * dibujar cuando algo cambia (y un momento después, por las imágenes que cargan aparte); entre
 * cambios se reenvía el mismo cuadro. Por eso el fondo animado sale quieto por NDI.
 */
class NdiOutput(
    private val state: StateFlow<PresentationState>,
    private val look: () -> Look,
) {
    enum class Kind(val sourceName: String, val label: String) {
        /** Lo mismo que el videobeam (fondo, letra, anuncios, presentaciones). */
        PROJECTION("Selah Proyección", "Proyección (igual que el videobeam)"),
        /** Solo la letra y el nombre sobre fondo transparente, para poner encima de la cámara. */
        LYRICS("Selah Letra", "Letra con fondo transparente (para encima de la cámara)"),
    }

    private val threads = ConcurrentHashMap<Kind, Thread>()
    private val _receivers = MutableStateFlow<Map<Kind, Int>>(emptyMap())
    /** Cuántos programas reciben cada fuente (para mostrarlo en la ventana). */
    val receivers: StateFlow<Map<Kind, Int>> = _receivers.asStateFlow()

    /** Prende o apaga una fuente. Devuelve un mensaje si no se puede (NDI no instalado). */
    @Synchronized
    fun set(kind: Kind, on: Boolean): String? {
        if (!on) {
            threads.remove(kind)?.interrupt()
            _receivers.value = _receivers.value - kind
            return null
        }
        if (threads.containsKey(kind)) return null
        val lib = Ndi.lib ?: return "Falta NDI Tools (gratis): instálalo desde ${Ndi.TOOLS_URL} y vuelve a abrir Selah."
        val t = Thread({ run(lib, kind) }, "ndi-${kind.name.lowercase()}").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }
        threads[kind] = t
        t.start()
        return null
    }

    fun stopAll() { Kind.entries.forEach { set(it, false) } }

    private fun run(lib: Ndi.Lib, kind: Kind) {
        try {
            Ndi.Sender(lib, kind.sourceName, WIDTH, HEIGHT, FPS).use { sender ->
                FrameRenderer(kind, state.value, look()).use { renderer ->
                    val start = System.nanoTime()
                    while (!Thread.currentThread().isInterrupted) {
                        val n = sender.connections()
                        if (_receivers.value[kind] != n) _receivers.value = _receivers.value + (kind to n)
                        if (n == 0) { Thread.sleep(500); continue }
                        val l = look()
                        sender.send(renderer.frame(state.value, l.copy(frozenTime = 0f), System.nanoTime() - start))
                    }
                }
            }
        } catch (_: InterruptedException) {
        } catch (e: Throwable) {
            Log.error("Salida NDI ${kind.sourceName}", e)
        } finally {
            threads.remove(kind, Thread.currentThread())
        }
    }

    companion object {
        const val WIDTH = 1920
        const val HEIGHT = 1080
        const val FPS = 30
    }
}

/** Dibuja cuadros de 1920×1080 fuera de la pantalla y los entrega en BGRA (para NDI). */
class FrameRenderer(
    private val kind: NdiOutput.Kind,
    initial: PresentationState,
    initialLook: Look,
    val width: Int = NdiOutput.WIDTH,
    val height: Int = NdiOutput.HEIGHT,
) : AutoCloseable {
    private val shownState: MutableState<PresentationState> = mutableStateOf(initial)
    private val shownLook: MutableState<Look> = mutableStateOf(initialLook)
    // Densidad como la de un videobeam de 1080p: el tamaño de la letra sale igual que en la proyección.
    private val scene = ImageComposeScene(width, height, Density(1.5f)) {
        when (kind) {
            NdiOutput.Kind.PROJECTION -> ProjectorScreen(shownState.value, look = shownLook.value)
            NdiOutput.Kind.LYRICS -> LyricsOverlay(shownState.value)
        }
    }
    private val info = ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL)
    private val bitmap = Bitmap().apply { allocPixels(info) }

    private var lastKey: Any? = null
    private var lastFrame: ByteArray? = null
    private var redrawUntil = 0L

    /**
     * El cuadro para enviar: se dibuja de nuevo solo si cambió lo que se proyecta o el aspecto
     * (y durante [SETTLE_MILLIS] después, para que aparezcan las imágenes que cargan aparte).
     */
    fun frame(state: PresentationState, look: Look, nanos: Long, nowMillis: Long = System.currentTimeMillis()): ByteArray {
        val key = Triple(state, look.theme, look.frozenTime)
        if (key != lastKey) { lastKey = key; redrawUntil = nowMillis + SETTLE_MILLIS }
        val cached = lastFrame
        if (cached != null && nowMillis > redrawUntil) return cached
        return render(state, look, nanos).also { lastFrame = it }
    }

    /** Un cuadro en BGRA (sin premultiplicar, como lo pide NDI). */
    fun render(state: PresentationState, look: Look, nanos: Long): ByteArray {
        if (shownState.value != state) shownState.value = state
        if (shownLook.value != look) shownLook.value = look
        Snapshot.sendApplyNotifications()
        val image = scene.render(nanos)
        image.readPixels(bitmap, 0, 0)
        return bitmap.readPixels(info, width * 4, 0, 0) ?: ByteArray(width * height * 4)
    }

    companion object {
        const val SETTLE_MILLIS = 1500L
    }

    override fun close() {
        scene.close()
        bitmap.close()
    }
}

/**
 * Solo la letra (abajo, con sombra) y el nombre para la transmisión, sobre fondo transparente:
 * lo mismo que la página obs.html, para programas que reciben NDI.
 */
@Composable
fun LyricsOverlay(state: PresentationState, now: Long = System.currentTimeMillis()) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val h = maxHeight
        val cur = state.current
        val counting = (state.countdownSeconds(now) ?: 0) > 0
        val showLyrics = cur != null && !state.blank && !state.logo && !counting && state.media == null && state.presentation == null
        val named = state.lowerThird.isNotEmpty()
        val outline = Shadow(Color.Black, Offset(2f, 2f), blurRadius = 8f)
        if (showLyrics) {
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .padding(start = maxWidth * 0.06f, end = maxWidth * 0.06f, bottom = h * 0.06f + if (named) h * 0.14f else 0.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val size = (h.value * 0.045f).sp
                Text(
                    cur!!.text, color = Color.White, textAlign = TextAlign.Center,
                    style = TextStyle(fontSize = size, lineHeight = size * 1.25f, fontWeight = FontWeight.Bold, shadow = outline),
                )
                if (state.isBible && cur.label.isNotEmpty()) {
                    Text(state.labelWithVersion(cur.label), color = Color.White, style = TextStyle(fontSize = size * 0.6f, fontWeight = FontWeight.Medium, shadow = outline))
                }
            }
        }
        if (named) {
            Column(Modifier.align(Alignment.BottomStart).padding(start = maxWidth * 0.05f, bottom = h * 0.06f)) {
                Text(
                    state.lowerThird, color = Color.White,
                    style = TextStyle(fontSize = (h.value * 0.034f).sp, fontWeight = FontWeight.Bold),
                    modifier = Modifier.background(Color(0xFF4F378B), RoundedCornerShape(6.dp)).padding(horizontal = 22.dp, vertical = 8.dp),
                )
                if (state.lowerThirdSub.isNotEmpty()) {
                    Text(
                        state.lowerThirdSub, color = Color(0xFFE8DEF8),
                        style = TextStyle(fontSize = (h.value * 0.022f).sp),
                        modifier = Modifier.background(Color(0xBF000000), RoundedCornerShape(bottomStart = 6.dp, bottomEnd = 6.dp)).padding(horizontal = 22.dp, vertical = 5.dp),
                    )
                }
            }
        }
    }
}
