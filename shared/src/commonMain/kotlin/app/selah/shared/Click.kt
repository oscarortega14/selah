package app.selah.shared

/**
 * Sincroniza el reloj de un celular con el del PC (como NTP): se manda un Ping con la hora local t0, el PC
 * responde con la suya (s) y llega en t1. Si la ida y la vuelta tardan lo mismo, el PC marcaba s en
 * (t0 + t1) / 2. Se usa la muestra con la ida y vuelta más corta (la menos afectada por el Wi-Fi).
 */
class ClockSync(private val keep: Int = 16) {
    private class Sample(val rtt: Long, val offset: Long)
    private val samples = ArrayDeque<Sample>()

    /** Hora del PC − hora local, en ms (0 hasta tener muestras). */
    var offset: Long = 0
        private set

    /** Ida y vuelta de la mejor muestra (ms), o null si no hay: para saber qué tan preciso es. */
    var bestRtt: Long? = null
        private set

    /** Agrega una respuesta. [t0] hora local al mandar, [server] hora del PC, [t1] hora local al recibir. */
    fun add(t0: Long, server: Long, t1: Long) {
        val rtt = t1 - t0
        if (rtt < 0 || rtt > 5_000) return // reloj que saltó o respuesta perdida
        samples.addLast(Sample(rtt, server - (t0 + t1) / 2))
        while (samples.size > keep) samples.removeFirst()
        val best = samples.minBy { it.rtt }
        offset = best.offset
        bestRtt = best.rtt
    }

    /** Si todavía no hay ping, una aproximación con la hora que trae el estado (sin descontar la demora). */
    fun roughFromState(serverNow: Long, localNow: Long) {
        if (samples.isEmpty() && serverNow > 0) offset = serverNow - localNow
    }

    fun reset() { samples.clear(); offset = 0; bestRtt = null }
}

/**
 * Letra automática: qué diapositiva debe estar en pantalla en el tiempo [beat] según [timing] (en qué tiempo
 * empieza cada una; -1 = sin grabar). Cambia [ahead] tiempos antes, para que la congregación alcance a leer.
 * null = todavía no empieza (la cuenta) o no hay tiempos.
 */
fun autoSlide(timing: List<Int>, beat: Double, ahead: Double = 0.5): Int? {
    // La de tiempo más alto que ya llegó (si dos empiezan igual, la última).
    var best = -1
    var bestTime = -1
    timing.forEachIndexed { i, t -> if (t >= 0 && t - ahead <= beat && t >= bestTime) { best = i; bestTime = t } }
    return best.takeIf { it >= 0 }
}

/**
 * Al terminar de grabar: los tiempos de cada diapositiva ([recorded]: índice → tiempo) para la línea "Tiempos:".
 * Las que no se mostraron quedan en -1. Si un tiempo quedó antes que el anterior (se devolvieron), se descarta.
 */
fun timingFromRecording(slides: Int, recorded: Map<Int, Int>): List<Int> {
    val out = MutableList(slides) { -1 }
    var last = -1
    for (i in 0 until slides) {
        val t = recorded[i] ?: continue
        if (t >= last) { out[i] = t; last = t }
    }
    return out
}

/** Hay al menos dos diapositivas con tiempo: sirve para la letra automática. */
fun usableTiming(timing: List<Int>): Boolean = timing.count { it >= 0 } >= 2
