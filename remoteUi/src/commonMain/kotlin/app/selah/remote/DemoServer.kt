package app.selah.remote

import app.selah.shared.cleanListName
import app.selah.shared.Bible
import app.selah.shared.CAROUSEL_MAX_SECONDS
import app.selah.shared.CAROUSEL_MIN_SECONDS
import app.selah.shared.FEATURE_CAROUSEL
import app.selah.shared.BibleReference
import app.selah.shared.ClientMessage
import app.selah.shared.PlanItem
import app.selah.shared.PlanKind
import app.selah.shared.PresentationState
import app.selah.shared.ServerMessage
import app.selah.shared.ServicePlanState
import app.selah.shared.Slide
import app.selah.shared.SongParser
import app.selah.shared.searchSongsFull

/**
 * Un computador de mentira dentro de la app, para probarla sin tener Selah cerca
 * (probadores, revisores de las tiendas, alguien que la acaba de instalar).
 * Atiende las mismas órdenes que el PC y responde con los mismos mensajes, así que
 * las pantallas no saben la diferencia. Nada sale del teléfono.
 */
internal class DemoServer(private val clock: () -> Long = ::nowMillis) {
    private val songs = DEMO_SONGS.mapIndexed { i, text -> SongParser.parse("demo-${i + 1}", text) }
    private val bible = Bible(
        DEMO_BIBLE_NAME,
        DEMO_BIBLE_BOOKS.map { (name, count) -> Bible.Book(name, (1..count).map { DEMO_BIBLE_CHAPTERS[name to it].orEmpty() }) },
    )
    private val announcements = listOf("Bienvenidos.jpg", "Retiro de jóvenes.jpg")
    private val presentations = listOf("Informe anual.pdf")

    private var state = PresentationState()
    private var plan = ServicePlanState(
        listOf(
            PlanItem(PlanKind.MEDIA, announcements[0], "Anuncio: Bienvenidos"),
            PlanItem(PlanKind.SONG, songs[1].id, songs[1].title),
            PlanItem(PlanKind.SONG, songs[0].id, songs[0].title),
            PlanItem(PlanKind.BIBLE, "Salmos 23", "Salmos 23"),
            PlanItem(PlanKind.SONG, songs[3].id, songs[3].title),
        ),
    )
    private val saved = linkedMapOf<String, ServicePlanState>()

    // Que al entrar ya haya una canción "en pantalla", como en un culto.
    init { selectSong(songs[0].id) }

    /** Lo que manda el PC apenas alguien se conecta. */
    fun welcome(): List<ServerMessage> = listOf(
        ServerMessage.Auth(required = false, ok = true),
        ServerMessage.Library(
            songs = songs.map { it.summary() },
            bibleName = bible.name,
            bibleBooks = bible.bookInfos(),
            announcements = announcements,
            bibleNames = listOf(bible.name),
            presentations = presentations,
            features = listOf(FEATURE_CAROUSEL),
        ),
        planMessage(),
        stateMessage(),
    )

    /** Aplica una orden y devuelve lo que el PC respondería. */
    fun handle(msg: ClientMessage): List<ServerMessage> {
        val before = state
        val beforePlan = plan
        val error: String? = when (msg) {
            is ClientMessage.SelectSong -> selectSong(msg.id)
            is ClientMessage.GoTo -> { if (msg.index in state.slides.indices) state = state.copy(index = msg.index); null }
            ClientMessage.Next -> if (state.carouselSeconds > 0) carouselStep(+1) else { state = state.copy(index = (state.index + 1).coerceAtMost(state.slides.lastIndex)); null }
            ClientMessage.Prev -> if (state.carouselSeconds > 0) carouselStep(-1) else { state = state.copy(index = (state.index - 1).coerceAtLeast(if (state.slides.isEmpty()) -1 else 0)); null }
            is ClientMessage.StartCarousel ->
                if (msg.seconds !in CAROUSEL_MIN_SECONDS..CAROUSEL_MAX_SECONDS) "Cada imagen debe durar de $CAROUSEL_MIN_SECONDS a $CAROUSEL_MAX_SECONDS segundos"
                else { carouselIndex = -1; state = state.copy(carouselSeconds = msg.seconds); carouselStep(+1) }
            ClientMessage.StopCarousel -> { state = state.copy(carouselSeconds = 0); null }
            is ClientMessage.SetBlank -> { state = state.copy(blank = msg.blank); null }
            is ClientMessage.ShowBible -> showBible(msg.reference)
            is ClientMessage.ShowMedia -> showMedia(msg.name)
            is ClientMessage.ShowPresentation -> showPresentation(msg.name)
            is ClientMessage.ShowSermon -> "En la demostración no hay predicaciones"
            is ClientMessage.StageMessage -> { state = state.copy(stageMessage = msg.text.trim()); null }
            is ClientMessage.ShowLogo -> { state = state.copy(logo = msg.show, blank = if (msg.show) false else state.blank); null }
            is ClientMessage.SetAlert -> { state = state.copy(alert = msg.text.trim()); null }
            is ClientMessage.StartCountdown ->
                if (msg.seconds !in 1..(24 * 3600)) "La cuenta regresiva debe ser de 1 segundo a 24 horas"
                else { state = state.copy(countdownEnd = clock() + msg.seconds * 1000L, countdownText = msg.text.trim(), logo = false, blank = false); null }
            ClientMessage.StopCountdown -> { state = state.copy(countdownEnd = null, countdownText = ""); null }
            is ClientMessage.StartTimer ->
                if (msg.minutes !in 0..600) "El tiempo debe ser de 0 a 600 minutos"
                else { state = state.copy(timerStart = clock(), timerMinutes = msg.minutes); null }
            ClientMessage.StopTimer -> { state = state.copy(timerStart = null, timerMinutes = 0); null }
            is ClientMessage.SetLowerThird -> { state = state.copy(lowerThird = msg.title.trim(), lowerThirdSub = msg.subtitle.trim()); null }

            is ClientMessage.PlanAddSong -> songs.find { it.id == msg.id }?.let { addToPlan(PlanItem(PlanKind.SONG, it.id, it.title)) } ?: "Canción no encontrada"
            is ClientMessage.PlanAddBible -> runCatching { bible.lookup(msg.reference) }
                .map { addToPlan(PlanItem(PlanKind.BIBLE, it.title, it.title)) }
                .getOrElse { bibleError(msg.reference, it) }
            is ClientMessage.PlanAddMedia -> if (msg.name in announcements) addToPlan(PlanItem(PlanKind.MEDIA, msg.name, "Anuncio: ${msg.name.substringBeforeLast('.')}")) else "Anuncio no encontrado"
            is ClientMessage.PlanAddPresentation -> if (msg.name in presentations) addToPlan(PlanItem(PlanKind.PRESENTATION, msg.name, "Presentación: ${msg.name.substringBeforeLast('.')}")) else "Presentación no encontrada"
            is ClientMessage.PlanAddSermon -> "En la demostración no hay predicaciones"
            is ClientMessage.PlanRemove -> { removeFromPlan(msg.index); null }
            is ClientMessage.PlanMove -> { moveInPlan(msg.from, msg.to); null }
            is ClientMessage.PlanGo -> planGo(msg.index)
            ClientMessage.PlanNext -> if (plan.current + 1 < plan.items.size) planGo(plan.current + 1) else "No hay más elementos en la lista"
            ClientMessage.PlanClear -> { plan = ServicePlanState(); null }
            is ClientMessage.PlanSave -> {
                val name = cleanListName(msg.name).orEmpty() // igual que el PC
                when {
                    name.isEmpty() -> "Escribe un nombre para la lista"
                    plan.items.isEmpty() -> "La lista está vacía"
                    else -> { saved.remove(name); saved[name] = plan.copy(current = -1, name = name); plan = plan.copy(name = name); null }
                }
            }
            is ClientMessage.PlanOpen -> saved[msg.name]?.let { plan = it.copy(current = -1); null } ?: "No encontré la lista \"${msg.name}\""

            is ClientMessage.SongSearch -> return listOf(ServerMessage.SongResults(msg.query, searchSongsFull(songs, msg.query)))
            is ClientMessage.BibleSearch -> {
                val (hits, total) = bible.search(msg.query)
                return listOf(ServerMessage.BibleResults(msg.query, hits, total))
            }
            is ClientMessage.BibleChapter -> {
                val verses = bible.chapter(msg.book, msg.chapter).orEmpty()
                return listOf(ServerMessage.BibleChapter(msg.book, msg.chapter, verses))
            }
            // Una sola traducción, sin clave: no hay nada que hacer.
            is ClientMessage.SelectBible, is ClientMessage.SetSecondBible, is ClientMessage.Hello, is ClientMessage.Login -> null
        }
        return buildList {
            if (error != null) add(ServerMessage.Error(error))
            if (plan != beforePlan) add(planMessage())
            if (state != before) add(stateMessage())
        }
    }

    // Carrusel (igual que en el PC): las imágenes pasan solas; Siguiente/Anterior cambian de imagen.
    private var carouselIndex = -1
    private var carouselNextAt = 0L

    private fun carouselStep(step: Int): String? {
        val seconds = state.carouselSeconds
        carouselIndex = (carouselIndex + step).mod(announcements.size)
        showMedia(announcements[carouselIndex])
        state = state.copy(carouselSeconds = seconds)
        carouselNextAt = clock() + seconds * 1000L
        return null
    }

    /** El reloj de la demostración (la app lo llama cada medio segundo): cambia la imagen del carrusel cuando toca. */
    fun tick(): List<ServerMessage> {
        if (state.carouselSeconds <= 0 || clock() < carouselNextAt) return emptyList()
        carouselStep(+1)
        return listOf(stateMessage())
    }

    private fun stateMessage() = ServerMessage.State(state, serverNow = clock())
    private fun planMessage() = ServerMessage.Plan(plan, saved.keys.reversed())

    private fun selectSong(id: String): String? {
        val song = songs.find { it.id == id } ?: return "Canción no encontrada"
        state = state.showing(PresentationState(song.title, song.slides, if (song.slides.isEmpty()) -1 else 0, songId = song.id))
        return null
    }

    private fun showBible(reference: String): String? {
        val passage = runCatching { bible.lookup(reference) }.getOrElse { return bibleError(reference, it) }
        state = state.showing(PresentationState(passage.title, passage.slides, 0, isBible = true))
        return null
    }

    /** Si piden un capítulo que no viene en la demostración, decirlo claro (no "no tiene el versículo 1"). */
    private fun bibleError(reference: String, e: Throwable): String {
        val missing = BibleReference.parseAll(reference).orEmpty().any { ref ->
            val index = bible.findBook(ref.book)
            index == null || bible.books[index].chapters.getOrNull(ref.chapter - 1).isNullOrEmpty()
        }
        return if (missing) DEMO_BIBLE_ONLY else e.message ?: "No entiendo la cita"
    }

    private fun showMedia(name: String): String? {
        if (name !in announcements) return "Anuncio no encontrado"
        state = state.showing(PresentationState("Anuncio: ${name.substringBeforeLast('.')}", listOf(Slide("Anuncio", name)), 0, media = name))
        return null
    }

    private fun showPresentation(name: String): String? {
        if (name !in presentations) return "Presentación no encontrada"
        val slides = (1..PRESENTATION_PAGES).map { Slide("", "Diapositiva $it de $PRESENTATION_PAGES") }
        state = state.showing(PresentationState(name.substringBeforeLast('.'), slides, 0, presentation = name))
        return null
    }

    private fun addToPlan(item: PlanItem): String? { plan = plan.copy(items = plan.items + item); return null }

    private fun planGo(index: Int): String? {
        val item = plan.items.getOrNull(index) ?: return null
        val error = when (item.kind) {
            PlanKind.SONG -> selectSong(item.ref)
            PlanKind.BIBLE -> showBible(item.ref)
            PlanKind.MEDIA -> showMedia(item.ref)
            PlanKind.PRESENTATION -> showPresentation(item.ref)
            PlanKind.SERMON -> "En la demostración no hay predicaciones"
        }
        if (error == null) plan = plan.copy(current = index)
        return error
    }

    private fun removeFromPlan(index: Int) {
        if (index !in plan.items.indices) return
        val current = when {
            index < plan.current -> plan.current - 1
            index == plan.current -> -1
            else -> plan.current
        }
        plan = plan.copy(items = plan.items.filterIndexed { i, _ -> i != index }, current = current)
    }

    private fun moveInPlan(from: Int, to: Int) {
        if (from !in plan.items.indices || to !in plan.items.indices || from == to) return
        val items = plan.items.toMutableList().apply { add(to, removeAt(from)) }
        val current = when (plan.current) {
            from -> to
            in minOf(from, to)..maxOf(from, to) -> if (from < to) plan.current - 1 else plan.current + 1
            else -> plan.current
        }
        plan = plan.copy(items = items, current = current)
    }

    /** Igual que en el PC: al mostrar otra cosa se sale del logo y se conservan negro, avisos y tiempos. */
    private fun PresentationState.showing(new: PresentationState) = new.copy(
        blank = blank, stageMessage = stageMessage, alert = alert,
        countdownEnd = countdownEnd, countdownText = countdownText,
        timerStart = timerStart, timerMinutes = timerMinutes,
        lowerThird = lowerThird, lowerThirdSub = lowerThirdSub, logo = false,
    )

    companion object {
        const val DEMO_BIBLE_NAME = "Reina-Valera 1909"
        const val PRESENTATION_PAGES = 6
        const val DEMO_BIBLE_ONLY = "En la demostración solo están Salmos 23 y 46, Juan 3, Romanos 5, " +
            "1 Corintios 13 y Filipenses 4. Con el computador tienes la Biblia completa."
    }
}
