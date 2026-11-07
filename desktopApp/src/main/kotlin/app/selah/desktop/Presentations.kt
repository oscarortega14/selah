package app.selah.desktop

import app.selah.shared.normalizeText
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Presentaciones de PowerPoint (.pptx) y PDF en la carpeta Selah/presentaciones.
 *
 * Cada diapositiva se convierte una vez en una imagen de 1920 de ancho y se guarda en
 * `presentaciones/.diapositivas/<nombre>/`. Si el archivo cambia (otra fecha o tamaño), se vuelve
 * a convertir. Así en pleno culto pasar de diapositiva es tan rápido como mostrar una foto.
 *
 * PowerPoint se dibuja sin Office (Apache POI): las fuentes y efectos raros pueden verse un poco
 * distinto. Para que se vea idéntico: en PowerPoint, Archivo → Exportar → PDF.
 */
class Presentations(val dir: File) {
    val cacheDir = File(dir, ".diapositivas")

    private val _ready = kotlinx.coroutines.flow.MutableStateFlow(0)
    /** Sube cada vez que termina de convertirse una presentación (la ventana refresca las miniaturas). */
    val ready: kotlinx.coroutines.flow.StateFlow<Int> = _ready

    /** Archivos que se pueden mostrar, ordenados por nombre. */
    fun list(): List<String> = dir.listFiles { f -> f.isFile && isPresentation(f.name) && !f.name.startsWith("~$") }.orEmpty()
        .sortedBy { normalizeText(it.name) }.map { it.name }

    /** El archivo, solo si está dentro de la carpeta (nada de "../"). */
    fun file(name: String): File? =
        File(dir, name).takeIf { it.isFile && it.parentFile == dir && isPresentation(name) }

    private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()

    /**
     * Las imágenes de cada diapositiva, convirtiéndola si hace falta (puede tardar unos segundos
     * la primera vez). Lanza una excepción con un mensaje para mostrar si no se puede leer.
     */
    fun pages(name: String): List<File> {
        val src = file(name) ?: throw IllegalArgumentException("No encontré la presentación \"$name\"")
        synchronized(locks.getOrPut(name) { Any() }) {
            val out = File(cacheDir, cacheName(name))
            val stamp = File(out, "origen.txt")
            val expected = "${src.length()}|${src.lastModified()}|$FORMAT_VERSION"
            val cached = out.listFiles { f -> f.name.endsWith(".png") && !f.name.startsWith(THUMB_PREFIX) }.orEmpty().sortedBy { it.name }
            if (stamp.isFile && stamp.readText() == expected && cached.isNotEmpty()) return cached
            out.deleteRecursively()
            out.mkdirs()
            val count = try {
                render(src) { index, image ->
                    ImageIO.write(image, "png", File(out, pageName(index)))
                    ImageIO.write(thumbnail(image), "png", File(out, THUMB_PREFIX + pageName(index)))
                }
            } catch (e: Throwable) {
                // También falta de memoria o un archivo que hace recursión infinita: un mensaje, no un cierre.
                out.deleteRecursively()
                Log.error("No pude convertir la presentación $name", e)
                val why = if (e is OutOfMemoryError) "es demasiado grande" else e.message ?: e::class.simpleName
                throw IllegalArgumentException("No pude abrir \"$name\": $why")
            }
            if (count == 0) { out.deleteRecursively(); throw IllegalArgumentException("\"$name\" no tiene diapositivas") }
            stamp.writeText(expected)
            _ready.value++
            return (0 until count).map { File(out, pageName(it)) }
        }
    }

    /** Imagen de la diapositiva [index] (desde 0) si ya está convertida (para dibujarla rápido). */
    fun page(name: String, index: Int): File? =
        File(File(cacheDir, cacheName(name)), pageName(index)).takeIf { it.isFile }

    /** Miniatura (320 px de ancho) de la diapositiva [index], para las listas. */
    fun thumb(name: String, index: Int): File? =
        File(File(cacheDir, cacheName(name)), THUMB_PREFIX + pageName(index)).takeIf { it.isFile }

    /** Copia presentaciones a la carpeta. Devuelve cuántas se agregaron. */
    fun add(files: List<File>): Int {
        dir.mkdirs()
        var n = 0
        for (f in files.filter { it.isFile && isPresentation(it.name) }) {
            var target = File(dir, f.name)
            var i = 2
            // Mismo nombre y mismo contenido: ya está. Mismo nombre y otro contenido: "nombre (2).pdf".
            while (target.exists() && !Library.sameContent(target, f)) target = File(dir, "${f.nameWithoutExtension} ($i).${f.extension}").also { i++ }
            if (!target.exists()) { f.copyTo(target); n++ }
        }
        return n
    }

    /** Convierte en segundo plano las que falten, para que estén listas antes del culto. */
    fun prepareAllInBackground() {
        val names = list()
        if (names.isEmpty()) return
        Thread({
            for (n in names) runCatching { pages(n) }
            // Se borran las conversiones de presentaciones que ya no están.
            val keep = names.map(::cacheName).toSet()
            cacheDir.listFiles().orEmpty().filter { it.name !in keep }.forEach { it.deleteRecursively() }
        }, "presentaciones").apply { isDaemon = true; start() }
    }

    companion object {
        const val WIDTH = 1920
        /** Más que esto no es una presentación para un culto (y convertirla llenaría el disco). */
        const val MAX_PAGES = 300
        /** Subirlo obliga a convertir todo de nuevo (si cambia la forma de dibujar). */
        private const val FORMAT_VERSION = 1
        val EXTENSIONS = setOf("pdf", "pptx")

        fun isPresentation(name: String) = name.substringAfterLast('.', "").lowercase() in EXTENSIONS

        private fun pageName(index: Int) = "%04d.png".format(index + 1)
        private const val THUMB_PREFIX = "mini-"
        private const val THUMB_WIDTH = 320

        private fun thumbnail(src: BufferedImage): BufferedImage {
            val h = (src.height * THUMB_WIDTH / src.width.toDouble()).toInt().coerceAtLeast(1)
            val out = BufferedImage(THUMB_WIDTH, h, BufferedImage.TYPE_INT_RGB)
            val g = out.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.drawImage(src.getScaledInstance(THUMB_WIDTH, h, java.awt.Image.SCALE_AREA_AVERAGING), 0, 0, null)
            g.dispose()
            return out
        }

        /** Nombre de carpeta seguro y distinto para cada archivo. */
        private fun cacheName(name: String) =
            name.substringBeforeLast('.').replace(Regex("[^\\p{L}\\p{N} _-]"), "_").take(60) + "-" +
                java.security.MessageDigest.getInstance("SHA-1").digest(name.toByteArray()).take(8).joinToString("") { "%02x".format(it) }

        /** Dibuja cada diapositiva y se la pasa a [emit]. Devuelve cuántas hay. */
        fun render(src: File, emit: (Int, BufferedImage) -> Unit): Int = when (src.extension.lowercase()) {
            "pdf" -> renderPdf(src, emit)
            "pptx" -> renderPptx(src, emit)
            else -> throw IllegalArgumentException("Formato no soportado")
        }

        private fun renderPdf(src: File, emit: (Int, BufferedImage) -> Unit): Int {
            org.apache.pdfbox.Loader.loadPDF(src).use { doc ->
                val renderer = org.apache.pdfbox.rendering.PDFRenderer(doc)
                if (doc.numberOfPages > MAX_PAGES) throw IllegalArgumentException("tiene más de $MAX_PAGES páginas")
                for (i in 0 until doc.numberOfPages) {
                    val box = doc.getPage(i).cropBox
                    // Ancho de 1920 px (sin pasarse en alto de 1920 si la página es vertical)
                    val scale = minOf(WIDTH / box.width, WIDTH / box.height).coerceAtMost(8f)
                    emit(i, renderer.renderImage(i, scale, org.apache.pdfbox.rendering.ImageType.RGB))
                }
                return doc.numberOfPages
            }
        }

        private fun renderPptx(src: File, emit: (Int, BufferedImage) -> Unit): Int {
            src.inputStream().use { input ->
                org.apache.poi.xslf.usermodel.XMLSlideShow(input).use { show ->
                    val size = show.pageSize
                    // Ni el ancho ni el alto pasan de 1920 px (una diapositiva muy alta pedía cientos de MB).
                    val scale = minOf(WIDTH / size.width.toDouble(), WIDTH / size.height.toDouble())
                    val w = (size.width * scale).toInt().coerceIn(1, WIDTH)
                    val h = (size.height * scale).toInt().coerceIn(1, WIDTH)
                    if (show.slides.size > MAX_PAGES) throw IllegalArgumentException("tiene más de $MAX_PAGES diapositivas")
                    show.slides.forEachIndexed { i, slide ->
                        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
                        val g = img.createGraphics()
                        try {
                            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
                            g.color = Color.WHITE
                            g.fillRect(0, 0, w, h)
                            g.scale(scale, scale)
                            slide.draw(g)
                        } finally {
                            g.dispose()
                        }
                        emit(i, img)
                    }
                    return show.slides.size
                }
            }
        }
    }
}
