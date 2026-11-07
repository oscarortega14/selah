package app.selah.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SongParserTest {
    @Test
    fun parsesTitleBlocksAndLabels() {
        val song = SongParser.parse(
            "x",
            "﻿# Mi canción\r\n\r\nLínea 1\r\nLínea 2\r\n\r\n\r\nCoro:\nA\nB\n\nSolo:\n",
        )
        assertEquals("Mi canción", song.title)
        assertEquals(3, song.slides.size)
        assertEquals(Slide("", "Línea 1\nLínea 2"), song.slides[0])
        assertEquals(Slide("Coro", "A\nB"), song.slides[1])
        // un bloque de una sola línea que termina en ":" es texto, no etiqueta
        assertEquals(Slide("", "Solo:"), song.slides[2])
    }

    @Test
    fun fallsBackToFileNameAsTitle() {
        assertEquals("archivo", SongParser.parse("archivo", "Hola\nmundo").title)
    }
}

class BibleTest {
    private val bible = Bible(
        "Prueba",
        Bible.BOOK_NAMES.map { name ->
            Bible.Book(name, List(5) { ch -> List(10) { v -> "$name ${ch + 1}:${v + 1}" } })
        },
    )

    @Test
    fun parsesReferences() {
        assertEquals(BibleReference("Juan", 3, 16, null), BibleReference.parse("Juan 3:16"))
        assertEquals(BibleReference("1 Corintios", 13, 4, 7), BibleReference.parse("1 Corintios 13:4-7"))
        assertEquals(BibleReference("sal", 23), BibleReference.parse("sal 23"))
        assertEquals(BibleReference("jn", 3, 16), BibleReference.parse("jn 3 16"))
        assertEquals(BibleReference("Gén", 1, 1), BibleReference.parse("Gén. 1.1"))
        assertNull(BibleReference.parse("Juan"))
        assertNull(BibleReference.parse(""))
    }

    @Test
    fun findsBooksWithAccentsAbbreviationsAndNumbers() {
        assertEquals("Juan", bible.books[bible.findBook("juan")!!].name)
        assertEquals("Juan", bible.books[bible.findBook("jn")!!].name)
        assertEquals("1 Juan", bible.books[bible.findBook("1 juan")!!].name)
        assertEquals("Génesis", bible.books[bible.findBook("genesis")!!].name)
        assertEquals("Salmos", bible.books[bible.findBook("Salmo")!!].name)
        assertEquals("1 Corintios", bible.books[bible.findBook("1co")!!].name)
        assertNull(bible.findBook("xyz"))
    }

    @Test
    fun looksUpVersesRangesAndChapters() {
        val one = bible.lookup("juan 3:4")
        assertEquals("Juan 3:4", one.title)
        assertEquals(listOf(Slide("Juan 3:4", "Juan 3:4")), one.slides)

        val range = bible.lookup("1 co 2:3-50")
        assertEquals("1 Corintios 2:3-10", range.title)
        assertEquals(8, range.slides.size)

        val chapter = bible.lookup("Salmos 5")
        assertEquals("Salmos 5", chapter.title)
        assertEquals(10, chapter.slides.size)
    }

    @Test
    fun parsesSeveralReferencesAndCrossChapterRanges() {
        assertEquals(BibleReference("Mateo", 5, 3, 4, chapterTo = 6), BibleReference.parse("Mateo 5:3-6:4"))
        assertEquals(BibleReference("Salmos", 1, chapterTo = 2), BibleReference.parse("Salmos 1-2"))
        assertEquals(
            listOf(BibleReference("Juan", 3, 16), BibleReference("Romanos", 5, 8)),
            BibleReference.parseAll("Juan 3:16; Romanos 5:8"),
        )
        // Sin libro después de ";": mismo libro (cap:vers) o mismo capítulo (solo versículo)
        assertEquals(
            listOf(BibleReference("Juan", 3, 16), BibleReference("Juan", 5, 24), BibleReference("Juan", 5, 26, 27)),
            BibleReference.parseAll("Juan 3:16; 5:24; 26-27"),
        )
        // Después de un capítulo entero, un número solo es otro capítulo
        assertEquals(listOf(BibleReference("Salmos", 23), BibleReference("Salmos", 24)), BibleReference.parseAll("Salmos 23; 24"))
        assertNull(BibleReference.parse("Juan 3:16; Romanos 5:8")) // parse = una sola cita
        assertNull(BibleReference.parseAll("5:24; Juan 3:16")) // la primera debe tener libro
        assertNull(BibleReference.parseAll("Juan 3:16; hola"))
    }

    @Test
    fun looksUpSeveralPassagesAtOnce() {
        val p = bible.lookup("Juan 3:6; Romanos 5:8")
        assertEquals("Juan 3:6; Romanos 5:8", p.title)
        assertEquals(listOf("Juan 3:6", "Romanos 5:8"), p.slides.map { it.label })

        val sameBook = bible.lookup("juan 3:9; 4:1-2")
        assertEquals("Juan 3:9; 4:1-2", sameBook.title)
        assertEquals(listOf("Juan 3:9", "Juan 4:1", "Juan 4:2"), sameBook.slides.map { it.label })

        val cross = bible.lookup("Mateo 2:9-3:2")
        assertEquals("Mateo 2:9-3:2", cross.title)
        assertEquals(listOf("Mateo 2:9", "Mateo 2:10", "Mateo 3:1", "Mateo 3:2"), cross.slides.map { it.label })

        val chapters = bible.lookup("Salmos 1-2")
        assertEquals("Salmos 1-2", chapters.title)
        assertEquals(20, chapters.slides.size)
    }

    @Test
    fun rejectsBadOrHugeReadings() {
        assertTrue(assertFailsWith<IllegalArgumentException> { bible.lookup("Juan 3:8-2") }.message!!.contains("final"))
        assertTrue(assertFailsWith<IllegalArgumentException> { bible.lookup("Juan 4-2") }.message!!.contains("final"))
        assertTrue(assertFailsWith<IllegalArgumentException> { bible.lookup("Juan 3:1-9:1") }.message!!.contains("capítulo 9"))
        val huge = Bible("Grande", listOf(Bible.Book("Salmos", List(150) { List(20) { "v" } })))
        assertTrue(assertFailsWith<IllegalArgumentException> { huge.lookup("Salmos 1-150") }.message!!.contains("muy larga"))
    }

    @Test
    fun reportsClearErrors() {
        assertTrue(assertFailsWith<IllegalArgumentException> { bible.lookup("juan 99") }.message!!.contains("capítulo"))
        assertTrue(assertFailsWith<IllegalArgumentException> { bible.lookup("juan 3:40") }.message!!.contains("versículo"))
        assertTrue(assertFailsWith<IllegalArgumentException> { bible.lookup("hola") }.message!!.contains("No entiendo"))
    }

    @Test
    fun loadsVplFormat() {
        val codes = List(66) { "B$it" }
        val text = buildString {
            append("﻿")
            for (c in codes) append("$c 1:1 EN el principio.\n$c 1:2 Segundo.\n$c 2:1 JEHOVÁ es mi pastor.\n")
            append("línea que no es versículo\n")
        }
        val b = Bible.fromVpl(text, "RV")
        assertEquals("RV", b.name)
        assertEquals(66, b.books.size)
        assertEquals("Génesis", b.books.first().name)
        assertEquals("Apocalipsis", b.books.last().name)
        assertEquals("En el principio.", b.lookup("gen 1:1").slides.single().text)
        assertEquals("JEHOVÁ es mi pastor.", b.lookup("ap 2:1").slides.single().text)
        assertEquals(2, b.lookup("juan 1").slides.size)

        assertFailsWith<IllegalArgumentException> { Bible.fromVpl("GEN 1:1 x\n", "x") }

        // versículo vacío en medio de un rango: se salta
        val gap = Bible("g", listOf(Bible.Book("Job", listOf(listOf("uno", "", "tres")))))
        assertEquals(listOf("uno", "tres"), gap.lookup("job 1:1-3").slides.map { it.text })
        assertFailsWith<IllegalArgumentException> { gap.lookup("job 1:2") }
    }

    @Test
    fun fixesDropCapsOnly() {
        assertEquals("En el principio", Bible.fixDropCap("EN el principio"))
        assertEquals("Dios es nuestro amparo", Bible.fixDropCap("DIOS es nuestro amparo"))
        assertEquals("JEHOVÁ es mi pastor", Bible.fixDropCap("JEHOVÁ es mi pastor"))
        assertEquals("Y dijo Dios", Bible.fixDropCap("Y dijo Dios"))
        assertEquals("Salmo de David. JEHOVÁ", Bible.fixDropCap("Salmo de David. JEHOVÁ"))
    }

    @Test
    fun loadsBothJsonFormats() {
        val a = Bible.fromJson("""{"name":"X","books":[{"name":"Génesis","chapters":[["En el principio"]]}]}""", "f")
        assertEquals("X", a.name)
        assertEquals("En el principio", a.lookup("gen 1:1").slides.single().text)

        val b = Bible.fromJson("﻿[{\"abbrev\":\"gn\",\"chapters\":[[\"uno\",\"dos\"]]}]", "Archivo")
        assertEquals("Archivo", b.name)
        assertEquals("Génesis", b.books.single().name)
    }
}

class SectionsTest {
    private val song = listOf(
        Slide("", "estrofa a"), Slide("", "estrofa b"),
        Slide("Coro", "coro a"), Slide("", "coro b"),
        Slide("Pregón 1", "p1"),
    )

    @Test
    fun groupsSlidesUnderTheirLabels() {
        assertEquals(listOf(Section("Inicio", 0), Section("Coro", 2), Section("Pregón 1", 4)), song.sections())
    }

    @Test
    fun currentSectionFollowsIndex() {
        val st = PresentationState("T", song, index = 3)
        assertEquals(1, st.currentSection)
        assertEquals(0, st.copy(index = 1).currentSection)
        assertEquals(2, st.copy(index = 4).currentSection)
    }

    @Test
    fun noSectionsWithoutLabelsOrForBible() {
        assertEquals(emptyList(), listOf(Slide("", "a"), Slide("", "b")).sections())
        assertEquals(emptyList(), PresentationState("Juan 3", listOf(Slide("Juan 3:1", "x")), 0, isBible = true).sections)
    }
}

class SongOrderTest {
    private val text = """
        # Alabad
        Orden: Inicio, Coro, Pregón 1, coro, Puente

        estrofa a
        estrofa b

        Coro:
        coro a

        coro b

        Pregón 1:
        p1
    """.trimIndent()

    @Test
    fun expandsTheOrderRepeatingSections() {
        val song = SongParser.parse("x", text)
        assertEquals(
            listOf("estrofa a\nestrofa b", "coro a", "coro b", "p1", "coro a", "coro b"),
            song.slides.map { it.text },
        )
        assertEquals(listOf("Inicio", "Coro", "", "Pregón 1", "Coro", ""), song.slides.map { it.label })
        assertEquals(listOf("Puente"), song.unknownOrder)
    }

    @Test
    fun withoutOrderNothingChanges() {
        val song = SongParser.parse("x", text.lines().filterNot { it.startsWith("Orden") }.joinToString("\n"))
        assertEquals(4, song.slides.size)
        assertEquals(emptyList(), song.unknownOrder)
    }

    @Test
    fun jumpsGoToTheNextOccurrence() {
        val st = PresentationState("A", SongParser.parse("x", text).slides, index = 0)
        assertEquals(listOf("Inicio", "Coro", "Pregón 1"), st.sectionLabels)
        assertEquals(1, st.jumpTarget("Coro"))
        assertEquals(4, st.copy(index = 2).jumpTarget("Coro"))  // en el primer coro -> el segundo
        assertEquals(1, st.copy(index = 5).jumpTarget("Coro"))  // en el último -> vuelve al primero
        assertEquals(null, st.jumpTarget("Puente"))
        assertEquals("coro b", st.copy(index = 1).next?.text)
    }
}

class ThemeAndBackgroundTest {
    @Test
    fun readsTheFondoLine() {
        val song = SongParser.parse("x", "# T\nFondo: montañas.jpg\nOrden: Coro, Coro\n\nCoro:\nla la\n")
        assertEquals("montañas.jpg", song.background)
        assertEquals(2, song.slides.size)
        assertNull(SongParser.parse("x", "# T\n\nletra\n\nFondo: no es la línea\n").background)
        // Autor y número CCLI: datos de la canción, no se proyectan
        val withMeta = SongParser.parse("x", "# T\nAutor: Carl Boberg\nCCLI #: 14181\nOrden: Coro, Coro\n\nCoro:\nla la\n\nCCLI: 999\n")
        assertEquals("Carl Boberg", withMeta.author)
        assertEquals("14181", withMeta.ccli)
        assertEquals(listOf("la la", "CCLI: 999", "la la", "CCLI: 999"), withMeta.slides.map { it.text }) // después de la letra, es letra (y es parte del coro)
        assertNull(SongParser.parse("x", "# T\n\nletra\n").ccli)
    }

    @Test
    fun sanitizesThemes() {
        val t = Theme(color = "rojo", color2 = "#12", image = " ", dim = 3f, textColor = "#ffffff", fontScale = 9f).sanitized()
        assertEquals(Theme(color = "#000000", color2 = null, image = null, dim = 0.85f, textColor = "#ffffff", fontScale = 1.4f), t)
        assertTrue(Theme.PRESETS.all { it.second == it.second.sanitized() })
        assertEquals("luz", Theme().builtin)                       // el predeterminado ya no es negro
        assertNull(Theme(builtin = "no-existe").sanitized().builtin)
        assertTrue(Theme.PRESETS.all { it.second.builtin == null })
        assertEquals(Theme.BUILTINS.size, Theme.BUILTINS.map { it.first }.toSet().size)
        // un tema guardado antes de existir "builtin" toma el fondo incluido predeterminado
        assertEquals("luz", ProtocolJson.decodeFromString(Theme.serializer(), "{\"color\":\"#000000\"}").builtin)
    }
}

class SearchAndCountdownTest {
    private val bible = Bible(
        "T",
        listOf(
            Bible.Book("Génesis", listOf(listOf("En el principio crió Dios los cielos y la tierra."))),
            Bible.Book("Juan", listOf(
                listOf("En el principio era el Verbo"),
                listOf("", "Porque de tal manera amó Dios al mundo", "Dios es amor y manera de vivir"),
            )),
        ),
    )

    @Test
    fun searchesPhrasesIgnoringAccentsExactFirst() {
        val (hits, total) = bible.search("manera AMO dios")
        assertEquals(2, total)
        assertEquals(listOf("Juan 2:2", "Juan 2:3"), hits.map { it.reference })
        val (exact, _) = bible.search("dios manera")  // palabras sueltas: orden bíblico
        assertEquals(listOf("Juan 2:2", "Juan 2:3"), exact.map { it.reference })
        assertEquals("Juan 2:3", bible.search("de vivir").first.single().reference)
        assertEquals(2, bible.search("en el principio").second)
        assertEquals(0, bible.search("  a ").second)  // palabras de 1 letra no cuentan
        assertEquals(1, bible.search("principio", limit = 1).first.size)
    }

    @Test
    fun countdownAndClock() {
        val st = PresentationState(countdownEnd = 10_000)
        assertEquals(10, st.countdownSeconds(0))
        assertEquals(1, st.countdownSeconds(9_500))
        assertEquals(0, st.countdownSeconds(20_000))
        assertNull(PresentationState().countdownSeconds(0))
        assertEquals("02:05", formatClock(125))
        assertEquals("1:02:05", formatClock(3725))
        assertEquals("00:00", formatClock(0))
    }

    @Test
    fun newMessagesRoundTrip() {
        val msgs = listOf(
            ClientMessage.ShowLogo(true), ClientMessage.SetAlert("Carro ABC"), ClientMessage.StartCountdown(300, "Comenzamos en"),
            ClientMessage.StopCountdown, ClientMessage.BibleSearch("amó Dios"),
        )
        for (m in msgs) assertEquals(m, decodeClientMessage(m.encode()))
        val r = ServerMessage.BibleResults("x", listOf(BibleHit("Juan 3:16", "Porque")), 7)
        assertEquals(r, decodeServerMessage(r.encode()))
    }
}

class ProtocolTest {
    @Test
    fun roundTripsPlanAndStageMessages() {
        val msgs = listOf(
            ClientMessage.PlanAddSong("a"), ClientMessage.PlanAddBible("Juan 3:16"), ClientMessage.PlanRemove(1),
            ClientMessage.PlanMove(0, 2), ClientMessage.PlanGo(1), ClientMessage.PlanNext, ClientMessage.PlanClear,
            ClientMessage.StageMessage("2 minutos"),
        )
        for (m in msgs) assertEquals(m, decodeClientMessage(m.encode()))
        val plan = ServerMessage.Plan(ServicePlanState(listOf(PlanItem(PlanKind.SONG, "a", "A")), 0))
        assertEquals(plan, decodeServerMessage(plan.encode()))
        assertTrue(plan.encode().contains("\"kind\":\"SONG\""))
    }

    @Test
    fun roundTripsMessages() {
        val msgs = listOf(
            ClientMessage.SelectSong("a"), ClientMessage.GoTo(3), ClientMessage.Next,
            ClientMessage.Prev, ClientMessage.SetBlank(true), ClientMessage.ShowBible("Juan 3:16"),
        )
        for (m in msgs) assertEquals(m, decodeClientMessage(m.encode()))

        val state = ServerMessage.State(PresentationState("T", listOf(Slide("Coro", "x")), 0, blank = false, isBible = false))
        assertEquals(state, decodeServerMessage(state.encode()))
        assertTrue(ClientMessage.Next.encode().contains("\"type\":\"next\""))
    }

    @Test
    fun searchIgnoresAccentsAndCase() {
        val songs = listOf(SongSummary("1", "Cuán grande es Él", "Señor mi Dios"), SongSummary("2", "Otra", "nada"))
        assertEquals(listOf("1"), songs.search("cuan GRANDE").map { it.id })
        assertEquals(listOf("1"), songs.search("senor").map { it.id })
        assertEquals(2, songs.search("  ").size)
    }

    @Test
    fun vplSkipsDeuterocanonicalBooksAndAcceptsUsfmCodes() {
        // Como spabll de eBible.org: los deuterocanónicos (TOB, SIR…) van entre Malaquías y Mateo.
        val ot = listOf("GEN", "EXO", "LEV", "NUM", "DEU", "JOS", "JDG", "RUT", "1SA", "2SA", "1KI", "2KI", "1CH", "2CH", "EZR",
            "NEH", "EST", "JOB", "PSA", "PRO", "ECC", "SOL", "ISA", "JER", "LAM", "EZE", "DAN", "HOS", "JOE", "AMO", "OBA",
            "JON", "MIC", "NAH", "HAB", "ZEP", "HAG", "ZEC", "MAL")
        val nt = listOf("MAT", "MRK", "LUK", "JHN", "ACT", "ROM", "1CO", "2CO", "GAL", "EPH", "PHP", "COL", "1TH", "2TH",
            "1TI", "2TI", "TIT", "PHM", "HEB", "JAS", "1PE", "2PE", "1JN", "2JN", "3JN", "JUD", "REV")
        val text = (ot + listOf("TOB", "SIR", "1MA") + nt).joinToString("\n") { "$it 1:1 Texto de $it." }
        val b = Bible.fromVpl(text, "Libre")
        assertEquals(66, b.books.size)
        assertEquals("Texto de JHN.", b.lookup("Juan 1:1").slides.single().text)
        assertEquals("Texto de MAL.", b.lookup("Malaquías 1:1").slides.single().text)

        // Solo el Nuevo Testamento: sirve, y Mateo queda en el Nuevo Testamento.
        val onlyNt = Bible.fromVpl(nt.joinToString("\n") { "$it 1:1 x" }, "NT")
        assertEquals(27, onlyNt.books.size)
        assertEquals("Mateo", onlyNt.books.first().name)
        assertEquals(emptySet(), oldTestamentIndices(onlyNt.books.map { it.name }))
        assertEquals(setOf(0), oldTestamentIndices(listOf("Salmos", "Juan", "Romanos")))
    }

    @Test
    fun loadsZefaniaXml() {
        val books = (1..66).joinToString("") { n ->
            """<BIBLEBOOK bnumber="$n" bname="L$n"><CHAPTER cnumber="1"><VERS vnumber="1">Uno &amp; dos</VERS>
               <VERS vnumber="2">Con <STYLE css="x">estilo</STYLE><NOTE>nota que no va</NOTE> y &#233;nfasis</VERS></CHAPTER></BIBLEBOOK>"""
        }
        val extra = """<BIBLEBOOK bnumber="67"><CHAPTER cnumber="1"><VERS vnumber="1">Tobías</VERS></CHAPTER></BIBLEBOOK>"""
        val b = Bible.fromZefania("""<?xml version="1.0"?><XMLBIBLE biblename="Prueba">$books$extra</XMLBIBLE>""", "Z")
        assertEquals(66, b.books.size)
        assertEquals("Juan", b.books[42].name)
        assertEquals("Uno & dos", b.lookup("Juan 1:1").slides.single().text)
        assertEquals("Con estilo y énfasis", b.lookup("Apocalipsis 1:2").slides.single().text)
        assertFailsWith<IllegalArgumentException> { Bible.fromZefania("<XMLBIBLE></XMLBIBLE>", "vacía") }
    }
}
