package app.selah.desktop

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Respaldos: un .zip con lo que no se puede volver a bajar de internet (canciones, predicaciones,
 * listas del culto, historial). Se hace solo una vez por semana en Selah/respaldos (se guardan
 * los últimos [KEEP]) y, si se eligió, una copia más en otra carpeta (una memoria USB, Google Drive…).
 */
class Backups(
    private val baseDir: File,
    private val settings: SettingsStore,
    private val now: () -> Long = System::currentTimeMillis,
) {
    val dir = File(baseDir, "respaldos")

    fun isDue(): Boolean = now() - settings.value.lastBackup >= WEEK_MILLIS

    /** Respaldos guardados, el más reciente primero. */
    fun list(): List<File> = dir.listFiles { f -> f.isFile && f.name.startsWith(PREFIX) && f.extension == "zip" }.orEmpty()
        .sortedByDescending { it.name }

    /** Hace un respaldo ahora. Devuelve el archivo creado. */
    @Synchronized
    fun create(): File {
        dir.mkdirs()
        val stamp = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.ROOT).format(Date(now()))
        val target = File(dir, "$PREFIX$stamp.zip")
        val tmp = File(dir, ".${target.name}.tmp")
        ZipOutputStream(tmp.outputStream()).use { zip ->
            for (path in INCLUDED) {
                val f = File(baseDir, path)
                val files = if (f.isDirectory) f.walkTopDown().filter { it.isFile && !it.name.startsWith(".") }.toList() else listOfNotNull(f.takeIf { it.isFile })
                for (file in files) {
                    zip.putNextEntry(ZipEntry(file.relativeTo(baseDir).invariantSeparatorsPath))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
        if (!tmp.renameTo(target)) { tmp.copyTo(target, overwrite = true); tmp.delete() }
        // Copia extra (USB, carpeta de Google Drive…), si se eligió y existe
        settings.value.backupCopyDir?.let(::File)?.takeIf { it.isDirectory }?.let { extra ->
            runCatching { target.copyTo(File(extra, target.name), overwrite = true) }
        }
        list().drop(KEEP).forEach { it.delete() }
        settings.update { it.copy(lastBackup = now()) }
        return target
    }

    /**
     * Vuelve a lo guardado en [zip]. Antes hace un respaldo de lo actual, por si acaso.
     * Solo se restauran las carpetas y archivos de [INCLUDED]; nada fuera de Selah.
     */
    @Synchronized
    fun restore(zip: File): Int {
        create()
        val root = baseDir.canonicalFile
        var n = 0
        ZipFile(zip).use { z ->
            for (e in z.entries().asSequence().filter { !it.isDirectory }) {
                val name = e.name.replace('\\', '/')
                if (INCLUDED.none { name == it || name.startsWith("$it/") }) continue
                val target = File(baseDir, name).canonicalFile
                // Ya resuelto (sin "../"), tiene que seguir siendo uno de los incluidos: "canciones/../ajustes.json" no.
                val allowed = INCLUDED.any { inc -> File(root, inc).canonicalFile.let { target == it || target.path.startsWith(it.path + File.separator) } }
                if (!allowed) continue
                target.parentFile.mkdirs()
                z.getInputStream(e).use { input -> target.outputStream().use { input.copyTo(it) } }
                n++
            }
        }
        return n
    }

    companion object {
        const val KEEP = 10
        const val WEEK_MILLIS = 7L * 24 * 3600 * 1000
        const val PREFIX = "Selah-respaldo-"
        /** Lo que entra en el respaldo (relativo a la carpeta Selah). */
        val INCLUDED = listOf("canciones", "predicaciones", "listas", "lista-del-culto.json", "historial.csv")
    }
}
