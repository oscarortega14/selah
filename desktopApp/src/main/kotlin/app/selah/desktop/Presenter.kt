package app.selah.desktop

import app.selah.shared.ClientMessage
import app.selah.shared.CAROUSEL_MAX_SECONDS
import app.selah.shared.CAROUSEL_MIN_SECONDS
import app.selah.shared.PresentationState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Única fuente de verdad de lo que se proyecta. La ventana de operador y los
 * remotos mandan las mismas órdenes (ClientMessage); todos ven el mismo estado.
 */
class Presenter(
    private val library: Library,
    /** Reloj del PC (inyectable para las pruebas de la cuenta regresiva). */
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Se llama cada vez que se proyecta una canción o una lectura (para el historial). */
    var onShown: ((HistoryEntry.Kind, title: String, ref: String) -> Unit)? = null

    /**
     * Se llama cuando el editor le cambió el nombre (y con él el id = nombre de archivo) a una canción o
     * predicación, para que la lista del culto y el historial sigan apuntando a ella.
     */
    val renameListeners = mutableListOf<(kind: HistoryEntry.Kind, oldId: String, newId: String, title: String) -> Unit>()

    private fun notifyRenamed(kind: HistoryEntry.Kind, oldId: String?, newId: String, title: String) {
        if (oldId != null && oldId != newId) renameListeners.forEach { it(kind, oldId, newId, title) }
    }

    private val _state = MutableStateFlow(PresentationState())
    val state: StateFlow<PresentationState> = _state.asStateFlow()

    /** Aplica una orden. Devuelve un mensaje de error para mostrar, o null si salió bien. */
    @Synchronized
    fun handle(msg: ClientMessage): String? = handleInner(msg).also { err -> if (err != null) Log.warn("Orden ${msg::class.simpleName}: $err") }

    private fun handleInner(msg: ClientMessage): String? = when (msg) {
        is ClientMessage.SelectSong -> selectSong(msg.id)
        is ClientMessage.GoTo -> { _state.update { s -> if (msg.index in s.slides.indices) s.copy(index = msg.index) else s }; null }
        // Con el carrusel andando, Siguiente/Anterior pasan de imagen (y el tiempo vuelve a empezar).
        ClientMessage.Next -> if (_state.value.carouselSeconds > 0) carouselStep(+1) else { _state.update { s -> s.copy(index = (s.index + 1).coerceAtMost(s.slides.lastIndex)) }; null }
        ClientMessage.Prev -> if (_state.value.carouselSeconds > 0) carouselStep(-1) else { _state.update { s -> s.copy(index = (s.index - 1).coerceAtLeast(if (s.slides.isEmpty()) -1 else 0)) }; null }
        is ClientMessage.StartCarousel -> startCarousel(msg.seconds, msg.names)
        ClientMessage.StopCarousel -> { _state.update { it.copy(carouselSeconds = 0) }; null }
        is ClientMessage.SetBlank -> { _state.update { it.copy(blank = msg.blank) }; null }
        is ClientMessage.ShowBible -> showBible(msg.reference)
        is ClientMessage.ShowMedia -> showMedia(msg.name)
        is ClientMessage.ShowPresentation -> showPresentation(msg.name)
        is ClientMessage.ShowSermon -> showSermon(msg.id)
        is ClientMessage.StageMessage -> { _state.update { it.copy(stageMessage = msg.text.trim()) }; null }
        is ClientMessage.ShowLogo -> { _state.update { it.copy(logo = msg.show, blank = if (msg.show) false else it.blank) }; null }
        is ClientMessage.SetAlert -> { _state.update { it.copy(alert = msg.text.trim()) }; null }
        is ClientMessage.StartCountdown -> startCountdown(msg.seconds, msg.text)
        ClientMessage.StopCountdown -> { _state.update { it.copy(countdownEnd = null, countdownText = "") }; null }
        is ClientMessage.StartTimer -> {
            if (msg.minutes !in 0..600) "El tiempo debe ser de 0 a 600 minutos"
            else { _state.update { it.copy(timerStart = clock(), timerMinutes = msg.minutes) }; null }
        }
        is ClientMessage.SetLowerThird -> { _state.update { it.copy(lowerThird = msg.title.trim(), lowerThirdSub = msg.subtitle.trim()) }; null }
        ClientMessage.StopTimer -> { _state.update { it.copy(timerStart = null, timerMinutes = 0) }; null }
        // Las órdenes de la lista del culto las atiende ServicePlan (ver Commands).
        else -> "Orden no soportada aquí"
    }

    private fun selectSong(id: String): String? {
        val song = library.song(id) ?: return "Canción no encontrada (¿se borró el archivo?)"
        _state.update {
            it.keepingOverlays(
                PresentationState(
                    song.title, song.slides, if (song.slides.isEmpty()) -1 else 0,
                    songId = song.id, background = song.background,
                ),
            )
        }
        onShown?.invoke(HistoryEntry.Kind.SONG, song.title, song.id)
        return null
    }

    /**
     * Llamar después de guardar una canción en el editor: si es la que está en pantalla,
     * se actualiza el texto sin moverse de la diapositiva actual (o la última que exista).
     */
    fun songSaved(oldId: String?, newId: String) {
        val song = library.song(newId) ?: return
        notifyRenamed(HistoryEntry.Kind.SONG, oldId, newId, song.title)
        _state.update { s ->
            val onScreen = !s.isBible && !s.sermon && s.songId != null && (s.songId == oldId || s.songId == newId)
            if (!onScreen) return@update s
            val idx = when {
                song.slides.isEmpty() -> -1
                else -> s.index.coerceIn(0, song.slides.lastIndex)
            }
            s.copy(title = song.title, slides = song.slides, index = idx, songId = song.id, background = song.background)
        }
    }

    private fun showBible(reference: String): String? {
        val bible = library.bible ?: return "No hay Biblia cargada"
        val passage = try {
            bible.lookup(reference)
        } catch (e: IllegalArgumentException) {
            return e.message
        }
        _state.update { it.keepingOverlays(PresentationState(passage.title, withSecond(passage.slides, reference), 0, isBible = true)) }
        onShown?.invoke(HistoryEntry.Kind.BIBLE, passage.title, passage.title)
        return null
    }

    /** Si hay segunda traducción, cada versículo lleva también su texto en ella (se emparejan por la cita). */
    private fun withSecond(slides: List<app.selah.shared.Slide>, reference: String): List<app.selah.shared.Slide> {
        val second = library.secondBible ?: return slides
        val other = runCatching { second.lookup(reference).slides }.getOrNull()?.associate { it.label to it.text } ?: return slides
        return slides.map { s -> other[s.label]?.let { s.copy(text2 = it) } ?: s }
    }

    private fun showSermon(id: String): String? {
        val sermon = library.sermon(id) ?: return "Predicación no encontrada (¿se borró el archivo?)"
        val slides = app.selah.shared.Sermon.expand(sermon, library.bible).slides
        _state.update { it.keepingOverlays(PresentationState(sermon.title, slides, 0, songId = sermon.id, sermon = true, background = sermon.background)) }
        onShown?.invoke(HistoryEntry.Kind.SERMON, sermon.title, sermon.id)
        return null
    }

    /** Tras guardar una predicación en el editor: si está en pantalla, se actualiza sin moverse. */
    fun sermonSaved(oldId: String?, newId: String) {
        val sermon = library.sermon(newId) ?: return
        notifyRenamed(HistoryEntry.Kind.SERMON, oldId, newId, sermon.title)
        _state.update { s ->
            if (!s.sermon || (s.songId != oldId && s.songId != newId)) return@update s
            val slides = app.selah.shared.Sermon.expand(sermon, library.bible).slides
            s.copy(title = sermon.title, slides = slides, index = s.index.coerceIn(0, slides.lastIndex), songId = sermon.id)
        }
    }

    private fun showMedia(name: String): String? {
        library.announcementFile(name) ?: return "No encontré el anuncio \"$name\" en la carpeta anuncios"
        _state.update { it.keepingOverlays(PresentationState("Anuncio: ${name.substringBeforeLast('.')}", listOf(app.selah.shared.Slide("Anuncio", name)), 0, media = name)) }
        return null
    }

    private fun showPresentation(name: String): String? {
        val pages = try {
            library.presentations.pages(name)
        } catch (e: IllegalArgumentException) {
            return e.message
        }
        val slides = pages.indices.map { app.selah.shared.Slide("", "Diapositiva ${it + 1} de ${pages.size}") }
        _state.update { it.keepingOverlays(PresentationState(name.substringBeforeLast('.'), slides, 0, presentation = name)) }
        return null
    }

    // --- Carrusel de anuncios: las imágenes pasan solas (antes del culto, en el receso…) ---
    private var carouselImages: List<String> = emptyList()
    private var carouselIndex = 0
    private var carouselNextAt = 0L

    private fun startCarousel(seconds: Int, names: List<String>): String? {
        if (seconds !in CAROUSEL_MIN_SECONDS..CAROUSEL_MAX_SECONDS) return "Cada imagen debe durar de $CAROUSEL_MIN_SECONDS a $CAROUSEL_MAX_SECONDS segundos"
        // Solo imágenes (un video tiene su propia duración); las que pidieron, o todas las de Anuncios.
        val wanted = names.ifEmpty { library.catalog.value.announcements }
        val images = wanted.distinct().filter { isImage(it) && library.announcementFile(it) != null }
        if (images.isEmpty()) return "No hay imágenes en Anuncios para el carrusel"
        carouselImages = images
        carouselIndex = 0
        showCarouselImage(seconds)
        return null
    }

    private fun carouselStep(step: Int): String? {
        val seconds = _state.value.carouselSeconds
        // Si borraron imágenes de la carpeta mientras andaba, se saltan.
        carouselImages = carouselImages.filter { library.announcementFile(it) != null }
        if (carouselImages.isEmpty()) { _state.update { it.copy(carouselSeconds = 0) }; return "Ya no hay imágenes en Anuncios" }
        carouselIndex = Math.floorMod(carouselIndex + step, carouselImages.size)
        showCarouselImage(seconds)
        return null
    }

    private fun showCarouselImage(seconds: Int) {
        val name = carouselImages[carouselIndex]
        _state.update {
            it.keepingOverlays(
                PresentationState("Anuncio: ${name.substringBeforeLast('.')}", listOf(app.selah.shared.Slide("Anuncio", name)), 0, media = name),
            ).copy(carouselSeconds = seconds, logo = it.logo)
        }
        carouselNextAt = clock() + seconds * 1000L
    }

    /** Lo llama un reloj del programa (varias veces por segundo): pasa a la siguiente imagen cuando toca. */
    @Synchronized
    fun tick() {
        if (_state.value.carouselSeconds > 0 && clock() >= carouselNextAt) carouselStep(+1)
    }

    private fun isImage(name: String) = name.substringAfterLast('.', "").lowercase() in Library.IMAGE_EXTENSIONS

    private fun startCountdown(seconds: Int, text: String): String? {
        if (seconds !in 1..(24 * 3600)) return "La cuenta regresiva debe ser de 1 segundo a 24 horas"
        _state.update { it.copy(countdownEnd = clock() + seconds * 1000L, countdownText = text.trim(), logo = false, blank = false) }
        return null
    }

    /**
     * Al mostrar otra canción o cita: se sale del logo, pero se conservan la pantalla negra,
     * el mensaje al escenario, la alerta y la cuenta regresiva.
     */
    private fun PresentationState.keepingOverlays(new: PresentationState) = new.copy(
        blank = blank,
        stageMessage = stageMessage,
        alert = alert,
        countdownEnd = countdownEnd,
        countdownText = countdownText,
        timerStart = timerStart,
        timerMinutes = timerMinutes,
        lowerThird = lowerThird,
        lowerThirdSub = lowerThirdSub,
        logo = false,
    )
}
