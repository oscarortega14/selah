package app.selah.remote

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.selah.shared.FEATURE_CHORDS
import app.selah.shared.FEATURE_CLICK
import app.selah.shared.FEATURE_SCORES
import app.selah.shared.FEATURE_SEQUENCES
import app.selah.shared.GENERAL_INSTRUMENT
import app.selah.shared.PresentationState
import app.selah.shared.ScoreRef
import app.selah.shared.Transpose
import app.selah.shared.instrumentsOf
import app.selah.shared.scoreFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** ¿El PC tiene encendido algo para los músicos (partituras, acordes o secuencias)? */
fun hasMusicianFeatures(features: List<String>): Boolean = listOf(FEATURE_SCORES, FEATURE_CHORDS, FEATURE_SEQUENCES).any { it in features }

/** Qué ve el músico de la canción en pantalla. */
enum class MusicView(val label: String) { SCORE("Partitura"), CHORDS("Acordes"), LYRICS("Letra") }

/** Las vistas que tienen sentido para esta canción, la mejor primero. */
fun musicViews(features: List<String>, hasScore: Boolean, hasChords: Boolean): List<MusicView> = buildList {
    if (FEATURE_SCORES in features && hasScore) add(MusicView.SCORE)
    if (FEATURE_CHORDS in features && hasChords) add(MusicView.CHORDS)
    add(MusicView.LYRICS)
}

/**
 * Pantalla del músico: la canción en pantalla con su partitura (la de su instrumento), los acordes en el tono
 * que quiera, o la letra; y el click o la secuencia. Sigue sola al operador: no hay que tocar nada.
 */
@Composable
fun MusicianView(
    client: RemoteClient,
    state: PresentationState,
    features: List<String>,
    scores: List<ScoreRef>,
    sequences: List<String>,
    instrument: String?,
    onInstrument: (String?) -> Unit,
    canControl: Boolean,
    onNeedPin: () -> Unit,
) {
    val songId = state.songId?.takeIf { !state.isBible && !state.sermon && state.media == null && state.presentation == null }
    val score = songId?.let { scoreFor(scores, it, instrument) }
    val hasChords = state.slides.any { it.chords.isNotEmpty() }
    val views = musicViews(features, score != null, hasChords)
    var chosen by rememberSaveable { mutableStateOf<String?>(null) }
    // Lo que eligió si todavía sirve en esta canción; si no, la mejor que haya.
    val view = views.firstOrNull { it.name == chosen } ?: views.first()
    val transpose = remember { mutableStateMapOf<String, Int>() }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (FEATURE_CLICK in features || FEATURE_SEQUENCES in features) {
            ClickPanel(
                state.click, { client.serverOffset }, client::send, { client.clockRtt },
                clickAllowed = FEATURE_CLICK in features,
                sequenceAvailable = FEATURE_SEQUENCES in features && songId != null && songId in sequences,
                canControl = canControl, onNeedPin = onNeedPin,
            )
        }
        Text(
            state.title.ifEmpty { "Esperando la canción…" },
            style = MaterialTheme.typography.titleLarge, maxLines = 1,
        )
        state.currentLabel?.let { Text(it, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) }
        val instruments = instrumentsOf(scores).filter { it != GENERAL_INSTRUMENT }
        if (FEATURE_SCORES in features && instruments.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                item { Text("Toco:", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(instruments) { i -> FilterChip(instrument.equals(i, ignoreCase = true), { onInstrument(if (instrument.equals(i, true)) null else i) }, { Text(i) }) }
            }
        }
        if (views.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                views.forEach { v -> FilterChip(v == view, { chosen = v.name }, { Text(v.label) }) }
            }
        }
        when (view) {
            MusicView.SCORE -> ScoreView(client, score!!.file, if (score.instrument == GENERAL_INSTRUMENT && instrument != null) "No hay de $instrument: esta es para todos" else null)
            MusicView.CHORDS -> ChordsView(state, transpose[songId] ?: 0) { songId?.let { id -> transpose[id] = it } }
            MusicView.LYRICS -> LyricsView(state)
        }
    }
}

@Composable
private fun ScoreView(client: RemoteClient, file: String, note: String?) {
    // Páginas que van llegando (la primera se ve sin esperar las demás).
    val pages by produceState<List<ImageBitmap>?>(null, file) {
        value = null
        val count = client.scorePageCount(file)
        if (count == null) { value = emptyList(); return@produceState }
        val loaded = mutableListOf<ImageBitmap>()
        for (i in 0 until count.coerceAtMost(MAX_SCORE_PAGES)) {
            val img = client.scorePage(file, i)?.let { bytes -> withContext(Dispatchers.Default) { decodeImage(bytes) } } ?: continue
            loaded += img
            value = loaded.toList()
        }
        if (loaded.isEmpty()) value = emptyList()
    }
    var zoom by rememberSaveable { mutableStateOf(1f) }
    note?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Tamaño:", color = MaterialTheme.colorScheme.onSurfaceVariant)
        listOf(1f to "Normal", 1.5f to "Grande", 2f to "Muy grande").forEach { (z, label) -> FilterChip(zoom == z, { zoom = z }, { Text(label) }) }
    }
    val list = pages
    when {
        list == null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(Modifier.width(24.dp)); Text("Cargando la partitura…")
        }
        list.isEmpty() -> Text("No pude cargar la partitura. Revisa la conexión con el computador.", color = MaterialTheme.colorScheme.error)
        // El ancho se mide antes de desplazar de lado (dentro del desplazamiento no hay límite de ancho).
        else -> BoxWithConstraints(Modifier.fillMaxSize()) {
            val width = maxWidth * zoom
            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
                LazyColumn(Modifier.width(width), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(list) { img ->
                        Image(img, "Página de la partitura", Modifier.width(width).background(Color.White), contentScale = ContentScale.FillWidth)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChordsView(state: PresentationState, semis: Int, onSemis: (Int) -> Unit) {
    val key = Transpose.keyOf(state.slides)
    val flats = Transpose.flatsFor(key, semis)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            if (key == null) "Tono" else if (semis == 0) "Tono: $key" else "Tono: ${Transpose.key(key, semis)} (original $key)",
            Modifier.weight(1f), fontWeight = FontWeight.Bold,
        )
        OutlinedButton({ onSemis(semis - 1) }) { Text("♭ −", fontSize = 16.sp) }
        OutlinedButton({ onSemis(semis + 1) }) { Text("♯ +", fontSize = 16.sp) }
        if (semis != 0) TextButton({ onSemis(0) }) { Text("Original") }
    }
    SlideList(state) { slide ->
        val source = slide.chords.ifEmpty { slide.text }
        source.lines().forEach { raw ->
            val (chords, text) = Transpose.layout(Transpose.line(raw, semis, flats))
            if (chords.isNotEmpty()) Text(chords, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.tertiary, fontSize = 15.sp, softWrap = false)
            if (text.isNotEmpty()) Text(text, fontFamily = FontFamily.Monospace, fontSize = 15.sp, softWrap = false)
        }
    }
}

@Composable
private fun LyricsView(state: PresentationState) {
    SlideList(state) { slide -> Text(slide.text, fontSize = 17.sp) }
}

/** Las diapositivas, con la que está en pantalla resaltada y siempre a la vista. */
@Composable
private fun SlideList(state: PresentationState, content: @Composable (app.selah.shared.Slide) -> Unit) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.index, state.title) { if (state.index >= 0) listState.animateScrollToItem(state.index) }
    LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(state.slides) { i, slide ->
            val selected = i == state.index
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant),
                border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
            ) {
                Column(Modifier.padding(12.dp).horizontalScroll(rememberScrollState())) {
                    if (slide.label.isNotEmpty()) Text(slide.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    content(slide)
                }
            }
        }
    }
}

/** Más páginas no tiene una partitura de una canción (y cada una ocupa memoria en el celular). */
private const val MAX_SCORE_PAGES = 30
