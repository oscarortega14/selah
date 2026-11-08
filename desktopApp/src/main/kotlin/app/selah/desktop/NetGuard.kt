package app.selah.desktop

import app.selah.shared.ClientMessage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Defensas del servidor de los remotos contra abusos desde la red local.
 */
object NetGuard {
    /** Tope de un mensaje de un remoto (los normales pesan menos de 1 KB). */
    const val MAX_FRAME_BYTES = 64 * 1024L

    /** Conexiones abiertas a la vez desde una misma dirección (un celular usa 1 o 2). */
    const val MAX_CONNECTIONS_PER_ADDRESS = 10

    /** Textos que llegan de un remoto: avisos, mensajes, búsquedas… */
    const val MAX_TEXT = 300
    /** Tope de nombres en una orden con lista (el carrusel). */
    const val MAX_LIST = 200

    private val open = ConcurrentHashMap<String, AtomicInteger>()

    /** Reserva una conexión para [address]; false si ya tiene demasiadas. Llamar a [release] al cerrar. */
    fun acquire(address: String): Boolean {
        val n = open.computeIfAbsent(address) { AtomicInteger() }.incrementAndGet()
        if (n > MAX_CONNECTIONS_PER_ADDRESS) { release(address); return false }
        return true
    }

    fun release(address: String) {
        open[address]?.let { if (it.decrementAndGet() <= 0) open.remove(address, it) }
    }

    /**
     * ¿La petición viene de nuestras páginas (o de la app)? Bloquea:
     * - DNS rebinding: un sitio malicioso que hace que su dominio apunte a este PC (Host debe ser
     *   una IP, "localhost" o el nombre de este PC).
     * - Páginas de otros sitios abiertas en un celular de la iglesia que intentan conectarse por detrás
     *   (Origin, si viene, debe ser este mismo servidor). La app no manda Origin.
     */
    fun trusted(hostHeader: String?, origin: String?, pcName: String): Boolean {
        val host = hostHeader?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return false
        val hostname = if (host.startsWith("[")) host.substringAfter('[').substringBefore(']') else host.substringBefore(':')
        val pc = pcName.lowercase()
        val okHost = hostname == "localhost" || IPV4.matches(hostname) ||
            (host.startsWith("[") && ':' in hostname) || hostname == pc || hostname == "$pc.local"
        if (!okHost) return false
        return origin == null || origin.trim().lowercase() == "http://$host"
    }

    private val IPV4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

    /** Recorta los textos que llegan de un remoto (nadie necesita un aviso de 10.000 letras). */
    fun clamp(msg: ClientMessage): ClientMessage = when (msg) {
        is ClientMessage.StageMessage -> msg.copy(text = msg.text.take(MAX_TEXT))
        is ClientMessage.SetAlert -> msg.copy(text = msg.text.take(MAX_TEXT))
        is ClientMessage.SetLowerThird -> msg.copy(title = msg.title.take(MAX_TEXT), subtitle = msg.subtitle.take(MAX_TEXT))
        is ClientMessage.StartCountdown -> msg.copy(text = msg.text.take(MAX_TEXT))
        is ClientMessage.BibleSearch -> msg.copy(query = msg.query.take(MAX_TEXT))
        is ClientMessage.SongSearch -> msg.copy(query = msg.query.take(MAX_TEXT))
        is ClientMessage.ShowBible -> msg.copy(reference = msg.reference.take(MAX_TEXT))
        is ClientMessage.PlanAddBible -> msg.copy(reference = msg.reference.take(MAX_TEXT))
        is ClientMessage.ShowPresentation -> msg.copy(name = msg.name.take(MAX_TEXT))
        is ClientMessage.PlanAddPresentation -> msg.copy(name = msg.name.take(MAX_TEXT))
        is ClientMessage.StartCarousel -> msg.copy(names = msg.names.take(MAX_LIST).map { it.take(MAX_TEXT) })
        else -> msg
    }

    /**
     * Límite de órdenes por conexión (cubeta de fichas): 20 por segundo con ráfagas de 40.
     * Buscar en la Biblia cuesta 5 fichas (recorre toda la Biblia).
     */
    class RateLimiter(private val clock: () -> Long = System::nanoTime) {
        private var tokens = BURST.toDouble()
        private var last = clock()

        fun allow(msg: ClientMessage): Boolean {
            val now = clock()
            tokens = minOf(BURST.toDouble(), tokens + (now - last) / 1e9 * PER_SECOND)
            last = now
            // Buscar y abrir una presentación (puede convertirla) cuestan más
            val cost = if (msg is ClientMessage.BibleSearch || msg is ClientMessage.SongSearch || msg is ClientMessage.ShowPresentation) 5.0 else 1.0
            if (tokens < cost) return false
            tokens -= cost
            return true
        }

        companion object {
            const val PER_SECOND = 20
            const val BURST = 40
        }
    }
}
