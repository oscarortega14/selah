package app.selah.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BibleVersionTest {
    // Génesis 1: el 1 trae unidos el 1 al 3 (como la Lenguaje Sencillo); el 5 trae el 6 (como Hechos 19:40-41 en la DHH).
    private val joined = Bible(
        "Biblia en Lenguaje Sencillo",
        listOf(Bible.Book("Génesis", listOf(listOf("Uno, dos y tres.", "", "", "Cuatro.", "Cinco.", "")))),
    )
    private val plain = Bible(
        "Reina-Valera 1960",
        listOf(Bible.Book("Génesis", listOf(listOf("Uno.", "Dos.", "Tres.", "Cuatro.", "Cinco.")))),
    )

    @Test
    fun aVerseJoinedIntoAnotherShowsTheOneThatHasIt() {
        val p = joined.lookup("Génesis 1:2")
        assertEquals(listOf("Génesis 1:1-3"), p.slides.map { it.label })
        assertEquals("Uno, dos y tres.", p.slides.single().text)
        assertEquals("Génesis 1:1-3", p.title)
    }

    @Test
    fun rangesLabelJoinedVerses() {
        val p = joined.lookup("Génesis 1:1-4")
        assertEquals(listOf("Génesis 1:1-3", "Génesis 1:4"), p.slides.map { it.label })
        // Empieza en uno unido: arranca desde el versículo que lo trae.
        val q = joined.lookup("Génesis 1:3-4")
        assertEquals(listOf("Génesis 1:1-3", "Génesis 1:4"), q.slides.map { it.label })
        assertEquals("Génesis 1:1-4", q.title)
    }

    @Test
    fun theLastVerseCanBeJoinedToo() {
        assertEquals(listOf("Génesis 1:5-6"), joined.lookup("Génesis 1:6").slides.map { it.label })
        assertEquals("Génesis 1:5-6", joined.lookup("Génesis 1:5").title)
        // Vacío al comienzo del capítulo: no hay versículo que lo traiga.
        assertFailsWith<IllegalArgumentException> { Bible("x", listOf(Bible.Book("Rut", listOf(listOf("", "Dos."))))).lookup("Rut 1:1") }
    }

    @Test
    fun secondTranslationMatchesJoinedVerses() {
        val other = plain.lookup("Génesis 1:1-4").slides
        assertEquals("Uno. Dos. Tres.", matchingVerses("Génesis 1:1-3", other))
        assertEquals("Cuatro.", matchingVerses("Génesis 1:4", other))
        // Al revés: la primera trae los versículos sueltos y la segunda unidos.
        val joinedOther = joined.lookup("Génesis 1:1-4").slides
        assertEquals("Uno, dos y tres.", matchingVerses("Génesis 1:1", joinedOther))
        assertEquals("Uno, dos y tres.", matchingVerses("Génesis 1:2", joinedOther))
    }

    @Test
    fun abbreviations() {
        assertEquals("RVR1960", bibleAbbreviation("Reina-Valera 1960"))
        assertEquals("NTV", bibleAbbreviation("Nueva Traducción Viviente"))
        assertEquals("BTX", bibleAbbreviation("La Biblia Textual (3.ª ed.)"))
        assertEquals("BJ", bibleAbbreviation("Biblia de Jerusalén (3.ª ed.)"))
        assertEquals("RVA", bibleAbbreviation("Reina-Valera Actualizada 1989"))
        assertEquals("RV1909", bibleAbbreviation("Reina-Valera 1909"))
        assertEquals("VBL", bibleAbbreviation("Versión Biblia Libre"))
        assertEquals("NVI", bibleAbbreviation("NVI"))
        assertEquals("MBN2001", bibleAbbreviation("Mi Biblia Nueva 2001"))
        // Las que suelen exportarse de otros programas
        val expected = mapOf(
            "Reina-Valera 1977" to "RV1977", "Nueva Reina Valera 1990" to "NRV1990", "Reina Valera Contemporánea" to "RVC",
            "La Biblia de las Américas" to "LBLA", "Nueva Biblia de los Hispanos" to "NBLH", "Dios Habla Hoy" to "DHH",
            "Biblia en Lenguaje Sencillo" to "BLS", "La Palabra de Dios para Todos" to "PDT", "Biblia Latinoamericana" to "BL",
        )
        for ((name, abbr) in expected) assertEquals(abbr, bibleAbbreviation(name), name)
    }

    @Test
    fun theVersionGoesNextToVersesOnly() {
        val s = PresentationState(version = "NTV")
        assertEquals("Josué 1:9 - NTV", s.labelWithVersion("Josué 1:9"))
        assertEquals("Génesis 1:1-3 - NTV", s.labelWithVersion("Génesis 1:1-3"))
        assertEquals("Punto 1", s.labelWithVersion("Punto 1"))
        assertEquals("Josué 1:9", PresentationState().labelWithVersion("Josué 1:9"))
    }
}
