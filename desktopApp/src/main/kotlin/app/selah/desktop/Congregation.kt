package app.selah.desktop

import app.selah.shared.PresentationState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Letra en los celulares de la congregación (página /letra): para quien ve poco, se sentó lejos o
 * llegó tarde. Es solo de lectura y recibe únicamente lo que ya se ve en el videobeam: nunca los
 * mensajes al escenario, el tiempo del predicador, el nombre para la transmisión ni la lista de canciones.
 */
object Congregation {
    const val PATH = "/letra"
    const val WS_PATH = "/ws-letra"

    /** Tope de celulares conectados a la vez a esta página (una iglesia grande cabe de sobra). */
    const val MAX_VIEWERS = 500

    /** Lo que se le manda a la página: la diapositiva en pantalla, o nada si no hay letra que mostrar. */
    fun view(s: PresentationState, now: Long): JsonObject = buildJsonObject {
        val slide = s.current
        // Sin letra: pantalla en negro, logo, un anuncio (imagen o video) o una presentación (son imágenes).
        val lyrics = slide != null && !s.blank && !s.logo && s.media == null && s.presentation == null
        put("visible", lyrics)
        put("title", if (lyrics) s.title else "")
        put("text", if (lyrics) slide.text else "")
        put("text2", if (lyrics) slide.text2 else "")
        // La cita va debajo del versículo; en una canción la etiqueta ("Coro") no hace falta.
        put("ref", if (lyrics && s.isBible) slide.label else "")
        put("alert", s.alert)
        s.countdownEnd?.let { put("countdownEnd", it) }
        put("countdownText", if (s.countdownEnd != null) s.countdownText else "")
        put("serverNow", now)
    }

    /**
     * Imagen para Anuncios (1920×1080) con el código QR de la página y cómo usarla: se proyecta antes
     * del culto (sola o en el carrusel) para que la congregación la escanee.
     */
    fun qrAnnouncement(url: String): BufferedImage {
        val w = 1920; val h = 1080
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.color = Color(0x1C, 0x1B, 0x2E)
            g.fillRect(0, 0, w, h)
            // Código QR en un cuadro blanco (se lee mejor desde lejos)
            val matrix = qrMatrix(url)
            val box = 760; val x0 = 140; val y0 = (h - box) / 2
            g.color = Color.WHITE
            g.fillRoundRect(x0, y0, box, box, 48, 48)
            val quiet = 40
            val cell = (box - 2 * quiet) / matrix.width
            val off = (box - cell * matrix.width) / 2
            g.color = Color.BLACK
            for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
                if (matrix[x, y]) g.fillRect(x0 + off + x * cell, y0 + off + y * cell, cell, cell)
            }
            // Instrucciones
            val left = x0 + box + 110
            g.color = Color(0xE8, 0xDE, 0xF8)
            g.font = Font(Font.SANS_SERIF, Font.BOLD, 76)
            g.drawString("Sigue la letra", left, 330)
            g.drawString("en tu celular", left, 420)
            g.color = Color(0xCA, 0xC4, 0xD0)
            g.font = Font(Font.SANS_SERIF, Font.PLAIN, 46)
            g.drawString("1. Conéctate al Wi-Fi de la iglesia", left, 560)
            g.drawString("2. Abre la cámara y apunta", left, 640)
            g.drawString("    a este código", left, 700)
            g.font = Font(Font.SANS_SERIF, Font.PLAIN, 36)
            g.color = Color(0x9A, 0x94, 0xA6)
            g.drawString(url.removePrefix("http://"), left, 820)
        } finally {
            g.dispose()
        }
        return img
    }

    /** Nombre del anuncio con el código (se reemplaza cada vez: la dirección del PC puede cambiar). */
    const val QR_ANNOUNCEMENT = "Letra en tu celular.png"

    /** Guarda la imagen del código en Anuncios. Devuelve su nombre. */
    fun saveQrAnnouncement(library: Library, url: String): String {
        library.announcementsDir.mkdirs()
        ImageIO.write(qrAnnouncement(url), "png", File(library.announcementsDir, QR_ANNOUNCEMENT))
        library.refreshAnnouncements()
        return QR_ANNOUNCEMENT
    }
}
