package app.selah.desktop

import app.selah.shared.ProtocolJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import java.io.File

/** Ajustes del programa (no del tema): se guardan en Selah/ajustes.json. */
@Serializable
data class Settings(
    /** Pantalla de la proyección: id de la pantalla, [WINDOWED], o null = automático (la segunda pantalla). */
    val projectorScreen: String? = null,
    /** Nombre de la Biblia elegida (null = la primera disponible). */
    val bible: String? = null,
    /** Segunda traducción debajo de la primera (null = ninguna). */
    val secondBible: String? = null,
    /** Ya se mostró la guía de bienvenida. */
    val welcomeDone: Boolean = false,
    /** Versión nueva que el usuario pidió no volver a avisar. */
    val skippedUpdate: String? = null,
    /** Clave de 4 números para controlar desde celulares y tablets (null = todavía no se creó). */
    val pin: String? = null,
    /** El operador quitó la clave a propósito: cualquiera en la red puede controlar. */
    val pinOff: Boolean = false,
    /** Último respaldo automático o manual (milisegundos). */
    val lastBackup: Long = 0,
    /** Carpeta extra donde copiar cada respaldo (memoria USB, Google Drive…), o null. */
    val backupCopyDir: String? = null,
    /** Fuentes NDI encendidas (nombres de NdiOutput.Kind). */
    val ndi: Set<String> = emptySet(),
    /** "¿Qué usa tu iglesia?": lo que está apagado no aparece ni en el PC ni en los celulares. */
    val church: ChurchFeatures = ChurchFeatures(),
) {
    /** La clave que se exige ahora (null = no se pide). */
    val activePin: String? get() = pin?.takeIf { !pinOff }

    companion object {
        const val WINDOWED = "ventana"

        /** Clave al azar de [digits] números (4 o 6), sin cero al inicio. */
        fun randomPin(digits: Int = 4): String {
            val low = if (digits >= 6) 100_000 else 1_000
            return (java.security.SecureRandom().nextInt(low * 9) + low).toString()
        }
    }
}

/** Lo que usa cada iglesia además de letra y Biblia (que van siempre). */
@Serializable
data class ChurchFeatures(
    /** Click para los músicos y letra automática. */
    val click: Boolean = false,
    /** Secuencias: el PC reproduce el audio de la canción y la letra lo sigue. */
    val sequences: Boolean = false,
    /** Partituras por instrumento en el celular de cada músico. */
    val scores: Boolean = false,
    /** Acordes en el celular de los músicos (y cambiar el tono). */
    val chords: Boolean = false,
    /** Letra en el celular de la congregación (código QR). */
    val congregation: Boolean = true,
    /** Salida de sonido de las secuencias (nombre del dispositivo; null = la del sistema). */
    val audioDevice: String? = null,
    /** Click a la izquierda y música a la derecha (para mandar cada uno a un canal de la consola). */
    val splitClick: Boolean = false,
) {
    companion object {
        /** Todo encendido (las pruebas y quien quiera verlo todo). */
        val ALL = ChurchFeatures(click = true, sequences = true, scores = true, chords = true, congregation = true)
    }
}

class SettingsStore(private val file: File) {
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<Settings> = _settings.asStateFlow()
    val value: Settings get() = _settings.value

    private val _pin = MutableStateFlow(_settings.value.activePin)
    /** Clave en uso (para el servidor de los remotos). */
    val pin: StateFlow<String?> = _pin.asStateFlow()

    private val _church = MutableStateFlow(_settings.value.church)
    /** "¿Qué usa tu iglesia?" (para el servidor y el presentador). */
    val church: StateFlow<ChurchFeatures> = _church.asStateFlow()

    fun update(f: (Settings) -> Settings) {
        val new = f(_settings.value)
        if (new == _settings.value) return
        _settings.value = new
        _pin.value = new.activePin
        _church.value = new.church
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(ProtocolJson.encodeToString(Settings.serializer(), new), Charsets.UTF_8)
        }
    }

    private fun load(): Settings = runCatching {
        ProtocolJson.decodeFromString(Settings.serializer(), file.readText(Charsets.UTF_8))
    }.getOrDefault(Settings())
}
