package app.selah.remote

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * RemoteClient against a hostile fake PC on [::1]:8765.
 * Tests that FAIL expose a bug.
 */
class AdversarialRemoteClientTest {
    private val crashes = CopyOnWriteArrayList<Throwable>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> crashes += e })
    private val clients = mutableListOf<RemoteClient>()

    private fun client() = RemoteClient(scope).also { clients += it }

    @AfterTest fun tearDown() {
        clients.forEach { runCatching { it.close() } }
        scope.cancel()
        assertTrue(crashes.isEmpty(), "uncaught exceptions: $crashes")
    }

    private val AUTH_REQUIRED = """{"type":"auth","required":true,"ok":false}"""
    private val AUTH_OK = """{"type":"auth","required":true,"ok":true}"""
    private val LIBRARY = """{"type":"library","songs":[{"id":"s1","title":"Uno"}],"bibleName":"RV"}"""

    private fun connected(c: RemoteClient) = c.status.value is ConnectionStatus.Connected

    @Test
    fun smokeConnectsToFakePcOverIpv6Loopback() {
        AdversarialFakePc { it.send(LIBRARY) }.use { pc ->
            val c = client()
            c.connect(AdversarialFakePc.HOST)
            assertTrue(eventually { c.songs.value.size == 1 }, "status=${c.status.value}")
            assertTrue(pc.sessions[0].await { it.contains("\"hello\"") } != null)
        }
    }

    /**
     * A message with a type this app version doesn't know (a newer PC) or a garbled frame
     * should be ignored, not tear down the whole connection.
     */
    @Test
    fun unknownMessageTypeDropsTheWholeConnection() {
        AdversarialFakePc { s ->
            if (it_isFirst.getAndSet(false)) {
                s.send("""{"type":"something_new_in_2_6","x":1}""")
                s.send(LIBRARY)
            } else s.send(LIBRARY)
        }.use { pc ->
            val c = client()
            c.connect(AdversarialFakePc.HOST)
            Thread.sleep(3_000)
            assertEquals(1, pc.sessions.size, "client reconnected: an unknown message type killed the connection (status=${c.status.value})")
            assertEquals(1, c.songs.value.size, "Library after the unknown message was lost")
        }
    }

    @Test
    fun invalidJsonFrameDropsTheWholeConnection() {
        AdversarialFakePc { s ->
            if (it_isFirst.getAndSet(false)) { s.send("{not json"); s.send(LIBRARY) } else s.send(LIBRARY)
        }.use { pc ->
            val c = client()
            c.connect(AdversarialFakePc.HOST)
            Thread.sleep(3_000)
            assertEquals(1, pc.sessions.size, "one garbled frame forced a reconnect (status=${c.status.value})")
        }
    }

    /**
     * PIN leak: the saved PIN of PC "A" was auto-sent; A's connection dropped before answering
     * (Wi-Fi blip). Then the phone opens a malicious selah://connect?host=B link.
     * B answers Auth(ok=true) and RemoteClient binds A's PIN to B (stale pendingPin),
     * then B asks for the PIN and gets it.
     */
    @Test
    fun stalePendingPinIsBoundToADifferentHostAndLeaked() {
        val hostA = "[0:0:0:0:0:0:0:1]" // same loopback, different host string = "another PC"
        val hostB = AdversarialFakePc.HOST
        val store = MapStore("host" to hostA, "pin" to "4321", "pinHost" to hostA)
        val bLogins = CopyOnWriteArrayList<String>()
        AdversarialFakePc { s ->
            when (it_counter.incrementAndGet()) {
                1 -> { // PC A: asks for PIN, receives it, dies without answering
                    s.send(AUTH_REQUIRED)
                    s.await { it.contains("\"login\"") }
                    s.close()
                }
                else -> { // attacker B
                    s.send(AUTH_OK)
                    Thread.sleep(200)
                    s.send(AUTH_REQUIRED)
                    s.await(2_000) { it.contains("\"login\"") }?.let(bLogins::add)
                }
            }
        }.use { pc ->
            val c = client()
            c.useStore(store) // connects to A (store "host")
            assertTrue(eventually { pc.sessions.size >= 1 && pc.sessions[0].all.any { it.contains("4321") } }, "A should get its PIN")
            // malicious link without pin -> acceptLink + what SelahRemoto's connect lambda does
            val host = store.acceptLink(ConnectLink(hostB, null))
            store.put("host" to host, "name" to host)
            c.connect(host)
            eventually(4_000) { bLogins.isNotEmpty() }
            assertTrue(bLogins.isEmpty(), "attacker B received A's PIN: $bLogins")
            assertEquals(hostA, store.map["pinHost"], "PIN of A re-bound to attacker host B")
        }
    }

    /**
     * Legacy store (from 2.4, no "pinHost"): savedPin falls back to "host", but "host" is rewritten
     * by every connect (link, list, discovery), so the PIN follows the phone to ANY host.
     */
    @Test
    fun legacyPinWithoutPinHostIsSentToAnyNewHost() {
        val store = MapStore("host" to "192.168.1.20", "name" to "PC iglesia", "pin" to "9550")
        AdversarialFakePc { s -> s.send(AUTH_REQUIRED) }.use { pc ->
            val c = client()
            c.savedPin = { null } // set below via useStore without auto-connecting to .20
            // Malicious link selah://connect?host=<attacker> (no pin), exactly as MainActivity/SelahRemoto handle it
            val host = store.acceptLink(ConnectLink(AdversarialFakePc.HOST, null)) // a bare IPv4/name in real life; [::1] only because 127.0.0.1:8765 is busy here
            store.put("host" to host, "name" to host)
            c.useStore(store) // useStore also connects to store "host" = attacker
            val leaked = run { eventually { pc.sessions.isNotEmpty() }; pc.sessions.firstOrNull()?.await(3_000) { it.contains("9550") } }
            assertNull(leaked, "legacy PIN for 192.168.1.20 was sent to the attacker")
        }
    }

    /** Hostile PC: Auth flapping + Error spam + huge lists must not crash, and status stays Connected. */
    @Test
    fun hostileButValidTrafficIsSurvived() {
        val bigSongs = (0 until 100_000).joinToString(",") { """{"id":"s$it","title":"T$it"}""" }
        AdversarialFakePc { s ->
            repeat(50) { s.send(if (it % 2 == 0) AUTH_REQUIRED else AUTH_OK) }
            repeat(500) { s.send("""{"type":"error","message":"spam $it"}""") }
            s.send("""{"type":"library","songs":[$bigSongs],"bibleBooks":[{"name":"X","chapters":-5},{"name":"Y","chapters":0}]}""")
            s.send("""{"type":"state","state":{"title":"t","slides":[],"index":999},"keepSlides":true}""")
            s.send("""{"type":"state","state":{"title":"t","slides":[],"index":-77},"serverNow":9223372036854775807}""")
            s.send("""{"type":"plan","plan":{"items":[],"current":42}}""")
            s.send("""{"type":"bible_chapter","book":"NoExiste","chapter":-1,"verses":[]}""")
        }.use { pc ->
            val c = client()
            c.connect(AdversarialFakePc.HOST)
            assertTrue(eventually(10_000) { c.bibleChapter.value != null }, "status=${c.status.value}")
            assertEquals(100_000, c.songs.value.size)
            assertEquals(-77, c.state.value.index)
            assertTrue(connected(c))
            assertEquals(1, pc.sessions.size)
        }
    }

    /** Frames from the real PC must never show up in the demo after startDemo(). */
    @Test
    fun demoTransitionsDoNotLeakState() {
        AdversarialFakePc { s ->
            s.send(LIBRARY)
            repeat(200) { s.send("""{"type":"state","state":{"title":"REAL PC","slides":[],"index":-1}}"""); Thread.sleep(2) }
        }.use { pc ->
            val c = client()
            c.connect(AdversarialFakePc.HOST)
            assertTrue(eventually { c.state.value.title == "REAL PC" })
            c.startDemo()
            Thread.sleep(500)
            assertEquals(ConnectionStatus.Demo, c.status.value)
            assertTrue(c.state.value.title != "REAL PC", "real PC state leaked into the demo")
            assertTrue(c.songs.value.none { it.id == "s1" }, "real PC songs leaked into the demo")
            c.stopDemo(); c.stopDemo()
            assertEquals(ConnectionStatus.Idle, c.status.value)
            assertTrue(c.songs.value.isEmpty())
            // connect() from demo: demo data must be gone
            c.startDemo()
            c.connect(AdversarialFakePc.HOST)
            assertTrue(c.songs.value.none { it.id.startsWith("demo-") }, "demo songs leaked into a real connection")
            assertTrue(eventually { connected(c) })
        }
    }

    /** Demo answers Auth(ok) without a host: must not call onPinAccepted. */
    @Test
    fun loginDuringDemoDoesNotStorePin() {
        val accepted = CopyOnWriteArrayList<Pair<String, String>>()
        val c = client()
        c.onPinAccepted = { h, p -> accepted += h to p }
        c.startDemo()
        c.login("1111")
        c.send(app.selah.shared.ClientMessage.Login("1111"))
        c.stopDemo()
        c.startDemo()
        assertTrue(accepted.isEmpty(), "$accepted")
    }

    /**
     * parseConnectLink accepts IPv6 (":" is in its allow-list) and returns it bare; RemoteClient passes it
     * bare to Ktor, which can't build ws://::1:8765. An IPv6 link is accepted, saved, and never connects.
     */
    @Test
    fun ipv6HostFromALinkCanNeverConnect() {
        AdversarialFakePc { it.send(LIBRARY) }.use { pc ->
            val link = parseConnectLink("selah://connect?host=::1")
            val c = client()
            c.connect(link?.host ?: return@use)
            assertTrue(eventually(4_000) { c.songs.value.isNotEmpty() }, "IPv6 link accepted but unconnectable: ${c.status.value}")
        }
    }

    /** A "PC" that accepts TCP and never answers the handshake: the client must give up and retry. */
    @Test
    fun silentServerDoesNotHangForever() {
        val ss = java.net.ServerSocket().apply {
            reuseAddress = true
            val end = System.currentTimeMillis() + 60_000
            while (true) try { bind(java.net.InetSocketAddress(java.net.InetAddress.getByName("::1"), 8765)); break }
                catch (e: java.net.BindException) { if (System.currentTimeMillis() > end) throw e; Thread.sleep(250) }
        }
        val accepted = java.util.concurrent.atomic.AtomicInteger()
        val keep = CopyOnWriteArrayList<java.net.Socket>()
        Thread { while (!ss.isClosed) runCatching { keep += ss.accept(); accepted.incrementAndGet() } }.apply { isDaemon = true; start() }
        try {
            val c = client()
            c.connect(AdversarialFakePc.HOST)
            assertTrue(eventually(25_000) { accepted.get() >= 2 }, "client hung on a silent server: ${c.status.value}")
        } finally { ss.close(); keep.forEach { it.close() } }
    }

    /** Server that never answers / closes immediately: the client keeps retrying without crashing. */
    @Test
    fun closeRightAfterConnectKeepsReconnecting() {
        AdversarialFakePc { s -> s.close() }.use { pc ->
            val c = client()
            c.connect(AdversarialFakePc.HOST)
            assertTrue(eventually(8_000) { pc.sessions.size >= 3 }, "sessions=${pc.sessions.size} status=${c.status.value}")
        }
    }

    companion object {
        val it_isFirst = java.util.concurrent.atomic.AtomicBoolean(true)
        val it_counter = java.util.concurrent.atomic.AtomicInteger(0)
    }

    init { it_isFirst.set(true); it_counter.set(0) }
}

@Suppress("unused") private fun never(): Nothing = fail()
