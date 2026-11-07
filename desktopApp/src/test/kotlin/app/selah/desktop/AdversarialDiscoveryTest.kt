package app.selah.desktop

import app.selah.shared.DISCOVERY_REQUEST
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Descubrimiento por UDP (8766) frente a paquetes raros. */
class AdversarialDiscoveryTest {

    private fun DatagramSocket.ask(port: Int, payload: ByteArray, timeoutMs: Int = 800): String? {
        soTimeout = timeoutMs
        send(DatagramPacket(payload, payload.size, InetAddress.getLoopbackAddress(), port))
        val buf = ByteArray(2048)
        val p = DatagramPacket(buf, buf.size)
        return try { receive(p); String(p.data, 0, p.length) } catch (e: SocketTimeoutException) { null }
    }

    @Test
    fun garbageDatagramsDoNotKillDiscovery() {
        val responder = DiscoveryResponder(port = 0).also { it.start() }
        try {
            val port = responder.boundPort
            DatagramSocket().use { c ->
                val junk = listOf(
                    ByteArray(0), ByteArray(65_000) { 1 }, "PROYECTA?".repeat(100).toByteArray(), "proyecta?".toByteArray(),
                    byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0, 0), ("PROYECTA?" + "\u0000".repeat(300)).toByteArray(),
                    "SELAH?".toByteArray(), "PROYECTA?\nPROYECTA?".toByteArray(),
                )
                val answered = junk.count { c.ask(port, it, 300) != null }
                // Paquete más largo que el búfer: "PROYECTA?" al inicio y basura después no debe contestarse
                val prefixed = c.ask(port, ("PROYECTA?" + " ".repeat(400) + "x").toByteArray(), 300)
                println("ADV descubrimiento: $answered paquetes raros contestados; prefijo+relleno → ${prefixed != null}")
                val reply = c.ask(port, DISCOVERY_REQUEST.toByteArray())
                assertNotNull(reply, "el descubrimiento dejó de contestar tras paquetes raros")
                assertTrue(reply.length < 200)
            }
        } finally {
            responder.stop()
        }
    }
}
