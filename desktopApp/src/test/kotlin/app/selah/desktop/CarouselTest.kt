package app.selah.desktop

import app.selah.shared.ClientMessage
import app.selah.shared.ServerMessage
import app.selah.shared.decodeServerMessage
import app.selah.shared.encode
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Carrusel de anuncios: las imágenes pasan solas cada N segundos. */
class CarouselTest {
    private lateinit var dir: File
    private lateinit var library: Library
    private var now = 1_000_000L
    private lateinit var presenter: Presenter
    private val st get() = presenter.state.value

    @BeforeTest
    fun setUp() {
        dir = createTempDirectory("selah-carrusel").toFile()
        File(dir, "canciones").mkdirs()
        File(dir, "canciones/A.txt").writeText("# A\n\nuno\n\ndos\n")
        File(dir, "anuncios").mkdirs()
        // Se ordenan por nombre: a, b, c (el video no entra al carrusel)
        for (n in listOf("a-bienvenida.png", "b-retiro.jpg", "c-ofrenda.webp", "d-video.mp4")) File(dir, "anuncios/$n").writeBytes(byteArrayOf(1, 2, 3))
        library = Library(dir).also { it.reload() }
        presenter = Presenter(library) { now }
    }

    @AfterTest
    fun tearDown() { dir.deleteRecursively() }

    private fun advance(seconds: Int) { now += seconds * 1000L; presenter.tick() }

    @Test
    fun imagesChangeByThemselvesAndWrapAround() {
        assertNull(presenter.handle(ClientMessage.StartCarousel(8)))
        assertEquals(8, st.carouselSeconds)
        assertEquals("a-bienvenida.png", st.media)
        advance(7)
        assertEquals("a-bienvenida.png", st.media) // todavía no
        advance(1)
        assertEquals("b-retiro.jpg", st.media)
        advance(8)
        assertEquals("c-ofrenda.webp", st.media)
        advance(8)
        assertEquals("a-bienvenida.png", st.media) // vuelve a empezar; el video no aparece
    }

    @Test
    fun nextAndPrevMoveTheCarouselAndRestartTheTime() {
        presenter.handle(ClientMessage.StartCarousel(10))
        advance(9)
        presenter.handle(ClientMessage.Next)
        assertEquals("b-retiro.jpg", st.media)
        advance(9)
        assertEquals("b-retiro.jpg", st.media) // el tiempo volvió a empezar al tocar Siguiente
        presenter.handle(ClientMessage.Prev)
        presenter.handle(ClientMessage.Prev)
        assertEquals("c-ofrenda.webp", st.media) // Anterior desde la primera da la vuelta
        assertEquals(10, st.carouselSeconds)
    }

    @Test
    fun showingSomethingElseStopsItButOverlaysStay() {
        presenter.handle(ClientMessage.StartCountdown(300, "Comenzamos en"))
        presenter.handle(ClientMessage.StartCarousel(5))
        assertNotNull(st.countdownEnd) // la cuenta regresiva sigue encima de las imágenes
        presenter.handle(ClientMessage.SelectSong("A"))
        assertEquals(0, st.carouselSeconds)
        assertEquals("A", st.title)
        advance(30)
        assertEquals("A", st.title) // ya no cambia solo

        presenter.handle(ClientMessage.StartCarousel(5))
        presenter.handle(ClientMessage.ShowMedia("d-video.mp4"))
        assertEquals(0, st.carouselSeconds) // elegir un anuncio a mano también lo detiene
        presenter.handle(ClientMessage.StartCarousel(5))
        presenter.handle(ClientMessage.StopCarousel)
        assertEquals(0, st.carouselSeconds)
        assertEquals("a-bienvenida.png", st.media) // al detener se queda la imagen que estaba
    }

    @Test
    fun logoAndBlankDoNotStopIt() {
        presenter.handle(ClientMessage.StartCarousel(5))
        presenter.handle(ClientMessage.ShowLogo(true))
        advance(5)
        assertTrue(st.logo)
        assertEquals(5, st.carouselSeconds)
        assertEquals("b-retiro.jpg", st.media) // sigue andando detrás del logo
        presenter.handle(ClientMessage.SetBlank(true))
        advance(5)
        assertTrue(st.blank)
        assertEquals("c-ofrenda.webp", st.media)
    }

    @Test
    fun chosenImagesAndClearErrors() {
        assertNull(presenter.handle(ClientMessage.StartCarousel(5, listOf("c-ofrenda.webp", "a-bienvenida.png", "d-video.mp4", "../ajustes.json", "no-existe.png"))))
        assertEquals("c-ofrenda.webp", st.media)
        advance(5)
        assertEquals("a-bienvenida.png", st.media)
        advance(5)
        assertEquals("c-ofrenda.webp", st.media) // solo las dos imágenes válidas que pidieron

        assertEquals("Cada imagen debe durar de 3 a 600 segundos", presenter.handle(ClientMessage.StartCarousel(1)))
        assertEquals("Cada imagen debe durar de 3 a 600 segundos", presenter.handle(ClientMessage.StartCarousel(100_000)))
        assertEquals("No hay imágenes en Anuncios para el carrusel", presenter.handle(ClientMessage.StartCarousel(5, listOf("d-video.mp4"))))
    }

    @Test
    fun deletedImagesAreSkippedAndAnEmptyFolderStopsIt() {
        presenter.handle(ClientMessage.StartCarousel(5))
        File(dir, "anuncios/b-retiro.jpg").delete()
        advance(5)
        assertEquals("c-ofrenda.webp", st.media)
        File(dir, "anuncios/a-bienvenida.png").delete()
        File(dir, "anuncios/c-ofrenda.webp").delete()
        advance(5)
        assertEquals(0, st.carouselSeconds)
    }

    @Test
    fun theStateTravelsToAppsAndOldAppsIgnoreIt() {
        presenter.handle(ClientMessage.StartCarousel(8))
        val json = ServerMessage.State(st, now).encode()
        assertTrue("\"carouselSeconds\":8" in json)
        // Una app vieja que no conoce el campo lo ignora (ignoreUnknownKeys); una nueva lo lee.
        assertEquals(8, (decodeServerMessage(json) as ServerMessage.State).state.carouselSeconds)
        // Un PC viejo no manda "features": la app no ofrece el carrusel.
        val oldLibrary = """{"type":"library","songs":[]}"""
        assertEquals(emptyList(), (decodeServerMessage(oldLibrary) as ServerMessage.Library).features)
        assertTrue(app.selah.shared.FEATURE_CAROUSEL in RemoteServer.SERVER_FEATURES)
    }
}
