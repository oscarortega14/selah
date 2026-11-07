package app.selah.desktop

import java.io.File
import java.io.RandomAccessFile
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** POST /anuncios y las páginas web, desde un dispositivo hostil. */
class AdversarialUploadTest {

    private fun upload(port: Int, nameQuery: String, body: ByteArray, pin: String? = "4821", extra: String = ""): Pair<Int, String> {
        val head = "POST /anuncios?nombre=$nameQuery HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n" +
            (pin?.let { "X-Proyecta-Pin: $it\r\n" } ?: "") + "Content-Length: ${body.size}\r\nConnection: close\r\n$extra\r\n"
        return rawHttp(port, head.toByteArray(Charsets.ISO_8859_1), body)
    }

    @Test
    fun uploadNamesCannotEscapeTheAnnouncementsFolder(): Unit = AdvServer(pin = "4821").use { s ->
        val names = listOf(
            "..%2F..%2Fevil.jpg", "..%5C..%5Cevil.jpg", "%2Ftmp%2Fselah-evil.jpg", "C%3A%5CWindows%5Cevil.jpg", "evil%00.jpg",
            "evil.jpg%00.exe", "CON.jpg", "evil.jpg.", "evil.jpg%20", "evil.JPG", "evil.jpg.exe", "evil.exe%2F.jpg",
            "..%EF%BC%8Fevil.jpg", "%2E%2E%2F%2E%2E%2Fevil.png", "....%2F%2Fevil.png", "a%2F..%2F..%2F..%2Fevil.mp4",
            "%E2%80%AEgpj.exe", "x" + "y".repeat(5000) + ".png", "evil.jpg%3A%24DATA", "evil.html", "evil.svg", ".htaccess", ".jpg",
        )
        val accepted = mutableListOf<String>()
        names.forEachIndexed { i, n ->
            val (code, body) = upload(s.port, n, byteArrayOf(i.toByte(), 1, 2, 3, 4))
            assertTrue(code in setOf(200, 400, 413, 507), "$n → $code $body")
            if (code == 200) accepted += n
        }
        println("ADV subidas aceptadas: ${accepted.map { it.take(40) }}")
        assertEquals(emptyList(), s.filesOutsideLibrary().filter { it.isFile }, "se escribió fuera de la carpeta")
        val files = s.library.announcementsDir.listFiles().orEmpty()
        assertTrue(files.all { it.isFile && it.extension.lowercase() in RemoteServer.UPLOAD_EXTENSIONS }, files.joinToString())
        assertTrue(files.none { it.name.contains('\u0000') || it.name.length > 100 }, files.joinToString { it.name.take(40) })
    }

    @Test
    fun truncatedUploadIsNotSaved(): Unit = AdvServer(pin = "4821").use { s ->
        val head = "POST /anuncios?nombre=Cortado.png HTTP/1.1\r\nHost: 127.0.0.1:${s.port}\r\nX-Proyecta-Pin: 4821\r\nContent-Length: 100000\r\n\r\n"
        val (code, _) = rawHttp(s.port, head.toByteArray(), ByteArray(5000) { 7 }, halfClose = true)
        Thread.sleep(500)
        val saved = s.library.announcementsDir.listFiles().orEmpty().map { "${it.name} (${it.length()} bytes)" }
        assertTrue(saved.isEmpty(), "se guardó un archivo a medias (respuesta $code): $saved")
    }

    @Test
    fun abortedUploadLeavesNoTempFiles(): Unit = AdvServer(pin = "4821").use { s ->
        val tmp = File(System.getProperty("java.io.tmpdir"))
        fun count() = tmp.listFiles { f -> f.name.startsWith("selah-subida") }.orEmpty().size
        val before = count()
        repeat(5) {
            Socket("127.0.0.1", s.port).use { sock ->
                val head = "POST /anuncios?nombre=Medio.png HTTP/1.1\r\nHost: 127.0.0.1:${s.port}\r\nX-Proyecta-Pin: 4821\r\nContent-Length: 1000000\r\n\r\n"
                sock.getOutputStream().write(head.toByteArray()); sock.getOutputStream().write(ByteArray(200_000)); sock.getOutputStream().flush()
                Thread.sleep(100)
                sock.setSoLinger(true, 0) // RST
            }
        }
        Thread.sleep(1000)
        assertEquals(before, count(), "quedaron carpetas temporales de subidas cortadas")
        assertTrue(s.library.announcementsDir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun weirdContentLengthsAreHandled(): Unit = AdvServer(pin = "4821").use { s ->
        for (cl in listOf("-1", "abc", "99999999999999999999999", "1, 2")) {
            val head = "POST /anuncios?nombre=Raro.png HTTP/1.1\r\nHost: 127.0.0.1:${s.port}\r\nX-Proyecta-Pin: 4821\r\nContent-Length: $cl\r\nConnection: close\r\n\r\n"
            val (code, _) = rawHttp(s.port, head.toByteArray(), ByteArray(10) { 1 }, halfClose = true, timeoutMs = 3000)
            assertNotEquals(500, code, "Content-Length: $cl")
        }
        // Más datos que los declarados: solo se guarda lo declarado
        val head = "POST /anuncios?nombre=Mas.png HTTP/1.1\r\nHost: 127.0.0.1:${s.port}\r\nX-Proyecta-Pin: 4821\r\nContent-Length: 10\r\nConnection: close\r\n\r\n"
        rawHttp(s.port, head.toByteArray(), ByteArray(5000) { 2 })
        File(s.library.announcementsDir, "Mas.png").takeIf { it.exists() }?.let { assertEquals(10, it.length()) }
        // Y el servidor sigue bien
        assertEquals(200, upload(s.port, "Bien.png", ByteArray(100) { 3 }).first)
    }

    @Test
    fun crossSiteUploadsAreRejected(): Unit = AdvServer(pin = null).use { s ->
        for (origin in listOf("http://evil.com", "null", "https://127.0.0.1:${s.port}", "http://127.0.0.1:${s.port}.evil.com")) {
            assertEquals(403, upload(s.port, "x.png", ByteArray(10), pin = null, extra = "Origin: $origin\r\n").first, origin)
        }
        val req = "POST /anuncios?nombre=x.png HTTP/1.1\r\nHost: evil.com:${s.port}\r\nContent-Length: 3\r\nConnection: close\r\n\r\n"
        assertEquals(403, rawHttp(s.port, req.toByteArray(), byteArrayOf(1, 2, 3)).first)
        assertTrue(s.library.announcementsDir.listFiles().orEmpty().isEmpty())
    }

    /**
     * La cuota de 5 GB se comprueba después de recibir cada archivo y antes de copiarlo, sin bloqueo:
     * varias subidas a la vez ven la carpeta "con espacio" y todas pasan.
     */
    @Test
    fun concurrentUploadsCannotExceedTheQuota(): Unit = AdvServer(pin = "4821").use { s ->
        val dir = s.library.announcementsDir.apply { mkdirs() }
        val each = 200_000
        val n = 8
        // Archivo disperso: ocupa casi toda la cuota sin gastar disco (no es imagen, no aparece en la lista)
        RandomAccessFile(File(dir, "relleno.bin"), "rw").use { it.setLength(RemoteServer.MAX_ANNOUNCEMENTS_BYTES - each - 1000) }
        val socks = (1..n).map { i ->
            Socket("127.0.0.1", s.port).apply {
                soTimeout = 10_000
                val head = "POST /anuncios?nombre=Cuota$i.png HTTP/1.1\r\nHost: 127.0.0.1:${s.port}\r\nX-Proyecta-Pin: 4821\r\nContent-Length: $each\r\nConnection: close\r\n\r\n"
                getOutputStream().write(head.toByteArray()); getOutputStream().write(ByteArray(each - 1) { i.toByte() }); getOutputStream().flush()
            }
        }
        Thread.sleep(500) // todas esperando el último byte
        val go = CountDownLatch(1)
        val ok = AtomicInteger()
        val threads = socks.map { sock ->
            Thread {
                go.await()
                sock.getOutputStream().write(byteArrayOf(9)); sock.getOutputStream().flush()
                val head = readHttpHead(sock.getInputStream())
                if (head.startsWith("HTTP/1.1 200")) ok.incrementAndGet()
                sock.close()
            }.apply { start() }
        }
        go.countDown()
        threads.forEach { it.join(10_000) }
        val total = s.library.announcementsBytes()
        println("ADV cuota: $ok de $n subidas aceptadas; carpeta = $total bytes (tope ${RemoteServer.MAX_ANNOUNCEMENTS_BYTES})")
        assertTrue(total <= RemoteServer.MAX_ANNOUNCEMENTS_BYTES, "se pasó de la cuota: $total bytes con ${ok.get()} subidas aceptadas")
    }

    /** Si ya hay un anuncio con el mismo nombre y el mismo tamaño, la subida dice "ok" pero no guarda la foto nueva. */
    @Test
    fun differentPhotoWithSameNameAndSizeIsNotSilentlyDropped(): Unit = AdvServer(pin = "4821").use { s ->
        assertEquals(200, upload(s.port, "Cartel.png", ByteArray(1000) { 1 }).first)
        val (code, body) = upload(s.port, "Cartel.png", ByteArray(1000) { 2 })
        assertEquals(200, code)
        val versions = s.library.announcementsDir.listFiles().orEmpty().map { it.readBytes()[0].toInt() }.toSet()
        assertTrue(2 in versions, "respondió $body pero la foto nueva no está en la carpeta (solo versiones $versions)")
    }

    @Test
    fun staticPagesDoNotLeakFilesOutsideWeb(): Unit = AdvServer().use { s ->
        val paths = listOf(
            "/../biblia/Reina-Valera%201909.txt", "/%2e%2e/biblia/Reina-Valera%201909.txt", "/..%2fbiblia%2fReina-Valera%201909.txt",
            "/web/../biblia/Reina-Valera%201909.txt", "/..%5cbiblia%5cReina-Valera%201909.txt", "/biblia/Reina-Valera%201909.txt",
            "/../app/selah/desktop/RemoteServer.class", "/%2e%2e%2fapp/selah/desktop/RemoteServer.class", "/....//biblia/Reina-Valera%201909.txt",
            "/../../../../../../etc/passwd", "/%2e%2e%2f%2e%2e%2f%2e%2e%2fetc%2fpasswd", "/index.html%00.txt",
        )
        for (p in paths) {
            val (code, body) = rawHttp(s.port, "GET $p HTTP/1.1\r\nHost: 127.0.0.1:${s.port}\r\nConnection: close\r\n\r\n".toByteArray())
            assertFalse(code == 200 && !body.contains("<html", ignoreCase = true), "$p devolvió $code con ${body.take(80)}")
        }
        val (code, _) = rawHttp(s.port, "GET / HTTP/1.1\r\nHost: 127.0.0.1:${s.port}\r\nConnection: close\r\n\r\n".toByteArray())
        assertEquals(200, code)
    }
}
