package app.selah.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Nombres reales de secuencias de un ministerio (IC Salem). */
class SequenceNameTest {
    @Test
    fun tempoKeyAndVersionComeOutOfTheName() {
        assertEquals(SequenceName("Aclamad a Dios", 150, "D"), parseSequenceName("Aclamad a Dios - 150 BMP - D .wav"))
        assertEquals(SequenceName("Celebrare", 145, "Dm"), parseSequenceName("Celebrare - 65 - 145 BMP - Dm .wav"))
        assertEquals(SequenceName("El Dios de Jacob", 145, "Am"), parseSequenceName("El Dios de Jacob - 145 BMP - Am.wav"))
        assertEquals(SequenceName("Salmo 23", version = "No Bass"), parseSequenceName("Salmo 23 No Bass.wav"))
        assertEquals(SequenceName("Isaias 54-17", version = "No Bass"), parseSequenceName("Isaias 54-17 No Bass.wav"))
        assertEquals(SequenceName("Su Nombre es Jehova", version = "Strings No Bass"), parseSequenceName("Su Nombre es Jehova Strings No Bass.wav"))
        assertEquals(SequenceName("Que Dios Como Tú", version = "Original Salem & Brass No Bass"), parseSequenceName("Que Dios Como Tú Original Salem & Brass No Bass.wav"))
        assertEquals(SequenceName("ADORAREMOS", version = "BRASS OFF"), parseSequenceName("ADORAREMOS SEC BRASS OFF .wav"))
        assertEquals(SequenceName("CONTIGO ESTARA", version = "BRASS ON"), parseSequenceName("SECUENCIA CONTIGO ESTARA BRASS ON 2.wav"))
        assertEquals(SequenceName("Y AUN SI NO"), parseSequenceName("Y AUN SI NO- SECUENCIA .wav"))
        assertEquals(SequenceName("EN LA SOMBRA DE TUS ALAS", version = "GUITAR ON"), parseSequenceName("EN LA SOMBRA DE TUS ALAS GUITAR ON  2.wav"))
        assertEquals(SequenceName("CUARTO VARÓN"), parseSequenceName("CUARTO VARÓN SECUENCIA_.m4a"))
        assertEquals(SequenceName("Yo cantare"), parseSequenceName("Yo cantare Secuencia final  2.wav"))
    }

    private val songs = listOf("Aclamad a Dios", "Salmo 2:7", "Gracias", "Contigo estará", "Y aún si no", "Adoraremos al Padre", "Su Nombre es Jehová")
        .map { SongSummary(it, it) }

    @Test
    fun matchingIgnoresAccentsPunctuationAndAsksWhenUnsure() {
        fun match(file: String) = matchSequence(parseSequenceName(file), songs)?.let { it.first.title to it.second }
        assertEquals("Aclamad a Dios" to true, match("Aclamad a Dios - 150 BMP - D .wav"))
        assertEquals("Salmo 2:7" to true, match("Salmo 2.7 No Bass.wav"))
        assertEquals("Contigo estará" to true, match("CONTIGO ESTARÁ .mp3")) // tilde como carácter aparte
        assertEquals("Y aún si no" to true, match("Y Aún Si No - 142 BMP - Gm.wav"))
        assertEquals("Su Nombre es Jehová" to true, match("Su Nombre es Jehova Strings No Bass.wav"))
        // Solo coincide el comienzo: hay que confirmarlo
        assertEquals("Gracias" to false, match("Gracias Señor Jesus No Bass.wav"))
        assertEquals("Adoraremos al Padre" to false, match("ADORAREMOS SEC BRASS OFF .wav"))
        assertNull(match("KADOSH SECUENCIA OK .wav"))
    }
}
