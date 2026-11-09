package app.selah.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.selah.shared.ClickState
import app.selah.shared.ClientMessage
import kotlin.math.floor

/**
 * Click de los músicos en el PC: iniciarlo, cambiar el tempo, la letra automática y grabar tiempos.
 * Aquí NO suena (para que nunca salga por los parlantes): suena en los celulares con audífonos.
 */
@Composable
fun ClickBar(
    click: ClickState?,
    isSong: Boolean,
    send: (ClientMessage) -> Unit,
    church: ChurchFeatures = ChurchFeatures.ALL,
    /** La canción en pantalla tiene secuencia (y las secuencias están encendidas). */
    hasSequence: Boolean = false,
) {
    var tempo by remember { mutableStateOf("") }
    if (click == null) {
        if (!isSong || (!church.click && !hasSequence)) return
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (hasSequence) Button({ send(ClientMessage.StartSequence) }, Modifier.weight(1f).hand()) { Text("▶ Secuencia") }
            if (church.click) {
                OutlinedButton({ send(ClientMessage.StartClick(bpm = tempo.toIntOrNull() ?: 0)) }, Modifier.weight(1f).hand()) { Text("♩ Click para los músicos") }
                OutlinedTextField(tempo, { v -> tempo = v.filter { it.isDigit() }.take(3) }, placeholder = { Text("BPM") }, singleLine = true, modifier = Modifier.width(80.dp).marksTyping())
            }
        }
        return
    }
    val beat by produceState(0.0, click) { while (true) withFrameMillis { value = click.beatAt(System.currentTimeMillis()) } }
    val whole = floor(beat).toLong()
    val glow = (1.0 - (beat - whole) * 3).coerceIn(0.0, 1.0).toFloat()
    val down = Math.floorMod(whole, click.beatsPerBar.toLong()) == 0L
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val color = if (down) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
            Box(Modifier.size(18.dp).clip(CircleShape).background(color.copy(alpha = 0.15f + 0.85f * glow)))
            val where = when {
                !click.audible -> sequenceClock(beat)
                whole < 0 -> "cuenta"
                else -> click.barAndBeat(whole).let { (b, t) -> "compás $b · $t" }
            }
            val seq = click.sequence
            val head = if (seq != null) "▶ ${seq.substringBeforeLast('.')}" + (if (click.audible) " · ♩ ${click.bpm}" else "") else "♩ ${click.bpm}"
            Text("$head · $where", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, maxLines = 1)
            if (click.sequence == null) {
                TextButton({ send(ClientMessage.SetTempo(click.bpm - 1)) }, Modifier.hand()) { Text("−") }
                TextButton({ send(ClientMessage.SetTempo(click.bpm + 1)) }, Modifier.hand()) { Text("+") }
                TextButton({ send(ClientMessage.StopClick) }, Modifier.hand()) { Text("■") }
            } else {
                Button({ send(ClientMessage.StopSequence) }, Modifier.hand()) { Text("■ Parar") }
            }
        }
        when {
            click.recording -> Button({ send(ClientMessage.RecordTiming(false)) }, Modifier.fillMaxWidth().hand()) { Text("● Grabando tiempos · Terminar") }
            click.hasTiming && click.auto -> TextButton({ send(ClientMessage.SetAutoLyrics(false)) }, Modifier.hand()) { Text("Letra automática ✓ · Pausar") }
            click.hasTiming -> Button({ send(ClientMessage.SetAutoLyrics(true)) }, Modifier.fillMaxWidth().hand()) { Text("▶ Retomar letra automática") }
            else -> TextButton({ send(ClientMessage.RecordTiming(true)) }, Modifier.hand()) { Text("● Grabar tiempos (para la letra automática)") }
        }
    }
}

/** Sin BPM la secuencia cuenta segundos: "1:23". */
internal fun sequenceClock(seconds: Double): String {
    val s = seconds.toLong().coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}
