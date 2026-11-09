package app.selah.remote

import androidx.compose.ui.graphics.toComposeImageBitmap
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import app.selah.shared.DEFAULT_PORT
import app.selah.shared.DiscoveryReply
import app.selah.shared.ProtocolJson
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import platform.posix.AF_INET
import platform.posix.INET_ADDRSTRLEN
import platform.darwin.freeifaddrs
import platform.darwin.getifaddrs
import platform.darwin.ifaddrs
import platform.darwin.inet_ntop
import platform.posix.sockaddr_in
import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarUnitDay
import platform.Foundation.NSCalendarUnitMonth
import platform.Foundation.NSCalendarUnitYear
import platform.Foundation.NSDate
import platform.Foundation.NSUserDefaults
import platform.Foundation.timeIntervalSince1970

actual fun nowMillis(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()

actual fun today(): Triple<Int, Int, Int> {
    val c = NSCalendar.currentCalendar.components(
        NSCalendarUnitYear or NSCalendarUnitMonth or NSCalendarUnitDay, fromDate = NSDate(),
    )
    return Triple(c.year.toInt(), c.month.toInt(), c.day.toInt())
}

actual fun platformHttpClient(config: HttpClientConfig<*>.() -> Unit): HttpClient = HttpClient(Darwin, config)

/**
 * Búsqueda en iPhone: Apple no deja enviar broadcast sin el permiso especial
 * com.apple.developer.networking.multicast, así que se le pregunta a cada dirección de la red Wi-Fi
 * (GET /info, que contesta el nombre del PC). Solo necesita el permiso de "red local" del Info.plist.
 */
actual suspend fun discoverServers(timeoutMs: Long): List<FoundServer> {
    val hosts = SubnetScan.hosts(wifiIpv4() ?: return emptyList())
    val http = HttpClient(Darwin) {
        install(HttpTimeout) { connectTimeoutMillis = 800; requestTimeoutMillis = 1_200 }
    }
    return try {
        SubnetScan.scan(hosts) { host ->
            val response = http.get("http://$host:$DEFAULT_PORT/info")
            if (response.status != HttpStatusCode.OK) null
            else ProtocolJson.decodeFromString(DiscoveryReply.serializer(), response.bodyAsText()).name.ifBlank { host }
        }
    } finally {
        http.close()
    }
}

/** IP del iPhone en el Wi-Fi (interfaz en0), o null si no está en un Wi-Fi. */
@OptIn(ExperimentalForeignApi::class)
internal fun wifiIpv4(): String? = memScoped {
    val list = alloc<CPointerVar<ifaddrs>>()
    if (getifaddrs(list.ptr) != 0) return null
    try {
        var p = list.value
        while (p != null) {
            val ifa = p.pointed
            val addr = ifa.ifa_addr
            if (ifa.ifa_name?.toKString() == "en0" && addr != null && addr.pointed.sa_family.toInt() == AF_INET) {
                val buf = allocArray<ByteVar>(INET_ADDRSTRLEN)
                inet_ntop(AF_INET, addr.reinterpret<sockaddr_in>().pointed.sin_addr.ptr, buf, INET_ADDRSTRLEN.convert())
                return buf.toKString()
            }
            p = ifa.ifa_next
        }
        null
    } finally {
        freeifaddrs(list.value)
    }
}


/** Preferencias del iPhone (NSUserDefaults). */
class IosStore : KeyValueStore {
    private val defaults = NSUserDefaults.standardUserDefaults
    override fun get(key: String): String? = defaults.stringForKey("selah.$key")
    override fun put(vararg pairs: Pair<String, String>) {
        pairs.forEach { (k, v) -> defaults.setObject(v, forKey = "selah.$k") }
    }
}

actual fun decodeImage(bytes: ByteArray): androidx.compose.ui.graphics.ImageBitmap? = runCatching {
    org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
}.getOrNull()
