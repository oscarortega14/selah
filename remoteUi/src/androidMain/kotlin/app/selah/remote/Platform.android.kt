package app.selah.remote

import androidx.compose.ui.graphics.asImageBitmap
import android.content.Context
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import app.selah.shared.DISCOVERY_PORT
import app.selah.shared.DISCOVERY_REQUEST
import app.selah.shared.DiscoveryReply
import app.selah.shared.ProtocolJson
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.Calendar

actual fun nowMillis(): Long = System.currentTimeMillis()

actual fun today(): Triple<Int, Int, Int> = Calendar.getInstance().let {
    Triple(it.get(Calendar.YEAR), it.get(Calendar.MONTH) + 1, it.get(Calendar.DAY_OF_MONTH))
}

actual fun platformHttpClient(config: HttpClientConfig<*>.() -> Unit): HttpClient = HttpClient(OkHttp, config)

actual suspend fun discoverServers(timeoutMs: Long): List<FoundServer> = withContext(Dispatchers.IO) {
    runCatching {
        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.soTimeout = 300
            val request = DISCOVERY_REQUEST.toByteArray()
            socket.send(DatagramPacket(request, request.size, InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT))
            val found = LinkedHashMap<String, FoundServer>()
            val buf = ByteArray(512)
            val end = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < end) {
                val packet = DatagramPacket(buf, buf.size)
                try { socket.receive(packet) } catch (_: SocketTimeoutException) { continue }
                val reply = runCatching {
                    ProtocolJson.decodeFromString(DiscoveryReply.serializer(), String(packet.data, 0, packet.length))
                }.getOrNull() ?: continue
                val host = packet.address.hostAddress ?: continue
                found[host] = FoundServer(reply.name, host)
            }
            found.values.toList()
        }
    }.getOrDefault(emptyList())
}

@Composable
actual fun rememberMediaPicker(onPicked: (PickedFile) -> Unit): (() -> Unit)? {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val (name, size) = context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val s = c.getColumnIndex(OpenableColumns.SIZE)
            if (c.moveToFirst()) (c.getString(n) ?: "foto.jpg") to (if (s >= 0 && !c.isNull(s)) c.getLong(s) else null) else null
        } ?: ("foto.jpg" to null)
        onPicked(PickedFile(name, size) { context.contentResolver.openInputStream(uri)!!.toByteReadChannel() })
    }
    return { launcher.launch(arrayOf("image/*", "video/*")) }
}

/** Preferencias de Android ("selah"). */
class AndroidStore(context: Context) : KeyValueStore {
    private val prefs = context.getSharedPreferences("selah", Context.MODE_PRIVATE)
    override fun get(key: String): String? = prefs.getString(key, null)
    override fun put(vararg pairs: Pair<String, String>) {
        prefs.edit().apply { pairs.forEach { (k, v) -> putString(k, v) } }.apply()
    }
}

actual fun decodeImage(bytes: ByteArray): androidx.compose.ui.graphics.ImageBitmap? = runCatching {
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
}.getOrNull()
