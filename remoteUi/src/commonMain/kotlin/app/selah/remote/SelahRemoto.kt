package app.selah.remote

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.MutableStateFlow

/** Un enlace selah://connect?host=…&pin=… (botón "Abrir en la app" o QR del control web). */
data class ConnectLink(val host: String, val pin: String?)

/**
 * Lee selah://connect?host=192.168.x.x[&pin=1234]; cualquier otra cosa devuelve null.
 * También acepta proyecta://connect…: el control web de un PC que todavía tiene la versión 2.4 (cuando se llamaba Proyecta).
 */
fun parseConnectLink(url: String): ConnectLink? {
    val link = url.trim()
    val rest = listOf("selah://connect", "proyecta://connect").firstOrNull { link.startsWith(it) }?.let { link.removePrefix(it) } ?: return null
    val query = rest.removePrefix("/").takeIf { it.startsWith("?") }?.drop(1) ?: return null
    val params = query.split('&').mapNotNull { p ->
        val i = p.indexOf('=')
        if (i <= 0) null else p.substring(0, i) to decodeQuery(p.substring(i + 1))
    }.toMap()
    val host = params["host"]?.trim().orEmpty()
    if (!isValidHost(host)) return null
    val pin = params["pin"]?.trim()?.takeIf { it.matches(Regex("""\d{4,6}""")) }
    return ConnectLink(host, pin)
}

/**
 * Una dirección a la que se puede conectar: IPv4 o nombre ("192.168.1.20", "pc-iglesia.local"), o IPv6
 * ("fe80::1" o "[fe80::1]"). Sin puerto, sin partes vacías ("a..b") ni cosas como ".." o "-".
 */
internal fun isValidHost(host: String): Boolean {
    if (host.isEmpty() || host.length > 64) return false
    val bare = host.removeSurrounding("[", "]")
    if (':' in bare) {
        // IPv6: grupos de 1-4 dígitos hex separados por ":", con a lo sumo un "::".
        val parts = bare.split("::")
        if (parts.size > 2 || bare.count { it == ':' } < 2) return false
        return parts.all { half -> half.isEmpty() || half.split(':').all { g -> g.length in 1..4 && g.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' } } }
    }
    if (host.startsWith("[")) return false
    return host.split('.').all { label ->
        label.length in 1..63 && label.first() != '-' && label.last() != '-' &&
            label.all { (it in '0'..'9') || (it in 'a'..'z') || (it in 'A'..'Z') || it == '-' }
    }
}

private fun decodeQuery(s: String): String = buildString {
    var i = 0
    while (i < s.length) {
        val c = s[i]
        when {
            c == '+' -> append(' ')
            c == '%' && i + 2 < s.length ->
                s.substring(i + 1, i + 3).toIntOrNull(16)?.let { append(it.toChar()); i += 2 } ?: append(c)
            else -> append(c)
        }
        i++
    }
}

/**
 * Guarda el enlace recibido: la clave vale solo para ese computador.
 * Devuelve la dirección a la que hay que conectarse.
 */
fun KeyValueStore.acceptLink(link: ConnectLink): String {
    migrateLegacyPin()
    link.pin?.let { put("pin" to it, "pinHost" to link.host) }
    return link.host
}

/**
 * La 2.4 guardaba la clave sin decir de qué computador era. Se ata, una sola vez y antes de que nada cambie
 * "host", al computador que estaba guardado entonces. (Si no, la clave se le mandaría a cualquier PC nuevo.)
 */
internal fun KeyValueStore.migrateLegacyPin() {
    if (get("pin") != null && get("pinHost") == null) get("host")?.takeIf { it.isNotEmpty() }?.let { put("pinHost" to it) }
}

/** Le enseña al cliente dónde está la clave guardada y la recuerda cuando el PC la acepta. */
fun RemoteClient.useStore(store: KeyValueStore) {
    store.migrateLegacyPin()
    // La clave se manda sola solo al computador donde se aceptó.
    savedPin = { host -> store.get("pin")?.takeIf { store.get("pinHost") == host } }
    onPinAccepted = { host, pin -> store.put("pin" to pin, "pinHost" to host) }
    onHostChanged = { host -> store.put("host" to host) }
    store.get("host")?.takeIf { it.isNotEmpty() }?.let { connect(it) }
}

/**
 * Toda la app (igual en Android y en iPhone). Cada sistema solo pone la ventana alrededor.
 * @param linkHost dirección que llegó por un enlace selah://connect (se vuelve null al usarla)
 * @param topBanner aviso de actualización (solo la versión de Android de GitHub lo tiene)
 */
@Composable
fun SelahRemoto(
    client: RemoteClient,
    store: KeyValueStore,
    linkHost: MutableStateFlow<String?>,
    topBanner: @Composable () -> Unit = {},
) {
    val notices = remember { Notices() }
    LaunchedEffect(client) { client.errors.collect { notices.show(it, long = true) } }
    MaterialTheme(colorScheme = darkColorScheme()) {
        CompositionLocalProvider(LocalNotices provides notices) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                // El sistema dibuja la app detrás de sus barras: se deja libre su espacio
                // (barra de estado, botones, isla del iPhone y teclado) para que nada quede tapado.
                Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                    var pcName by remember { mutableStateOf(store.get("name").orEmpty()) }
                    val link by linkHost.collectAsState()
                    val connect: (FoundServer) -> Unit = { server ->
                        store.put("host" to server.host, "name" to server.name)
                        pcName = server.name
                        client.connect(server.host)
                    }
                    // Enlace desde el navegador: conectarse a ese PC y averiguar su nombre.
                    LaunchedEffect(link) {
                        val host = link ?: return@LaunchedEffect
                        connect(FoundServer(client.pcName(host) ?: host, host))
                        linkHost.value = null
                    }
                    // Conexión guardada de una versión anterior, sin nombre: preguntárselo al PC.
                    LaunchedEffect(Unit) {
                        val host = store.get("host").orEmpty()
                        if (pcName.isEmpty() && host.isNotEmpty()) {
                            client.pcName(host)?.let { name ->
                                store.put("name" to name)
                                pcName = name
                            }
                        }
                    }
                    RemoteApp(
                        client = client,
                        savedHost = store.get("host").orEmpty(),
                        pcName = pcName,
                        linkKey = link,
                        onConnect = connect,
                        topBanner = topBanner,
                        store = store,
                    )
                    NoticeHost(notices)
                }
            }
        }
    }
}
