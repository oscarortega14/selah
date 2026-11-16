package app.selah.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.double
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.io.File

/**
 * Lee la voz guía de una secuencia (canal izquierdo): "Intro", "Verso", "Coro", "Repetir", "Final"… y la cuenta
 * "uno, dos, tres, cuatro". Usa Vosk (reconocimiento de voz sin internet) con una lista cerrada de palabras.
 */
object GuideVoice {
    /** Una palabra de la guía y cuándo se dijo (ms del audio). */
    data class Word(val word: String, val startMs: Int, val endMs: Int)

    /** Las únicas palabras que se buscan (así no confunde la guía con otra cosa). */
    val WORDS = listOf(
        "intro", "verso", "estrofa", "coro", "pre", "puente", "repetir", "repite", "final", "instrumental", "interludio",
        "toda", "la", "banda", "solo", "pregón", "uno", "dos", "tres", "cuatro", "bajar", "subir", "intensidad",
    )

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Solo las funciones de Vosk que se usan. (La clase LibVosk de su paquete pide todas al cargar, y la librería
     * para Mac que trae no tiene una de ellas.)
     */
    @Suppress("FunctionName")
    private interface Vosk : Library {
        fun vosk_set_log_level(level: Int)
        fun vosk_model_new(path: String): Pointer?
        fun vosk_model_free(model: Pointer)
        fun vosk_recognizer_new_grm(model: Pointer, rate: Float, grammar: String): Pointer?
        fun vosk_recognizer_set_words(rec: Pointer, words: Int)
        fun vosk_recognizer_accept_waveform(rec: Pointer, data: ByteArray, length: Int): Int
        fun vosk_recognizer_result(rec: Pointer): String
        fun vosk_recognizer_final_result(rec: Pointer): String
        fun vosk_recognizer_free(rec: Pointer)
    }

    private val vosk: Vosk? by lazy {
        runCatching { Native.load("vosk", Vosk::class.java).also { it.vosk_set_log_level(-1) } }
            .onFailure { Log.warn("No se pudo cargar el reconocimiento de voz: ${it.message}") }.getOrNull()
    }

    /** ¿Se puede leer la voz guía en este equipo? */
    val available: Boolean get() = vosk != null

    /** Escucha el canal izquierdo de [file] con el modelo de [modelDir]. */
    fun read(file: File, modelDir: File): List<Word> {
        val v = vosk ?: throw IllegalStateException("El reconocimiento de voz no funciona en este equipo")
        val model = v.vosk_model_new(modelDir.absolutePath) ?: throw IllegalStateException("No pude abrir el modelo de voz")
        try {
            val grammar = (WORDS + "[unk]").joinToString(",", "[", "]") { "\"$it\"" }
            val rec = v.vosk_recognizer_new_grm(model, 16_000f, grammar) ?: throw IllegalStateException("No pude preparar el reconocimiento")
            try {
                v.vosk_recognizer_set_words(rec, 1)
                val out = mutableListOf<Word>()
                fun collect(text: String) {
                    val result = runCatching { json.parseToJsonElement(text).jsonObject["result"]?.jsonArray }.getOrNull() ?: return
                    for (w in result) {
                        val o = w.jsonObject
                        val word = o["word"]?.jsonPrimitive?.content ?: continue
                        if (word == "[unk]") continue
                        out += Word(word, (o["start"]!!.jsonPrimitive.double * 1000).toInt(), (o["end"]!!.jsonPrimitive.double * 1000).toInt())
                    }
                }
                openPcm(file).use { input ->
                    val rate = input.format.sampleRate.toInt()
                    val ch = input.format.channels
                    val inBuf = ByteArray(rate / 5 * ch * 2) // 200 ms
                    val step = rate / 16_000.0
                    var pos = 0.0 // posición (en muestras de entrada) de la próxima muestra de 16 kHz
                    var consumed = 0L
                    val outBuf = java.io.ByteArrayOutputStream()
                    while (true) {
                        val n = input.readNBytes(inBuf, 0, inBuf.size)
                        if (n <= 0) break
                        val frames = n / (ch * 2)
                        fun left(i: Int) = ((inBuf[i * ch * 2 + 1].toInt() shl 8) or (inBuf[i * ch * 2].toInt() and 0xff))
                        // Pasar a 16 kHz (interpolando) solo el canal izquierdo: ahí está la guía.
                        while (pos < consumed + frames - 1) {
                            val i = (pos - consumed).toInt()
                            val f = pos - consumed - i
                            val v = (left(i) * (1 - f) + left(i + 1) * f).toInt()
                            outBuf.write(v); outBuf.write(v shr 8)
                            pos += step
                        }
                        consumed += frames
                        val bytes = outBuf.toByteArray(); outBuf.reset()
                        if (v.vosk_recognizer_accept_waveform(rec, bytes, bytes.size) != 0) collect(v.vosk_recognizer_result(rec))
                    }
                    collect(v.vosk_recognizer_final_result(rec))
                }
                return out
            } finally {
                v.vosk_recognizer_free(rec)
            }
        } finally {
            v.vosk_model_free(model)
        }
    }
}
