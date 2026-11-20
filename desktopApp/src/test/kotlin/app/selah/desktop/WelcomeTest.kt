package app.selah.desktop

import androidx.compose.ui.use
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WelcomeTest {
    @Test
    fun guideCoversTheMainThings() {
        val all = WIZARD_STEPS.joinToString(" ") { it.title + " " + it.body }
        for (must in listOf("canción", "Biblia", "Lista", "QR", "F5", "Proyección", "Equipo", "Secuencias", "Partituras")) {
            assertTrue(must in all, "la guía debería mencionar $must")
        }
        // Lo que la guía promete existe en los atajos
        val keys = SHORTCUTS.joinToString(" ") { it.first }
        for (k in listOf("C", "B", "N", "F5", "Esc")) assertTrue(k in keys, k)
        // Cada captura existe en el programa
        for (img in WIZARD_STEPS.flatMap { it.images }.distinct()) {
            assertTrue(WizardStep::class.java.classLoader.getResource("guia/$img") != null, "falta la captura $img")
        }
        // Cada función opcional de "¿Qué usa tu iglesia?" se puede elegir en la guía
        var c = ChurchFeatures(congregation = false)
        for (s in WIZARD_STEPS.mapNotNull { it.toggle }) c = s.set(c, true)
        assertEquals(ChurchFeatures.ALL.copy(clickByDirector = true), c) // el asistente no toca quién da el click
    }

    @Test
    fun skippingWarnsUntilTheImportantPagesWereSeen() {
        val w = WizardState()
        assertEquals(WIZARD_IMPORTANT, w.unseenImportant().map { it.key }.toSet())
        w.goToImportant()
        assertEquals("congregacion", w.step.key)
        while (!w.isLast) w.next()
        assertTrue(w.unseenImportant().isEmpty()) // ya las vio: saltar no avisa
        w.back()
        assertEquals("equipo", w.step.key)
        assertEquals("letra y Biblia, click para los músicos, secuencias (en otro aparato), acordes",
            summaryText(ChurchFeatures(click = true, sequences = true, sequencePlays = false, chords = true, congregation = false)))
    }

    @Test
    fun shownOnlyOnFirstRun() {
        val dir = createTempDirectory("selah-bienvenida").toFile()
        val file = java.io.File(dir, "ajustes.json")
        assertFalse(SettingsStore(file).value.welcomeDone) // primera vez: se muestra
        SettingsStore(file).update { it.copy(welcomeDone = true) }
        assertTrue(SettingsStore(file).value.welcomeDone) // después ya no
        dir.deleteRecursively()
    }

    /** SELAH_RENDER_DIR=… para ver la guía (cada página) */
    @Test
    fun renderWelcome() {
        val out = System.getenv("SELAH_RENDER_DIR")?.let { java.io.File(it) } ?: return
        val church = ChurchFeatures(click = true, sequences = true, sequencePlays = false, scores = true)
        for (i in WIZARD_STEPS.indices) {
            val state = WizardState().also { it.go(i) }
            androidx.compose.ui.ImageComposeScene(1000, 700, androidx.compose.ui.unit.Density(1f)) {
                SetupWizardContent(church, {}, {}, state)
            }.use { java.io.File(out, "guia-%02d.png".format(i)).writeBytes(it.render(0).encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.bytes) }
        }
    }
}
