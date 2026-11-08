package app.selah.shared

import kotlinx.serialization.Serializable

/*
 * Cultos de la iglesia: cada uno con su día y hora, una plantilla (la estructura fija del culto) y
 * listas en rotación ("Dominical A, B, C, D"). Viven en el PC (Selah/cultos.json).
 */

/** Qué va en cada paso de la plantilla. */
@Serializable
enum class StepKind { SONG, BIBLE, ANNOUNCEMENT, SERMON }

/** Un paso de la plantilla. En una canción, [tag] es el tipo ("Alabanza", "Adoración"; "" = cualquiera). */
@Serializable
data class ServiceStep(val kind: StepKind, val tag: String = "")

/**
 * Un culto: "Dominical", el domingo (día 7; lunes = 1) a las "9:00".
 * [rotation]: listas guardadas que se turnan; [lastUsed]: la última que se abrió (la siguiente es la que toca).
 */
@Serializable
data class WorshipService(
    val name: String,
    val day: Int,
    val time: String = "",
    val steps: List<ServiceStep> = emptyList(),
    val rotation: List<String> = emptyList(),
    val lastUsed: String? = null,
) {
    /** "Dominical · domingo 9:00" */
    val label: String get() = name + " · " + (DAY_NAMES.getOrNull(day - 1) ?: "") + if (time.isBlank()) "" else " $time"

    /** La lista que toca: la que sigue a la última usada (de las que todavía existen), o la primera. */
    fun nextList(saved: Collection<String>): String? {
        val available = rotation.filter { it in saved }
        if (available.isEmpty()) return null
        val i = available.indexOf(lastUsed)
        return available[(i + 1) % available.size]
    }

    /** "Alabanza 1", "Alabanza 2", "Adoración 1", "Lectura", "Anuncio", "Predicación" (numerados si se repiten). */
    fun stepLabels(): List<String> {
        val names = steps.map { stepName(it) }
        return names.mapIndexed { i, n -> if (names.count { it == n } > 1) "$n ${names.take(i + 1).count { it == n }}" else n }
    }
}

/** Todos los cultos y cada cuántos días se avisa que una canción se repite. */
@Serializable
data class ServicesConfig(
    val services: List<WorshipService> = emptyList(),
    val repeatDays: Int = DEFAULT_REPEAT_DAYS,
) {
    /**
     * El culto de hoy ([day]: lunes = 1 … domingo = 7). Si hay varios ese día, el próximo según la hora
     * ([minutes] desde medianoche; uno que empezó hace menos de 3 horas todavía cuenta); si ya pasaron todos, el último.
     */
    fun serviceFor(day: Int, minutes: Int): WorshipService? {
        val today = services.filter { it.day == day }.sortedBy { parseMinutes(it.time) ?: 0 }
        if (today.isEmpty()) return null
        return today.firstOrNull { (parseMinutes(it.time) ?: 0) + 180 >= minutes } ?: today.last()
    }

    /** Se abrió la lista [list]: en cada culto que la tiene en rotación, queda como la última usada. */
    fun markUsed(list: String): ServicesConfig =
        copy(services = services.map { if (list in it.rotation) it.copy(lastUsed = list) else it })
}

const val DEFAULT_REPEAT_DAYS = 14

val DAY_NAMES = listOf("lunes", "martes", "miércoles", "jueves", "viernes", "sábado", "domingo")

/** "Alabanza", "Canción", "Lectura", "Anuncio", "Predicación". */
fun stepName(step: ServiceStep): String = when (step.kind) {
    StepKind.SONG -> step.tag.ifBlank { "Canción" }
    StepKind.BIBLE -> "Lectura"
    StepKind.ANNOUNCEMENT -> "Anuncio"
    StepKind.SERMON -> "Predicación"
}

/** "9:00", "19:30", "7:00 p.m." → minutos desde medianoche; null si no se entiende. */
fun parseMinutes(time: String): Int? {
    val m = Regex("""^\s*(\d{1,2})(?::(\d{2}))?\s*([ap])?\.?\s*m?\.?\s*$""", RegexOption.IGNORE_CASE).matchEntire(time) ?: return null
    var h = m.groupValues[1].toInt()
    val min = m.groupValues[2].toIntOrNull() ?: 0
    when (m.groupValues[3].lowercase()) {
        "p" -> if (h < 12) h += 12
        "a" -> if (h == 12) h = 0
    }
    return if (h in 0..23 && min in 0..59) h * 60 + min else null
}

/** ¿La canción tiene ese tipo? (sin importar mayúsculas ni tildes). Una sin tipo vale para cualquiera. */
fun SongSummary.fits(tag: String): Boolean =
    tag.isBlank() || tags.isEmpty() || tags.any { normalizeKey(it) == normalizeKey(tag) }

/**
 * Canciones sugeridas para un espacio de la plantilla: primero las de ese tipo, luego las sin tipo;
 * dentro de cada grupo, las que nunca se han cantado y luego las que llevan más tiempo sin cantarse.
 */
fun suggestSongs(songs: List<SongSummary>, tag: String, exclude: Set<String> = emptySet()): List<SongSummary> =
    songs.filter { it.id !in exclude && it.fits(tag) }
        .sortedWith(
            compareBy<SongSummary>({ if (tag.isNotBlank() && it.tags.isEmpty()) 1 else 0 }, { it.lastSung ?: "" }, { normalizeText(it.title) }),
        )

/**
 * Días desde que se cantó ([lastSung] "2026-11-02") hasta [today] (año, mes, día), o null si nunca.
 * Sirve en el PC y en el celular (sin java.time).
 */
fun daysSince(lastSung: String?, today: Triple<Int, Int, Int>): Int? {
    val p = lastSung?.split('-')?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 3 } ?: return null
    return (epochDay(today.first, today.second, today.third) - epochDay(p[0], p[1], p[2])).toInt()
}

/** Días desde 1970-01-01 (calendario gregoriano). */
fun epochDay(y: Int, m: Int, d: Int): Long {
    val yy = if (m <= 2) y - 1 else y
    val era = (if (yy >= 0) yy else yy - 399) / 400
    val yoe = yy - era * 400
    val doy = (153 * (m + (if (m > 2) -3 else 9)) + 2) / 5 + d - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146097L + doe - 719468
}

/** "se cantó hace poco" según [repeatDays] (0 = no avisar). */
fun recentlySung(lastSung: String?, today: Triple<Int, Int, Int>, repeatDays: Int): Boolean =
    repeatDays > 0 && (daysSince(lastSung, today) ?: Int.MAX_VALUE) in 0 until repeatDays

/** "hoy", "ayer", "hace 6 días", "hace 3 semanas", "hace 2 meses". */
fun agoText(days: Int): String = when {
    days <= 0 -> "hoy"
    days == 1 -> "ayer"
    days < 14 -> "hace $days días"
    days < 60 -> "hace ${days / 7} semanas"
    days < 730 -> "hace ${days / 30} meses"
    else -> "hace ${days / 365} años"
}
