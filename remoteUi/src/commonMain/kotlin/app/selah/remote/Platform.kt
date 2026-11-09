package app.selah.remote

import androidx.compose.runtime.Composable
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.utils.io.ByteReadChannel

/*
 * Lo que cambia entre Android e iPhone. Cada sistema lo implementa en androidMain / iosMain.
 */

/** Hora actual en milisegundos (como System.currentTimeMillis). */
expect fun nowMillis(): Long

/** Fecha de hoy en el teléfono: año, mes (1-12), día. */
expect fun today(): Triple<Int, Int, Int>

/** Cliente HTTP con el motor de cada sistema (OkHttp en Android, Darwin en iPhone). */
expect fun platformHttpClient(config: HttpClientConfig<*>.() -> Unit): HttpClient

/**
 * Busca a Selah en la red local con un mensaje UDP a todos (broadcast).
 * En iPhone esto requiere el permiso de Apple "multicast networking"; mientras tanto devuelve
 * una lista vacía y se usa la dirección escrita a mano o el QR.
 */
expect suspend fun discoverServers(timeoutMs: Long): List<FoundServer>

/**
 * Click de los músicos en este celular (con audífonos con cable: los Bluetooth atrasan). Cada sistema genera el
 * sonido y lo programa para que caiga justo en el tiempo del PC: [serverOffset] = hora del PC − hora local.
 * null = callado.
 */
expect class ClickPlayer() {
    fun update(click: app.selah.shared.ClickState?, serverOffset: Long)
    fun release()
}

/**
 * El sonido del click: un "toc" corto (seno con caída rápida). [accent] = primer tiempo del compás (más agudo).
 * Muestras de -1 a 1 a [sampleRate].
 */
fun clickSamples(sampleRate: Int, accent: Boolean): FloatArray {
    val n = sampleRate * 30 / 1000 // 30 ms
    val freq = if (accent) 1760.0 else 1175.0
    return FloatArray(n) { i ->
        val t = i.toDouble() / sampleRate
        (kotlin.math.sin(2 * kotlin.math.PI * freq * t) * kotlin.math.exp(-t * 140) * if (accent) 0.9 else 0.7).toFloat()
    }
}

/** ¿El tiempo [beat] (puede ser negativo: la cuenta) es el primero de su compás? */
fun isDownbeat(beat: Long, beatsPerBar: Int): Boolean = beat.mod(beatsPerBar.toLong()) == 0L

/** Un archivo elegido por el usuario para subir (foto o video). */
class PickedFile(val name: String, val size: Long?, val open: () -> ByteReadChannel)

/**
 * Botón "Subir foto o video": devuelve la función que abre el selector del sistema,
 * o null si este sistema todavía no lo tiene (entonces no se muestra el botón).
 */
@Composable
expect fun rememberMediaPicker(onPicked: (PickedFile) -> Unit): (() -> Unit)?

/** Preferencias guardadas en el teléfono (SharedPreferences / NSUserDefaults). */
interface KeyValueStore {
    fun get(key: String): String?
    fun put(vararg pairs: Pair<String, String>)
}

/**
 * Nombre para subir un anuncio: el que sugiere el sistema (sin carpetas ni extensión) + [extension].
 * "IMG_1234.HEIC" convertida a JPG → "IMG_1234.jpg". Si no hay nombre o es un código (el iPhone
 * entrega "610B91B5-1580-…"), se usa [generic] ("foto", "video"): el PC lo cambia por "Anuncio <fecha>".
 */
fun uploadFileName(suggested: String?, extension: String, generic: String = "anuncio"): String {
    val base = suggested.orEmpty().substringAfterLast('/').substringAfterLast('\\')
        .let { if ('.' in it) it.substringBeforeLast('.') else it }
        .trim()
        .takeUnless { it.isBlank() || UUID_LIKE.matches(it) } ?: generic
    return "$base.${extension.lowercase().removePrefix(".")}"
}

private val UUID_LIKE = Regex("""^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}$""")

/** Imagen (PNG o JPG) para mostrar, o null si no se puede leer. Para las páginas de las partituras. */
expect fun decodeImage(bytes: ByteArray): androidx.compose.ui.graphics.ImageBitmap?
