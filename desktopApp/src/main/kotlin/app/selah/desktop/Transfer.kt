package app.selah.desktop

import java.io.File
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Llevar Selah a otro computador: un .zip con lo que se elija (canciones, partituras, secuencias, fondos, Biblias…),
 * para preparar todo en un computador y pasarlo al de la iglesia. Lo propio de cada computador no viaja: pantallas,
 * clave, salida de sonido, celulares aprobados, el modelo de voz (se baja solo) ni los respaldos.
 */
class Transfer(private val baseDir: File) {
    /** Una parte que se puede llevar: carpetas o archivos de la carpeta Selah. */
    data class Part(val key: String, val label: String, val paths: List<String>, val default: Boolean, val note: String = "")

    data class Summary(val files: Int, val bytes: Long)

    /** Cuánto ocupa cada parte en este computador. */
    fun sizes(): Map<String, Summary> = PARTS.associate { p -> p.key to files(p).let { fs -> Summary(fs.size, fs.sumOf { it.length() }) } }

    private fun files(p: Part): List<File> = p.paths.flatMap { path ->
        val f = File(baseDir, path)
        when {
            f.isDirectory -> f.walkTopDown().filter { it.isFile && !it.name.startsWith(".") }.toList()
            f.isFile -> listOf(f)
            else -> emptyList()
        }
    }

    /** Arma el .zip en [target] con las partes [keys]. [progress]: bytes hechos, total y el archivo en que va. Devuelve cuántos archivos. */
    fun export(target: File, keys: Set<String>, progress: (Long, Long, String) -> Unit = { _, _, _ -> }): Int {
        val parts = PARTS.filter { it.key in keys }
        val all = parts.flatMap(::files).distinct()
        val total = all.sumOf { it.length() }.coerceAtLeast(1)
        var done = 0L
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, ".${target.name}.tmp")
        ZipOutputStream(tmp.outputStream().buffered(1 shl 16)).use { zip ->
            // Una marca para reconocer el .zip al abrirlo en el otro computador.
            zip.putNextEntry(ZipEntry(MARK))
            zip.write(("Selah ${Updater.currentVersion}\n" + parts.joinToString("\n") { it.key }).toByteArray())
            zip.closeEntry()
            val buf = ByteArray(1 shl 16)
            for (f in all) {
                // Audio, video e imágenes ya vienen comprimidos: guardarlos tal cual es mucho más rápido.
                zip.setLevel(if (f.extension.lowercase() in PACKED) Deflater.NO_COMPRESSION else Deflater.DEFAULT_COMPRESSION)
                zip.putNextEntry(ZipEntry(f.relativeTo(baseDir).invariantSeparatorsPath).apply { time = f.lastModified() })
                f.inputStream().use { input ->
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        zip.write(buf, 0, n); done += n
                        progress(done, total, f.relativeTo(baseDir).invariantSeparatorsPath)
                    }
                }
                zip.closeEntry()
            }
        }
        target.delete()
        if (!tmp.renameTo(target)) { tmp.copyTo(target, overwrite = true); tmp.delete() }
        return all.size
    }

    /** Qué trae un .zip (por parte), o null si no es uno de "Llevar a otro computador". */
    fun inspect(zip: File): Map<String, Summary>? = runCatching {
        ZipFile(zip).use { z ->
            if (z.getEntry(MARK) == null) return null
            val out = mutableMapOf<String, Summary>()
            for (e in z.entries().asSequence().filter { !it.isDirectory }) {
                val part = partOf(e.name.replace('\\', '/')) ?: continue
                val s = out[part.key] ?: Summary(0, 0)
                out[part.key] = Summary(s.files + 1, s.bytes + e.size.coerceAtLeast(0))
            }
            out
        }
    }.getOrNull()

    /**
     * Copia a esta carpeta Selah lo que trae [zip] (solo lo de las partes conocidas, nada fuera de la carpeta). Lo que
     * ya estaba igual no se toca; lo distinto se reemplaza por lo del .zip. Devuelve cuántos archivos copió.
     */
    fun import(zip: File, progress: (Long, Long, String) -> Unit = { _, _, _ -> }): Int {
        val root = baseDir.canonicalFile
        var n = 0
        ZipFile(zip).use { z ->
            if (z.getEntry(MARK) == null) throw IllegalArgumentException("Ese .zip no es de «Llevar a otro computador»")
            val entries = z.entries().asSequence().filter { !it.isDirectory && partOf(it.name.replace('\\', '/')) != null }.toList()
            val total = entries.sumOf { it.size.coerceAtLeast(0) }.coerceAtLeast(1)
            var done = 0L
            val buf = ByteArray(1 shl 16)
            for (e in entries) {
                val name = e.name.replace('\\', '/')
                val part = partOf(name)!!
                val target = File(baseDir, name).canonicalFile
                // Ya resuelto (sin "../"), tiene que quedar dentro de una de las carpetas de esa parte.
                val inside = part.paths.any { p -> File(root, p).canonicalFile.let { target == it || target.path.startsWith(it.path + File.separator) } }
                if (!inside) continue
                if (target.isFile && target.length() == e.size && e.crc >= 0 && crc(target) == e.crc) {
                    done += e.size; progress(done, total, name); continue
                }
                target.parentFile.mkdirs()
                val tmp = File(target.parentFile, ".${target.name}.llegando")
                z.getInputStream(e).use { input ->
                    tmp.outputStream().use { out ->
                        while (true) {
                            val r = input.read(buf); if (r < 0) break
                            out.write(buf, 0, r); done += r
                            progress(done, total, name)
                        }
                    }
                }
                target.delete()
                if (!tmp.renameTo(target)) { tmp.copyTo(target, overwrite = true); tmp.delete() }
                if (e.time > 0) target.setLastModified(e.time)
                n++
            }
        }
        return n
    }

    private fun crc(f: File): Long {
        val c = java.util.zip.CRC32()
        val buf = ByteArray(1 shl 16)
        f.inputStream().use { while (true) { val n = it.read(buf); if (n < 0) break; c.update(buf, 0, n) } }
        return c.value
    }

    companion object {
        const val MARK = "selah-llevar.txt"

        val PARTS = listOf(
            Part("canciones", "Canciones (con sus tiempos y acordes)", listOf("canciones"), true),
            Part("cultos", "Listas, cultos y predicaciones", listOf("listas", "lista-del-culto.json", "cultos.json", "predicaciones"), true),
            Part("partituras", "Partituras", listOf("partituras"), true),
            Part("secuencias", "Secuencias (audios)", listOf("secuencias"), false, "Pesan mucho: llévalas solo si las van a usar allá."),
            Part("diseno", "Diseño de la proyección y fondos", listOf("tema.json", "fondos"), true),
            Part("anuncios", "Anuncios y presentaciones", listOf("anuncios", "presentaciones"), true),
            Part("biblias", "Biblias agregadas", listOf("biblia"), true, "Las que tienen derechos de autor, solo a los computadores de tu iglesia."),
            Part("historial", "Historial de lo proyectado (reporte CCLI)", listOf("historial.csv"), false),
        )

        private val PACKED = setOf("mp3", "m4a", "aac", "mp4", "mov", "jpg", "jpeg", "png", "webp", "zip", "pdf", "pptx")

        /** La parte a la que pertenece un nombre del .zip ("canciones/A.txt" → canciones). */
        fun partOf(name: String): Part? {
            if (name.startsWith("/") || name.contains("../") || name.contains("..\\")) return null
            return PARTS.firstOrNull { p -> p.paths.any { name == it || name.startsWith("$it/") } }
        }
    }
}
