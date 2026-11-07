package app.selah.remote

import java.io.DataInputStream
import java.io.OutputStream
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * A tiny, hostile "PC": a raw WebSocket server on [::1]:8765 (127.0.0.1:8765 is taken on this Mac,
 * and RemoteClient's port is hard-coded). Each accepted connection is handed to [onSession].
 */
class AdversarialFakePc(private val onSession: (Session) -> Unit) : AutoCloseable {
    val server: ServerSocket = bindWithRetry()
    val sessions = CopyOnWriteArrayList<Session>()
    private val acceptor = Thread {
        while (!server.isClosed) {
            val s = try { server.accept() } catch (_: Exception) { break }
            Thread {
                try {
                    val session = Session(s)
                    if (session.handshake()) { sessions += session; session.startReader(); onSession(session) }
                } catch (_: Exception) { runCatching { s.close() } }
            }.apply { isDaemon = true }.start()
        }
    }.apply { isDaemon = true; start() }

    override fun close() { runCatching { server.close() }; sessions.forEach { it.close() } }

    class Session(val socket: Socket) {
        private val input = DataInputStream(socket.getInputStream())
        private val out: OutputStream = socket.getOutputStream()
        val received = LinkedBlockingQueue<String>()
        val all = CopyOnWriteArrayList<String>()
        @Volatile var closed = false
        var requestHost: String? = null

        fun handshake(): Boolean {
            val lines = mutableListOf<String>()
            val sb = StringBuilder()
            while (true) {
                val b = input.read(); if (b < 0) return false
                if (b == '\n'.code) { val l = sb.toString().trimEnd('\r'); sb.clear(); if (l.isEmpty()) break; lines += l } else sb.append(b.toChar())
            }
            val key = lines.firstOrNull { it.lowercase().startsWith("sec-websocket-key:") }?.substringAfter(':')?.trim() ?: return false
            requestHost = lines.firstOrNull { it.lowercase().startsWith("host:") }?.substringAfter(':')?.trim()
            val accept = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
            out.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n".toByteArray())
            out.flush()
            return true
        }

        fun startReader() = Thread {
            try {
                while (true) {
                    val b0 = input.readUnsignedByte(); val b1 = input.readUnsignedByte()
                    val op = b0 and 0x0f
                    var len = (b1 and 0x7f).toLong()
                    if (len == 126L) len = input.readUnsignedShort().toLong() else if (len == 127L) len = input.readLong()
                    val mask = if (b1 and 0x80 != 0) ByteArray(4).also { input.readFully(it) } else null
                    val payload = ByteArray(len.toInt()).also { input.readFully(it) }
                    if (mask != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
                    when (op) {
                        1 -> { val t = String(payload); all += t; received.put(t) }
                        8 -> { close(); return@Thread }
                        9 -> frame(0xA, payload)
                    }
                }
            } catch (_: Exception) { closed = true }
        }.apply { isDaemon = true; start() }

        @Synchronized fun frame(op: Int, payload: ByteArray) {
            out.write(0x80 or op)
            when {
                payload.size < 126 -> out.write(payload.size)
                payload.size < 65536 -> { out.write(126); out.write(payload.size shr 8); out.write(payload.size and 0xff) }
                else -> { out.write(127); for (s in 56 downTo 0 step 8) out.write(((payload.size.toLong() shr s) and 0xff).toInt()) }
            }
            out.write(payload); out.flush()
        }

        fun send(text: String) = frame(1, text.toByteArray())

        /** Wait for a client text frame matching [pred]. */
        fun await(timeoutMs: Long = 5_000, pred: (String) -> Boolean): String? {
            all.firstOrNull(pred)?.let { return it }
            val end = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < end) {
                val t = received.poll(50, TimeUnit.MILLISECONDS) ?: continue
                if (pred(t)) return t
            }
            return all.firstOrNull(pred)
        }

        fun close() { closed = true; runCatching { socket.close() } }
    }

    companion object {
        const val HOST = "[::1]"
        private fun bindWithRetry(): ServerSocket {
            val end = System.currentTimeMillis() + 120_000
            while (true) {
                try {
                    return ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(InetAddress.getByName("::1"), 8765)) }
                } catch (e: BindException) {
                    if (System.currentTimeMillis() > end) throw e
                    Thread.sleep(250)
                }
            }
        }
    }
}

/** Polls [cond] up to [timeoutMs]. */
fun eventually(timeoutMs: Long = 5_000, cond: () -> Boolean): Boolean {
    val end = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(20) }
    return cond()
}

class MapStore(vararg init: Pair<String, String>) : KeyValueStore {
    val map = java.util.concurrent.ConcurrentHashMap<String, String>(init.toMap())
    override fun get(key: String) = map[key]
    override fun put(vararg pairs: Pair<String, String>) { map.putAll(pairs) }
}
