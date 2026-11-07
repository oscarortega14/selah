package app.selah.desktop

import app.selah.shared.ClientMessage
import kotlinx.coroutines.flow.StateFlow

/**
 * Clave para controlar desde la red. Sin la clave un dispositivo puede mirar (vista de escenario,
 * OBS, buscar en la Biblia) pero no cambiar lo que se proyecta.
 *
 * Contra quien prueba claves:
 * - Por dirección: tras [MAX_TRIES] fallos queda bloqueada 1 minuto; cada nuevo bloqueo dura el doble (hasta 30 min).
 * - En total: si en un minuto hay [MAX_GLOBAL_FAILURES] fallos (aunque vengan de muchas direcciones,
 *   alguien cambiándose de IP), nadie más puede probar hasta que pase ese minuto. Los que ya entraron siguen.
 */
class PinGuard(
    private val pin: StateFlow<String?>,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    sealed interface Result {
        data object Ok : Result
        data class Wrong(val triesLeft: Int) : Result
        data class Locked(val seconds: Long) : Result
    }

    private class Failures(var count: Int, var lockedUntil: Long, var lockouts: Int, var last: Long = 0)
    private val failures = HashMap<String, Failures>()
    private val recent = ArrayDeque<Long>() // fallos de todos en el último minuto

    val required: Boolean get() = !pin.value.isNullOrEmpty()

    /** ¿Una sesión que dio [granted] puede controlar ahora? (si la clave cambió, ya no) */
    fun allows(granted: String?): Boolean = !required || granted == pin.value

    @Synchronized
    fun check(address: String, attempt: String): Result {
        val now = clock()
        while (recent.isNotEmpty() && recent.first() <= now - WINDOW_MILLIS) recent.removeFirst()
        // Solo se anota una dirección cuando de verdad falla (antes, cada intento rechazado dejaba una
        // entrada para siempre: alguien con miles de direcciones IPv6 hacía crecer la memoria sin fin).
        failures[address]?.let { if (it.lockedUntil > now) return Result.Locked((it.lockedUntil - now + 999) / 1000) }
        if (recent.size >= MAX_GLOBAL_FAILURES) return Result.Locked((recent.first() + WINDOW_MILLIS - now + 999) / 1000)
        if (!required || attempt.trim() == pin.value) {
            failures.remove(address)
            return Result.Ok
        }
        if (failures.size >= MAX_TRACKED) forgetOld(now)
        val f = failures.getOrPut(address) { Failures(0, 0, 0) }
        f.last = now
        recent.addLast(now)
        f.count++
        if (f.count >= MAX_TRIES) {
            f.count = 0
            f.lockouts++
            val lock = (LOCK_MILLIS shl (f.lockouts - 1).coerceAtMost(5)).coerceAtMost(MAX_LOCK_MILLIS)
            f.lockedUntil = now + lock
            return Result.Locked(lock / 1000)
        }
        return Result.Wrong(MAX_TRIES - f.count)
    }

    /** Olvida las direcciones que no están bloqueadas y no fallan hace rato (y, si aún son muchas, las más viejas). */
    private fun forgetOld(now: Long) {
        failures.entries.removeAll { (_, f) -> f.lockedUntil <= now && f.last < now - MAX_LOCK_MILLIS }
        if (failures.size >= MAX_TRACKED) {
            failures.entries.filter { it.value.lockedUntil <= now }.sortedBy { it.value.last }
                .take(failures.size - MAX_TRACKED / 2).forEach { failures.remove(it.key) }
        }
    }

    companion object {
        /** Direcciones que se recuerdan a la vez (de sobra para una iglesia). */
        const val MAX_TRACKED = 1024
        const val MAX_TRIES = 5
        const val LOCK_MILLIS = 60_000L
        const val MAX_LOCK_MILLIS = 30 * 60_000L
        const val MAX_GLOBAL_FAILURES = 20
        const val WINDOW_MILLIS = 60_000L

        /** Lo que se puede pedir sin clave: solo mirar o consultar. */
        fun isReadOnly(msg: ClientMessage) =
            msg is ClientMessage.BibleSearch || msg is ClientMessage.SongSearch || msg is ClientMessage.BibleChapter || msg is ClientMessage.Login || msg is ClientMessage.Hello
    }
}
