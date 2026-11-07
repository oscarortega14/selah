package app.selah.desktop

import app.selah.shared.ClientMessage
import app.selah.shared.PresentationState
import app.selah.shared.ProtocolJson
import app.selah.shared.ServicePlanState
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import kotlin.io.path.createTempDirectory
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** Pruebas adversarias: secuencias al azar (con semilla) y casos mínimos de fallas encontradas. */
class AdversarialStateTest {
    private lateinit var dir: File
    private lateinit var library: Library
    private lateinit var commands: Commands
    private val screen get() = commands.presenter.state.value
    private val plan get() = commands.plan.state.value

    private val songTexts = mapOf(
        "A" to "# A\nCCLI: 111\nAutor: Ana\n\nVerso 1:\na1\n\nCoro:\nc1\n\nc2\n\nVerso 2:\na2\n\nCoro:\nc3\n",
        "B" to "# B\n\nb1\n\nb2\n\nb3\n",
        "C" to "# C\n\nsolo\n",
        "E" to "# E\n",
        "O" to "# O\nOrden: Inicio, Coro, Verso, Coro, Coro\n\ni1\n\nCoro:\nco\n\nVerso:\nve\n",
    )

    @BeforeTest
    fun setUp() {
        dir = createTempDirectory("selah-adv").toFile()
        File(dir, "canciones").mkdirs()
        songTexts.forEach { (id, t) -> File(dir, "canciones/$id.txt").writeText(t) }
        File(dir, "biblia").mkdirs()
        File(dir, "biblia/Uno.json").writeText(
            """{"name":"Uno","books":[{"name":"Juan","chapters":[["j1","j2","j3"],["k1",""]]},{"name":"Salmos","chapters":[["s1","s2"]]}]}""",
        )
        File(dir, "biblia/Dos.json").writeText(
            """{"name":"Dos","books":[{"name":"Juan","chapters":[["J1","J2"],["K1","K2","K3"]]}]}""",
        )
        File(dir, "predicaciones").mkdirs()
        File(dir, "predicaciones/P.txt").writeText("# P\n\nIntro:\nhola\n\nBiblia: Juan 1:1-3\n\nPunto 1:\nuno\n")
        File(dir, "anuncios").mkdirs()
        File(dir, "anuncios/x.jpg").writeBytes(byteArrayOf(1, 2, 3))
        library = Library(dir).also { it.reload() }
        commands = Commands.create(library)
    }

    @AfterTest
    fun tearDown() { dir.deleteRecursively() }

    // ---------- invariantes ----------

    private fun checkScreen(s: PresentationState, ctx: String) {
        if (s.slides.isEmpty()) assertEquals(-1, s.index, "index con diapositivas vacías: $ctx")
        else assertTrue(s.index in s.slides.indices, "index ${s.index} fuera de ${s.slides.indices}: $ctx")
        assertTrue(s.currentSection in -1..s.sections.lastIndex, "currentSection: $ctx")
        for (label in s.sectionLabels) {
            val t = s.jumpTarget(label)
            assertTrue(t != null && t in s.slides.indices, "jumpTarget($label)=$t: $ctx")
        }
    }

    private fun checkPlan(p: ServicePlanState, ctx: String) {
        assertTrue(p.current in -1..p.items.lastIndex, "plan.current ${p.current} con ${p.items.size} elementos: $ctx")
        val f = File(dir, "lista-del-culto.json")
        val onDisk = if (f.exists()) ProtocolJson.decodeFromString(ServicePlanState.serializer(), f.readText()) else ServicePlanState()
        assertEquals(p, onDisk, "la lista en disco no es la de memoria: $ctx")
    }

    private fun overlays(s: PresentationState) = listOf(s.blank, s.stageMessage, s.alert, s.countdownEnd, s.countdownText, s.timerStart, s.timerMinutes, s.lowerThird, s.lowerThirdSub)

    private val names = listOf("", "   ", "../x", "con", "Lista", "lista", "a/b", "x".repeat(300), "😀 culto", "nope", " .. ")
    private val refs = listOf("Juan 1:2", "jn 1", "Juan 2", "Juan 2:2", "Salmos 1", "Juan 9", "xyz", "", "Juan 1:3-1", "Juan 1:1; 2:1")

    private fun randomMessage(r: Random): ClientMessage {
        val ids = listOf("A", "B", "C", "E", "O", "A2", "nope", "../canciones/A")
        fun idx() = r.nextInt(-2, 9)
        return when (r.nextInt(34)) {
            0, 1 -> ClientMessage.SelectSong(ids.random(r))
            2 -> ClientMessage.GoTo(idx())
            3, 4 -> ClientMessage.Next
            5 -> ClientMessage.Prev
            6 -> ClientMessage.SetBlank(r.nextBoolean())
            7 -> ClientMessage.ShowBible(refs.random(r))
            8 -> ClientMessage.ShowMedia(listOf("x.jpg", "y.jpg", "../x.jpg").random(r))
            9 -> ClientMessage.ShowSermon(listOf("P", "Q").random(r))
            10 -> ClientMessage.StageMessage(listOf("", " hola ", "x").random(r))
            11 -> ClientMessage.ShowLogo(r.nextBoolean())
            12 -> ClientMessage.SetAlert(listOf("", "auto").random(r))
            13 -> ClientMessage.StartCountdown(r.nextInt(-5, 100_000), "t")
            14 -> ClientMessage.StopCountdown
            15 -> ClientMessage.StartTimer(r.nextInt(-2, 700))
            16 -> ClientMessage.StopTimer
            17 -> ClientMessage.SetLowerThird("n", "s")
            18, 19 -> ClientMessage.PlanAddSong(ids.random(r))
            20 -> ClientMessage.PlanAddBible(refs.random(r))
            21 -> ClientMessage.PlanAddSermon(listOf("P", "Q").random(r))
            22 -> ClientMessage.PlanAddMedia(listOf("x.jpg", "y.jpg").random(r))
            23 -> ClientMessage.PlanRemove(idx())
            24, 25 -> ClientMessage.PlanMove(idx(), idx())
            26, 27 -> ClientMessage.PlanGo(idx())
            28 -> ClientMessage.PlanNext
            29 -> ClientMessage.PlanClear
            30 -> ClientMessage.PlanSave(names.random(r))
            31 -> ClientMessage.PlanOpen(names.random(r))
            32 -> ClientMessage.SelectBible(listOf("Uno", "Dos", "Reina-Valera 1909", "nada").random(r))
            else -> ClientMessage.SetSecondBible(listOf("Uno", "Dos", null, "nada").random(r))
        }
    }

    /** Ediciones del operador en medio del culto: renombrar, acortar, borrar canciones y anuncios. */
    private fun randomEdit(r: Random): String {
        when (r.nextInt(7)) {
            0 -> { // renombrar A <-> A2
                val (old, new) = if (library.song("A") != null) "A" to "A2" else "A2" to "A"
                if (library.song(old) == null) return "rename-skip"
                val id = library.save(old, songTexts.getValue("A").replace("# A", "# $new"))
                commands.presenter.songSaved(old, id); return "rename $old->$new"
            }
            1 -> { // B con menos diapositivas (o más)
                if (library.song("B") == null) return "shrink-skip"
                val n = r.nextInt(0, 5)
                val id = library.save("B", "# B\n\n" + (1..n).joinToString("\n\n") { "b$it" })
                commands.presenter.songSaved("B", id); return "B con $n"
            }
            2 -> { library.delete(listOf("A", "A2", "B", "C").random(r)); return "delete song" }
            3 -> { // predicación más corta
                val id = library.save("P", "# P\n", sermon = true)
                commands.presenter.sermonSaved("P", id); return "P corta"
            }
            4 -> { File(dir, "anuncios/x.jpg").delete(); library.refreshAnnouncements(); return "borrar anuncio" }
            5 -> { // restaurar
                songTexts.forEach { (id, t) -> if (!(id == "A" && library.song("A2") != null)) File(dir, "canciones/$id.txt").takeIf { !it.exists() }?.writeText(t) }
                File(dir, "anuncios/x.jpg").writeBytes(byteArrayOf(1))
                library.reload(); return "restaurar"
            }
            else -> { library.delete("P", sermon = true); return "borrar predicación" }
        }
    }

    @Test
    fun randomSequencesKeepInvariants() {
        val contentMsgs = setOf(ClientMessage.SelectSong::class, ClientMessage.ShowBible::class, ClientMessage.ShowMedia::class, ClientMessage.ShowSermon::class)
        tearDown()
        for (seed in 0 until 60) {
            setUp() // directorio nuevo por semilla
            val r = Random(seed)
            val log = mutableListOf<String>()
            try {
                repeat(300) { step ->
                    if (r.nextInt(12) == 0) { log += "EDIT " + randomEdit(r); checkScreen(screen, "seed=$seed step=$step ${log.takeLast(8)}"); return@repeat }
                    val msg = randomMessage(r)
                    log += msg.toString()
                    val before = screen
                    val err = commands.handle(msg)
                    val after = screen
                    val ctx = "seed=$seed step=$step ${log.takeLast(8)}"
                    checkScreen(after, ctx)
                    checkPlan(plan, ctx)
                    if (msg::class in contentMsgs && err == null) {
                        assertEquals(overlays(before), overlays(after), "se perdieron capas: $ctx")
                        assertTrue(!after.logo, "logo sigue: $ctx")
                    }
                }
            } catch (e: AssertionError) {
                throw e
            } catch (e: Throwable) {
                fail("excepción ${e::class.simpleName}: ${e.message} seed=$seed ${log.takeLast(10)}", e)
            } finally {
                tearDown()
            }
        }
        setUp()
    }

    // ---------- casos mínimos ----------

    /** BUG: quitar o mover un elemento borra el nombre de la lista guardada/abierta (agregar lo conserva). */
    @Test
    fun removingAnItemKeepsTheListName() {
        commands.handle(ClientMessage.PlanAddSong("A"))
        commands.handle(ClientMessage.PlanAddSong("B"))
        assertNull(commands.handle(ClientMessage.PlanSave("Domingo")))
        commands.handle(ClientMessage.PlanAddSong("C"))
        assertEquals("Domingo", plan.name) // agregar: lo conserva
        commands.handle(ClientMessage.PlanRemove(2))
        assertEquals("Domingo", plan.name, "PlanRemove borró el nombre de la lista")
    }

    @Test
    fun movingAnItemKeepsTheListName() {
        commands.handle(ClientMessage.PlanAddSong("A"))
        commands.handle(ClientMessage.PlanAddSong("B"))
        assertNull(commands.handle(ClientMessage.PlanSave("Domingo")))
        commands.handle(ClientMessage.PlanMove(0, 1))
        assertEquals("Domingo", plan.name, "PlanMove borró el nombre de la lista")
    }

    /** BUG: un nombre de 80+ caracteres con emoji en el corte deja media pareja sustituta: no se puede volver a abrir. */
    @Test
    fun longEmojiListNameCanBeReopened() {
        commands.handle(ClientMessage.PlanAddSong("A"))
        val name = "a".repeat(79) + "😀 culto"
        assertNull(commands.handle(ClientMessage.PlanSave(name)))
        val savedName = plan.name
        assertTrue(savedName.none { it.isSurrogate() } || savedName.zipWithNext().all { (a, b) -> !a.isHighSurrogate() || b.isLowSurrogate() } && !savedName.last().isHighSurrogate(),
            "el nombre guardado termina en media pareja sustituta: ${savedName.takeLast(3).map { it.code.toString(16) }}")
        assertNull(commands.handle(ClientMessage.PlanOpen(savedName)), "no abre la lista recién guardada con su propio nombre")
    }

    /** En un disco que no distingue mayúsculas (Windows, macOS): "Lista" y "lista" son el mismo archivo. */
    @Test
    fun namesDifferingOnlyInCase() {
        val probe = File(dir, "Probe"); probe.writeText("x")
        val caseInsensitive = File(dir, "probe").exists(); probe.delete()
        commands.handle(ClientMessage.PlanAddSong("A"))
        assertNull(commands.handle(ClientMessage.PlanSave("Lista")))
        commands.handle(ClientMessage.PlanAddSong("B"))
        assertNull(commands.handle(ClientMessage.PlanSave("lista")))
        if (caseInsensitive) {
            // El archivo conserva el nombre "Lista.json"; la lista en uso se llama "lista".
            assertEquals(listOf("Lista"), commands.plan.saved.value)
            assertNull(commands.handle(ClientMessage.PlanOpen(plan.name)), "no abre \"${plan.name}\": saved=${commands.plan.saved.value}")
        }
    }

    @Test
    fun corruptedAndDeletedSavedLists() {
        commands.handle(ClientMessage.PlanAddSong("A"))
        assertNull(commands.handle(ClientMessage.PlanSave("L")))
        File(dir, "listas/L.json").writeText("{ roto")
        val before = plan
        assertTrue(commands.handle(ClientMessage.PlanOpen("L")) != null)
        assertEquals(before, plan)
        File(dir, "listas/L.json").delete()
        assertTrue(commands.handle(ClientMessage.PlanOpen("L")) != null)
        // Lista del culto corrupta al arrancar: empieza vacía, sin excepción.
        File(dir, "lista-del-culto.json").writeText("\u0000\u0000")
        assertEquals(ServicePlanState(), Commands.create(library).plan.state.value)
    }

    @Test
    fun songSavedWithFewerSlidesKeepsAValidIndex() {
        commands.handle(ClientMessage.SelectSong("B"))
        commands.handle(ClientMessage.GoTo(2))
        commands.presenter.songSaved("B", library.save("B", "# B\n"))
        checkScreen(screen, "B sin diapositivas")
        assertEquals(-1, screen.index)
        commands.presenter.songSaved("B", library.save("B", "# B\n\nx\n\ny\n"))
        assertEquals(0, screen.index)
    }

    /** BUG: al renombrar una canción, la lista del culto sigue apuntando al nombre viejo y ya no se puede proyectar. */
    @Test
    fun renamedSongStillWorksFromThePlan() {
        commands.handle(ClientMessage.PlanAddSong("A"))
        commands.handle(ClientMessage.PlanGo(0))
        val newId = library.save("A", songTexts.getValue("A").replace("# A", "# A nueva"))
        commands.presenter.songSaved("A", newId)
        assertEquals("A nueva", screen.title) // la pantalla sí se actualiza
        assertNull(commands.handle(ClientMessage.PlanGo(0)), "la lista quedó apuntando a \"A\": ${plan.items}")
    }

    /** BUG: el reporte CCLI parte en dos la canción renombrada y pierde su número CCLI y autor antes del cambio. */
    @Test
    fun ccliReportSurvivesARename() {
        var now = LocalDateTime.of(2026, 11, 1, 10, 0)
        val history = History(File(dir, "h.csv")) { now }
        commands.presenter.onShown = history::record
        commands.presenter.renameListeners += history::renamed // como en Commands.create
        commands.handle(ClientMessage.SelectSong("A"))
        val newId = library.save("A", songTexts.getValue("A").replace("# A", "# A nueva"))
        commands.presenter.songSaved("A", newId)
        now = LocalDateTime.of(2026, 11, 8, 10, 0)
        commands.handle(ClientMessage.SelectSong(newId))
        val rows = history.ccliReport(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30), library::song)
        assertEquals(listOf("111"), rows.map { it.ccli }, "filas: $rows")
    }

    /** BUG: inyección de fórmulas en el CSV para Excel: un título que empieza con = + - @ se ejecuta como fórmula. */
    @Test
    fun ccliCsvNeutralizesFormulas() {
        val evil = listOf("=HYPERLINK(\"http://x\",\"clic\")", "+1+1", "-2+3", "@SUM(A1)", "=1+1")
        val csv = History.ccliCsv(evil.map { CcliRow(it, "", "", 1, listOf(LocalDate.of(2026, 1, 1))) })
        val cells = csv.lines().drop(1).filter { it.isNotEmpty() }.map { History.parseCsvLine(it).first() }
        for (c in cells) assertTrue(c.firstOrNull() !in setOf('=', '+', '-', '@'), "celda peligrosa en Excel: $c")
    }

    @Test
    fun historyAcrossMidnightAndYearAndClockBackwards() {
        var now = LocalDateTime.of(2026, 12, 31, 23, 59)
        val h = History(File(dir, "h2.csv")) { now }
        h.record(HistoryEntry.Kind.SONG, "T, \"q\"", "t")
        now = LocalDateTime.of(2027, 1, 1, 0, 0)
        h.record(HistoryEntry.Kind.SONG, "T, \"q\"", "t")
        now = LocalDateTime.of(2026, 12, 31, 23, 30) // el reloj retrocede
        h.record(HistoryEntry.Kind.SONG, "T, \"q\"", "t")
        assertEquals(2, h.entries.value.size)
        assertEquals(h.entries.value, History(File(dir, "h2.csv")) { now }.entries.value)
        val row = h.ccliReport(LocalDate.of(2026, 1, 1), LocalDate.of(2027, 12, 31)) { null }.single()
        assertEquals(2, row.times)
        assertEquals("T, \"q\"", row.title)
    }

    /** Dos Biblias con distinta numeración: el texto 2 solo se pega donde existe el mismo versículo. */
    @Test
    fun secondBibleWithDifferentNumbering() {
        commands.handle(ClientMessage.SelectBible("Uno"))
        commands.handle(ClientMessage.SetSecondBible("Dos"))
        assertNull(commands.handle(ClientMessage.ShowBible("Juan 1")))
        assertEquals(listOf("J1", "J2", ""), screen.slides.map { it.text2 })
        // Cambiar de traducción con un pasaje en pantalla no lo rompe
        commands.handle(ClientMessage.SelectBible("Dos"))
        checkScreen(screen, "cambio de Biblia")
        // La lectura "Juan 2:2" está vacía en Uno pero existe en Dos
        assertNull(commands.handle(ClientMessage.PlanAddBible("Juan 2:2")))
        commands.handle(ClientMessage.SelectBible("Uno"))
        assertTrue(commands.handle(ClientMessage.PlanGo(0)) != null)
        assertEquals(-1, plan.current)
    }

    /** BUG (lógica de BackgroundsWindow.setSongBackground): con una predicación en pantalla se cambia una canción homónima. */
    @Test
    fun songBackgroundWithSermonOnScreenTouchesASongWithTheSameName() {
        File(dir, "canciones/P.txt").writeText("# P\n\nletra de la canción P\n")
        library.reload()
        commands.handle(ClientMessage.ShowSermon("P"))
        assertTrue(screen.sermon)
        // Lo que hace la ventana "Fondos y letra" al tocar "Usar en esta canción":
        screen.songIdForBackground()?.let { id ->
            val newId = library.setSongBackground(id, "monte.jpg")
            commands.presenter.songSaved(id, newId)
        }
        assertEquals(null, library.song("P")!!.background, "se cambió el fondo de la CANCIÓN P aunque en pantalla está la predicación P")
        commands.handle(ClientMessage.SelectSong("P"))
        assertEquals("P", screen.songIdForBackground()) // con la canción en pantalla, sí
    }

    // ---------- concurrencia ----------

    @Test
    fun manyRemotesAtOnce() {
        val errors = ConcurrentLinkedQueue<Throwable>()
        val threads = 8
        val perThread = 1500
        val start = CountDownLatch(1)
        val workers = (0 until threads).map { t ->
            Thread {
                start.await()
                val r = Random(1000 + t)
                repeat(perThread) {
                    try {
                        val msg = randomMessage(r).let { if (it is ClientMessage.PlanSave || it is ClientMessage.PlanOpen || it is ClientMessage.SelectBible || it is ClientMessage.SetSecondBible) ClientMessage.Next else it }
                        commands.handle(msg)
                        val s = screen
                        if (s.slides.isEmpty() && s.index != -1 || s.slides.isNotEmpty() && s.index !in s.slides.indices) throw AssertionError("estado inválido $s")
                        val p = plan
                        if (p.current !in -1..p.items.lastIndex) throw AssertionError("plan inválido $p")
                    } catch (e: Throwable) { errors += e }
                }
            }.apply { start() }
        }
        start.countDown()
        workers.forEach { it.join() }
        assertTrue(errors.isEmpty(), "errores: ${errors.take(3)}")
        checkPlan(plan, "final")
    }

    @Test
    fun concurrentAddsAreNotLost() {
        commands.handle(ClientMessage.PlanClear)
        val threads = 8; val per = 200
        val start = CountDownLatch(1)
        (0 until threads).map { Thread { start.await(); repeat(per) { commands.handle(ClientMessage.PlanAddSong("C")) } }.apply { start() } }
            .also { start.countDown() }.forEach { it.join() }
        assertEquals(threads * per, plan.items.size)
        checkPlan(plan, "adds")
    }

    /** Navegar (Next) desde varios remotos a la vez mientras se guarda la canción: el índice siempre vale. */
    @Test
    fun navigatingWhileEditing() {
        commands.handle(ClientMessage.SelectSong("B"))
        val errors = ConcurrentLinkedQueue<Throwable>()
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val nav = (0 until 4).map { t ->
            Thread {
                val r = Random(t)
                while (!stop.get()) {
                    try {
                        commands.handle(listOf(ClientMessage.Next, ClientMessage.Prev, ClientMessage.GoTo(r.nextInt(6))).random(r))
                        checkScreen(screen, "nav")
                    } catch (e: Throwable) { errors += e }
                }
            }.apply { start() }
        }
        val r = Random(7)
        repeat(150) {
            val n = r.nextInt(0, 6)
            commands.presenter.songSaved("B", library.save("B", "# B\n\n" + (1..n).joinToString("\n\n") { "b$it" }))
        }
        stop.set(true); nav.forEach { it.join() }
        assertTrue(errors.isEmpty(), "errores: ${errors.take(3)}")
    }

    @Test
    fun countdownAndTimerWithClockGoingBackwards() {
        var t = 1_000_000L
        val p = Presenter(library) { t }
        p.handle(ClientMessage.StartCountdown(60))
        p.handle(ClientMessage.StartTimer(5))
        t -= 3_600_000 // el reloj del PC se atrasa una hora
        val s = p.state.value
        assertEquals(3660L, s.countdownSeconds(t)) // hasta 1 h de más: comportamiento esperado de un reloj de pared
        assertEquals(0L, s.timerElapsed(t))
        assertEquals(300L, s.timerLeft(t))
        assertEquals(0L, s.countdownSeconds(Long.MAX_VALUE / 2))
    }
}
