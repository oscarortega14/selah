package app.selah.desktop

import app.selah.shared.ServerMessage
import app.selah.shared.WS_PATH
import app.selah.shared.decodeServerMessage
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Base64
import kotlin.io.path.createTempDirectory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Herramientas para las pruebas "Adversarial*": un servidor real en un puerto libre y un cliente
 * WebSocket/HTTP hecho a mano (para mandar cabeceras y marcos que un cliente normal no deja mandar).
 */
internal class AdvServer(pin: String? = null, songs: Boolean = true) : Closeable {
    val root: File = createTempDirectory("selah-adv").toFile()
    /** La biblioteca vive en una subcarpeta: así se puede ver si algo se escribe al lado (fuera). */
    val dir: File = File(root, "Selah").apply { mkdirs() }
    val pin = MutableStateFlow(pin)
    val library: Library
    val commands: Commands
    val port: Int = ServerSocket(0).use { it.localPort }
    val server: RemoteServer

    init {
        if (songs) {
            File(dir, "canciones").mkdirs()
            File(dir, "canciones/b.txt").writeText("# Beta\n\nuno\n\ndos\n\ntres\n")
            File(dir, "canciones/a.txt").writeText("# Alfa\n\nuno\n\ndos\n")
        }
        library = Library(dir).also { it.reload() }
        commands = Commands.create(library).apply {
            onSelectBible = library::selectBible
            onSelectSecondBible = library::selectSecondBible
        }
        server = RemoteServer(commands, library, port, this.pin).also { it.start() }
    }

    val presenter get() = commands.presenter

    /** Todo lo que hay debajo de [root] fuera de la carpeta de la biblioteca. */
    fun filesOutsideLibrary(): List<File> = root.walkTopDown().filter { it != root && !it.path.startsWith(dir.path) }.toList()

    override fun close() {
        server.stop()
        root.deleteRecursively()
    }
}

/** Cliente WebSocket mínimo sobre un Socket: controla cabeceras, opcodes, fragmentos y máscaras. */
internal class RawWs(
    port: Int,
    headers: List<Pair<String, String>> = listOf("Host" to "127.0.0.1:$port"),
    path: String = WS_PATH,
) : Closeable {
    val socket = Socket("127.0.0.1", port).apply { soTimeout = 5000; tcpNoDelay = true }
    private val out = socket.getOutputStream()
    private val inp = DataInputStream(BufferedInputStream(socket.getInputStream()))
    val status: Int
    var closedByServer = false
        private set

    init {
        val key = Base64.getEncoder().encodeToString(Random.nextBytes(16))
        val sb = StringBuilder("GET $path HTTP/1.1\r\n")
        headers.forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
        sb.append("Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1)); out.flush()
        status = readHttpHead(inp).lineSequence().first().split(' ')[1].toInt()
    }

    @Synchronized
    fun send(opcode: Int, payload: ByteArray, fin: Boolean = true) {
        val buf = ByteArrayOutputStream()
        buf.write((if (fin) 0x80 else 0) or opcode)
        val n = payload.size
        when {
            n < 126 -> buf.write(0x80 or n)
            n < 65536 -> { buf.write(0x80 or 126); buf.write(n shr 8); buf.write(n and 0xff) }
            else -> { buf.write(0x80 or 127); for (i in 7 downTo 0) buf.write(((n.toLong() shr (8 * i)) and 0xff).toInt()) }
        }
        val mask = Random.nextBytes(4)
        buf.write(mask)
        buf.write(ByteArray(n) { (payload[it].toInt() xor mask[it % 4].toInt()).toByte() })
        out.write(buf.toByteArray()); out.flush()
    }

    fun sendText(s: String) = send(1, s.toByteArray(Charsets.UTF_8))

    /** Siguiente marco (opcode, datos); null si se cerró o no llegó nada a tiempo. */
    fun readFrame(timeoutMs: Int = 3000): Pair<Int, ByteArray>? {
        socket.soTimeout = timeoutMs
        return try {
            val b0 = inp.readUnsignedByte()
            val b1 = inp.readUnsignedByte()
            var len = (b1 and 0x7f).toLong()
            if (len == 126L) len = inp.readUnsignedShort().toLong() else if (len == 127L) len = inp.readLong()
            val data = ByteArray(len.toInt()).also { inp.readFully(it) }
            val op = b0 and 0x0f
            if (op == 8) closedByServer = true
            op to data
        } catch (e: SocketTimeoutException) {
            null
        } catch (e: java.io.IOException) {
            closedByServer = true; null
        }
    }

    /** Lee mensajes hasta que uno cumpla [pred]; null si se acaba el tiempo o se cierra. */
    fun await(timeoutMs: Long = 3000, pred: (ServerMessage) -> Boolean): ServerMessage? {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            val left = (end - System.currentTimeMillis()).toInt().coerceAtLeast(1)
            val (op, data) = readFrame(left) ?: return null
            if (op == 8) return null
            if (op != 1) continue
            val m = runCatching { decodeServerMessage(data.toString(Charsets.UTF_8)) }.getOrNull() ?: continue
            if (pred(m)) return m
        }
        return null
    }

    /** ¿El servidor cerró esta conexión (marco de cierre o fin del socket) antes de [timeoutMs]? */
    fun waitClosed(timeoutMs: Long = 3000): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end && !closedByServer) {
            readFrame((end - System.currentTimeMillis()).toInt().coerceAtLeast(1))
        }
        return closedByServer
    }

    override fun close() { runCatching { socket.close() } }
}

internal fun readHttpHead(inp: InputStream): String {
    val head = ByteArrayOutputStream()
    var last4 = 0
    while (true) {
        val b = inp.read()
        if (b < 0) break
        head.write(b)
        last4 = (last4 shl 8) or b
        if (last4 == 0x0d0a0d0a) break
    }
    return head.toString(Charsets.ISO_8859_1)
}

/** Respuesta HTTP cruda: (código, cuerpo). Lee hasta Content-Length o hasta que se cierre. */
internal fun rawHttp(port: Int, request: ByteArray, body: ByteArray? = null, halfClose: Boolean = false, timeoutMs: Int = 5000): Pair<Int, String> {
    Socket("127.0.0.1", port).use { s ->
        s.soTimeout = timeoutMs
        s.getOutputStream().write(request)
        if (body != null) s.getOutputStream().write(body)
        s.getOutputStream().flush()
        if (halfClose) s.shutdownOutput()
        val inp = BufferedInputStream(s.getInputStream())
        val head = try { readHttpHead(inp) } catch (e: java.io.IOException) { return -1 to "" }
        if (head.isEmpty()) return -1 to ""
        val code = head.lineSequence().first().split(' ').getOrNull(1)?.toIntOrNull() ?: -1
        val len = Regex("""(?im)^content-length:\s*(\d+)""").find(head)?.groupValues?.get(1)?.toInt()
        val bodyOut = if (len != null) ByteArray(len).also { DataInputStream(inp).readFully(it) } else runCatching { inp.readBytes() }.getOrDefault(ByteArray(0))
        return code to bodyOut.toString(Charsets.UTF_8)
    }
}

class AdversarialSupportTest {
    @Test
    fun theRawClientWorks() {
        AdvServer().use { s ->
            RawWs(s.port).use { ws ->
                assertEquals(101, ws.status)
                kotlin.test.assertNotNull(ws.await { it is ServerMessage.State })
            }
        }
    }
}
