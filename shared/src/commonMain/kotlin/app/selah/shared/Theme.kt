package app.selah.shared

import kotlinx.serialization.Serializable

/**
 * Cómo se ve la proyección. Se guarda en Selah/tema.json.
 * Colores en formato "#RRGGBB".
 */
@Serializable
data class Theme(
    /** Color de fondo (o color de arriba si hay [color2]). */
    val color: String = "#000000",
    /** Si no es null, degradado vertical de [color] a [color2]. */
    val color2: String? = null,
    /** Imagen de fondo: nombre de archivo dentro de Selah/fondos. Tiene prioridad sobre [builtin]. */
    val image: String? = null,
    /** Fondo incluido en el programa (dibujado, sin archivos), ej. "luz". Ver BUILTINS. */
    val builtin: String? = "luz",
    /** Movimiento suave en los fondos incluidos (destellos, partículas…). Apagar si el PC es lento. */
    val animate: Boolean = true,
    /** Fluidez máxima (60 cuadros/s) en los fondos animados; apagado, 30 (gasta menos en un PC modesto). */
    val smooth: Boolean = true,
    /**
     * Modo liviano para PCs lentos: los videos de fondo a 854 px y 20 cuadros/s, y los fondos
     * animados a 24 cuadros/s en baja resolución. Detrás de la letra casi no se nota.
     */
    val light: Boolean = false,
    /** Fundido corto (unos 300 ms) al cambiar de diapositiva, en vez de un salto. */
    val fade: Boolean = true,
    /** Tipografía de la letra ("Montserrat", "Nunito"…); null = la del sistema. */
    val font: String? = null,
    /** Cuánto oscurecer el fondo de imagen para que se lea la letra (0 = nada, 0.8 = casi negro). */
    val dim: Float = 0.3f,
    val textColor: String = "#FFFFFF",
    /** Multiplica el tamaño automático de la letra (0.6 a 1.4). */
    val fontScale: Float = 1f,
    val shadow: Boolean = true,
    /** Poner la traducción junto a la cita de la Biblia ("Josué 1:9 - NTV"). */
    val bibleVersion: Boolean = true,
    /** Logo de la iglesia para la pantalla de espera: archivo dentro de Selah/fondos. */
    val logo: String? = null,
) {
    companion object {
        /** Fondos de color incluidos (no necesitan archivos). */
        val PRESETS: List<Pair<String, Theme>> = listOf(
            "Negro" to Theme(builtin = null),
            "Azul noche" to Theme(color = "#0B1E3F", color2 = "#1E4A7A", builtin = null),
            "Púrpura" to Theme(color = "#2A0845", color2 = "#6441A5", builtin = null),
            "Atardecer" to Theme(color = "#3A1C71", color2 = "#B24F6E", builtin = null),
            "Bosque" to Theme(color = "#0F2027", color2 = "#2C5364", builtin = null),
            "Vino" to Theme(color = "#2B0A12", color2 = "#6E1423", builtin = null),
        )

        /** Fondos incluidos: clave -> nombre visible. Se dibujan en el escritorio (BuiltinBackgrounds). */
        val BUILTINS: List<Pair<String, String>> = listOf(
            "luz" to "Luz celestial",
            "aurora" to "Aurora",
            "bokeh" to "Bokeh dorado",
            "atardecer" to "Atardecer",
            "noche" to "Noche estrellada",
            "oceano" to "Océano",
            "calvario" to "Calvario",
            "destellos" to "Destellos",
        )

        val TEXT_COLORS: List<Pair<String, String>> = listOf(
            "Blanco" to "#FFFFFF",
            "Crema" to "#FFF4D6",
            "Amarillo" to "#FFE082",
            "Celeste" to "#B3E5FC",
        )

        private val HEX = Regex("^#[0-9a-fA-F]{6}$")
        fun isColor(s: String?) = s != null && HEX.matches(s)
    }

    /** Valores fuera de rango o colores mal escritos vuelven a algo seguro. */
    fun sanitized(): Theme = copy(
        color = if (isColor(color)) color else "#000000",
        color2 = color2?.takeIf { isColor(it) },
        image = image?.takeIf { it.isNotBlank() },
        builtin = builtin?.takeIf { key -> BUILTINS.any { it.first == key } },
        logo = logo?.takeIf { it.isNotBlank() },
        dim = dim.coerceIn(0f, 0.85f),
        textColor = if (isColor(textColor)) textColor else "#FFFFFF",
        fontScale = fontScale.coerceIn(0.6f, 1.4f),
    )
}
