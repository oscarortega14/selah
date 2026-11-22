package app.selah.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import app.selah.shared.PresentationState
import app.selah.shared.Slide
import app.selah.shared.Theme
import java.awt.Desktop
import kotlin.math.roundToInt

/** Para qué es el fondo que se elige. */
internal enum class BgTarget { ALL, BIBLE, TYPE, SONG }

private val SAMPLE_BIBLE = PresentationState(
    title = "Juan 3",
    slides = listOf(Slide(label = "Juan 3:16", text = "Porque de tal manera amó Dios al mundo, que ha dado a su Hijo unigénito")),
    index = 0,
    isBible = true,
)

private val SAMPLE = PresentationState(
    title = "Vista previa",
    slides = listOf(Slide(text = "Cuán grande es Él\nCuán grande es Él")),
    index = 0,
)

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun BackgroundsWindow(
    library: Library,
    themes: ThemeStore,
    presenter: Presenter,
    onClose: () -> Unit,
) {
    val theme by themes.theme.collectAsState()
    val state by presenter.state.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }
    var searchOpen by remember { mutableStateOf(false) }
    val pixabay = remember { PixabayClient() }
    val images = remember(refresh) { library.backgrounds() }
    val videos = remember(refresh) { library.videos() }
    var message by remember { mutableStateOf<String?>(null) }
    val songId = state.songIdForBackground()
    // Para qué es el fondo que se toca: todo, la Biblia, un tipo de canción ("tipo:Adoración") o la canción en pantalla.
    var target by remember { mutableStateOf(BgTarget.ALL) }
    var targetType by remember { mutableStateOf<String?>(null) }
    val catalog by library.catalog.collectAsState()
    val types = remember(catalog) { catalog.songs.flatMap { it.tags }.distinctBy { app.selah.shared.normalizeKey(it) }.sortedBy { app.selah.shared.normalizeKey(it) } }
    if (target == BgTarget.SONG && songId == null) target = BgTarget.ALL
    val preview = when (target) {
        BgTarget.BIBLE -> SAMPLE_BIBLE
        BgTarget.TYPE -> SAMPLE.copy(songId = "tipo")
        else -> if (state.current != null && !state.blank) state else SAMPLE
    }
    val previewTags: (String) -> List<String> = { id -> if (target == BgTarget.TYPE && id == "tipo") listOfNotNull(targetType) else library.song(id)?.tags.orEmpty() }
    /** El fondo elegido para el destino actual (para marcarlo con el borde). */
    fun chosen(): String? = when (target) {
        BgTarget.ALL -> theme.image
        BgTarget.BIBLE -> theme.bibleImage
        BgTarget.TYPE -> targetType?.let { t -> theme.typeImages.entries.firstOrNull { app.selah.shared.normalizeKey(it.key) == app.selah.shared.normalizeKey(t) }?.value }
        BgTarget.SONG -> state.background
    }

    fun setSongBackground(name: String?) {
        val id = songId ?: return
        message = try {
            val newId = library.setSongBackground(id, name)
            presenter.songSaved(id, newId)
            if (name == null) "Se quitó el fondo propio de «${state.title}»" else "«${state.title}» usa ahora $name"
        } catch (e: Exception) {
            "No pude cambiar la canción: ${e.message}"
        }
    }

    fun apply(name: String?) {
        when (target) {
            BgTarget.ALL -> if (name != null) themes.update { it.copy(image = name) }
            BgTarget.BIBLE -> themes.update { it.copy(bibleImage = name) }
            BgTarget.TYPE -> targetType?.let { tp ->
                themes.update { th ->
                    val rest = th.typeImages.filterKeys { app.selah.shared.normalizeKey(it) != app.selah.shared.normalizeKey(tp) }
                    th.copy(typeImages = if (name == null) rest else rest + (tp to name))
                }
            }
            BgTarget.SONG -> setSongBackground(name)
        }
    }

    Window(
        onCloseRequest = onClose,
        title = "Fondos y letra",
        state = rememberFittedWindowState(DpSize(1200.dp, 760.dp)),
    ) {
        MaterialTheme(colorScheme = darkColorScheme()) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Row(Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Este fondo es para:", style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(target == BgTarget.ALL, { target = BgTarget.ALL }, { Text("Todo") })
                        FilterChip(target == BgTarget.BIBLE, { target = BgTarget.BIBLE }, { Text("Biblia") })
                        types.forEach { tp ->
                            FilterChip(target == BgTarget.TYPE && targetType == tp, { target = BgTarget.TYPE; targetType = tp }, { Text(tp) })
                        }
                        if (songId != null) FilterChip(target == BgTarget.SONG, { target = BgTarget.SONG }, { Text("Esta canción: ${state.title}", maxLines = 1) })
                    }
                    Text(
                        when (target) {
                            BgTarget.ALL -> "El fondo de siempre. Lo de abajo (Biblia, tipos de canción, una canción) lo cambia solo para eso."
                            BgTarget.BIBLE -> "Solo para los versículos." + if (theme.bibleImage == null) " Ahora usa el fondo de siempre." else " Ahora: ${theme.bibleImage}"
                            BgTarget.TYPE -> "Todas las canciones con «Tipo: $targetType»." + (chosen()?.let { " Ahora: $it" } ?: " Ahora usan el fondo de siempre.") +
                                " Para que una canción sea de este tipo, ponle la línea «Tipo: $targetType» en el editor."
                            BgTarget.SONG -> "Solo «${state.title}»." + (state.background?.let { " Ahora: $it" } ?: " Ahora usa el de su tipo o el de siempre.")
                        },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (target != BgTarget.ALL && chosen() != null) OutlinedButton({ apply(null) }, Modifier.hand()) { Text("Quitar: usar el fondo de siempre") }
                    // --- Elegir fondo ---
                    LazyVerticalGrid(
                        GridCells.Adaptive(150.dp),
                        Modifier.weight(1f).fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (target != BgTarget.ALL) item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                "Para esto se usa una imagen o un video de la carpeta de fondos (los incluidos y los colores son solo para «Todo»).",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (target == BgTarget.ALL) header("Fondos incluidos")
                        if (target == BgTarget.ALL) items(Theme.BUILTINS) { (key, name) ->
                            Tile(name, theme.image == null && theme.builtin == key, onClick = { themes.update { it.copy(builtin = key, image = null) } }) {
                                BuiltinBackground(key)
                            }
                        }
                        if (target == BgTarget.ALL) header("Colores")
                        if (target == BgTarget.ALL) items(Theme.PRESETS) { (name, preset) ->
                            val selected = theme.image == null && theme.builtin == null && theme.color == preset.color && theme.color2 == preset.color2
                            Tile(name, selected, onClick = { themes.update { it.copy(color = preset.color, color2 = preset.color2, image = null, builtin = null) } }) {
                                val top = hexColor(preset.color)
                                val bottom = preset.color2?.let(::hexColor) ?: top
                                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(top, bottom))))
                            }
                        }
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Videos en bucle (${videos.size})", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                Button({ searchOpen = true }) { Text("Buscar fondos en internet…") }
                            }
                        }
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                if (!VideoBackgrounds.available) {
                                    "Los videos de fondo no están disponibles en esta instalación (falta el reproductor incluido)."
                                } else if (videos.isEmpty()) {
                                    "Copia loops .mp4 (ej. de open.life.church) en la carpeta de fondos y presiona \"Actualizar\"."
                                } else "Se reproducen en bucle y sin sonido. Revisa la licencia de cada video si transmiten el culto.",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (!VideoBackgrounds.available) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        items(videos, key = { "v:$it" }) { name ->
                            Column {
                                Tile(name, chosen() == name, onClick = { apply(name) }) {
                                    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF1B1B22)), contentAlignment = Alignment.Center) {
                                        Text("▶", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                        }
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Imágenes (${images.size})", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                TextButton({ refresh++ }) { Text("Actualizar") }
                                TextButton({ runCatching { Desktop.getDesktop().open(library.backgroundsDir) } }) { Text("Abrir carpeta de fondos") }
                            }
                        }
                        if (images.isEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Text(
                                    "Copia fotos .jpg o .png en ${library.backgroundsDir.absolutePath} y presiona \"Actualizar\". " +
                                        "Las de 1920×1080 se ven mejor.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        items(images, key = { it }) { name ->
                            Column {
                                Tile(name, chosen() == name, onClick = { apply(name) }) {
                                    val bmp = rememberBackground(library.backgroundFile(name))
                                    if (bmp != null) Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                }
                                Row {
                                    TextButton({ themes.update { it.copy(logo = if (it.logo == name) null else name) } }, Modifier.weight(1f)) {
                                        Text(if (theme.logo == name) "✓ Logo" else "Usar de logo", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }

                    }

                    // --- Vista previa y letra ---
                    Column(Modifier.width(440.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Vista previa", style = MaterialTheme.typography.titleMedium)
                        Surface(Modifier.fillMaxWidth().aspectRatio(16f / 9f), shape = MaterialTheme.shapes.small) {
                            ProjectorScreen(preview, look = LocalLook.current.copy(theme = theme, preview = true, songTags = previewTags))
                        }
                        if (state.background != null) {
                            Text(
                                "La canción en pantalla tiene su propio fondo: ${state.background}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            OutlinedButton({ setSongBackground(null) }) { Text("Quitar el fondo propio de la canción") }
                        }

                        Text(
                            theme.logo?.let { "Logo de la iglesia: $it (pantalla de espera, tecla L)" }
                                ?: "Logo: elige una imagen con \"Usar de logo\" (mejor un PNG con fondo transparente)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        LabeledSlider("Oscurecer el fondo", theme.dim, 0f..0.85f, enabled = theme.image != null || theme.builtin != null || state.background != null) { v ->
                            themes.update { it.copy(dim = v) }
                        }
                        LabeledSlider("Tamaño de la letra", theme.fontScale, 0.6f..1.4f) { v -> themes.update { it.copy(fontScale = v) } }

                        Text("Color de la letra", style = MaterialTheme.typography.titleSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for ((name, hex) in Theme.TEXT_COLORS) {
                                FilterChip(
                                    selected = theme.textColor.equals(hex, ignoreCase = true),
                                    onClick = { themes.update { it.copy(textColor = hex) } },
                                    label = { Text(name) },
                                    leadingIcon = { Box(Modifier.width(14.dp).aspectRatio(1f).clip(RoundedCornerShape(3.dp)).background(hexColor(hex))) },
                                )
                            }
                        }
                        Text("Tipografía", style = MaterialTheme.typography.titleSmall)
                        androidx.compose.foundation.layout.FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            FilterChip(theme.font == null, { themes.update { it.copy(font = null) } }, { Text("Del sistema") })
                            for ((name, what) in Fonts.CHOICES) {
                                FilterChip(
                                    theme.font == name, { themes.update { it.copy(font = name) } },
                                    { Text("$name · $what", fontFamily = Fonts.family(name)) },
                                )
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Animación de los fondos incluidos", modifier = Modifier.weight(1f))
                            Switch(theme.animate, { v -> themes.update { it.copy(animate = v) } })
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Fluidez máxima (60 cuadros/s). Apágala si el PC va lento", modifier = Modifier.weight(1f))
                            Switch(theme.smooth, { v -> themes.update { it.copy(smooth = v) } }, enabled = theme.animate && !theme.light)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Modo liviano (para PCs lentos)")
                                Text(
                                    "Videos y fondos animados con menos resolución y cuadros: casi no se nota detrás de la letra y el PC trabaja mucho menos." +
                                        if (!RenderInfo.hardware) " Recomendado: este PC dibuja sin tarjeta gráfica." else "",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(theme.light, { v -> themes.update { it.copy(light = v) } })
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Fundido suave al cambiar de diapositiva", modifier = Modifier.weight(1f))
                            Switch(theme.fade, { v -> themes.update { it.copy(fade = v) } })
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Sombra detrás de la letra", modifier = Modifier.weight(1f))
                            Switch(theme.shadow, { v -> themes.update { it.copy(shadow = v) } })
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Traducción junto a la cita de la Biblia")
                                Text(
                                    "Abajo del versículo sale \"Josué 1:9 - NTV\", para saber qué versión se está leyendo.",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(theme.bibleVersion, { v -> themes.update { it.copy(bibleVersion = v) } })
                        }
                        message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                        Text(
                            "Los cambios se ven al instante en el videobeam. \"Pantalla negra\" sigue siendo negro total.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
    if (searchOpen) {
        PixabayWindow(library, themes, pixabay, onDownloaded = { refresh++ }, onClose = { searchOpen = false; refresh++ })
    }
}

private fun androidx.compose.foundation.lazy.grid.LazyGridScope.header(text: String) =
    item(span = { GridItemSpan(maxLineSpan) }) { Text(text, style = MaterialTheme.typography.titleMedium) }

@Composable
private fun Tile(name: String, selected: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.handClickable(onClick = onClick)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp))
                .border(
                    if (selected) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    RoundedCornerShape(6.dp),
                ),
        ) { content() }
        Text(name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, enabled: Boolean = true, onChange: (Float) -> Unit) {
    Column {
        Text("$label: ${(value * 100).roundToInt()}%", style = MaterialTheme.typography.titleSmall)
        Slider(value, onChange, valueRange = range, enabled = enabled)
    }
}

/**
 * La canción en pantalla a la que se le puede poner "fondo propio", o null. Con una predicación en pantalla
 * songId es el id de la predicación: no hay que tocar una canción que se llame igual.
 */
internal fun app.selah.shared.PresentationState.songIdForBackground(): String? =
    songId.takeIf { !sermon && !isBible && media == null && presentation == null }
