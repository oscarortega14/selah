package app.selah.desktop

import app.selah.shared.ClientMessage
import java.io.File
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import kotlin.io.path.createTempDirectory
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Secuencias como las reparten los ministerios: nombres con tempo y versión, click a la izquierda. */
class SequenceFilesTest {
    private lateinit var dir: File
    private lateinit var library: Library
    private lateinit var music: MusicLibrary

    @BeforeTest
    fun setUp() {
        dir = createTempDirectory("selah-sec").toFile()
        File(dir, "canciones").mkdirs()
        for (t in listOf("Aclamad a Dios", "Salmo 23", "Gracias", "Que Dios como Tú")) File(dir, "canciones/$t.txt").writeText("# $t\n\nuno\n\ndos\n")
        library = Library(dir).also { it.reload() }
        music = MusicLibrary(dir)
        music.sequencesDir.mkdirs()
    }

    @AfterTest
    fun tearDown() { dir.deleteRecursively() }

    private fun refresh() = music.refresh(library.catalog.value.songs) { id -> library.song(id)?.sequence }

    /** WAV estéreo: a la izquierda un click a [bpm] que empieza en [firstMs]; a la derecha "música" (ruido con tono). */
    private fun clickWav(f: File, bpm: Int, firstMs: Int = 20, seconds: Int = 20, clickLeft: Boolean = true) {
        val rate = 22_050
        val frames = rate * seconds
        val beat = rate * 60.0 / bpm
        val rnd = Random(1)
        val bytes = ByteArray(frames * 4)
        for (i in 0 until frames) {
            val sinceFirst = i - firstMs * rate / 1000
            val k = if (sinceFirst >= 0) (sinceFirst % beat).toInt() else Int.MAX_VALUE
            val click = if (k < rate / 100) (sin(2 * PI * 2000 * k / rate) * 20_000).toInt() else 0
            val musicSample = (sin(2 * PI * 220 * i / rate) * 6_000 + rnd.nextInt(-3_000, 3_000)).toInt()
            val l = if (clickLeft) click else musicSample
            val r = musicSample
            bytes[4 * i] = l.toByte(); bytes[4 * i + 1] = (l shr 8).toByte()
            bytes[4 * i + 2] = r.toByte(); bytes[4 * i + 3] = (r shr 8).toByte()
        }
        AudioSystem.write(AudioInputStream(bytes.inputStream(), AudioFormat(rate.toFloat(), 16, 2, true, false), frames.toLong()), AudioFileFormat.Type.WAVE, f)
    }

    @Test
    fun theClickOnTheLeftIsHeardWithItsTempoAndStart() {
        val f = File(music.sequencesDir, "a.wav").also { clickWav(it, 145, firstMs = 398) }
        val a = SequenceAnalysis.analyze(f)
        assertTrue(a.clickLeft)
        assertEquals(145, a.bpm)
        // 398 ms con tiempos de 414 ms: el primero cae 16 ms antes de donde "debería" (-16)
        assertTrue(abs(a.offsetMs - (398 - 414)) <= 4, "desfase ${a.offsetMs}")
        // Música normal (los dos canales iguales o sin click): no hay click
        val plain = File(music.sequencesDir, "b.wav").also { clickWav(it, 120, clickLeft = false) }
        assertFalse(SequenceAnalysis.analyze(plain).clickLeft)
        assertFalse(SequenceAnalysis.analyze(File(dir, "no-existe.wav")).clickLeft)
    }

    @Test
    fun versionsDoubtsAndOrphans() {
        for (n in listOf("Aclamad a Dios - 150 BMP - D .wav", "Salmo 23 No Bass.wav", "Salmo 23 Strings No Bass.wav",
            "Gracias Señor Jesus No Bass.wav", "Que Dios Como Tú Brass & Pads No Bass.wav", "KADOSH SECUENCIA OK .wav")) {
            File(music.sequencesDir, n).writeText("x")
        }
        refresh()
        var cat = music.catalog.value
        assertEquals("Aclamad a Dios - 150 BMP - D .wav", cat.sequences["Aclamad a Dios"])
        assertEquals("Salmo 23 No Bass.wav", cat.sequences["Salmo 23"])
        assertEquals(listOf("Salmo 23 No Bass.wav", "Salmo 23 Strings No Bass.wav"), cat.versionsOf("Salmo 23").map { it.file })
        assertEquals("Que Dios Como Tú Brass & Pads No Bass.wav", cat.sequences["Que Dios como Tú"])
        // "Gracias Señor Jesús" no suena con "Gracias" hasta que alguien lo confirme
        assertNull(cat.sequences["Gracias"])
        assertEquals(listOf("Gracias Señor Jesus No Bass.wav"), cat.toConfirm.map { it.file })
        assertEquals(listOf("KADOSH SECUENCIA OK .wav"), cat.sequenceOrphans)

        // Confirmar, cambiar de versión, asignar a mano y quitar: queda en la canción ("Secuencia:")
        library.setSongSequence("Gracias", "Gracias Señor Jesus No Bass.wav")
        library.setSongSequence("Salmo 23", "Salmo 23 Strings No Bass.wav")
        library.setSongSequence("Aclamad a Dios", MusicLibrary.NONE)
        library.setSongSequence("Que Dios como Tú", "KADOSH SECUENCIA OK .wav")
        refresh()
        cat = music.catalog.value
        assertEquals("Gracias Señor Jesus No Bass.wav", cat.sequences["Gracias"])
        assertTrue(cat.toConfirm.isEmpty())
        assertEquals("Salmo 23 Strings No Bass.wav", cat.sequences["Salmo 23"])
        assertNull(cat.sequences["Aclamad a Dios"])
        assertEquals("KADOSH SECUENCIA OK .wav", cat.sequences["Que Dios como Tú"])
        assertTrue(cat.sequenceOrphans.isEmpty())
        assertTrue("Secuencia: Salmo 23 Strings No Bass.wav" in File(dir, "canciones/Salmo 23.txt").readText())
        // La letra sigue intacta
        assertEquals(listOf("uno", "dos"), library.song("Salmo 23")!!.slides.map { it.text })
    }

    @Test
    fun aSequenceWithItsOwnClickPlaysAsIsWithTheTempoOfItsName() {
        clickWav(File(music.sequencesDir, "Aclamad a Dios - 150 BMP - D .wav"), 150, firstMs = 20)
        refresh()
        val output = MusiciansTest.FakeOutput()
        var now = 1_000_000L
        val presenter = Presenter(library) { now }
        presenter.sequences = SequenceHookup({ music.sourceFor(it) }, output, { ChurchFeatures.ALL.copy(splitClick = true) })
        presenter.handle(ClientMessage.SelectSong("Aclamad a Dios"))
        assertNull(presenter.handle(ClientMessage.StartSequence))
        val plan = output.played.single().second
        assertEquals(150, plan.bpm) // del nombre: no hizo falta escribir "BPM:"
        assertFalse(plan.splitClick) // ya trae el click: no se mezcla otro
        assertEquals(0, plan.countIn)
        val click = presenter.state.value.click!!
        assertTrue(click.audible)
        // El click de los celulares cae donde suena el del audio (20 ms después del inicio)
        output.listener!!.musicStartsAt(now + 500)
        assertTrue(abs(presenter.state.value.click!!.startAt - (now + 520)) <= 4)
    }

    /** WAV como los de Pro Tools/Logic: bloque "JUNK" antes del formato, 24 bits o coma flotante. Java no los abre. */
    private fun rawWav(f: File, format: Int, bits: Int, samples: List<Double>, junk: Boolean = true) {
        val out = java.io.ByteArrayOutputStream()
        fun u32(v: Int) { out.write(v); out.write(v shr 8); out.write(v shr 16); out.write(v shr 24) }
        fun u16(v: Int) { out.write(v); out.write(v shr 8) }
        val bytes = bits / 8
        val data = java.io.ByteArrayOutputStream()
        for (v in samples) for (ch in 0 until 2) {
            when {
                format == 3 -> { val b = java.lang.Float.floatToIntBits(v.toFloat()); for (k in 0 until 4) data.write(b shr (8 * k)) }
                else -> { val i = (v * ((1 shl (bits - 1)) - 1)).toInt(); for (k in 0 until bytes) data.write(i shr (8 * k)) }
            }
        }
        out.write("RIFF".toByteArray()); u32(0); out.write("WAVE".toByteArray())
        if (junk) { out.write("JUNK".toByteArray()); u32(28); out.write(ByteArray(28)) }
        out.write("fmt ".toByteArray()); u32(16); u16(format); u16(2); u32(48_000); u32(48_000 * 2 * bytes); u16(2 * bytes); u16(bits)
        out.write("data".toByteArray()); u32(data.size()); data.writeTo(out)
        f.writeBytes(out.toByteArray())
    }

    @Test
    fun wavFilesThatJavaCannotOpenStillPlay() {
        val values = listOf(0.0, 0.5, -0.5, 0.25)
        for ((name, format, bits) in listOf(Triple("24.wav", 1, 24), Triple("float.wav", 3, 32), Triple("16-junk.wav", 1, 16))) {
            val f = File(dir, name).also { rawWav(it, format, bits, values) }
            openPcm(f).use { input ->
                assertEquals(48_000f, input.format.sampleRate)
                assertEquals(2, input.format.channels)
                assertEquals(16, input.format.sampleSizeInBits)
                val b = input.readAllBytes()
                assertEquals(values.size * 4, b.size, name)
                val left = (0 until values.size).map { i -> ((b[4 * i + 1].toInt() shl 8) or (b[4 * i].toInt() and 0xff)) / 32767.0 }
                values.zip(left).forEach { (want, got) -> assertTrue(abs(want - got) < 0.01, "$name: $want vs $got") }
            }
            assertNull(JavaSoundSequenceOutput().check(f), name)
        }
        File(dir, "malo.wav").writeText("RIFF....WAVEnada")
        assertTrue(JavaSoundSequenceOutput().check(File(dir, "malo.wav"))!!.contains("MP3 o WAV"))
    }

    @Test
    fun whenItPlaysOnAnotherDeviceSelahOnlyFollowsIt() {
        clickWav(File(music.sequencesDir, "Aclamad a Dios - 150 BMP - D .wav"), 150, firstMs = 20)
        File(dir, "canciones/Aclamad a Dios.txt").writeText("# Aclamad a Dios\nTiempos: 0 8\n\nuno\n\ndos\n")
        library.reload()
        refresh()
        val speakers = MusiciansTest.FakeOutput()
        val silent = MusiciansTest.FakeOutput()
        var now = 1_000_000L
        var church = ChurchFeatures.ALL.copy(sequencePlays = false, splitClick = true)
        val presenter = Presenter(library) { now }
        presenter.sequences = SequenceHookup({ music.sourceFor(it) }, speakers, { church }, silent)
        presenter.handle(ClientMessage.SelectSong("Aclamad a Dios"))
        assertNull(presenter.handle(ClientMessage.StartSequence))
        assertTrue(speakers.played.isEmpty()) // no suena nada en el PC
        val plan = silent.played.single().second
        assertFalse(plan.splitClick)
        assertTrue(plan.durationMs in 19_900..20_100, "duración ${plan.durationMs}")
        val c = presenter.state.value.click!!
        assertTrue(c.following)
        // Arranca en el momento del "▶" (más lo que tarda el primer click del audio)
        assertTrue(abs(c.startAt - (now + 20)) <= 4)
        // La letra va adelantada: se corre 100 ms más tarde
        assertNull(presenter.handle(ClientMessage.SequenceNudge(100)))
        assertEquals(c.startAt + 100, presenter.state.value.click!!.startAt)
        // La letra sigue el tiempo igual que si sonara
        now = presenter.state.value.click!!.startAt + 8 * 400
        presenter.tick()
        assertEquals(1, presenter.state.value.index)
        // Si suena en el PC no hace falta correrla
        presenter.handle(ClientMessage.StopSequence)
        church = church.copy(sequencePlays = true)
        presenter.handle(ClientMessage.StartSequence)
        assertEquals(1, speakers.played.size)
        assertTrue(presenter.handle(ClientMessage.SequenceNudge(100))!!.contains("no hace falta"))
    }
}
