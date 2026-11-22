package app.selah.desktop
import java.io.File
import kotlin.test.Test
/** Herramienta: SELAH_FONDOS=carpeta de fondos → prepara las miniaturas de todos los videos. */
class RealThumbsTool {
    @Test fun preparar() {
        val dir = File(System.getenv("SELAH_FONDOS") ?: return)
        val bad = dir.listFiles().orEmpty().filter { isVideoFile(it.name) }.sortedBy { it.name }.filter { VideoThumbs.of(it) == null }
        File(dir, ".miniaturas/fallaron.txt").writeText(bad.joinToString("\n") { it.name })
    }
}
