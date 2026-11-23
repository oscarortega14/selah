package app.selah.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.selah.shared.PresentationState
import app.selah.shared.Theme
import app.selah.shared.imageFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Tema actual y cómo encontrar el archivo de una imagen de fondo. */
data class Look(
    val theme: Theme = Theme(),
    val backgroundFile: (String) -> File? = { null },
    /** Tipos de una canción (línea "Tipo:"), para usar el fondo de su tipo. */
    val songTags: (String) -> List<String> = { emptyList() },
    /** Si no es null, la animación queda fija en este instante (pruebas y miniaturas). */
    val frozenTime: Float? = null,
    /** Vista previa (miniatura): el video se redibuja menos veces por segundo. */
    val preview: Boolean = false,
    /** Archivo de un anuncio (carpeta anuncios). */
    val mediaFile: (String) -> File? = { null },
    /** Imagen de la diapositiva N (desde 0) de una presentación, ya convertida. */
    val presentationPage: (String, Int) -> File? = { _, _ -> null },
    /** Miniatura de esa diapositiva (para las listas). */
    val presentationThumb: (String, Int) -> File? = { _, _ -> null },
)

val LocalLook = staticCompositionLocalOf { Look() }

@Composable
fun ProvideLook(look: Look, content: @Composable () -> Unit) = CompositionLocalProvider(LocalLook provides look, content = content)

/** Lo que se ve en el videobeam. También se usa en miniatura como vista previa. */
@Composable
fun ProjectorScreen(state: PresentationState, modifier: Modifier = Modifier, look: Look = LocalLook.current) {
    val theme = look.theme
    val imageName = theme.imageFor(state, state.songId?.let(look.songTags).orEmpty())
    val bgFile = imageName?.let(look.backgroundFile)
    // Un video solo se usa si VLC está disponible; si no, se cae al fondo incluido o al color.
    val video = bgFile?.takeIf { isVideoFile(it.name) && VideoBackgrounds.available }
    val image = rememberBackground(bgFile?.takeIf { !isVideoFile(it.name) })

    val logo = rememberBackground(theme.logo?.let(look.backgroundFile))
    val now = rememberClock(active = state.countdownEnd != null)
    val seconds = state.countdownSeconds(now)
    val media = state.media?.let(look.mediaFile)
    val page = state.presentation?.let { look.presentationPage(it, state.index) }
    val nextPage = state.presentation?.let { look.presentationPage(it, state.index + 1) }

    BoxWithConstraints(modifier.fillMaxSize().background(Color.Black)) {
        // El título de la canción antes de la letra: se dibuja como una diapositiva solo con el título.
        val slide = state.current?.let { if (state.titleCard) app.selah.shared.Slide(text = state.title) else it }
        if (!state.blank) {
            // Un anuncio ocupa toda la pantalla (sin fondo ni letra); la cuenta regresiva y el logo van primero.
            val showMedia = media != null && !state.logo && (seconds == null || seconds <= 0)
            // Una presentación también ocupa toda la pantalla, igual que un anuncio de imagen.
            val showPage = state.presentation != null && !state.logo && (seconds == null || seconds <= 0)
            if (!showMedia && !showPage) Background(theme, image, video, look.frozenTime, look.preview)
            when {
                seconds != null && seconds > 0 -> Countdown(state.countdownText, seconds, theme, maxWidth, maxHeight)
                state.logo -> if (logo != null) Logo(logo, maxHeight)
                showMedia -> Announcement(media!!, look.preview, theme.light)
                showPage -> if (page != null) PresentationPage(page, nextPage)
                slide != null -> {
                    // Con alerta, la letra deja libre la franja de abajo.
                    val w = maxWidth
                    val h = maxHeight
                    val bottom = if (state.alert.isNotEmpty()) alertBarHeight(h) else 0.dp
                    // Fundido corto entre diapositivas (el fondo no se toca). En miniaturas y pruebas, sin animación.
                    // (Sin fundido se dibuja directo: un Crossfade de 0 ms deja un cuadro en negro al cambiar.)
                    if (theme.fade && look.frozenTime == null) {
                        androidx.compose.animation.Crossfade(
                            targetState = ShownSlide(slide.text, state.labelWithVersion(slide.label), slide.text2),
                            animationSpec = androidx.compose.animation.core.tween(FADE_MILLIS),
                            label = "diapositiva",
                        ) { shown ->
                            Box(Modifier.fillMaxSize().padding(bottom = bottom)) {
                                SlideText(state, shown.text, shown.label, theme, w, h - bottom, shown.text2)
                            }
                        }
                    } else {
                        Box(Modifier.fillMaxSize().padding(bottom = bottom)) {
                            SlideText(state, slide.text, state.labelWithVersion(slide.label), theme, w, h - bottom, slide.text2)
                        }
                    }
                }
            }
            if (state.alert.isNotEmpty()) AlertBar(state.alert, maxHeight)
        }
    }
}

/** Duración del fundido entre diapositivas. Corto: se nota suave pero no hace esperar. */
internal const val FADE_MILLIS = 300

/** Lo que identifica a una diapositiva en pantalla (si cambia, hay fundido). */
private data class ShownSlide(val text: String, val label: String, val text2: String)

/** Imagen entera (con franjas negras si no es 16:9) o video con sonido. */
@Composable
private fun Announcement(file: File, preview: Boolean, light: Boolean) {
    if (isVideoFile(file.name)) {
        if (VideoBackgrounds.available) VideoBackground(file, maxFps = if (preview) 10 else 30, announcement = true, light = light)
    } else {
        val img = rememberBackground(file)
        if (img != null) Image(img, contentDescription = "Anuncio", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
    }
}

/**
 * Una diapositiva de PowerPoint o PDF, entera y centrada (con franjas negras si no es 16:9).
 * Mientras carga la siguiente se sigue viendo la anterior (sin parpadeo en negro).
 */
@Composable
private fun PresentationPage(file: File, next: File?) {
    var shown by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(file) {
        PresentationImages.cached(file)?.let { shown = it }
        withContext(Dispatchers.IO) { PresentationImages.load(file) }?.let { shown = it }
        // La siguiente queda lista para cuando se pase
        next?.let { withContext(Dispatchers.IO) { PresentationImages.load(it) } }
    }
    shown?.let { Image(it, contentDescription = "Diapositiva", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
}

/** Pocas diapositivas en memoria (cada una ocupa ~8 MB): las últimas usadas. */
object PresentationImages {
    private const val MAX = 6
    private val cache = object : LinkedHashMap<String, ImageBitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > MAX
    }

    private fun key(file: File) = file.absolutePath + "|" + file.lastModified()

    @Synchronized
    fun cached(file: File): ImageBitmap? = cache[key(file)]

    fun load(file: File): ImageBitmap? {
        cached(file)?.let { return it }
        val img = runCatching { org.jetbrains.skia.Image.makeFromEncoded(file.readBytes()).toComposeImageBitmap() }.getOrNull() ?: return null
        synchronized(this) { cache[key(file)] = img }
        return img
    }
}

/** Hora actual que se actualiza 4 veces por segundo mientras [active]. */
@Composable
private fun rememberClock(active: Boolean): Long {
    val now by produceState(System.currentTimeMillis(), active) {
        while (active) { value = System.currentTimeMillis(); kotlinx.coroutines.delay(250) }
    }
    return now
}

@Composable
private fun Countdown(text: String, seconds: Long, theme: Theme, width: Dp, height: Dp) {
    val color = hexColor(theme.textColor)
    val shadow = if (theme.shadow) Shadow(Color.Black.copy(alpha = 0.9f), Offset(2f, 3f), blurRadius = 12f) else null
    val clockSize = (height.value * 0.32f).coerceAtMost(width.value * 0.22f)
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        if (text.isNotEmpty()) {
            Text(text, style = TextStyle(color = color, fontSize = (clockSize * 0.28f).sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, shadow = shadow))
        }
        Text(
            app.selah.shared.formatClock(seconds),
            style = TextStyle(color = color, fontSize = clockSize.sp, fontWeight = FontWeight.Bold, shadow = shadow,
                fontFeatureSettings = "tnum"),
        )
    }
}

@Composable
private fun Logo(logo: ImageBitmap, height: Dp) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Image(logo, contentDescription = "Logo", modifier = Modifier.height(height * 0.55f), contentScale = ContentScale.Fit)
    }
}

private fun alertFontSize(height: Dp) = (height.value * 0.055f).coerceIn(12f, 60f)

/** Alto de la franja de alerta: letra + relleno arriba y abajo. */
internal fun alertBarHeight(height: Dp): Dp = (alertFontSize(height) * 1.35f + alertFontSize(height) * 0.7f).dp

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun AlertBar(text: String, height: Dp) {
    val size = alertFontSize(height)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Box(
            Modifier.fillMaxWidth().background(Color(0xE6B71C1C)).padding(vertical = (size * 0.35f).dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text,
                maxLines = 1,
                modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE).padding(horizontal = (size * 0.6f).dp),
                style = TextStyle(color = Color.White, fontSize = size.sp, fontWeight = FontWeight.SemiBold),
            )
        }
    }
}

@Composable
private fun Background(theme: Theme, image: ImageBitmap?, video: File?, frozenTime: Float?, preview: Boolean) {
    val top = hexColor(theme.color)
    val bottom = theme.color2?.let(::hexColor)
    val fill = if (bottom != null) Modifier.background(Brush.verticalGradient(listOf(top, bottom))) else Modifier.background(top)
    Box(Modifier.fillMaxSize().then(fill))
    // Prioridad: video o imagen (de la canción o del tema) > fondo incluido > color/degradado.
    val builtin = theme.builtin?.takeIf { BuiltinBackgrounds.has(it) }
    when {
        video != null -> VideoBackground(video, maxFps = if (preview) 10 else videoQuality(false, theme.light).maxFps, light = theme.light)
        image != null -> Image(image, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        builtin != null -> {
            val animated = theme.animate && frozenTime == null
            // Con tarjeta gráfica: dibujo directo a 30 cuadros (60 con "fluidez máxima"). Sin ella o en modo liviano: baja resolución a 24.
            val gpu = RenderInfo.hardware && !preview && !theme.light
            val clock = rememberAnimationTime(animated, fps = animationFps(preview, RenderInfo.hardware, theme.smooth, theme.light))
            BuiltinBackground(builtin, time = if (frozenTime != null) androidx.compose.runtime.mutableStateOf(frozenTime) else clock, direct = gpu)
        }
    }
    if (video != null || image != null || builtin != null) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = theme.dim)))
    }
}

/** Cuadros por segundo de los fondos animados incluidos. */
internal fun animationFps(preview: Boolean, gpu: Boolean, smooth: Boolean, light: Boolean): Int = when {
    preview -> 15
    light || !gpu -> 24
    smooth -> 60
    else -> 30
}

@Composable
private fun SlideText(state: PresentationState, text: String, label: String, theme: Theme, width: Dp, height: Dp, text2: String = "") {
    val pad = width * 0.05f
    // Biblia: la cita abajo. Predicación: "Punto 1", "Juan 10:11"… como encabezado arriba.
    val showLabel = (state.isBible || state.sermon) && label.isNotEmpty()
    val labelOnTop = state.sermon
    val color = hexColor(theme.textColor)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val availW = with(density) { (width - pad * 2).roundToPx() }.coerceAtLeast(1)
    val availH = with(density) { (height - pad * 2).roundToPx() }.coerceAtLeast(1)
    // fontScale > 1 solo sube el tope (si el texto cabe); < 1 achica lo calculado.
    val maxSize = 110f * theme.fontScale.coerceAtLeast(1f)
    val family = Fonts.family(theme.font)
    val size = remember(text, label, showLabel, availW, availH, maxSize, density, text2, family) {
        fitFontSizeMeasured(measurer, density, text, if (showLabel) label else null, availW, availH, maxSize, text2 = text2.ifEmpty { null }, family = family)
    } * theme.fontScale.coerceAtMost(1f)
    val shadow = if (theme.shadow) Shadow(Color.Black.copy(alpha = 0.9f), Offset(2f, 3f), blurRadius = size / 5) else null

    Column(
        Modifier.fillMaxSize().padding(pad),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (showLabel && labelOnTop) {
            Text(label, style = labelStyle(size, family).copy(color = color.copy(alpha = 0.8f), shadow = shadow, fontWeight = FontWeight.Bold))
            Spacer(Modifier.height(with(density) { (size * LABEL_GAP).sp.toDp() }))
        }
        Text(text, style = slideStyle(size, family).copy(color = color, shadow = shadow))
        if (text2.isNotEmpty()) {
            // Segunda traducción: debajo, un poco más chica y en cursiva.
            Spacer(Modifier.height(with(density) { (size * LABEL_GAP).sp.toDp() }))
            Text(text2, style = secondStyle(size, family).copy(color = color.copy(alpha = 0.85f), shadow = shadow))
        }
        if (showLabel && !labelOnTop) {
            Spacer(Modifier.height(with(density) { (size * LABEL_GAP).sp.toDp() }))
            Text(label, style = labelStyle(size, family).copy(color = color.copy(alpha = 0.75f), shadow = shadow))
        }
    }
}

private const val LABEL_GAP = 0.4f

internal fun slideStyle(size: Float, family: androidx.compose.ui.text.font.FontFamily? = null) = TextStyle(
    fontFamily = family,
    fontSize = size.sp,
    lineHeight = (size * 1.2f).sp,
    fontWeight = FontWeight.SemiBold,
    textAlign = TextAlign.Center,
)

/** Segunda traducción: 80 % del tamaño y en cursiva. */
internal fun secondStyle(size: Float, family: androidx.compose.ui.text.font.FontFamily? = null) = slideStyle(size * 0.8f, family).copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, fontWeight = FontWeight.Normal)

internal fun labelStyle(size: Float, family: androidx.compose.ui.text.font.FontFamily? = null) =
    TextStyle(fontFamily = family, fontSize = (size * 0.55f).sp, textAlign = TextAlign.Center)

/**
 * El tamaño de letra más grande (en sp) con el que el texto cabe de verdad en [widthPx]×[heightPx].
 * Mide con el mismo motor que dibuja, así que el corte por palabras está incluido. Además
 * exige que ninguna palabra sea más ancha que el área, para no partir palabras a la mitad.
 */
internal fun fitFontSizeMeasured(
    measurer: TextMeasurer,
    density: Density,
    text: String,
    label: String?,
    widthPx: Int,
    heightPx: Int,
    maxSize: Float = 110f,
    minSize: Float = 8f,
    text2: String? = null,
    family: androidx.compose.ui.text.font.FontFamily? = null,
): Float {
    val longestWords = text.split(Regex("\\s+")).filter { it.isNotEmpty() }.sortedByDescending { it.length }.take(3)
    val unbounded = Constraints()
    fun fits(sp: Float): Boolean {
        val style = slideStyle(sp, family)
        if (longestWords.any { measurer.measure(it, style, constraints = unbounded, density = density).size.width > widthPx }) return false
        var h = measurer.measure(text, style, constraints = Constraints(maxWidth = widthPx), density = density).size.height
        if (label != null) {
            h += measurer.measure(label, labelStyle(sp, family), constraints = Constraints(maxWidth = widthPx), density = density).size.height
            h += with(density) { (sp * LABEL_GAP).sp.roundToPx() }
        }
        if (text2 != null) {
            h += measurer.measure(text2, secondStyle(sp, family), constraints = Constraints(maxWidth = widthPx), density = density).size.height
            h += with(density) { (sp * LABEL_GAP).sp.roundToPx() }
        }
        return h <= heightPx
    }
    if (fits(maxSize)) return maxSize
    var lo = minSize
    var hi = maxSize
    repeat(12) {
        val mid = (lo + hi) / 2
        if (fits(mid)) lo = mid else hi = mid
    }
    return lo
}

/** "#RRGGBB" -> Color (negro si está mal escrito). */
fun hexColor(hex: String): Color =
    hex.removePrefix("#").toLongOrNull(16)?.let { Color(0xFF000000 or it) } ?: Color.Black

/** Imágenes de fondo ya decodificadas (se vuelven a leer si el archivo cambia). */
object BackgroundImages {
    private val cache = ConcurrentHashMap<String, Pair<Long, ImageBitmap>>()

    fun cached(file: File): ImageBitmap? = cache[file.absolutePath]?.takeIf { it.first == file.lastModified() }?.second

    fun load(file: File): ImageBitmap? {
        val key = file.absolutePath
        val modified = file.lastModified()
        cache[key]?.takeIf { it.first == modified }?.let { return it.second }
        return runCatching { org.jetbrains.skia.Image.makeFromEncoded(file.readBytes()).toComposeImageBitmap() }
            .getOrNull()
            ?.also { cache[key] = modified to it }
    }
}

/** Carga la imagen fuera del hilo de la interfaz para no trabar la proyección. */
@Composable
fun rememberBackground(file: File?): ImageBitmap? {
    val image by produceState(file?.let { BackgroundImages.cached(it) }, file) {
        value = file?.let { withContext(Dispatchers.IO) { BackgroundImages.load(it) } }
    }
    return image
}
