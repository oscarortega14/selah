package app.selah.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.material3.FilterChip
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.selah.shared.ClientMessage

/**
 * Anuncios (imágenes y videos: cumpleaños, eventos, la ofrenda…) y presentaciones (PowerPoint o PDF),
 * a pantalla completa.
 */
@Composable
fun AnnouncementsTab(
    announcements: List<String>,
    current: String?,
    modifier: Modifier,
    send: (ClientMessage) -> Unit,
    present: (ClientMessage) -> Unit,
    onAdd: () -> Unit,
    onOpenFolder: () -> Unit,
    presentations: List<String> = emptyList(),
    currentPresentation: String? = null,
    onAddPresentations: () -> Unit = {},
    onOpenPresentationsFolder: () -> Unit = {},
    presentationsReady: Int = 0,
    /** Carrusel andando: segundos por imagen (0 = no). */
    carouselSeconds: Int = 0,
) {
    var showPresentations by rememberSaveable { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(!showPresentations, { showPresentations = false }, { Text("Anuncios (${announcements.size})") }, Modifier.hand())
            FilterChip(showPresentations, { showPresentations = true }, { Text("Presentaciones (${presentations.size})") }, Modifier.hand())
        }
        if (showPresentations) {
            PresentationsList(presentations, currentPresentation, Modifier.weight(1f), send, present, onAddPresentations, onOpenPresentationsFolder, presentationsReady)
        } else {
            AnnouncementsList(announcements, current, Modifier.weight(1f), send, present, onAdd, onOpenFolder, carouselSeconds)
        }
    }
}

@Composable
private fun AnnouncementsList(
    announcements: List<String>,
    current: String?,
    modifier: Modifier,
    send: (ClientMessage) -> Unit,
    present: (ClientMessage) -> Unit,
    onAdd: () -> Unit,
    onOpenFolder: () -> Unit,
    carouselSeconds: Int,
) {
    val look = LocalLook.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CarouselBar(announcements, carouselSeconds, present)
        if (announcements.isEmpty()) {
            Text(
                "Agrega imágenes o videos (eventos, cumpleaños, la ofrenda…). Se muestran a pantalla completa; " +
                    "los videos con sonido. También se pueden poner en la lista del culto.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyVerticalGrid(
            GridCells.Adaptive(140.dp), Modifier.weight(1f).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(announcements, key = { it }) { name ->
                val file = look.mediaFile(name)
                val selected = name == current
                Column(
                    Modifier.clip(RoundedCornerShape(8.dp))
                        .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                        .handClickable { present(ClientMessage.ShowMedia(name)) },
                ) {
                    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black), contentAlignment = Alignment.Center) {
                        if (file != null && isVideoFile(name)) {
                            Text("▶ Video", color = Color.White, style = MaterialTheme.typography.labelLarge)
                        } else {
                            rememberBackground(file)?.let {
                                Image(it, contentDescription = name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                            }
                        }
                    }
                    Text(
                        name.substringBeforeLast('.'), style = MaterialTheme.typography.labelMedium, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                    TextButton({ send(ClientMessage.PlanAddMedia(name)) }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("+ Lista") }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onAdd, Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) { Text("+ Agregar imagen o video…") }
        }
        Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
            TextButton(onOpenFolder) { Text("Abrir carpeta de anuncios") }
        }
    }
}

/** PowerPoint y PDF: miniatura de la primera diapositiva; clic para proyectarla desde el principio. */
@Composable
private fun PresentationsList(
    presentations: List<String>,
    current: String?,
    modifier: Modifier,
    send: (ClientMessage) -> Unit,
    present: (ClientMessage) -> Unit,
    onAdd: () -> Unit,
    onOpenFolder: () -> Unit,
    ready: Int,
) {
    val look = LocalLook.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (presentations.isEmpty()) {
            Text(
                "Agrega presentaciones de PowerPoint (.pptx) o PDF: cada diapositiva se pasa con Siguiente, " +
                    "igual que una canción. Si una de PowerPoint se ve distinta, guárdala como PDF desde PowerPoint " +
                    "(Archivo → Exportar → PDF).",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyVerticalGrid(
            GridCells.Adaptive(140.dp), Modifier.weight(1f).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(presentations, key = { it }) { name ->
                // [ready] cambia al terminar cada conversión: así aparece la miniatura sin recargar.
                val thumb = remember(name, ready) { look.presentationThumb(name, 0) }
                val selected = name == current
                Column(
                    Modifier.clip(RoundedCornerShape(8.dp))
                        .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                        .then(if (thumb != null) Modifier.handClickable { present(ClientMessage.ShowPresentation(name)) } else Modifier),
                ) {
                    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black), contentAlignment = Alignment.Center) {
                        val img = rememberBackground(thumb)
                        if (img != null) Image(img, contentDescription = name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                        else Text("Preparando…", color = Color.White, style = MaterialTheme.typography.labelMedium)
                    }
                    Text(
                        name.substringBeforeLast('.'), style = MaterialTheme.typography.labelMedium, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                    TextButton({ send(ClientMessage.PlanAddPresentation(name)) }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("+ Lista") }
                }
            }
        }
        Button(onAdd, Modifier.fillMaxWidth(), contentPadding = PaddingValues(4.dp)) { Text("+ Agregar PowerPoint o PDF…") }
        Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
            TextButton(onOpenFolder) { Text("Abrir carpeta de presentaciones") }
        }
    }
}

/**
 * Carrusel: las imágenes de Anuncios pasan solas (antes del culto, en el receso). Con él andando,
 * Siguiente/Anterior cambian de imagen; mostrar cualquier otra cosa lo detiene.
 */
@Composable
private fun CarouselBar(announcements: List<String>, seconds: Int, present: (ClientMessage) -> Unit) {
    val images = announcements.count { it.substringAfterLast('.', "").lowercase() in Library.IMAGE_EXTENSIONS }
    if (seconds > 0) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.primaryContainer).padding(start = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Carrusel: una imagen cada $seconds s", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            TextButton({ present(ClientMessage.StopCarousel) }, Modifier.hand()) { Text("Detener") }
        }
        return
    }
    if (images < 2) return
    var chosen by remember { mutableStateOf(app.selah.shared.CAROUSEL_SECONDS_DEFAULT) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Button({ present(ClientMessage.StartCarousel(chosen)) }, Modifier.hand(), contentPadding = PaddingValues(horizontal = 10.dp)) {
            Text("▶ Carrusel ($images imágenes)")
        }
        for (s in listOf(5, 8, 15, 30)) {
            FilterChip(chosen == s, { chosen = s }, { Text("$s s") }, Modifier.hand())
        }
    }
}

