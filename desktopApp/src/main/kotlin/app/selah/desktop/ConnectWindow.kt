package app.selah.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** Matriz del código QR (sin margen extra: el margen blanco lo pone el dibujo). */
fun qrMatrix(text: String): BitMatrix = QRCodeWriter().encode(
    text, BarcodeFormat.QR_CODE, 0, 0,
    mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.CHARACTER_SET to "UTF-8"),
)

/** Código QR dibujado en negro sobre blanco (con margen, como exige el estándar para leerse bien). */
@Composable
fun QrCode(text: String, size: Dp, modifier: Modifier = Modifier) {
    val matrix = remember(text) { qrMatrix(text) }
    Canvas(modifier.size(size).background(Color.White)) {
        val quiet = 2 // módulos de margen blanco
        val cells = matrix.width + quiet * 2
        val cell = this.size.width / cells
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            if (matrix[x, y]) drawRect(Color.Black, Offset((x + quiet) * cell, (y + quiet) * cell), Size(cell + 0.5f, cell + 0.5f))
        }
    }
}

/**
 * "Conectar dispositivos": todo lo necesario para que tablets, celulares, músicos y la transmisión
 * se conecten, sin tener que escribir direcciones (se escanea el QR).
 */
@Composable
fun ConnectWindow(
    addresses: List<String>,
    port: Int,
    pcName: String,
    firewallOk: Boolean?,
    onAllowFirewall: () -> Unit,
    /** Clave para controlar (null = sin clave). */
    pin: String?,
    onNewPin: (digits: Int) -> Unit,
    onPinOff: (Boolean) -> Unit,
    onClose: () -> Unit,
    /** Fuentes NDI encendidas, cuántos las reciben, y prender/apagar (devuelve un aviso si falta NDI). */
    ndiOn: Set<NdiOutput.Kind> = emptySet(),
    ndiReceivers: Map<NdiOutput.Kind, Int> = emptyMap(),
    onNdi: (NdiOutput.Kind, Boolean) -> String? = { _, _ -> null },
    /** Guarda en Anuncios la imagen con el código de la página de la letra; devuelve un mensaje. */
    onQrToAnnouncements: (url: String) -> String = { "" },
    /** Celulares de la congregación siguiendo la letra ahora. */
    congregationViewers: () -> Int = { 0 },
) {
    var selected by remember { mutableStateOf(addresses.firstOrNull()) }
    val host = selected
    Window(onCloseRequest = onClose, title = "Conectar dispositivos", state = rememberFittedWindowState(DpSize(980.dp, 720.dp))) {
        MaterialTheme(colorScheme = darkColorScheme()) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    // 1. Permiso de Windows
                    when (firewallOk) {
                        false -> Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "Windows todavía no deja que otros equipos se conecten a Selah.",
                                    color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f),
                                )
                                Button(onAllowFirewall) { Text("Permitir conexiones") }
                            }
                        }
                        true -> Text("✓ Windows permite las conexiones de la red local.", color = MaterialTheme.colorScheme.primary)
                        null -> Text("Revisando el permiso de Windows…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    if (host == null) {
                        Text(
                            "Este computador no está conectado a ninguna red. Conéctalo al Wi-Fi o al router de la iglesia.",
                            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleMedium,
                        )
                        return@Column
                    }
                    if (addresses.size > 1) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Red:", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            for (a in addresses) FilterChip(a == host, { selected = a }, { Text(a) })
                        }
                    }

                    // 2. Clave
                    PinSection(pin, onNewPin, onPinOff)

                    // 3. App Android
                    Section("Tablet o celular con la app Selah Remoto") {
                        Text(
                            "Solo hay que abrir la app: busca sola este computador ($pcName) y se conecta." +
                                if (pin != null) " La primera vez pide la clave." else " No hay que escribir nada.",
                        )
                    }

                    // 3 y 4. QR
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        QrCard(
                            "Control desde el navegador",
                            "Cualquier celular (Android o iPhone), sin instalar nada: abre la cámara y escanea." +
                                if (pin != null) " El código ya lleva la clave." else "",
                            controlUrl(host, port, pin), Modifier.weight(1f),
                        )
                        QrCard(
                            "Vista de escenario",
                            "Para músicos y predicador: estrofa actual, la que sigue, la hora y tus mensajes.",
                            "http://$host:$port/escenario.html", Modifier.weight(1f),
                        )
                    }

                    // 5. Letra para la congregación
                    if (host != null) CongregationSection("http://$host:$port${Congregation.PATH}", onQrToAnnouncements, congregationViewers)

                    // 6. OBS
                    Section("Transmisión en vivo (OBS)") {
                        Text("En OBS agrega una \"Fuente de navegador\" con esta dirección (fondo transparente, solo la letra):")
                        CopyableUrl("http://$host:$port/obs.html")
                        Text(
                            "Opcional al final: ?bg=1 (franja oscura detrás del texto), ?pos=top o ?pos=center, ?size=60.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // 6. NDI
                    NdiSection(ndiOn, ndiReceivers, onNdi)
                    Text(
                        "Todos los equipos deben estar en el mismo Wi-Fi que este computador. Las redes de \"invitados\" a veces no dejan que los equipos se vean entre sí.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Dirección del control web; con clave, va en el "#" (no viaja a la red ni queda en el historial del servidor). */
fun controlUrl(host: String, port: Int, pin: String?): String = "http://$host:$port" + if (pin != null) "/#pin=$pin" else ""

@Composable
private fun PinSection(pin: String?, onNewPin: (Int) -> Unit, onPinOff: (Boolean) -> Unit) {
    Section("Clave para controlar") {
        if (pin != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(pin.toList().joinToString("  "), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    Text("Sin esta clave, un celular solo puede mirar (vista de escenario, OBS), no cambiar lo que se proyecta.")
                    Text(
                        "Así nadie conectado al Wi-Fi de la iglesia mueve la letra por curiosidad.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ onNewPin(pin.length) }) { Text("Cambiar clave") }
                if (pin.length < 6) OutlinedButton({ onNewPin(6) }) { Text("Clave más segura (6 números)") }
                else TextButton({ onNewPin(4) }) { Text("Volver a 4 números") }
                TextButton({ onPinOff(true) }) { Text("Quitar la clave") }
            }
            Text(
                "Al cambiarla, los dispositivos conectados la vuelven a pedir.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text("Sin clave: cualquier celular conectado a la misma red puede controlar la proyección.", color = MaterialTheme.colorScheme.error)
            Button({ onPinOff(false) }) { Text("Poner clave") }
        }
    }
}

/** Video por la red para OBS, vMix, ATEM… sin captura de pantalla. */
@Composable
private fun NdiSection(on: Set<NdiOutput.Kind>, receivers: Map<NdiOutput.Kind, Int>, onNdi: (NdiOutput.Kind, Boolean) -> String?) {
    var warning by remember { mutableStateOf<String?>(null) }
    Section("Video por la red (NDI®)") {
        Text(
            "Para transmitir o grabar: OBS (con el complemento DistroAV), vMix, Resolume o una consola ATEM reciben la " +
                "imagen de Selah por la red, sin capturar la pantalla. Solo trabaja mientras algún programa la está recibiendo.",
        )
        for (kind in NdiOutput.Kind.entries) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Switch(kind in on, { want -> warning = onNdi(kind, want) }, Modifier.hand())
                Column(Modifier.weight(1f)) {
                    Text(kind.label)
                    val n = receivers[kind] ?: 0
                    Text(
                        when {
                            kind !in on -> "Apagada"
                            n == 0 -> "Encendida: búscala como \"${kind.sourceName}\" (nadie la está recibiendo todavía)"
                            n == 1 -> "Encendida: 1 programa la está recibiendo"
                            else -> "Encendida: $n programas la están recibiendo"
                        },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        warning?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text(
            "Necesita NDI Tools (gratis) instalado en este computador: ${Ndi.TOOLS_URL} · NDI® es una marca registrada de Vizrt NDI AB.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun QrCard(title: String, subtitle: String, url: String, modifier: Modifier) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = modifier) {
        Column(Modifier.padding(16.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box(Modifier.background(Color.White).padding(8.dp)) { QrCode(url, 220.dp) }
            CopyableUrl(url)
        }
    }
}

@Composable
private fun CopyableUrl(url: String) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(url) { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        SelectionContainer(Modifier.width(320.dp)) { Text(url, fontWeight = FontWeight.Medium) }
        TextButton({ clipboard.setText(AnnotatedString(url)); copied = true }) { Text(if (copied) "✓ Copiado" else "Copiar") }
    }
}

/**
 * Letra en los celulares de la congregación: página de solo lectura (no puede controlar nada) con lo que
 * se ve en el videobeam. El código se puede proyectar como anuncio para que la gente lo escanee.
 */
@Composable
private fun CongregationSection(url: String, onQrToAnnouncements: (String) -> String, viewers: () -> Int) {
    var message by remember { mutableStateOf<String?>(null) }
    var count by remember { mutableStateOf(viewers()) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(3000); count = viewers() } }
    Section("Letra en los celulares de la congregación") {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.background(Color.White).padding(8.dp)) { QrCode(url, 180.dp) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Para quien ve poco, se sentó lejos o llegó tarde: la letra grande en su celular. Solo puede mirar " +
                        "(no cambia nada) y solo ve lo que está en el videobeam, no tus mensajes al escenario.",
                )
                CopyableUrl(url)
                Button({ message = onQrToAnnouncements(url) }) { Text("Poner este código en Anuncios (para proyectarlo)") }
                message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                if (count > 0) Text("Siguiendo la letra ahora: $count", color = MaterialTheme.colorScheme.primary)
                Text(
                    "La congregación tiene que estar en el mismo Wi-Fi que este computador. Si les das una red de invitados, " +
                        "revisa que desde ella se pueda abrir esta dirección.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

