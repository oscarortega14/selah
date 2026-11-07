package app.selah.remote

import app.selah.shared.ClientMessage
import app.selah.shared.ServerMessage
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure-logic attacks: links, file names, calendar math, subnet scan, demo server fuzz. */
class AdversarialLogicTest {

    @Test
    fun connectLinkRejectsTricks() {
        val bad = listOf(
            "selah://connect?host=1.2.3.4%00", "selah://connect?host=1.2.3.4%2F..%2F", "selah://connect?host=1.2.3.4%252F",
            "selah://connect?host=a@b", "selah://connect?host=javascript:alert(1)", "javascript:selah://connect?host=1.2.3.4",
            "selah://connect?host=" + "1".repeat(65), "selah://connect?host=", "selah://connect?host",
            "selah://connectx?host=1.2.3.4", "selah://connect#?host=1.2.3.4", "selah://connect?host=1.2.3.4%0d%0aX:1",
            "selah://connect?host=%31%32%33%2E%34%40evil", "selah://connect?host=1.2.3.4#@evil", "selah://connect?=x&host=1.2.3.4/",
        )
        for (u in bad) assertNull(parseConnectLink(u), u)
    }

    @Test
    fun connectLinkPinMustBeAsciiDigits() {
        assertNull(parseConnectLink("selah://connect?host=1.2.3.4&pin=١٢٣٤")?.pin) // Arabic-Indic
        assertNull(parseConnectLink("selah://connect?host=1.2.3.4&pin=１２３４")?.pin) // full width
        assertNull(parseConnectLink("selah://connect?host=1.2.3.4&pin=12345678")?.pin)
        assertNull(parseConnectLink("selah://connect?host=1.2.3.4&pin=1234%00")?.pin)
        assertEquals("1234", parseConnectLink("selah://connect?host=1.2.3.4&pin=+1234+")?.pin)
    }

    /** Repeated params: last one wins (documenting; the scanner shows the link's host on screen). */
    @Test
    fun repeatedHostLastWins() {
        assertEquals("6.6.6.6", parseConnectLink("selah://connect?host=1.2.3.4&host=6.6.6.6")?.host)
    }

    /**
     * The parser accepts hosts that can never be connected to: "..", "-", ":::" and IPv6 / host:port
     * forms (Ktor builds ws://::1:8765 → "Invalid URL port"). Accepting them silently replaces the saved
     * PC with garbage. Expected: rejected (or IPv6 bracketed so it works).
     */
    @Test
    fun connectLinkAcceptsUnconnectableHosts() {
        val accepted = listOf("..", "-", ":::", "1.2.3.4:9999", ".", "a..b", "1::2::3", "[1.2.3.4]", "fe80::12345")
            .filter { parseConnectLink("selah://connect?host=$it") != null }
        assertEquals(emptyList(), accepted, "parseConnectLink accepted hosts RemoteClient can't use")
        // IPv6 sí sirve: al conectarse va entre corchetes.
        for (h in listOf("fe80::1", "[fe80::1]", "::1")) assertEquals(h, parseConnectLink("selah://connect?host=$h")?.host)
        assertEquals("[fe80::1]", urlHost("fe80::1"))
        assertEquals("[::1]", urlHost("[::1]"))
        assertEquals("192.168.1.20", urlHost("192.168.1.20"))
    }

    @Test
    fun uploadFileNameStripsPaths() {
        assertEquals("passwd.jpg", uploadFileName("../../etc/passwd.png", "jpg"))
        assertEquals("evil.jpg", uploadFileName("C:\\Windows\\..\\evil.exe", ".JPG"))
        assertEquals("anuncio.jpg", uploadFileName("/a/b/", "jpg"))
        assertEquals("foto.mp4", uploadFileName("610B91B5-1580-4C4D-9C2E-AAAAAAAAAAAA.MOV", "mp4", "foto"))
        assertEquals("anuncio.jpg", uploadFileName("   .jpg", "jpg"))
        assertTrue('/' !in uploadFileName("x/../..", "jpg") && '\\' !in uploadFileName("x\\..\\..", "jpg"))
    }

    @Test
    fun suggestedListNameMatchesJavaTimeForCenturies() {
        val months = listOf("ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sep", "oct", "nov", "dic")
        var d = LocalDate.of(1600, 1, 1)
        val end = LocalDate.of(2500, 12, 31)
        while (!d.isAfter(end)) {
            val sunday = d.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
            val expected = "Culto domingo ${sunday.dayOfMonth} ${months[sunday.monthValue - 1]} ${sunday.year}"
            assertEquals(expected, suggestedListName(Triple(d.year, d.monthValue, d.dayOfMonth)), d.toString())
            assertEquals(d.toEpochDay(), daysFromCivil(d.year, d.monthValue, d.dayOfMonth))
            d = d.plusDays(1)
        }
        for (e in listOf(-800_000L, -1L, 0L, 1L, 2_000_000L)) {
            val ld = LocalDate.ofEpochDay(e)
            assertEquals(Triple(ld.year, ld.monthValue, ld.dayOfMonth), civilFromDays(e))
        }
    }

    @Test
    fun subnetScanWeirdAddresses() {
        for (ip in listOf("", "192.168.1", "192.168.1.5.6", "256.1.1.1", "8.8.8.8", "172.32.0.1", "fe80::1", "192.168.1.x", "-1.168.1.5"))
            assertEquals(emptyList(), SubnetScan.hosts(ip), ip)
        val h = SubnetScan.hosts(" 10.0.0.254 ")
        assertEquals(254, h.size); assertEquals("10.0.0.254", h[0]); assertTrue(h.none { it.endsWith(".0") || it.endsWith(".255") })
        assertEquals(254, SubnetScan.hosts("172.31.255.0").toSet().size)
        // whatever the input looks like, hosts are canonical dotted ASCII IPv4
        for (ip in listOf("+192.168.+1.5", "192.168.01.005", "١٩٢.١٦٨.١.٥"))
            assertTrue(SubnetScan.hosts(ip).all { it.matches(Regex("""\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}""")) }, ip)
    }

    @Test
    fun subnetScanSurvivesThrowingProbes() = kotlinx.coroutines.runBlocking {
        val found = SubnetScan.scan(List(300) { "h$it" }, parallel = 7) { h ->
            when {
                h.endsWith("3") -> throw IllegalStateException("boom")
                h == "h42" -> "PC"
                else -> null
            }
        }
        assertEquals(listOf(FoundServer("PC", "h42")), found)
    }

    /** The demo runs on the UI thread: any exception from DemoServer.handle crashes the app. */
    @Test
    fun demoServerFuzzNeverThrows() {
        val msgs = listOf(
            ClientMessage.GoTo(Int.MIN_VALUE), ClientMessage.GoTo(Int.MAX_VALUE), ClientMessage.Next, ClientMessage.Prev,
            ClientMessage.SelectSong(""), ClientMessage.ShowBible(""), ClientMessage.ShowBible("Juan 3:999999999999"),
            ClientMessage.ShowBible("Juan 0"), ClientMessage.ShowBible("Juan -3:-1"), ClientMessage.ShowBible("Apocalipsis 22:21"),
            ClientMessage.ShowBible("((((*"), ClientMessage.ShowBible("Juan 3:16-2"), ClientMessage.ShowBible("Juan 3:16;;;,,,"),
            ClientMessage.PlanAddBible("Salmos 23:1-999999"), ClientMessage.PlanAddBible("1 Corintios 13:0"),
            ClientMessage.BibleChapter("Juan", 0), ClientMessage.BibleChapter("Juan", -1), ClientMessage.BibleChapter("Juan", Int.MAX_VALUE),
            ClientMessage.BibleChapter("", 1), ClientMessage.BibleChapter("Génesis", 51),
            ClientMessage.BibleSearch(""), ClientMessage.BibleSearch("((["), ClientMessage.BibleSearch("x".repeat(100_000)),
            ClientMessage.SongSearch(""), ClientMessage.SongSearch("[*"), ClientMessage.SongSearch("\u0000\uD800"),
            ClientMessage.PlanMove(Int.MAX_VALUE, 0), ClientMessage.PlanMove(0, Int.MIN_VALUE), ClientMessage.PlanRemove(-1),
            ClientMessage.PlanGo(-1), ClientMessage.PlanGo(Int.MAX_VALUE), ClientMessage.PlanNext, ClientMessage.PlanNext,
            ClientMessage.PlanNext, ClientMessage.PlanNext, ClientMessage.PlanNext, ClientMessage.PlanNext,
            ClientMessage.StartCountdown(Int.MAX_VALUE), ClientMessage.StartCountdown(Int.MIN_VALUE), ClientMessage.StartTimer(-1),
            ClientMessage.PlanSave(" "), ClientMessage.PlanOpen("nope"), ClientMessage.PlanClear, ClientMessage.PlanSave("x"),
            ClientMessage.PlanAddMedia("../../x"), ClientMessage.ShowMedia(""), ClientMessage.ShowPresentation("Informe anual.pdf"),
            ClientMessage.GoTo(5), ClientMessage.GoTo(6), ClientMessage.Next, ClientMessage.Next,
            ClientMessage.SetSecondBible(null), ClientMessage.Login(""),
        )
        val server = DemoServer { 1_000L }
        val errors = mutableListOf<String>()
        for (m in msgs) {
            val out = try { server.handle(m) } catch (e: Throwable) { errors += "$m -> $e"; continue }
            val st = out.filterIsInstance<ServerMessage.State>().lastOrNull()?.state
            if (st != null && st.slides.isNotEmpty() && st.index !in st.slides.indices) errors += "$m -> index ${st.index} of ${st.slides.size}"
        }
        assertEquals(emptyList(), errors)
    }

    /** Demo plan move bookkeeping against a reference model, exhaustively for small plans. */
    @Test
    fun demoPlanMoveKeepsCurrentPointingAtSameItem() {
        for (from in 0 until 5) for (to in 0 until 5) for (cur in 0 until 5) {
            val s = DemoServer { 0L }
            s.handle(ClientMessage.PlanGo(cur))
            val before = s.handle(ClientMessage.PlanAddSong("nope")) // no-op to read nothing
            val plan0 = s.welcome().filterIsInstance<ServerMessage.Plan>().single().plan
            val curItem = plan0.items.getOrNull(plan0.current)
            s.handle(ClientMessage.PlanMove(from, to))
            val plan1 = s.welcome().filterIsInstance<ServerMessage.Plan>().single().plan
            if (curItem != null) assertEquals(curItem, plan1.items.getOrNull(plan1.current), "cur=$cur from=$from to=$to $before")
        }
    }
}
