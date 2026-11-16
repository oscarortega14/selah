package app.selah.desktop

import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Modelo de voz en español para leer la voz guía (Vosk, Apache 2.0). No viene con Selah: se descarga una vez
 * (40 MB) cuando alguien pide "Sugerir tiempos", y queda en Selah/modelos.
 */
class VoiceModel(baseDir: File, private val url: String = URL, private val sha256: String = SHA256) {
    val dir = File(baseDir, "modelos")
    val modelDir = File(dir, NAME)

    val ready: Boolean get() = File(modelDir, "am/final.mdl").isFile

    /** Descarga y descomprime el modelo. [progress] de 0 a 1. */
    fun download(progress: (Double) -> Unit) {
        if (ready) return
        dir.mkdirs()
        val zip = File(dir, "$NAME.zip.part")
        val c = URI(url).toURL().openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", "Selah")
        if (c.responseCode != 200) throw IllegalStateException("No pude descargar el modelo de voz (${c.responseCode})")
        val total = c.contentLengthLong.takeIf { it > 0 } ?: 40_000_000L
        val md = MessageDigest.getInstance("SHA-256")
        c.inputStream.use { input ->
            zip.outputStream().use { out ->
                val buf = ByteArray(1 shl 16)
                var done = 0L
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    out.write(buf, 0, n); md.update(buf, 0, n); done += n
                    progress((done.toDouble() / total).coerceAtMost(0.99))
                }
            }
        }
        val got = md.digest().joinToString("") { "%02x".format(it) }
        if (got != sha256) { zip.delete(); throw IllegalStateException("El modelo de voz llegó dañado: intenta de nuevo") }
        val tmp = File(dir, "$NAME.tmp").also { it.deleteRecursively(); it.mkdirs() }
        ZipInputStream(zip.inputStream()).use { z ->
            val root = tmp.canonicalFile
            while (true) {
                val e = z.nextEntry ?: break
                val target = File(tmp, e.name).canonicalFile
                if (!target.path.startsWith(root.path + File.separator)) continue // nada fuera de la carpeta
                if (e.isDirectory) target.mkdirs() else { target.parentFile.mkdirs(); target.outputStream().use { z.copyTo(it) } }
            }
        }
        zip.delete()
        modelDir.deleteRecursively()
        // El zip trae una carpeta con el nombre del modelo adentro.
        val inner = File(tmp, NAME).takeIf { it.isDirectory } ?: tmp
        if (!inner.renameTo(modelDir)) inner.copyRecursively(modelDir, overwrite = true)
        tmp.deleteRecursively()
        progress(1.0)
    }

    companion object {
        const val NAME = "vosk-model-small-es-0.42"
        const val URL = "https://alphacephei.com/vosk/models/$NAME.zip"
        const val SHA256 = "09b239888f633ef2f0b4e09736e3d9936acfd810bc65d53fad45261762c6511f"
    }
}

/** Sugerir los tiempos de las canciones con secuencia (con la voz guía), sin pisar los que alguien grabó. */
class GuideTimings(private val library: Library, private val music: MusicLibrary, private val model: VoiceModel) {
    data class Result(val songId: String, val title: String, val ok: Boolean, val message: String)

    /** Canciones con secuencia que todavía no tienen tiempos (o tienen sugeridos, si [again]). */
    fun pending(again: Boolean = false): List<String> = music.catalog.value.sequences.keys.filter { id ->
        val s = library.song(id) ?: return@filter false
        s.timing.isEmpty() || (again && s.timingSuggested)
    }

    /** Hace todo: baja el modelo si falta y sugiere para cada canción. [status] va diciendo qué hace. */
    fun run(ids: List<String>, status: (String) -> Unit): List<Result> {
        if (!GuideVoice.available) throw IllegalStateException("El reconocimiento de voz no funciona en este equipo")
        if (!model.ready) model.download { p -> status("Descargando el modelo de voz… ${(p * 100).toInt()} %") }
        val out = mutableListOf<Result>()
        for ((k, id) in ids.withIndex()) {
            val song = library.song(id) ?: continue
            status("Escuchando la guía de «${song.title}» (${k + 1} de ${ids.size})…")
            out += runCatching { suggestOne(id) }.getOrElse { Result(id, song.title, false, it.message ?: "No se pudo") }
        }
        return out
    }

    fun suggestOne(id: String): Result {
        val song = library.song(id) ?: throw IllegalArgumentException("Canción no encontrada")
        val src = music.sourceFor(id) ?: return Result(id, song.title, false, "no tiene secuencia")
        val words = GuideVoice.read(src.file, model.modelDir)
        if (words.isEmpty()) return Result(id, song.title, false, "no se oye voz guía en la secuencia")
        val bpm = MusicLibrary.sequenceBpm(song.bpm, src)
        val beatMs = bpm?.let { 60_000.0 / it }
        val cues = TimingGuess.cues(words, beatMs?.takeIf { src.clickLeft || song.bpm != null }, src.offsetMs)
        val text = library.rawText(id) ?: return Result(id, song.title, false, "no pude leer la canción")
        val s = TimingGuess.suggest(text, cues, beatMs ?: 1000.0, src.offsetMs, src.durationMs)
            ?: return Result(id, song.title, false, "la canción no tiene secciones (Estrofa:, Coro:…) para ubicar")
        library.applySuggestion(id, s.order, s.timing)
        return Result(id, song.title, true, "${s.order.size} partes: " + s.order.joinToString(", "))
    }
}
