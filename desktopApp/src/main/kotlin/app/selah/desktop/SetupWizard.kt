package app.selah.desktop

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState

/** Una función que se puede encender o apagar desde el asistente. */
class WizardToggle(val get: (ChurchFeatures) -> Boolean, val set: (ChurchFeatures, Boolean) -> ChurchFeatures)

/** Una página del asistente: qué es, capturas (pasan solas como un video corto) y, si aplica, "la vamos a usar". */
data class WizardStep(
    val key: String,
    val title: String,
    val body: String,
    /** Archivos en resources/guia. */
    val images: List<String>,
    val toggle: WizardToggle? = null,
)

val WIZARD_STEPS = listOf(
    WizardStep(
        "bienvenida", "Bienvenido a Selah",
        "Selah muestra la letra de las canciones y la Biblia en el videobeam, y se controla desde este computador, una tablet o " +
            "un celular.\n\nEn las próximas páginas verás cada cosa que hace. En las que son opcionales eliges si tu iglesia la va a " +
            "usar: lo que no uses no aparece (ni aquí ni en los celulares). Todo se puede cambiar después.",
        listOf("pc-operador.jpg", "pc-proyeccion.jpg"),
    ),
    WizardStep(
        "letra", "Letra y Biblia en el videobeam",
        "Haz clic en una canción y avanza con → , Espacio o el clicker; retrocede con ←. C va al coro, 1–9 a cada sección, B pone " +
            "la pantalla en negro y Esc la vuelve a mostrar. En la pestaña Biblia escribe una cita como «Juan 3:16».\n\n" +
            "Con «+ Lista» preparas el orden del culto y N pasa al siguiente. La letra sale sola en la segunda pantalla (botón " +
            "«Proyección»); si se cierra por error, F5 la vuelve a abrir.",
        listOf("pc-operador.jpg", "pc-proyeccion.jpg"),
    ),
    WizardStep(
        "celular", "Control desde el celular",
        "Con la app Selah Remote (Android y iPhone) o el navegador, cualquiera en el Wi-Fi de la iglesia puede pasar la letra desde " +
            "el celular. En «Conectar dispositivos» está el código QR y una clave de 4 números para que nadie más la mueva.",
        listOf("cel-operador.jpg"),
    ),
    WizardStep(
        "congregacion", "La letra en el celular de la congregación",
        "Un código QR que la congregación escanea para seguir la letra en su celular, con letra grande. Solo ve lo que está en el " +
            "videobeam. El QR se puede proyectar como anuncio.",
        listOf("pc-qr.jpg", "cel-letra.jpg"),
        WizardToggle({ it.congregation }, { c, on -> c.copy(congregation = on) }),
    ),
    WizardStep(
        "click", "Click para los músicos",
        "Un metrónomo que suena a la vez en los celulares de los músicos, con audífonos (nunca por los parlantes): ideal sin consola " +
            "digital. Con la canción en marcha, «● Grabar tiempos» una vez y desde ahí la letra pasa sola con el click.",
        listOf("pc-operador.jpg", "cel-secuencia.jpg"),
        WizardToggle({ it.click }, { c, on -> c.copy(click = on) }),
    ),
    WizardStep(
        "secuencias", "Secuencias",
        "El audio de cada canción (MP3 o WAV, en la carpeta secuencias). Selah puede reproducirlo, o seguirlo si suena en otro " +
            "aparato: la letra y las partituras pasan solas. Si la secuencia trae voz guía («Verso… 1, 2, 3, 4»), Selah propone los " +
            "tiempos de la letra para que los revises.",
        listOf("cel-secuencia.jpg", "pc-que-usa.jpg"),
        WizardToggle({ it.sequences }, { c, on -> c.copy(sequences = on) }),
    ),
    WizardStep(
        "partituras", "Partituras",
        "Un PDF por canción e instrumento (carpeta partituras): «Canción - Bajo.pdf». Cada músico ve en su celular la de la canción " +
            "en pantalla: la de su instrumento primero (★), y las de los demás si quiere. Las páginas pasan solas con la letra.",
        listOf("cel-partitura.jpg"),
        WizardToggle({ it.scores }, { c, on -> c.copy(scores = on) }),
    ),
    WizardStep(
        "acordes", "Acordes",
        "Los acordes se escriben en la canción entre corchetes: «[G]Cuán grande es [C]Él». En el videobeam sale solo la letra; los " +
            "músicos ven los acordes y cada uno puede cambiar el tono.",
        listOf("cel-acordes.jpg"),
        WizardToggle({ it.chords }, { c, on -> c.copy(chords = on) }),
    ),
    WizardStep(
        "equipo", "El equipo: quién puede hacer qué",
        "Cada celular que se conecta pide entrar. En «Equipo» decides si es operador, director de alabanza, músico, predicador o " +
            "medios, y el computador no deja que nadie haga más de lo que le toca. A los músicos les sugieres su instrumento.",
        listOf("cel-esperando.jpg", "pc-equipo.jpg"),
    ),
    WizardStep(
        "listo", "¡Listo!",
        "Esto es lo que va a usar tu iglesia. Puedes cambiarlo cuando quieras en «¿Qué usa tu iglesia?», y ver esta guía otra vez " +
            "en «?».",
        listOf("pc-operador.jpg"),
    ),
)

/** Las páginas que conviene ver para configurar bien lo de los músicos (si se salta antes, se avisa). */
val WIZARD_IMPORTANT = setOf("congregacion", "click", "secuencias", "partituras", "acordes")

/** Por dónde va el asistente y qué páginas ya vio. */
class WizardState(val steps: List<WizardStep> = WIZARD_STEPS) {
    var page by mutableIntStateOf(0)
        private set
    private val seen = mutableSetOf(steps[0].key)

    val step get() = steps[page]
    val isLast get() = page == steps.lastIndex

    fun next() { if (!isLast) go(page + 1) }
    fun back() { if (page > 0) go(page - 1) }
    fun go(i: Int) { page = i.coerceIn(0, steps.lastIndex); seen += steps[page].key }

    /** Páginas importantes que no ha visto (para el aviso al saltar). */
    fun unseenImportant(): List<WizardStep> = steps.filter { it.key in WIZARD_IMPORTANT && it.key !in seen }

    /** Ir a la primera importante que falta. */
    fun goToImportant() { steps.indexOfFirst { it.key in WIZARD_IMPORTANT && it.key !in seen }.takeIf { it >= 0 }?.let(::go) }
}

private val imageCache = mutableMapOf<String, ImageBitmap?>()

private fun guideImage(name: String): ImageBitmap? = imageCache.getOrPut(name) {
    runCatching {
        val bytes = WizardStep::class.java.classLoader.getResourceAsStream("guia/$name")!!.use { it.readBytes() }
        org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
    }.getOrNull()
}

/** El asistente de la primera vez (y desde "?" → "Ver la guía"). */
@Composable
fun SetupWizard(church: ChurchFeatures, update: ((ChurchFeatures) -> ChurchFeatures) -> Unit, onDone: () -> Unit) {
    DialogWindow(icon = appIcon, 
        onCloseRequest = onDone, title = "Configurar Selah", resizable = true,
        state = rememberDialogState(size = DpSize(1000.dp, 700.dp)),
    ) {
        SetupWizardContent(church, update, onDone)
    }
}

@Composable
fun SetupWizardContent(church: ChurchFeatures, update: ((ChurchFeatures) -> ChurchFeatures) -> Unit, onDone: () -> Unit, state: WizardState = remember { WizardState() }) {
    var askSkip by remember { mutableStateOf(false) }
    val step = state.step
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(step.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Slideshow(step, Modifier.weight(1.3f).fillMaxHeight())
                    Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(step.body, style = MaterialTheme.typography.bodyLarge)
                        step.toggle?.let { t -> UseIt(t.get(church)) { on -> update { c -> t.set(c, on) } } }
                        if (step.key == "secuencias" && church.sequences) WhereItPlays(church, update)
                        if (step.key == "listo") Summary(church)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.steps.indices.forEach { i ->
                        Box(Modifier.size(9.dp).clip(CircleShape).background(if (i == state.page) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant))
                    }
                    Box(Modifier.weight(1f))
                    if (!state.isLast) TextButton({ if (state.unseenImportant().isEmpty()) onDone() else askSkip = true }, Modifier.hand()) { Text("Saltar") }
                    if (state.page > 0) OutlinedButton(state::back, Modifier.hand()) { Text("Anterior") }
                    Button({ if (state.isLast) onDone() else state.next() }, Modifier.hand()) { Text(if (state.isLast) "Empezar" else "Siguiente") }
                }
            }
            if (askSkip) {
                val missing = state.unseenImportant()
                AlertDialog(
                    onDismissRequest = { askSkip = false },
                    title = { Text("¿Saltar la guía?") },
                    text = {
                        Text(
                            "Te recomendamos ver por lo menos estas páginas (son cortas): " + missing.joinToString(", ") { "«${it.title}»" } +
                                ". Así Selah queda configurado con lo que usa tu iglesia.\n\nSi saltas, quedará así: " + summaryText(church) +
                                ". Puedes cambiarlo después en «¿Qué usa tu iglesia?» y ver esta guía otra vez en «?».",
                        )
                    },
                    confirmButton = { Button({ askSkip = false; state.goToImportant() }, Modifier.hand()) { Text("Verlas") } },
                    dismissButton = { TextButton({ askSkip = false; onDone() }, Modifier.hand()) { Text("Saltar igual") } },
                )
            }
        }
    }
}

/** Las capturas de la página, pasando solas (como un video corto). */
@Composable
private fun Slideshow(step: WizardStep, modifier: Modifier) {
    var shown by remember(step.key) { mutableIntStateOf(0) }
    LaunchedEffect(step.key) {
        while (step.images.size > 1) { kotlinx.coroutines.delay(3_000); shown = (shown + 1) % step.images.size }
    }
    Card(modifier, shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Crossfade(step.images.getOrNull(shown), animationSpec = tween(600), modifier = Modifier.fillMaxSize().padding(12.dp)) { name ->
            val img = name?.let(::guideImage)
            if (img != null) Image(img, step.title, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        }
    }
}

@Composable
private fun UseIt(on: Boolean, onChange: (Boolean) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (on) "Sí, la vamos a usar" else "No la vamos a usar", Modifier.weight(1f), fontWeight = FontWeight.Bold)
            Switch(on, onChange, Modifier.hand())
        }
    }
}

@Composable
private fun WhereItPlays(church: ChurchFeatures, update: ((ChurchFeatures) -> ChurchFeatures) -> Unit) {
    Column {
        Text("¿Dónde suena la secuencia?", fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(church.sequencePlays, { update { it.copy(sequencePlays = true) } }, Modifier.hand())
            Text("En este computador")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(!church.sequencePlays, { update { it.copy(sequencePlays = false) } }, Modifier.hand())
            Text("En otro aparato (Selah solo la sigue)")
        }
    }
}

/** "Letra y Biblia, click, secuencias…" */
fun summaryText(c: ChurchFeatures): String = (listOf("letra y Biblia") + listOfNotNull(
    "letra en el celular de la congregación".takeIf { c.congregation },
    "click para los músicos".takeIf { c.click },
    ("secuencias" + if (c.sequencePlays) "" else " (en otro aparato)").takeIf { c.sequences },
    "partituras".takeIf { c.scores },
    "acordes".takeIf { c.chords },
)).joinToString(", ")

@Composable
private fun Summary(church: ChurchFeatures) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Text("Tu iglesia va a usar: " + summaryText(church) + ".", Modifier.padding(14.dp), fontWeight = FontWeight.Bold)
    }
}
