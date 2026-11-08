package app.selah.desktop

import app.selah.shared.ClientMessage
import app.selah.shared.ServerMessage
import app.selah.shared.encode
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Un dispositivo hostil en la red de la iglesia contra /ws. */
class AdversarialWebSocketTest {

    private fun RawWs.ready() = assertNotNull(await { it is ServerMessage.State }, "no llegó el estado inicial")

    /** Pide un capítulo y espera la respuesta: como todo va en orden, lo anterior ya se procesó. */
    private fun RawWs.sync() {
        sendText(ClientMessage.BibleChapter("Juan", 1).encode())
        assertNotNull(await { it is ServerMessage.BibleChapter || it is ServerMessage.Error }, "el servidor no respondió")
    }

    @Test
    fun malformedFramesDoNotCrashOrKillTheSession(): Unit = AdvServer().use { s ->
        RawWs(s.port).use { ws ->
            ws.ready()
            ws.sendText(ClientMessage.SelectSong("b").encode())
            val nested = "[".repeat(30_000) + "]".repeat(30_000)
            val garbage = listOf(
                "", "{", "}", "null", "[]", "\"goto\"", "42", "{\"type\":\"nope\"}", "{\"type\":\"NEXT\"}",
                "{\"type\":123}", "{\"type\":null}", "{\"type\":\"goto\",\"index\":\"x\"}", "{\"type\":\"goto\",\"index\":1e99}",
                "{\"type\":\"goto\",\"index\":99999999999999999999}", "{\"type\":\"goto\"}", "{\"type\":\"goto\",\"index\":null}",
                "{\"type\":\"login\",\"pin\":{\"a\":1}}", "{\"type\":\"plan_save\",\"name\":[1,2]}",
                "{\"type\":\"next\",\"x\":$nested}", "{\"type\":\"next\",\"x\":" + "[".repeat(60_000),
                "{\"type\":\"next\"" + ",\"type\":\"prev\"".repeat(10), "{\"type\":\"hello\",\"features\":\"keepSlides\"}",
                "\u0000\u0001\uFFFF", "{\"type\":\"goto\",\"index\":1}{\"type\":\"goto\",\"index\":2}",
            )
            garbage.forEach { ws.sendText(it) }
            ws.send(1, byteArrayOf(0xC3.toByte(), 0x28, 0xFF.toByte())) // UTF-8 inválido
            ws.send(2, ByteArray(1000) { it.toByte() }) // binario
            ws.send(9, "ping".toByteArray()) // ping
            ws.send(0xA, ByteArray(0)) // pong suelto
            ws.sync()
            ws.sendText(ClientMessage.GoTo(2).encode())
            assertNotNull(ws.await { it is ServerMessage.State && it.state.index == 2 }, "la sesión dejó de responder tras basura")
        }
        // y el servidor sigue aceptando a otros
        RawWs(s.port).use { it.ready() }
    }

    @Test
    fun oversizedFrameClosesOnlyThatClient(): Unit = AdvServer().use { s ->
        RawWs(s.port).use { victim ->
            victim.ready()
            RawWs(s.port).use { bad ->
                bad.ready()
                bad.sendText("{\"type\":\"stage_message\",\"text\":\"" + "x".repeat(70_000) + "\"}")
                assertTrue(bad.waitClosed(), "un marco de más de 64 KB debería cerrar la conexión")
            }
            victim.sendText(ClientMessage.SelectSong("b").encode())
            assertNotNull(victim.await { it is ServerMessage.State && it.state.title == "Beta" })
        }
    }

    @Test
    fun fragmentedHugeMessageDoesNotHurtTheServer(): Unit = AdvServer().use { s ->
        RawWs(s.port).use { bad ->
            bad.ready()
            val chunk = ByteArray(60_000) { 'x'.code.toByte() }
            runCatching { bad.send(1, "{\"type\":\"stage_message\",\"text\":\"".toByteArray(), fin = false)
            repeat(100) { bad.send(0, chunk, fin = false) } // ~6 MB en fragmentos de 60 KB (puede cerrarla antes)
            bad.send(0, "\"}".toByteArray(), fin = true) }
        }
        RawWs(s.port).use { ok ->
            ok.ready()
            ok.sendText(ClientMessage.SelectSong("b").encode())
            assertNotNull(ok.await { it is ServerMessage.State && it.state.title == "Beta" })
        }
        assertTrue(s.presenter.state.value.stageMessage.length <= NetGuard.MAX_TEXT)
    }

    /** Todas las órdenes que cambian algo, mandadas sin clave: nada debe cambiar. */
    @Test
    fun withoutThePinNothingCanBeControlled(): Unit = AdvServer(pin = "4821").use { s ->
        File(s.library.announcementsDir.apply { mkdirs() }, "foto.png").writeBytes(ByteArray(10))
        s.library.refreshAnnouncements()
        s.commands.handle(ClientMessage.SelectSong("b"))
        s.commands.handle(ClientMessage.PlanAddSong("a"))
        s.commands.handle(ClientMessage.PlanAddSong("b"))
        val state0 = s.presenter.state.value
        val plan0 = s.commands.plan.state.value
        val bible0 = s.library.bible?.name
        val all = listOf(
            ClientMessage.SelectSong("a"), ClientMessage.GoTo(1), ClientMessage.Next, ClientMessage.Prev,
            ClientMessage.SetBlank(true), ClientMessage.ShowBible("Juan 3:16"), ClientMessage.PlanAddSong("b"),
            ClientMessage.PlanAddBible("Juan 3:16"), ClientMessage.PlanRemove(0), ClientMessage.PlanMove(0, 1),
            ClientMessage.PlanGo(0), ClientMessage.PlanNext, ClientMessage.PlanSave("hack"), ClientMessage.PlanOpen("hack"),
            ClientMessage.StageMessage("hack"), ClientMessage.ShowLogo(true), ClientMessage.SetAlert("hack"),
            ClientMessage.StartCountdown(60, "hack"), ClientMessage.StartTimer(5), ClientMessage.SetLowerThird("hack", "x"),
            ClientMessage.SelectBible("Versión Biblia Libre"), ClientMessage.SetSecondBible("Versión Biblia Libre"),
            ClientMessage.ShowMedia("foto.png"), ClientMessage.PlanAddMedia("foto.png"), ClientMessage.ShowSermon("x"),
            ClientMessage.PlanAddSermon("x"), ClientMessage.PlanClear,
        )
        RawWs(s.port).use { ws ->
            val auth = ws.await { it is ServerMessage.Auth } as ServerMessage.Auth
            assertTrue(auth.required); assertFalse(auth.ok)
            all.forEach { ws.sendText(it.encode()) }
            // Variantes que podrían colarse: mayúsculas, claves extra, clave dentro de la orden
            ws.sendText("{\"type\":\"next\",\"pin\":\"4821\"}")
            ws.sendText("{\"type\":\"blank\",\"blank\":true,\"granted\":\"4821\"}")
            ws.sync()
        }
        assertEquals(state0, s.presenter.state.value)
        assertEquals(plan0, s.commands.plan.state.value)
        assertEquals(bible0, s.library.bible?.name)
        assertEquals(null, s.library.secondBible)
        assertFalse(File(s.dir, "listas").exists())
    }

    /**
     * Cada dirección que prueba una clave queda guardada para siempre en PinGuard.failures, incluso
     * mientras el bloqueo global está activo. Con muchas direcciones (IPv6 en la red local) la memoria crece sin fin.
     */
    @Test
    fun pinGuardMemoryIsBoundedUnderManyAddresses() {
        var now = 0L
        val guard = PinGuard(MutableStateFlow("4821")) { now }
        repeat(200_000) { i ->
            guard.check("fd00::${i.toString(16)}", "0000")
            now += 10 // 200 000 intentos en ~33 minutos
        }
        val field = PinGuard::class.java.getDeclaredField("failures").apply { isAccessible = true }
        val size = (field.get(guard) as Map<*, *>).size
        assertTrue(size <= 10_000, "PinGuard recuerda $size direcciones (nunca las olvida)")
    }

    @Test
    fun searchesWithMetacharactersAnswer(): Unit = AdvServer().use { s ->
        RawWs(s.port).use { ws ->
            ws.ready()
            for (q in listOf(".*(a+)+$", "((((", "\\", "[", "\u0000", "%s%n", "de ".repeat(200), "ñ́́́́́́́́́́".repeat(30))) {
                ws.sendText(ClientMessage.SongSearch(q).encode())
                assertNotNull(ws.await { it is ServerMessage.SongResults }, "SongSearch($q)")
                ws.sendText(ClientMessage.BibleSearch(q).encode())
                assertNotNull(ws.await { it is ServerMessage.BibleResults }, "BibleSearch($q)")
                Thread.sleep(550) // limitador: 5 fichas por búsqueda
            }
        }
    }

    @Test
    fun pinLookalikesAreRejected() {
        val guard = PinGuard(MutableStateFlow("4821"))
        val wrong = listOf(
            "٤٨٢١", "۴۸۲۱", "４８２１", "04821", "48210", "4821\u0000", "\u00004821", "4821x", "4 821", "4821\u200B",
            "\u200B4821", "482", "", "4821".repeat(10_000), "' OR 1=1 --", "null", "*", "\\d{4}",
        )
        wrong.forEachIndexed { i, attempt ->
            assertFalse(guard.check("10.9.0.$i", attempt) == PinGuard.Result.Ok, "aceptó la clave \"${attempt.take(20)}\"")
        }
    }

    @Test
    fun lockoutHoldsOverTheNetworkForWebSocketAndUploads(): Unit = AdvServer(pin = "4821").use { s ->
        RawWs(s.port).use { ws ->
            ws.ready()
            repeat(PinGuard.MAX_TRIES) { ws.sendText(ClientMessage.Login("000$it").encode()) }
            ws.sync()
            // Bloqueada: ni con la buena por WebSocket…
            ws.sendText(ClientMessage.Login("4821").encode())
            val auth = ws.await { it is ServerMessage.Auth && it.message != null } as ServerMessage.Auth
            assertFalse(auth.ok, "entró estando bloqueada")
            // …ni por la subida (misma IP, otra puerta)
            val req = "POST /anuncios?nombre=x.png HTTP/1.1\r\nHost: 127.0.0.1:${s.port}\r\nX-Proyecta-Pin: 4821\r\nContent-Length: 3\r\nConnection: close\r\n\r\n"
            assertEquals(401, rawHttp(s.port, req.toByteArray(), byteArrayOf(1, 2, 3)).first)
            // y otra conexión nueva de la misma IP tampoco
            RawWs(s.port).use { ws2 ->
                ws2.ready()
                ws2.sendText(ClientMessage.Login("4821").encode())
                val a2 = ws2.await { it is ServerMessage.Auth && it.message != null } as ServerMessage.Auth
                assertFalse(a2.ok)
            }
        }
    }

    @Test
    fun outOfRangeNumbersAreHarmless(): Unit = AdvServer().use { s ->
        RawWs(s.port).use { ws ->
            ws.ready()
            ws.sendText(ClientMessage.SelectSong("b").encode())
            ws.sendText(ClientMessage.PlanAddSong("a").encode())
            ws.sendText(ClientMessage.PlanAddSong("b").encode())
            ws.sync()
            val extremes = listOf(Int.MIN_VALUE, -2, -1, 2, 3, 1000, Int.MAX_VALUE)
            for (n in extremes) {
                ws.sendText(ClientMessage.GoTo(n).encode())
                ws.sendText(ClientMessage.PlanGo(n).encode())
                ws.sendText(ClientMessage.PlanRemove(n).encode())
                ws.sendText(ClientMessage.PlanMove(n, 0).encode())
                ws.sendText(ClientMessage.PlanMove(0, n).encode())
                ws.sync() // también le da tiempo al limitador
                Thread.sleep(150)
            }
            // (StartTimer(0) sí vale: 0 = sin límite de tiempo. Aquí solo van valores que se deben rechazar.)
            for (n in listOf(Int.MIN_VALUE, -1, 0, 24 * 3600 + 1, Int.MAX_VALUE)) {
                ws.sendText(ClientMessage.StartCountdown(n).encode())
                if (n != 0) ws.sendText(ClientMessage.StartTimer(n).encode())
                ws.sendText(ClientMessage.BibleChapter("Juan", n).encode())
            }
            ws.sendText(ClientMessage.BibleChapter("x".repeat(60_000), 1).encode())
            ws.sync()
        }
        val st = s.presenter.state.value
        assertTrue(st.index in st.slides.indices, "índice fuera de rango: ${st.index}")
        assertEquals(null, st.countdownEnd)
        assertEquals(null, st.timerStart)
        assertEquals(2, s.commands.plan.state.value.items.size)
        assertTrue(s.commands.plan.state.value.current in -1..1)
    }

    @Test
    fun listNamesCannotEscapeTheListsFolder(): Unit = AdvServer().use { s ->
        val outside = File(s.root, "fuera.json").apply { writeText("{\"items\":[{\"kind\":\"SONG\",\"ref\":\"a\",\"title\":\"Robada\"}]}") }
        RawWs(s.port).use { ws ->
            ws.ready()
            ws.sendText(ClientMessage.PlanAddSong("a").encode())
            val names = listOf(
                "../../evil", "..\\..\\evil", "/tmp/selah-evil", "C:\\Windows\\evil", "evil\u0000x", "..", ".", "   ", "....",
                "%2e%2e%2fevil", "..／evil", "con", "evil.json", "x".repeat(5000), "a/../../b", "\u202Enosj.exe",
            )
            names.forEach { ws.sendText(ClientMessage.PlanSave(it).encode()) }
            ws.sync()
            listOf("../../fuera", "../fuera", "..\\..\\fuera", s.root.path + "/fuera", "../lista-del-culto").forEach {
                ws.sendText(ClientMessage.PlanOpen(it).encode())
            }
            ws.sync()
        }
        assertEquals(listOf(outside), s.filesOutsideLibrary().filter { it.isFile }, "se escribió algo fuera de la carpeta de Selah")
        val lists = File(s.dir, "listas").listFiles().orEmpty()
        assertTrue(lists.all { it.isFile && it.parentFile == File(s.dir, "listas") && it.name.length <= 85 }, lists.joinToString())
        assertFalse(s.commands.plan.state.value.items.any { it.title == "Robada" }, "abrió una lista de fuera de la carpeta")
    }

    /** Un nombre de Biblia que no existe no debería cambiar la traducción que el operador eligió. */
    @Test
    fun unknownBibleNameDoesNotSwitchTheBible(): Unit = AdvServer().use { s ->
        RawWs(s.port).use { ws ->
            ws.ready()
            ws.sendText(ClientMessage.SelectBible("Versión Biblia Libre").encode())
            ws.sync()
            assertEquals("Versión Biblia Libre", s.library.bible?.name)
            ws.sendText(ClientMessage.SelectBible("../../../etc/passwd").encode())
            ws.sync()
            assertEquals("Versión Biblia Libre", s.library.bible?.name, "un nombre inventado cambió la Biblia del operador")
        }
    }

    @Test
    fun unknownSecondBibleNameDoesNotRemoveTheSecondBible(): Unit = AdvServer().use { s ->
        RawWs(s.port).use { ws ->
            ws.ready()
            ws.sendText(ClientMessage.SetSecondBible("Versión Biblia Libre").encode())
            ws.sync()
            assertEquals("Versión Biblia Libre", s.library.secondBible?.name)
            ws.sendText(ClientMessage.SetSecondBible("x".repeat(60_000)).encode())
            ws.sync()
            assertEquals("Versión Biblia Libre", s.library.secondBible?.name, "un nombre inventado quitó la segunda traducción")
        }
    }

    @Test
    fun mediaAndPresentationNamesCannotEscape(): Unit = AdvServer().use { s ->
        File(s.root, "secreto.png").writeBytes(ByteArray(10))
        File(s.root, "secreto.pdf").writeBytes("%PDF-1.4".toByteArray())
        RawWs(s.port).use { ws ->
            ws.ready()
            val names = listOf("../secreto.png", "..\\secreto.png", s.root.path + "/secreto.png", "../../Selah/../secreto.png", "./../secreto.png")
            names.forEach {
                ws.sendText(ClientMessage.ShowMedia(it).encode())
                ws.sendText(ClientMessage.PlanAddMedia(it).encode())
            }
            ws.sync()
            Thread.sleep(600)
            listOf("../secreto.pdf", "..\\secreto.pdf", s.root.path + "/secreto.pdf").forEach {
                ws.sendText(ClientMessage.ShowPresentation(it).encode())
                ws.sendText(ClientMessage.PlanAddPresentation(it).encode())
            }
            ws.sync()
        }
        assertEquals(null, s.presenter.state.value.media)
        assertEquals(null, s.presenter.state.value.presentation)
        assertTrue(s.commands.plan.state.value.items.isEmpty())
        for (q in listOf("nombre=../secreto.pdf&n=0", "nombre=..%2Fsecreto.pdf&n=0", "nombre=%2E%2E%2Fsecreto.pdf&n=-1", "nombre=secreto.pdf&n=2147483647")) {
            val req = "GET /diapositiva?$q HTTP/1.1\r\nHost: 127.0.0.1:${s.port}\r\nConnection: close\r\n\r\n"
            assertEquals(404, rawHttp(s.port, req.toByteArray()).first, q)
        }
    }

    @Test
    fun hostileOriginsAndHostsAreRejected(): Unit = AdvServer().use { s ->
        val p = s.port
        val bad = listOf(
            listOf("Host" to "evil.com:$p"),
            listOf("Host" to "127.0.0.1.nip.io:$p"),
            listOf("Host" to "127.0.0.1:$p", "Origin" to "null"),
            listOf("Host" to "127.0.0.1:$p", "Origin" to "http://evil.com"),
            listOf("Host" to "127.0.0.1:$p", "Origin" to "http://127.0.0.1:$p.evil.com"),
            listOf("Host" to "127.0.0.1:$p", "Origin" to "https://127.0.0.1:$p"),
            listOf("Host" to "127.0.0.1:$p", "Origin" to "http://127.0.0.1:1"),
            listOf("Host" to "localhost.evil.com:$p"),
            listOf("Host" to "$p"), // solo puerto
            listOf("Host" to ""),
            emptyList(),
        )
        for (h in bad) RawWs(p, h).use { ws ->
            val got = if (ws.status == 101) ws.await(1500) { it is ServerMessage.State } else null
            assertEquals(null, got, "se aceptó $h")
        }
        val good = listOf(
            listOf("Host" to "127.0.0.1:$p"),
            listOf("Host" to "LOCALHOST:$p", "Origin" to "http://localhost:$p"),
            listOf("Host" to "[::1]:$p"),
        )
        for (h in good) RawWs(p, h).use { ws -> assertNotNull(ws.await { it is ServerMessage.State }, "se rechazó $h") }
    }

    @Test
    fun connectionLimitPerAddressAndNoLeaks(): Unit = AdvServer().use { s ->
        repeat(3) { round ->
            val open = (1..NetGuard.MAX_CONNECTIONS_PER_ADDRESS).map { RawWs(s.port) }
            open.forEach { it.ready() }
            RawWs(s.port).use { extra -> assertTrue(extra.waitClosed(), "ronda $round: la conexión 11 debería rechazarse") }
            // Rechazos por origen tampoco deben gastar cupo
            repeat(20) { RawWs(s.port, listOf("Host" to "evil.com")).use { it.waitClosed(500) } }
            open.forEach { it.close() }
            Thread.sleep(500)
        }
        RawWs(s.port).use { it.ready() }
    }

    /** Un cliente que inunda (órdenes y búsquedas) no debe congelar al operador de otro celular. */
    @Test
    fun floodingClientsDoNotFreezeOthers(): Unit = AdvServer().use { s ->
        s.library.bible!!.warmUp()
        RawWs(s.port).use { victim ->
            victim.ready()
            victim.sendText(ClientMessage.SelectSong("b").encode())
            assertNotNull(victim.await { it is ServerMessage.State && it.state.title == "Beta" })
            val attackers = (1..NetGuard.MAX_CONNECTIONS_PER_ADDRESS - 1).map { RawWs(s.port) }
            val stop = java.util.concurrent.atomic.AtomicBoolean(false)
            val heavy = ClientMessage.BibleSearch("de ".repeat(100)).encode()
            val threads = attackers.mapIndexed { i, a ->
                Thread {
                    runCatching {
                        while (!stop.get()) {
                            if (i % 2 == 0) a.sendText(heavy) else a.sendText(ClientMessage.BibleChapter("Salmos", 119).encode())
                        }
                    }
                }.apply { isDaemon = true; start() }
            }
            // Y uno que no lee nada de lo que se le manda (su cola se llena)
            Thread.sleep(800)
            var worst = 0L
            repeat(6) { i ->
                val t = System.nanoTime()
                val target = "medida $i"
                victim.sendText(ClientMessage.StageMessage(target).encode())
                assertNotNull(victim.await(5000) { it is ServerMessage.State && it.state.stageMessage == target }, "el operador no recibió respuesta")
                worst = maxOf(worst, (System.nanoTime() - t) / 1_000_000)
                Thread.sleep(150)
            }
            stop.set(true)
            attackers.forEach { it.close() }
            threads.forEach { it.join(2000) }
            println("ADV peor latencia del operador con 9 atacantes: $worst ms")
            assertTrue(worst < 1000, "el operador esperó $worst ms por culpa de otro cliente")
        }
    }
}
