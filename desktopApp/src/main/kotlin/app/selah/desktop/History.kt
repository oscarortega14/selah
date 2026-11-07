package app.selah.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Algo que se proyectó: una canción (ref = id) o una lectura (ref = cita). */
data class HistoryEntry(
    val date: LocalDate,
    val time: String,
    val kind: Kind,
    val title: String,
    val ref: String,
) {
    enum class Kind(val label: String) { SONG("Canción"), BIBLE("Lectura"), SERMON("Predicación") }
}

/** Una fila del reporte CCLI. */
data class CcliRow(val title: String, val author: String, val ccli: String, val times: Int, val dates: List<LocalDate>)

/**
 * Historial de lo que se proyectó, en Selah/historial.csv (se abre con Excel).
 * Cada canción o lectura cuenta una vez por día, aunque se proyecte varias veces.
 */
class History(
    private val file: File,
    private val now: () -> LocalDateTime = LocalDateTime::now,
) {
    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<List<HistoryEntry>> = _entries.asStateFlow()

    /** Volver a leer de disco (después de restaurar un respaldo). */
    @Synchronized
    fun reloadFromDisk() { _entries.value = load() }

    @Synchronized
    fun record(kind: HistoryEntry.Kind, title: String, ref: String) {
        val t = now()
        val date = t.toLocalDate()
        if (_entries.value.any { it.date == date && it.kind == kind && it.ref == ref }) return
        val entry = HistoryEntry(date, t.format(HOUR), kind, title, ref)
        runCatching {
            file.parentFile?.mkdirs()
            if (!file.exists()) file.writeText(HEADER + "\n", Charsets.UTF_8)
            file.appendText(toCsv(entry) + "\n", Charsets.UTF_8)
        }
        _entries.value = _entries.value + entry
    }

    /** Una canción o predicación cambió de nombre: sus registros pasan al nombre nuevo (no se parte en dos). */
    @Synchronized
    fun renamed(kind: HistoryEntry.Kind, oldRef: String, newRef: String, title: String) {
        val list = _entries.value
        if (list.none { it.kind == kind && it.ref == oldRef }) return
        // Si ese día ya estaba con el nombre nuevo, cuenta una sola vez (como siempre: una vez por día).
        val fixed = list.map { if (it.kind == kind && it.ref == oldRef) it.copy(ref = newRef, title = title) else it }
            .distinctBy { Triple(it.date, it.kind, it.ref) }
        _entries.value = fixed
        runCatching { exportCsv(file) }
    }

    /** Última fecha en que se cantó cada canción (por id). */
    fun lastSung(): Map<String, LocalDate> = _entries.value.asSequence()
        .filter { it.kind == HistoryEntry.Kind.SONG }
        .groupBy { it.ref }
        .mapValues { (_, list) -> list.maxOf { it.date } }

    /** Canciones más cantadas desde [since]: título y cuántos días se cantó. */
    fun topSongs(since: LocalDate): List<Pair<String, Int>> = _entries.value
        .filter { it.kind == HistoryEntry.Kind.SONG && !it.date.isBefore(since) }
        .groupBy { it.ref }
        .map { (_, list) -> list.last().title to list.size }
        .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })

    /**
     * Reporte para CCLI (licencia de canciones): cada canción proyectada entre [from] y [to]
     * (inclusive), con autor, número CCLI y cuántas veces (días) se usó. [song] da los datos actuales
     * de la canción por su id (null si ya no existe: se usa el título guardado).
     */
    fun ccliReport(from: LocalDate, to: LocalDate, song: (String) -> app.selah.shared.Song?): List<CcliRow> = _entries.value
        .filter { it.kind == HistoryEntry.Kind.SONG && !it.date.isBefore(from) && !it.date.isAfter(to) }
        .groupBy { it.ref }
        .map { (ref, list) ->
            val s = song(ref)
            CcliRow(s?.title ?: list.last().title, s?.author.orEmpty(), s?.ccli.orEmpty(), list.size, list.map { it.date }.sorted())
        }
        .sortedWith(compareByDescending<CcliRow> { it.times }.thenBy { it.title.lowercase() })

    fun exportCsv(target: File) {
        target.writeText(HEADER + "\n" + _entries.value.joinToString("") { toCsv(it) + "\n" }, Charsets.UTF_8)
    }

    private fun load(): List<HistoryEntry> = runCatching {
        file.readLines(Charsets.UTF_8).drop(1).mapNotNull { line ->
            val f = parseCsvLine(line)
            if (f.size < 5) return@mapNotNull null
            val kind = HistoryEntry.Kind.entries.firstOrNull { it.label == f[2] } ?: return@mapNotNull null
            runCatching { HistoryEntry(LocalDate.parse(f[0]), f[1], kind, unescapeFormula(f[3]), unescapeFormula(f[4])) }.getOrNull()
        }
    }.getOrDefault(emptyList())

    companion object {
        const val HEADER = "fecha,hora,tipo,titulo,referencia"
        private val HOUR = DateTimeFormatter.ofPattern("HH:mm")

        const val CCLI_HEADER = "titulo,autor,numero_ccli,veces,fechas"

        /** El reporte en CSV (se abre con Excel). Las canciones sin número CCLI quedan con la columna vacía. */
        fun ccliCsv(rows: List<CcliRow>): String = CCLI_HEADER + "\n" + rows.joinToString("") { r ->
            listOf(r.title, r.author, r.ccli, r.times.toString(), r.dates.joinToString(" ")).joinToString(",") { csvField(it) } + "\n"
        }

        internal fun toCsv(e: HistoryEntry) =
            listOf(e.date.toString(), e.time, e.kind.label, e.title, e.ref).joinToString(",") { csvField(it) }

        /**
         * Un campo para Excel. Si empieza con = + - @ (o tab / retorno), Excel lo tomaría como fórmula
         * (un título importado como =HYPERLINK(…) se ejecutaría): se le antepone ' para que quede como texto.
         */
        private fun csvField(raw: String): String {
            val s = if (raw.firstOrNull() in FORMULA_START) "'$raw" else raw
            return if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
        }

        private val FORMULA_START = setOf('=', '+', '-', '@', '\t', '\r')

        /** Al leer el historial: quita el ' que [csvField] le puso a un texto que empieza como fórmula. */
        private fun unescapeFormula(s: String) = if (s.length > 1 && s[0] == '\'' && s[1] in FORMULA_START) s.substring(1) else s

        internal fun parseCsvLine(line: String): List<String> {
            val out = mutableListOf<String>()
            val sb = StringBuilder()
            var quoted = false
            var i = 0
            while (i < line.length) {
                val c = line[i]
                when {
                    quoted && c == '"' && line.getOrNull(i + 1) == '"' -> { sb.append('"'); i++ }
                    c == '"' -> quoted = !quoted
                    c == ',' && !quoted -> { out += sb.toString(); sb.clear() }
                    else -> sb.append(c)
                }
                i++
            }
            out += sb.toString()
            return out
        }
    }
}

private val DAYS = listOf("lunes", "martes", "miércoles", "jueves", "viernes", "sábado", "domingo")
private val MONTHS_SHORT = listOf("ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sep", "oct", "nov", "dic")

/** "domingo 8 nov 2026" */
fun formatDay(d: LocalDate): String = "${DAYS[d.dayOfWeek.value - 1]} ${d.dayOfMonth} ${MONTHS_SHORT[d.monthValue - 1]} ${d.year}"

/** "hoy", "ayer", "hace 5 días", "8 nov", "8 nov 2025" — para la lista de canciones. */
fun formatAgo(d: LocalDate, today: LocalDate = LocalDate.now()): String {
    val days = java.time.temporal.ChronoUnit.DAYS.between(d, today)
    return when {
        days <= 0L -> "hoy"
        days == 1L -> "ayer"
        days < 7 -> "hace $days días"
        d.year == today.year -> "${d.dayOfMonth} ${MONTHS_SHORT[d.monthValue - 1]}"
        else -> "${d.dayOfMonth} ${MONTHS_SHORT[d.monthValue - 1]} ${d.year}"
    }
}
