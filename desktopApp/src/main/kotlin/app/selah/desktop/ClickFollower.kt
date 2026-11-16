package app.selah.desktop

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine
import kotlin.math.abs

/**
 * Sigue una secuencia que suena en otro aparato escuchando su click (por una entrada de audio del PC).
 * Conoce dónde cae cada golpe del click en el archivo ([fileClicks], ms). Al oír los primeros golpes con las mismas
 * distancias que los primeros del archivo, sabe a qué hora empezó ([onAnchor]: hora del PC del ms 0 del archivo); luego
 * compara cada golpe con el que le toca y se va corrigiendo ([onAdjust]).
 */
class ClickFollower(
    private val fileClicks: List<Int>,
    private val onAnchor: (fileZeroWall: Long) -> Unit,
    private val onAdjust: (fileZeroWall: Long) -> Unit,
) {
    private var fileZero: Long? = null
    private val recent = ArrayDeque<Long>()
    private val errors = mutableListOf<Long>()

    // Detección de golpes: el envolvente de lo agudo cada 2 ms, con un umbral que se adapta al volumen.
    private var lastSample = 0
    private val peaks = ArrayDeque<Int>() // máximo de cada 100 ms, los últimos 5 s
    private var blockMax = 0
    private var blockHops = 0
    private var lastOnset = Long.MIN_VALUE / 2

    val anchored: Boolean get() = fileZero != null

    /** Muestras (mono, 16 bits) que empezaron a sonar a la hora [wallOfFirst] del PC. */
    fun feed(samples: ShortArray, count: Int, rate: Int, wallOfFirst: Long) {
        val hop = (rate / 500).coerceAtLeast(1)
        var i = 0
        while (i < count) {
            var m = 0
            val end = minOf(i + hop, count)
            for (k in i until end) {
                val v = samples[k].toInt()
                val d = abs(v - lastSample)
                lastSample = v
                if (d > m) m = d
            }
            val t = wallOfFirst + i * 1000L / rate
            onHop(m, t)
            i = end
        }
    }

    private fun onHop(value: Int, t: Long) {
        blockMax = maxOf(blockMax, value)
        if (++blockHops >= 50) { // 100 ms
            peaks.addLast(blockMax); if (peaks.size > 50) peaks.removeFirst()
            blockMax = 0; blockHops = 0
        }
        // Umbral: 30 % de los golpes fuertes recientes (sin contar el más fuerte de todos), y nunca puro ruido.
        val loud = peaks.sortedDescending().let { it.getOrNull(2) ?: it.firstOrNull() ?: value }
        val threshold = maxOf(MIN_LEVEL, (maxOf(loud, value) * 0.3).toInt())
        if (value > threshold && t - lastOnset > 150) {
            lastOnset = t
            onOnset(t)
        }
    }

    /** Un golpe que se oyó a la hora [t] del PC. */
    fun onOnset(t: Long) {
        val zero = fileZero
        if (zero == null) {
            recent.addLast(t)
            while (recent.size > 8) recent.removeFirst()
            tryAnchor()
            return
        }
        // Ya va siguiendo: ¿qué tan lejos cayó del golpe del archivo que le toca?
        val f = t - zero
        val idx = fileClicks.binarySearch(f.toInt()).let { if (it < 0) -it - 1 else it }
        val near = listOfNotNull(fileClicks.getOrNull(idx - 1), fileClicks.getOrNull(idx)).minByOrNull { abs(it - f) } ?: return
        val err = f - near
        if (abs(err) <= MAX_ERROR_MS) errors += err
        if (errors.size >= 6) {
            val median = errors.sorted()[errors.size / 2]
            errors.clear()
            if (abs(median) >= 5) {
                fileZero = zero + median
                onAdjust(zero + median)
            }
        }
    }

    /** Los primeros tres golpes del archivo con sus distancias: el primero que se oyó con ese patrón es el comienzo. */
    private fun tryAnchor() {
        if (fileClicks.size < 3 || recent.size < 3) return
        val i1 = fileClicks[1] - fileClicks[0]
        val i2 = fileClicks[2] - fileClicks[1]
        val list = recent.toList()
        // La voz guía mete golpes en medio: se buscan tres (no necesariamente seguidos) con las distancias del click.
        for (a in list.indices) for (b in a + 1 until list.size) {
            if (abs((list[b] - list[a]) - i1) > i1 * 0.06 + 8) continue
            for (c in b + 1 until list.size) {
                if (abs((list[c] - list[b]) - i2) > i2 * 0.06 + 8) continue
                val zero = list[a] - fileClicks[0]
                fileZero = zero
                recent.clear()
                onAnchor(zero)
                return
            }
        }
    }

    companion object {
        /** Más bajo que esto es ruido (en 16 bits). */
        const val MIN_LEVEL = 400
        /** Un golpe más lejos que esto del que le toca no es el click (es la voz guía u otra cosa). */
        const val MAX_ERROR_MS = 80L
    }
}

/** De dónde se escucha el click (el real es una entrada de audio; las pruebas usan uno falso). */
interface ClickInput {
    /** Empieza a escuchar; devuelve un error para mostrar o null. */
    fun start(fileClicks: List<Int>, onAnchor: (Long) -> Unit, onAdjust: (Long) -> Unit): String?
    fun stop()
}

/** Escucha una entrada de audio del PC (la línea de la consola con el click, una interfaz USB…). */
class LineClickInput(private val device: String?) : ClickInput {
    @Volatile private var thread: Thread? = null
    @Volatile private var line: TargetDataLine? = null

    override fun start(fileClicks: List<Int>, onAnchor: (Long) -> Unit, onAdjust: (Long) -> Unit): String? {
        stop()
        val rate = 44_100
        val (l, channels) = open(rate) ?: return "No pude abrir la entrada de audio ${device ?: "del sistema"}"
        line = l
        val follower = ClickFollower(fileClicks, onAnchor, onAdjust)
        thread = Thread({
            val frames = rate / 50 // 20 ms
            val bytes = ByteArray(frames * channels * 2)
            val mono = ShortArray(frames)
            try {
                l.start()
                while (!Thread.currentThread().isInterrupted) {
                    val n = l.read(bytes, 0, bytes.size)
                    if (n <= 0) break
                    val got = n / (channels * 2)
                    // Hora del primer cuadro leído: lo que ya estaba en la entrada esperando también cuenta.
                    val waiting = l.available() / (channels * 2)
                    val wallFirst = System.currentTimeMillis() - (got + waiting) * 1000L / rate
                    for (i in 0 until got) {
                        // Con dos canales se usa el izquierdo (ahí suele venir el click).
                        val o = i * channels * 2
                        mono[i] = ((bytes[o + 1].toInt() shl 8) or (bytes[o].toInt() and 0xff)).toShort()
                    }
                    follower.feed(mono, got, rate, wallFirst)
                }
            } catch (_: Exception) {
            } finally {
                runCatching { l.stop(); l.close() }
            }
        }, "escuchar-click").apply { isDaemon = true; priority = Thread.MAX_PRIORITY; start() }
        return null
    }

    private fun open(rate: Int): Pair<TargetDataLine, Int>? {
        for (ch in listOf(1, 2)) {
            val format = AudioFormat(rate.toFloat(), 16, ch, true, false)
            val info = DataLine.Info(TargetDataLine::class.java, format)
            val l = runCatching {
                val mixer = device?.let { name -> AudioSystem.getMixerInfo().firstOrNull { it.name == name } }
                (if (mixer != null) AudioSystem.getMixer(mixer).getLine(info) else AudioSystem.getLine(info)) as TargetDataLine
            }.getOrNull() ?: continue
            if (runCatching { l.open(format, rate / 25 * ch * 2) }.isSuccess) return l to ch // 40 ms de colchón
        }
        return null
    }

    override fun stop() {
        thread?.interrupt()
        thread = null
        line?.let { runCatching { it.stop(); it.close() } }
        line = null
    }

    companion object {
        /** Entradas de audio que se pueden escuchar. */
        fun devices(): List<String> = runCatching {
            val probe = DataLine.Info(TargetDataLine::class.java, AudioFormat(44_100f, 16, 1, true, false))
            AudioSystem.getMixerInfo().filter { info -> runCatching { AudioSystem.getMixer(info).isLineSupported(probe) }.getOrDefault(false) }
                .map { it.name }.distinct()
        }.getOrDefault(emptyList())
    }
}
