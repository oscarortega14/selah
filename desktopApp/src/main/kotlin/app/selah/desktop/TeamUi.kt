package app.selah.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import app.selah.shared.COMMON_INSTRUMENTS
import app.selah.shared.Permission
import app.selah.shared.Role

/** Qué puede hacer cada papel, en palabras. */
fun Role.description(): String = when (this) {
    Role.OPERATOR -> "Todo (como con la clave)."
    Role.DIRECTOR -> "Click, secuencias y letra automática; mensajes al escenario; ve todas las partituras."
    Role.MUSICIAN -> "Ve su partitura sugerida y las de los demás, acordes, letra y el click. No cambia nada."
    Role.PREACHER -> "Su temporizador y la vista de escenario."
    Role.MEDIA -> "Nombres en pantalla para la transmisión y subir fotos o videos a Anuncios."
    Role.VIEWER -> "Ver lo que se proyecta, nada más."
}

/** Ventana "Equipo": aprobar celulares y darles papel e instrumento. */
@Composable
fun TeamWindow(team: TeamStore, instruments: List<String>, onClose: () -> Unit) {
    Window(icon = appIcon, onCloseRequest = onClose, title = "Equipo", state = rememberFittedWindowState(DpSize(760.dp, 760.dp))) {
        TeamContent(team, instruments)
    }
}

@Composable
fun TeamContent(team: TeamStore, instruments: List<String>) {
    val members by team.team.collectAsState()
    val pending by team.pending.collectAsState()
    val allInstruments = remember(instruments) { (instruments + COMMON_INSTRUMENTS).distinctBy { app.selah.shared.normalizeKey(it) } }
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Cada celular que se conecta pide entrar. Aquí le das su papel: lo que su papel no permite, el computador lo " +
                        "rechaza. Los músicos tienen un instrumento sugerido pero pueden ver todas las partituras. La clave de 4 " +
                        "números sigue sirviendo para entrar como operador.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (pending.isNotEmpty()) {
                    Text("Piden entrar (${pending.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.tertiary)
                    pending.forEach { p -> PendingCard(p, allInstruments, team) }
                    HorizontalDivider()
                }
                Text("En el equipo (${members.members.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (members.members.isEmpty()) Text("Todavía nadie. Cuando alguien abra la app conectada a este computador, aparece arriba.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                members.members.sortedWith(compareBy({ it.role.ordinal }, { it.name.lowercase() })).forEach { m ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(m.name, fontWeight = FontWeight.Bold)
                            Text(m.role.description(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        RolePicker(m.role) { r -> team.update(m.idHash) { it.copy(role = r) } }
                        if (m.role.can(Permission.SCORES)) InstrumentPicker(m.instrument, allInstruments) { i -> team.update(m.idHash) { it.copy(instrument = i) } }
                        TextButton({ team.remove(m.idHash) }, Modifier.hand()) { Text("Quitar") }
                    }
                }
                HorizontalDivider()
                Text("Qué puede cada papel", fontWeight = FontWeight.Bold)
                Role.entries.forEach { r -> Text("• ${r.label}: ${r.description()}", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable
private fun PendingCard(p: PendingDevice, instruments: List<String>, team: TeamStore) {
    var role by remember(p.idHash) { mutableStateOf(Role.MUSICIAN) }
    var instrument by remember(p.idHash) { mutableStateOf<String?>(null) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("«${p.name}» quiere entrar", fontWeight = FontWeight.Bold)
            Text("Desde ${p.address.substringBefore(':')}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Es:")
                RolePicker(role) { role = it }
                if (role.can(Permission.SCORES)) InstrumentPicker(instrument, instruments) { instrument = it }
            }
            Text(role.description(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ team.approve(p.idHash, role, instrument.takeIf { role.can(Permission.SCORES) }) }, Modifier.hand()) { Text("Dejarlo entrar") }
                TextButton({ team.reject(p.idHash) }, Modifier.hand()) { Text("No") }
            }
        }
    }
}

@Composable
private fun RolePicker(role: Role, onPick: (Role) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton({ open = true }, Modifier.hand()) { Text(role.label + " ▾") }
        DropdownMenu(open, { open = false }) {
            Role.entries.forEach { r -> DropdownMenuItem({ Text(r.label) }, { open = false; onPick(r) }) }
        }
    }
}

@Composable
private fun InstrumentPicker(current: String?, options: List<String>, onPick: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton({ open = true }, Modifier.hand()) { Text((current ?: "Instrumento") + " ▾") }
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem({ Text("Sin sugerir") }, { open = false; onPick(null) })
            options.forEach { i -> DropdownMenuItem({ Text(i) }, { open = false; onPick(i) }) }
        }
    }
}
