package app.selah.desktop

import app.selah.shared.Theme
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VideoThumbsTest {
    @Test
    fun sacaUnaImagenDelVideoYLaGuarda() {
        val dir = createTempDirectory("fondos").toFile()
        try {
            val video = File(dir, "Holyrics 001.mp4")
            File(javaClass.getResource("/fondo-prueba.mp4")!!.toURI()).copyTo(video)
            val thumb = assertNotNull(VideoThumbs.of(video))
            assertTrue(thumb.length() > 1000)
            assertEquals(File(dir, ".miniaturas"), thumb.parentFile) // carpeta oculta: no sale en la lista ni viaja al llevar
            val img = javax.imageio.ImageIO.read(thumb)
            assertEquals(384, img.width)
            // Otra vez: la misma (no la vuelve a sacar)
            assertEquals(thumb.lastModified(), VideoThumbs.of(video)!!.lastModified())
            // Un archivo que no es video: null, sin caerse
            assertNull(VideoThumbs.of(File(dir, "roto.mp4").apply { writeText("no soy un video") }))
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun dondeSeUsaCadaFondo() {
        val t = Theme(image = "a.mp4", bibleImage = "b.jpg", typeImages = mapOf("Alabanza" to "a.mp4", "Adoración" to "c.mp4"))
        assertEquals(listOf("Todo", "Alabanza"), usedFor(t, "a.mp4"))
        assertEquals(listOf("Biblia"), usedFor(t, "b.jpg"))
        assertEquals(emptyList(), usedFor(t, "z.jpg"))
    }
}
