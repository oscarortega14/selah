package app.selah.remote

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import app.selah.shared.PlanKind
import app.selah.shared.PresentationState
import app.selah.shared.ServerMessage
import app.selah.shared.ServicePlanState
import app.selah.shared.SongSummary
import app.selah.shared.search
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
 * Lo que ve el músico: lo que está en pantalla, o la canción que eligió mirar por su cuenta ([browsing]) cuando
 * ya llegó del PC. null = la eligió pero todavía no llega (se muestra "Cargando…").
 */
fun musicianShown(live: PresentationState, browsing: String?, viewed: ServerMessage.SongView?): PresentationState? = when {
    browsing == null -> live
    viewed?.id != browsing -> null
    // Sin diapositiva marcada: el videobeam no la está mostrando, así que nada se resalta ni se desplaza solo.
    else -> PresentationState(viewed.title, viewed.slides, index = -1, songId = viewed.id)
}

/** Para elegir otra canción: primero las de la lista del culto (en su orden), después las demás que coinciden. */
fun musicianChoices(plan: ServicePlanState, songs: List<SongSummary>, query: String): Pair<List<SongSummary>, List<SongSummary>> {
    val byId = songs.associateBy { it.id }
    val inPlan = plan.items.filter { it.kind == PlanKind.SONG }.mapNotNull { byId[it.ref] }.distinctBy { it.id }
    val found = songs.search(query)
    val planIds = inPlan.map { it.id }.toSet()
    return inPlan.filter { it in found } to found.filter { it.id !in planIds }
}

/** Las canciones de cada culto de la semana que coinciden con [query] (los cultos sin canciones no salen). */
fun weekChoices(week: List<app.selah.shared.WeekService>, songs: List<SongSummary>, query: String): List<Pair<app.selah.shared.WeekService, List<SongSummary>>> {
    val byId = songs.associateBy { it.id }
    return week.map { w ->
        w to w.items.filter { it.kind == PlanKind.SONG }.map { byId[it.ref] ?: SongSummary(it.ref, it.title) }.distinctBy { it.id }.search(query)
    }.filter { it.second.isNotEmpty() }
}

/**
 * Pantalla del músico: la canción en pantalla con su partitura (la de su instrumento), los acordes en el tono
 * que quiera, o la letra; y el click o la secuencia. Sigue sola al operador, pero puede mirar otra canción por su
 * cuenta ("Ver otra canción") sin que el videobeam cambie ni lo saquen de ahí, hasta que toque "Volver a en vivo".
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
    /** Preferencias del teléfono: dónde empieza cada página de cada partitura (lo que corrigió el músico). */
    prefs: KeyValueStore? = null,
    /** El instrumento que le sugiere el PC (se marca con ★); puede ver todos igual. */
    suggestedInstrument: String? = null,
    /** Mostrar los botones de iniciar/parar el click y la secuencia (el músico con papel solo ve el pulso). */
    showControls: Boolean = true,
    /** Para mirar otra canción por su cuenta: las canciones, la lista del culto y la que llegó del PC. */
    songs: List<SongSummary> = emptyList(),
    plan: ServicePlanState = ServicePlanState(),
    viewed: ServerMessage.SongView? = null,
    /** Pide una canción al PC sin proyectarla; null = este PC no sabe (no se ofrece). */
    onView: ((String) -> Unit)? = null,
    /** La canción que mira por su cuenta (null = sigue al operador). Vive afuera para no perderse al reconectar. */
    browsingState: androidx.compose.runtime.MutableState<String?> = rememberSaveable { mutableStateOf(null) },
    /** Los cultos de la semana (para elegir entre sus canciones). */
    week: List<app.selah.shared.WeekService> = emptyList(),
) {
    val live = state
    var browsing by browsingState
    var picking by rememberSaveable { mutableStateOf(false) }
    // Si el operador pone justo la que estaba mirando, vuelve a seguirlo (con la diapositiva resaltada).
    LaunchedEffect(live.songId, live.isBible) { if (browsing != null && !live.isBible && live.songId == browsing) browsing = null }
    // Al reconectar se vuelve a pedir la que estaba mirando.
    LaunchedEffect(browsing, viewed == null) { browsing?.let { if (viewed?.id != it) onView?.invoke(it) } }
    @Suppress("NAME_SHADOWING")
    val state = musicianShown(live, browsing, viewed) ?: PresentationState(title = songs.find { it.id == browsing }?.title.orEmpty())
    val liveSongId = live.songId?.takeIf { !live.isBible && !live.sermon && live.media == null && live.presentation == null }

    BoxWithConstraints(Modifier.fillMaxSize()) {
    // iPad o tablet: el título en una línea y todos los controles en una sola fila, para que quepa más partitura.
    val wide = maxWidth >= WIDE
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if ((FEATURE_CLICK in features || FEATURE_SEQUENCES in features) && (showControls || live.click != null)) {
            ClickPanel(
                live.click, { client.serverOffset }, client::send, { client.clockRtt },
                clickAllowed = FEATURE_CLICK in features,
                sequenceAvailable = FEATURE_SEQUENCES in features && liveSongId != null && liveSongId in sequences,
                canControl = canControl, onNeedPin = onNeedPin, controls = showControls,
            )
        }
        // En iPad, siguiendo al operador, el botón va en la fila del título (más espacio para la partitura).
        val pickInTitle = wide && onView != null && browsing == null && !picking
        if (onView != null && !pickInTitle) BrowseBar(browsing != null, picking, live.title,
            onPick = { picking = true }, onCancel = { picking = false }, onLive = { browsing = null; picking = false })
        if (picking && onView != null) {
            SongPicker(plan, songs, liveSongId, week) { id ->
                picking = false
                browsing = if (id == liveSongId) null else id // el pedido al PC lo hace el LaunchedEffect de arriba
            }
        } else {
            MusicSheet(
                client, state, features, scores, instrument, onInstrument, prefs, suggestedInstrument, wide,
                loading = browsing != null && viewed?.id != browsing,
                missing = browsing != null && viewed?.id == browsing && state.slides.isEmpty(),
                titleAction = if (pickInTitle) ({ OutlinedButton({ picking = true }) { Text("Ver otra canción") } }) else null,
            )
        }
    }
    }
}

/**
 * Una canción para el músico: título, instrumento, Partitura / Acordes / Letra, tamaño y el contenido.
 * [state] es lo que se ve (en vivo o la que se eligió mirar); [titleAction] va a la derecha del título en iPad.
 */
@Composable
fun MusicSheet(
    client: RemoteClient,
    state: PresentationState,
    features: List<String>,
    scores: List<ScoreRef>,
    instrument: String?,
    onInstrument: (String?) -> Unit,
    prefs: KeyValueStore?,
    suggestedInstrument: String?,
    wide: Boolean,
    loading: Boolean = false,
    missing: Boolean = false,
    titleAction: (@Composable () -> Unit)? = null,
) {
    val songId = state.songId?.takeIf { !state.isBible && !state.sermon && state.media == null && state.presentation == null }
    val score = songId?.let { scoreFor(scores, it, instrument) }
    val hasChords = state.slides.any { it.chords.isNotEmpty() }
    val views = musicViews(features, score != null, hasChords)
    var chosen by rememberSaveable { mutableStateOf<String?>(null) }
    // Lo que eligió si todavía sirve en esta canción; si no, la mejor que haya.
    val view = views.firstOrNull { it.name == chosen } ?: views.first()
    val transpose = remember { mutableStateMapOf<String, Int>() }

    // La partitura: tamaño y si pasa las páginas sola con la letra (se recuerda en este aparato).
    var zoom by rememberSaveable { mutableStateOf(1f) }
    var autoPages by remember { mutableStateOf(prefs?.get("paginasSolas") != "no") }
    val toggleAuto: () -> Unit = { autoPages = !autoPages; prefs?.put("paginasSolas" to if (autoPages) "si" else "no") }
    val instruments = instrumentsOf(scores).filter { it != GENERAL_INSTRUMENT }
    val showInstruments = FEATURE_SCORES in features && instruments.isNotEmpty()
    val instrumentChips: @Composable () -> Unit = {
        instruments.forEach { i ->
            val mine = suggestedInstrument.equals(i, ignoreCase = true)
            FilterChip(instrument.equals(i, ignoreCase = true), { onInstrument(if (instrument.equals(i, true)) null else i) }, { Text(if (mine) "★ $i" else i) })
        }
    }
    val viewChips: @Composable () -> Unit = { views.forEach { v -> FilterChip(v == view, { chosen = v.name }, { Text(v.label) }) } }
    val sizeChips: @Composable () -> Unit = { ZOOMS.forEach { (z, label) -> FilterChip(zoom == z, { zoom = z }, { Text(label) }) } }
    val autoChip: @Composable () -> Unit = { FilterChip(autoPages, toggleAuto, { Text("Pasar páginas solo") }) }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (wide) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(state.title.ifEmpty { "Esperando la canción…" }, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleLarge, maxLines = 1)
                    state.currentLabel?.let { Text(it, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, maxLines = 1) }
                }
                titleAction?.invoke()
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                var first = true
                val group: @Composable (@Composable () -> Unit) -> Unit = { chips ->
                    if (!first) androidx.compose.material3.VerticalDivider(Modifier.height(28.dp).padding(horizontal = 6.dp))
                    first = false
                    chips()
                }
                // Partitura / Acordes / Letra primero: los instrumentos son muchos y los taparían.
                if (views.size > 1) group(viewChips)
                if (view == MusicView.SCORE) { group(sizeChips); group(autoChip) }
                if (showInstruments) group(instrumentChips)
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(state.title.ifEmpty { "Esperando la canción…" }, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, maxLines = 1)
                titleAction?.invoke()
            }
            state.currentLabel?.let { Text(it, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) }
            if (showInstruments) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Toco:", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    instrumentChips()
                }
            }
            if (views.size > 1) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { viewChips() }
            if (view == MusicView.SCORE) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Tamaño:", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    sizeChips()
                }
                autoChip()
            }
        }
        when {
            loading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.width(24.dp)); Text("Cargando la canción…")
            }
            missing -> Text("No encontré esa canción en el computador (¿la borraron o le cambiaron el nombre?).", color = MaterialTheme.colorScheme.error)
            else -> when (view) {
                MusicView.SCORE -> ScoreView(
                    client, score!!.file, if (score.instrument == GENERAL_INSTRUMENT && instrument != null) "No hay de $instrument: esta es para todos" else null,
                    index = state.index, slideCount = state.slides.size, prefs = prefs, zoom = zoom, autoPages = autoPages,
                )
                MusicView.CHORDS -> ChordsView(state, transpose[songId] ?: 0) { songId?.let { id -> transpose[id] = it } }
                MusicView.LYRICS -> LyricsView(state)
            }
        }
    }
}

/** "Ver otra canción" mientras sigue al operador; "Volver a en vivo" mientras mira una por su cuenta. */
@Composable
private fun BrowseBar(browsing: Boolean, picking: Boolean, liveTitle: String, onPick: () -> Unit, onCancel: () -> Unit, onLive: () -> Unit) {
    when {
        picking -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Elige la canción que quieres ver", Modifier.weight(1f), fontWeight = FontWeight.Bold)
            TextButton(onCancel) { Text("Cancelar") }
        }
        browsing -> Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Mirando por tu cuenta", fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    if (liveTitle.isNotEmpty()) Text(
                        "En pantalla: $liveTitle", fontSize = 13.sp, maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
                TextButton(onPick) { Text("Otra") }
                Button(onLive) { Text("Volver a en vivo") }
            }
        }
        else -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            OutlinedButton(onPick) { Text("Ver otra canción") }
        }
    }
}

/** Elegir qué canción mirar: las de la lista del culto primero, y todas con un buscador. */
@Composable
private fun SongPicker(plan: ServicePlanState, songs: List<SongSummary>, liveSongId: String?, week: List<app.selah.shared.WeekService>, onChoose: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val (inPlan, others) = musicianChoices(plan, songs, query)
    // El culto que ya está en curso no se repite abajo.
    val weekSongs = weekChoices(week.filterNot { inPlan.isNotEmpty() && it.list != null && it.list.equals(plan.name, ignoreCase = true) }, songs, query)
    OutlinedTextField(query, { query = it }, label = { Text("Buscar por título") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (inPlan.isNotEmpty()) item { Text("En curso" + if (plan.name.isNotEmpty()) " · ${plan.name}" else "", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp)) }
        items(inPlan, key = { "plan-" + it.id }) { SongChoice(it, it.id == liveSongId, onChoose) }
        // Los cultos de la semana, cada uno con sus canciones (para ensayar lo que viene).
        for ((w, list) in weekSongs) {
            item(key = "w-" + w.date + w.name) { Text("${w.name} · ${w.day}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp)) }
            items(list, key = { "w-" + w.date + w.name + "-" + it.id }) { SongChoice(it, it.id == liveSongId, onChoose) }
        }
        if (others.isNotEmpty()) item { Text("Todas las canciones", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp)) }
        items(others, key = { it.id }) { SongChoice(it, it.id == liveSongId, onChoose) }
        if (inPlan.isEmpty() && others.isEmpty()) item { Text("No hay canciones con ese nombre.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun SongChoice(song: SongSummary, live: Boolean, onChoose: (String) -> Unit) {
    Card(
        Modifier.fillMaxWidth().clickable { onChoose(song.id) },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(song.title + if (live) "  · en pantalla" else "", fontWeight = FontWeight.Medium, maxLines = 1)
            if (song.firstLine.isNotEmpty()) Text(song.firstLine, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

private val ZOOMS = listOf(1f to "Normal", 1.5f to "Grande", 2f to "Muy grande")

@Composable
private fun ScoreView(client: RemoteClient, file: String, note: String?, index: Int, slideCount: Int, prefs: KeyValueStore?, zoom: Float, autoPages: Boolean) {
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
    // Lo que el músico corrigió para esta partitura (qué página va con cada diapositiva).
    var marks by remember(file) { mutableStateOf(parsePageMarks(prefs?.get("paginas:$file"))) }
    val listState = rememberLazyListState()
    val pageCount = pages?.size ?: 0
    LaunchedEffect(index, pageCount, autoPages) {
        if (autoPages && pageCount > 1 && index >= 0) {
            val target = pageFor(index, slideCount, pageCount, marks)
            if (listState.firstVisibleItemIndex != target) listState.animateScrollToItem(target)
        }
    }
    // El músico deslizó a mano: esa página es la de esta diapositiva (se recuerda en este celular).
    val dragged by listState.interactionSource.collectIsDraggedAsState()
    var everDragged by remember(file) { mutableStateOf(false) }
    LaunchedEffect(dragged) {
        if (dragged) { everDragged = true; return@LaunchedEffect }
        // Solo después de que el músico deslizó (no al abrir ni cuando la página pasa sola).
        if (everDragged && autoPages && index >= 0 && pageCount > 1) {
            kotlinx.coroutines.delay(400)
            val page = listState.firstVisibleItemIndex
            if (page != pageFor(index, slideCount, pageCount, marks)) {
                marks = marks + (index to page)
                prefs?.put("paginas:$file" to marks.entries.joinToString(",") { "${it.key}:${it.value}" })
            }
        }
    }
    note?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    if (marks.isNotEmpty()) TextButton({ marks = emptyMap(); prefs?.put("paginas:$file" to "") }) { Text("Olvidar mis correcciones") }
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
                LazyColumn(Modifier.width(width), state = listState, verticalArrangement = Arrangement.spacedBy(6.dp)) {
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

/**
 * Qué página de la partitura va con la diapositiva [index]: la que el músico marcó para esa diapositiva o una
 * anterior; si no marcó nada, en proporción (a la mitad de la canción, la mitad de las páginas).
 */
fun pageFor(index: Int, slideCount: Int, pageCount: Int, marks: Map<Int, Int>): Int {
    if (pageCount <= 1 || index < 0) return 0
    marks.filterKeys { it <= index }.maxByOrNull { it.key }?.let { return it.value.coerceIn(0, pageCount - 1) }
    return (index.toLong() * pageCount / slideCount.coerceAtLeast(1)).toInt().coerceIn(0, pageCount - 1)
}

/** "3:1,8:2" → {3=1, 8=2} (diapositiva → página). */
fun parsePageMarks(text: String?): Map<Int, Int> = text.orEmpty().split(',').mapNotNull { p ->
    val (a, b) = p.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
    (a.trim().toIntOrNull() ?: return@mapNotNull null) to (b.trim().toIntOrNull() ?: return@mapNotNull null)
}.toMap()
