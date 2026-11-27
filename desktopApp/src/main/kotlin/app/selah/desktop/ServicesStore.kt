package app.selah.desktop

import app.selah.shared.ProtocolJson
import app.selah.shared.ServicesConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** Los cultos de la iglesia (día, hora, plantilla y listas en rotación), en Selah/cultos.json. */
class ServicesStore(private val file: File) {
    private val _config = MutableStateFlow(load())
    val config: StateFlow<ServicesConfig> = _config.asStateFlow()

    @Synchronized
    fun update(f: (ServicesConfig) -> ServicesConfig) {
        val new = f(_config.value).let { c ->
            // Valores sanos aunque el archivo o la ventana traigan algo raro
            c.copy(
                repeatDays = c.repeatDays.coerceIn(0, 365),
                services = c.services.take(MAX_SERVICES).map { s ->
                    s.copy(name = s.name.trim().take(60), day = s.day.coerceIn(1, 7), time = s.time.trim().take(20), steps = s.steps.take(40), rotation = s.rotation.distinct().take(20), planned = s.planned.entries.sortedByDescending { it.key }.take(MAX_PLANNED).associate { it.key to it.value })
                },
            )
        }
        if (new == _config.value) return
        _config.value = new
        runCatching {
            file.parentFile?.mkdirs()
            // Se escribe aparte y luego se reemplaza: un corte de luz no deja el archivo a medias.
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(ProtocolJson.encodeToString(ServicesConfig.serializer(), new), Charsets.UTF_8)
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }.onFailure { Log.error("No pude guardar los cultos", it) }
    }

    /** Se abrió una lista guardada: la rotación de su culto avanza. */
    fun markUsed(list: String) = update { it.markUsed(list) }

    /** Volver a leer de disco (después de restaurar un respaldo). */
    @Synchronized
    fun reloadFromDisk() { _config.value = load() }

    private fun load(): ServicesConfig = runCatching {
        ProtocolJson.decodeFromString(ServicesConfig.serializer(), file.readText(Charsets.UTF_8))
    }.getOrDefault(ServicesConfig())

    companion object {
        const val MAX_SERVICES = 20
        /** Fechas planeadas que se recuerdan por culto (las más nuevas; unos meses de cultos semanales). */
        const val MAX_PLANNED = 30
    }
}

/** El culto de hoy ("Dominical · domingo 9:00") y la lista que le toca en su rotación (si tiene). */
fun todaySuggestion(cfg: app.selah.shared.ServicesConfig, saved: Collection<String>, now: java.time.LocalDateTime): Pair<String, String?> {
    val service = cfg.serviceFor(now.dayOfWeek.value, now.hour * 60 + now.minute) ?: return "" to null
    return service.label to service.listFor(now.toLocalDate().toString(), saved)
}

private val DAY_MONTHS = listOf("ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sep", "oct", "nov", "dic")

/** "domingo 29 nov" */
fun dayText(date: java.time.LocalDate) =
    "${app.selah.shared.DAY_NAMES[date.dayOfWeek.value - 1]} ${date.dayOfMonth} ${DAY_MONTHS[date.monthValue - 1]}"

/** Los cultos de los próximos [days] días desde [from] (hoy incluido): índice del culto y fecha, en orden. */
fun upcomingServices(cfg: app.selah.shared.ServicesConfig, from: java.time.LocalDate, days: Int = 7): List<Pair<Int, java.time.LocalDate>> =
    (0 until days).flatMap { k ->
        val d = from.plusDays(k.toLong())
        cfg.services.withIndex().filter { it.value.day == d.dayOfWeek.value }
            .sortedBy { app.selah.shared.parseMinutes(it.value.time) ?: 0 }
            .map { it.index to d }
    }

/**
 * La semana para la pestaña Lista (PC y celulares): cada culto de los próximos 7 días con su lista (la planeada
 * para esa fecha o la de la rotación) y lo que tiene esa lista.
 */
fun weekPlan(
    cfg: app.selah.shared.ServicesConfig,
    today: java.time.LocalDate,
    saved: Collection<String>,
    items: (String) -> List<app.selah.shared.PlanItem>?,
): List<app.selah.shared.WeekService> = upcomingServices(cfg, today).map { (i, d) ->
    val s = cfg.services[i]
    val list = s.listFor(d.toString(), saved)
    app.selah.shared.WeekService(
        s.name, d.toString(), dayText(d), s.time, list,
        planned = list != null && s.planned[d.toString()]?.equals(list, ignoreCase = true) == true,
        today = d == today,
        items = list?.let(items).orEmpty(),
    )
}

/** Las canciones con la fecha en que se cantaron por última vez ("2026-11-02"). */
fun withLastSung(songs: List<app.selah.shared.SongSummary>, sung: Map<String, java.time.LocalDate>): List<app.selah.shared.SongSummary> =
    songs.map { s -> sung[s.id]?.let { s.copy(lastSung = it.toString()) } ?: s }

