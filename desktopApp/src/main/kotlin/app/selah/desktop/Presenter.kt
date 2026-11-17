package app.selah.desktop

import app.selah.shared.ClientMessage
import app.selah.shared.CAROUSEL_MAX_SECONDS
import app.selah.shared.CAROUSEL_MIN_SECONDS
import app.selah.shared.CLICK_MAX_BPM
import app.selah.shared.CLICK_MIN_BPM
import app.selah.shared.ClickState
import app.selah.shared.PresentationState
import app.selah.shared.autoSlide
import app.selah.shared.timingFromRecording
import app.selah.shared.usableTiming
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Única fuente de verdad de lo que se proyecta. La ventana de operador y los
 * remotos mandan las mismas órdenes (ClientMessage); todos ven el mismo estado.
 */
/** Dónde están las secuencias, quién las suena y con qué opciones. */
class SequenceHookup(
    /** El audio de la canción con su tempo y si ya trae el click (null = no tiene). */
    val sourceFor: (songId: String) -> SequenceSource?,
    val output: SequenceOutput,
    val options: () -> ChurchFeatures,
    /** Para cuando la secuencia suena en otro aparato: solo lleva el tiempo. */
    val silent: SequenceOutput = SilentSequenceOutput(),
    /** Por dónde escuchar el click de la secuencia que suena en otro aparato (null = no se escucha). */
    val input: (ChurchFeatures) -> ClickInput? = { c -> if (c.listenClick) LineClickInput(c.listenDevice) else null },
)

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

    private fun handleInner(msg: ClientMessage): String? {
        // Grabando tiempos y se cambia de canción (o de lo que está en pantalla): se guarda lo grabado antes.
        if (_state.value.click?.recording == true && msg.replacesContent()) finishRecording()
        return handleCommand(msg)
    }

    private fun ClientMessage.replacesContent() = this is ClientMessage.SelectSong || this is ClientMessage.ShowBible ||
        this is ClientMessage.ShowMedia || this is ClientMessage.ShowPresentation || this is ClientMessage.ShowSermon || this is ClientMessage.StartCarousel

    private fun handleCommand(msg: ClientMessage): String? = when (msg) {
        is ClientMessage.SelectSong -> selectSong(msg.id)
        is ClientMessage.GoTo -> { manualMove { s -> if (msg.index in s.slides.indices) s.copy(index = msg.index) else s }; null }
        // Con el carrusel andando, Siguiente/Anterior pasan de imagen (y el tiempo vuelve a empezar).
        ClientMessage.Next -> if (_state.value.carouselSeconds > 0) carouselStep(+1) else { manualMove { s -> s.copy(index = (s.index + 1).coerceAtMost(s.slides.lastIndex)) }; null }
        ClientMessage.Prev -> if (_state.value.carouselSeconds > 0) carouselStep(-1) else { manualMove { s -> s.copy(index = (s.index - 1).coerceAtLeast(if (s.slides.isEmpty()) -1 else 0)) }; null }
        is ClientMessage.StartClick -> if (_state.value.click?.sequence != null) "Ya suena la secuencia: el click va con ella" else startClick(msg.bpm, msg.beatsPerBar)
        ClientMessage.StopClick, ClientMessage.StopSequence -> { stopClickAndSequence(); null }
        ClientMessage.StartSequence -> startSequence()
        is ClientMessage.SequenceNudge -> nudgeSequence(msg.ms)
        is ClientMessage.SetTempo -> if (_state.value.click?.sequence != null) "El tempo lo lleva la secuencia" else setTempo(msg.bpm)
        is ClientMessage.SetAutoLyrics -> {
            _state.update { s -> s.click?.let { c -> s.copy(click = c.copy(auto = msg.on && c.hasTiming && !c.recording && followsThisSong(s))) } ?: s }
            followClick(); null
        }
        is ClientMessage.RecordTiming -> if (msg.on) startRecording() else finishRecording()
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

    // --- Secuencias: el PC reproduce el audio de la canción; el click y la letra van con él ---
    /** Cómo encontrar y reproducir las secuencias (null = este PC no las tiene, p. ej. en las pruebas). */
    var sequences: SequenceHookup? = null

    /** Canción de la secuencia que suena (la letra automática solo vale con ella en pantalla). */
    private var sequenceSongId: String? = null
    /** Lo que está sonando (o siguiendo) la secuencia ahora. */
    private var sequenceOut: SequenceOutput? = null
    /** Escuchando el click de la secuencia que suena en otro aparato. */
    private var sequenceInput: ClickInput? = null
    /** Corrección a mano cuando la secuencia suena en otro aparato (ms). */
    private var sequenceNudgeMs = 0L
    /** El primer tiempo del click del audio cae tantos ms después del inicio (lo que se midió). */
    private var sequenceOffsetMs = 0L
    /** Sube con cada secuencia: los avisos de una anterior se ignoran. */
    private var sequenceGen = 0

    /** ¿La letra automática puede seguir lo que está en pantalla? (con secuencia, solo si es su canción) */
    private fun followsThisSong(s: PresentationState): Boolean = s.click?.sequence == null || (s.songId == sequenceSongId && !s.isBible && !s.sermon)

    private fun stopClickAndSequence() {
        if (_state.value.click?.recording == true) finishRecording()
        if (_state.value.click?.sequence != null) {
            sequenceOut?.stop(); sequenceOut = null
            sequenceInput?.stop(); sequenceInput = null
            sequenceGen++; sequenceSongId = null
        }
        _state.update { it.copy(click = null) }
    }

    private fun startSequence(): String? {
        val hook = sequences ?: return "Las secuencias están apagadas en \"¿Qué usa tu iglesia?\""
        val s = _state.value
        val song = s.songId?.takeIf { !s.isBible && !s.sermon && s.media == null && s.presentation == null }?.let { library.song(it) }
            ?: return "Primero pon en pantalla la canción de la secuencia"
        val src = hook.sourceFor(song.id) ?: return "\"${song.title}\" no tiene secuencia: pon el audio (MP3 o WAV) en la carpeta secuencias con el nombre de la canción"
        val file = src.file
        val opts = hook.options()
        // Suena en otro aparato: Selah no reproduce nada, solo sigue el tiempo desde este "▶".
        val following = !opts.sequencePlays
        val out = if (following) hook.silent else hook.output
        out.check(file)?.let { return it }
        if (s.click != null) stopClickAndSequence()
        val beats = song.beatsPerBar ?: 4
        // Tempo: el de la canción ("BPM:"), si no el del nombre del audio o el que se midió en su click.
        // Si el audio trae su propio click manda ese (lo que la banda ya oye en los audífonos).
        val knownBpm = MusicLibrary.sequenceBpm(song.bpm, src)
        // Si el audio ya trae el click a la izquierda, se reproduce tal cual (mezclarlo lo mandaría a los parlantes).
        val split = !following && opts.splitClick && knownBpm != null && !src.clickLeft
        val countIn = if (split) beats else 0
        sequenceOffsetMs = src.offsetMs.toLong()
        sequenceNudgeMs = 0
        // Sin BPM no hay click: los "tiempos" de la letra automática cuentan segundos (60 por minuto).
        val bpm = knownBpm ?: 60
        clickTiming = song.timing
        val hasTiming = usableTiming(clickTiming) && clickTiming.size <= s.slides.size
        val gen = ++sequenceGen
        sequenceSongId = song.id
        val start = clock() + (if (following) 0 else SEQUENCE_LEAD_MS) + Math.round(countIn * 60_000.0 / bpm) + sequenceOffsetMs
        _state.update { it.copy(click = ClickState(bpm, beats, start, countIn, auto = hasTiming, hasTiming = hasTiming, sequence = file.name, audible = knownBpm != null, following = following, suggested = hasTiming && song.timingSuggested)) }
        sequenceOut = out
        val plan = SequencePlan(knownBpm, beats, countIn, split, opts.audioDevice, src.durationMs)
        val listener = object : SequenceOutput.Listener {
            override fun musicStartsAt(wallMs: Long) = sequenceStartsAt(gen, wallMs)
            override fun finished(error: String?) = sequenceFinished(gen, error)
        }
        // Escuchar el click: se espera a oírlo para arrancar (y luego se corrige solo).
        val input = if (following && src.clicks.size >= 3) hook.input(opts) else null
        if (input != null) {
            _state.update { st -> st.click?.let { c -> st.copy(click = c.copy(waiting = true, startAt = clock() + WAITING_MS)) } ?: st }
            val error = input.start(
                src.clicks,
                onAnchor = { zero -> sequenceHeard(gen, zero, anchor = true) { out.play(file, plan.copy(durationMs = (src.durationMs - (clock() - zero)).coerceAtLeast(1)), listener) } },
                onAdjust = { zero -> sequenceHeard(gen, zero, anchor = false) {} },
            )
            if (error == null) { sequenceInput = input; return null }
            // Sin entrada de audio: se sigue desde el "▶", como sin escuchar.
            Log.warn(error)
            _state.update { st -> st.click?.let { c -> st.copy(click = c.copy(waiting = false, startAt = start)) } ?: st }
        }
        out.play(file, plan, listener)
        return null
    }

    /** La secuencia suena en otro aparato y la letra va adelantada o atrasada: correr el tiempo de Selah. */
    private fun nudgeSequence(ms: Int): String? {
        val c = _state.value.click?.takeIf { it.sequence != null } ?: return "No hay secuencia andando"
        if (!c.following) return "La secuencia suena en este computador: no hace falta correrla"
        val d = ms.coerceIn(-2_000, 2_000).toLong()
        sequenceNudgeMs += d
        _state.update { s -> s.copy(click = c.copy(startAt = c.startAt + d)) }
        followClick()
        return null
    }

    /** Se oyó el click: el ms 0 del archivo sonó a la hora [fileZero] del PC. */
    @Synchronized
    private fun sequenceHeard(gen: Int, fileZero: Long, anchor: Boolean, then: () -> Unit) {
        if (gen != sequenceGen) return
        _state.update { s ->
            val c = s.click?.takeIf { it.sequence != null } ?: return@update s
            s.copy(click = c.copy(startAt = fileZero + sequenceOffsetMs + sequenceNudgeMs, waiting = false))
        }
        if (anchor) then()
        followClick()
    }

    @Synchronized
    private fun sequenceStartsAt(gen: Int, wallMs: Long) {
        if (gen != sequenceGen) return
        // Escuchando el click, la hora la manda lo que se oye (no el reloj del "▶").
        if (sequenceInput != null) return
        _state.update { s ->
            val c = s.click?.takeIf { it.sequence != null } ?: return@update s
            // Solo si se corrió algo que se note (cada cambio se manda a todos los celulares).
            val beat0 = wallMs + sequenceOffsetMs + sequenceNudgeMs
            if (kotlin.math.abs(c.startAt - beat0) < 4) s else s.copy(click = c.copy(startAt = beat0))
        }
    }

    @Synchronized
    private fun sequenceFinished(gen: Int, error: String?) {
        if (gen != sequenceGen) return
        if (error != null) Log.warn(error)
        if (_state.value.click?.recording == true) finishRecording()
        sequenceInput?.stop(); sequenceInput = null; sequenceOut = null
        sequenceSongId = null
        _state.update { it.copy(click = null) }
    }

    // --- Click de los músicos y letra automática ---
    /** "Tiempos:" de la canción con el click andando (para la letra automática). */
    private var clickTiming: List<Int> = emptyList()
    /** Grabando: diapositiva → tiempo en que apareció. */
    private val recorded = mutableMapOf<Int, Int>()

    private fun startClick(bpmWanted: Int, beatsWanted: Int): String? {
        val s = _state.value
        val song = s.songId?.takeIf { !s.isBible && !s.sermon }?.let { library.song(it) }
        val bpm = bpmWanted.takeIf { it > 0 } ?: song?.bpm ?: return "Escribe el tempo (BPM) o ponlo en la canción con la línea \"BPM: 72\""
        if (bpm !in CLICK_MIN_BPM..CLICK_MAX_BPM) return "El tempo debe ser de $CLICK_MIN_BPM a $CLICK_MAX_BPM BPM"
        val beats = beatsWanted.takeIf { it in 1..16 } ?: song?.beatsPerBar ?: 4
        clickTiming = song?.timing.orEmpty()
        val hasTiming = usableTiming(clickTiming) && clickTiming.size <= s.slides.size
        // Un compás de cuenta, y un momento para que la orden llegue a todos los celulares antes.
        val start = clock() + CLICK_LEAD_MS + Math.round(beats * 60_000.0 / bpm)
        _state.update { it.copy(click = ClickState(bpm, beats, start, countIn = beats, auto = hasTiming, hasTiming = hasTiming, suggested = hasTiming && song?.timingSuggested == true)) }
        return null
    }

    /** Cambia el tempo sin saltarse: el tiempo en que va sigue siendo el mismo. */
    private fun setTempo(bpm: Int): String? {
        if (bpm !in CLICK_MIN_BPM..CLICK_MAX_BPM) return "El tempo debe ser de $CLICK_MIN_BPM a $CLICK_MAX_BPM BPM"
        _state.update { s ->
            val c = s.click ?: return@update s
            val now = clock()
            val beat = c.beatAt(now)
            s.copy(click = c.copy(bpm = bpm, startAt = now - Math.round(beat * 60_000.0 / bpm)))
        }
        return null
    }

    /** Un cambio de diapositiva a mano: pausa la letra automática (no le pelea al operador) o queda grabado. */
    private fun manualMove(change: (PresentationState) -> PresentationState) {
        _state.update { s ->
            val moved = change(s)
            val c = s.click ?: return@update moved
            if (c.recording) {
                if (moved.index != s.index && moved.index >= 0) recorded[moved.index] = Math.round(c.beatAt(clock())).toInt().coerceAtLeast(0)
                moved
            } else moved.copy(click = c.copy(auto = false))
        }
    }

    private fun startRecording(): String? {
        val s = _state.value
        val c = s.click ?: return "Primero inicia el click"
        if (s.songId == null || s.isBible || s.sermon || s.media != null || s.presentation != null) return "Solo se graban los tiempos de una canción"
        if (!followsThisSong(s)) return "Con la secuencia andando, se graban los tiempos de su canción"
        recorded.clear()
        if (s.index >= 0) recorded[s.index] = Math.round(c.beatAt(clock())).toInt().coerceAtLeast(0)
        _state.update { it.copy(click = c.copy(recording = true, auto = false)) }
        return null
    }

    /** Termina de grabar y guarda la línea "Tiempos:" en la canción. */
    private fun finishRecording(): String? {
        val s = _state.value
        val c = s.click ?: return null
        if (!c.recording) return null
        val timing = timingFromRecording(s.slides.size, recorded)
        recorded.clear()
        val id = s.songId
        if (id == null || !usableTiming(timing)) {
            _state.update { it.copy(click = c.copy(recording = false)) }
            return "Grabación muy corta: pasa al menos dos diapositivas con el click andando"
        }
        val error = runCatching { library.setSongTiming(id, timing); null }.getOrElse { "No pude guardar los tiempos: ${it.message}" }
        if (error == null) clickTiming = timing
        _state.update { it.copy(click = c.copy(recording = false, hasTiming = error == null || c.hasTiming, suggested = if (error == null) false else c.suggested)) }
        return error
    }

    /** Letra automática: pone la diapositiva que corresponde al tiempo en que va el click. */
    private fun followClick() {
        val s = _state.value
        val c = s.click ?: return
        if (!c.auto || c.recording || c.waiting || !followsThisSong(s)) return
        val target = autoSlide(clickTiming, c.beatAt(clock())) ?: return
        if (target != s.index && target in s.slides.indices) _state.update { it.copy(index = target) }
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
        // Volvieron a la canción de la secuencia: la letra la sigue otra vez.
        if (song.id == sequenceSongId) _state.update { s -> s.click?.let { c -> s.copy(click = c.copy(auto = c.hasTiming)) } ?: s }
        followClick()
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
        bibleReference = reference
        _state.update { it.keepingOverlays(PresentationState(passage.title, withSecond(passage.slides, reference), 0, isBible = true, version = versionShown())) }
        onShown?.invoke(HistoryEntry.Kind.BIBLE, passage.title, passage.title)
        return null
    }

    /** ¿Se pone la traducción junto a la cita? (opción del tema "Traducción junto a la cita"). */
    var versionOn: () -> Boolean = { true }

    /** La cita que pidieron para la lectura en pantalla (para volver a buscarla si cambian de Biblia). */
    @Volatile private var bibleReference: String? = null

    /**
     * Cambiaron la Biblia en uso, la segunda o la opción de la sigla: la lectura (o predicación) en pantalla pasa
     * a la traducción nueva sin moverse del versículo que se está leyendo.
     */
    fun refreshVersion() {
        val v = versionShown()
        _state.update { s ->
            when {
                s.isBible -> {
                    val reference = bibleReference
                    val passage = reference?.let { r -> library.bible?.let { b -> runCatching { b.lookup(r) }.getOrNull() } }
                    if (passage == null || reference == null) {
                        // No está en la nueva (otra numeración, sin ese libro…): queda la de antes, con su sigla.
                        s
                    } else {
                        val slides = withSecond(passage.slides, reference)
                        val index = s.current?.let { app.selah.shared.slideIndexForVerseLabel(it.label, slides) } ?: s.index
                        s.copy(title = passage.title, slides = slides, index = index.coerceIn(-1, slides.lastIndex), version = v)
                    }
                }
                s.sermon -> {
                    val sermon = s.songId?.let { library.sermon(it) } ?: return@update s.copy(version = v)
                    val slides = app.selah.shared.Sermon.expand(sermon, library.bible).slides
                    s.copy(slides = slides, index = s.index.coerceIn(-1, slides.lastIndex), version = v)
                }
                else -> s
            }
        }
    }

    /** Sigla de la traducción en uso (y de la segunda, si hay) para ponerla junto a la cita. */
    private fun versionShown(): String {
        if (!versionOn()) return ""
        val c = library.catalog.value
        val first = c.bibleName?.let { app.selah.shared.bibleAbbreviation(it) } ?: return ""
        val second = c.secondBibleName?.takeIf { library.secondBible != null }?.let { app.selah.shared.bibleAbbreviation(it) }
        return if (second != null && second != first) "$first / $second" else first
    }

    /** Si hay segunda traducción, cada versículo lleva también su texto en ella (se emparejan por la cita). */
    private fun withSecond(slides: List<app.selah.shared.Slide>, reference: String): List<app.selah.shared.Slide> {
        val second = library.secondBible ?: return slides
        val other = runCatching { second.lookup(reference).slides }.getOrNull() ?: return slides
        return slides.map { s -> app.selah.shared.matchingVerses(s.label, other)?.let { s.copy(text2 = it) } ?: s }
    }

    private fun showSermon(id: String): String? {
        val sermon = library.sermon(id) ?: return "Predicación no encontrada (¿se borró el archivo?)"
        val slides = app.selah.shared.Sermon.expand(sermon, library.bible).slides
        _state.update { it.keepingOverlays(PresentationState(sermon.title, slides, 0, songId = sermon.id, sermon = true, background = sermon.background, version = versionShown())) }
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
        followClick()
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
        // La secuencia sigue sonando aunque se cambie lo que está en pantalla (cortar la música por un toque
        // equivocado sería peor); la letra automática se pausa. Un click solo se detiene, como antes.
        click = click?.takeIf { it.sequence != null }?.copy(auto = false, recording = false),
    )

    companion object {
        /** Tiempo para que la orden de empezar llegue a todos los celulares antes de la cuenta. */
        const val CLICK_LEAD_MS = 1_200L

        /** Lo que tarda en arrancar el audio (se corrige solo con lo que dice la tarjeta de sonido). */
        const val SEQUENCE_LEAD_MS = 300L

        /** Mientras espera oír el click, el tiempo 0 queda "lejos" (nada se mueve solo). */
        const val WAITING_MS = 3_600_000L
    }
}
