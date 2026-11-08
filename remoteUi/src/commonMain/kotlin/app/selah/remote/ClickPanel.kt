package app.selah.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.selah.shared.ClickState
import app.selah.shared.ClientMessage
import kotlin.math.floor

/**
 * Click de los músicos: el baterista lo inicia; todos ven el pulso, y quien tenga audífonos (con cable)
 * lo escucha en su celular. También la letra automática y "grabar tiempos".
 */
@Composable
fun ClickPanel(click: ClickState?, serverOffset: () -> Long, send: (ClientMessage) -> Unit, clockRtt: () -> Long? = { null }) {
    var open by rememberSaveable { mutableStateOf(false) }
    var listening by rememberSaveable { mutableStateOf(false) }
    var tempo by rememberSaveable { mutableStateOf("") }
    val player = remember { ClickPlayer() }
    DisposableEffect(Unit) { onDispose { player.release() } }
    // El reloj del PC se afina con el tiempo: se le pasa al sonido de nuevo cada par de segundos.
    LaunchedEffect(click, listening) {
        while (true) {
            player.update(if (listening) click else null, serverOffset())
            kotlinx.coroutines.delay(2_000)
        }
    }

    if (click == null && !open) {
        TextButton({ open = true }, Modifier.fillMaxWidth()) { Text("♩ Click para los músicos") }
        return
    }
    Card(Modifier.fillMaxWidth().padding(bottom = 8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (click == null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        tempo, { v -> tempo = v.filter { it.isDigit() }.take(3) },
                        label = { Text("BPM") }, placeholder = { Text("de la canción") }, singleLine = true,
                        modifier = Modifier.width(130.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    Button({ send(ClientMessage.StartClick(bpm = tempo.toIntOrNull() ?: 0)) }, Modifier.weight(1f).height(56.dp)) { Text("▶ Iniciar click", fontSize = 17.sp) }
                    TextButton({ open = false }) { Text("✕") }
                }
                Text(
                    "Lo inicia el baterista: suena una cuenta y arranca en todos los celulares a la vez. Escúchalo con audífonos " +
                        "con cable (los Bluetooth se atrasan). En el computador no suena: nunca sale por los parlantes.",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BeatPulse(click, serverOffset)
                    Text("♩ ${click.bpm}", fontWeight = FontWeight.Bold, fontSize = 20.sp, maxLines = 1, softWrap = false)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(listening, { listening = !listening }, { Text(if (listening) "🎧 Sonando aquí" else "🎧 Escuchar aquí", maxLines = 1) }, Modifier.weight(1f))
                    OutlinedButton({ send(ClientMessage.SetTempo(click.bpm - 1)) }, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("−", fontSize = 18.sp) }
                    OutlinedButton({ send(ClientMessage.SetTempo(click.bpm + 1)) }, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("+", fontSize = 18.sp) }
                    Button({ send(ClientMessage.StopClick) }, contentPadding = PaddingValues(horizontal = 14.dp)) { Text("■ Parar") }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    when {
                        click.recording -> Button({ send(ClientMessage.RecordTiming(false)) }) { Text("● Grabando · Terminar") }
                        click.hasTiming && click.auto -> TextButton({ send(ClientMessage.SetAutoLyrics(false)) }) { Text("Letra automática ✓ · Pausar") }
                        click.hasTiming -> Button({ send(ClientMessage.SetAutoLyrics(true)) }) { Text("▶ Retomar letra automática") }
                        else -> TextButton({ send(ClientMessage.RecordTiming(true)) }) { Text("● Grabar tiempos") }
                    }
                }
                // Qué tan bien está sincronizado este celular con el PC (la mitad de la ida y vuelta más corta).
                val rttState = produceState(clockRtt()) { while (true) { value = clockRtt(); kotlinx.coroutines.delay(1_000) } }
                val rtt = rttState.value
                Text(
                    if (rtt == null) "Sincronizando con el computador…" else "Sincronizado con el computador (±${(rtt + 1) / 2} ms)",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (click.recording) {
                    Text(
                        "Pasa las estrofas a mano como siempre: Selah anota en qué tiempo cambia cada una. La próxima vez la letra se va sola.",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Pulso visual: "Cuenta 2" o "Compás 5 · 3", con un punto que se ilumina en cada tiempo (más fuerte en el 1). */
@Composable
private fun androidx.compose.foundation.layout.RowScope.BeatPulse(click: ClickState, serverOffset: () -> Long) {
    val beat by produceState(0.0, click) {
        while (true) withFrameMillis { value = click.beatAt(nowMillis() + serverOffset()) }
    }
    val whole = floor(beat).toLong()
    val phase = beat - whole // 0 justo en el tiempo, sube hasta 1
    val down = isDownbeat(whole, click.beatsPerBar)
    val glow = (1.0 - phase * 3).coerceIn(0.0, 1.0).toFloat()
    val color = if (down) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
    Box(Modifier.size(28.dp).clip(CircleShape).background(color.copy(alpha = 0.15f + 0.85f * glow)))
    val label = if (whole < 0) "Cuenta ${click.countIn + whole + 1}" else click.barAndBeat(whole).let { (bar, b) -> "Compás $bar · $b" }
    Text(label, Modifier.weight(1f), fontSize = 17.sp, color = if (whole < 0) MaterialTheme.colorScheme.tertiary else Color.Unspecified)
}
