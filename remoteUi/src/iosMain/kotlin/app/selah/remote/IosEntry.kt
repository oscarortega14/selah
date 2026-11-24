package app.selah.remote

import androidx.compose.ui.window.ComposeUIViewController
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import platform.UIKit.UIViewController

/*
 * Puerta de entrada para Swift (iosApp/). En Swift se llaman:
 *   IosEntryKt.MainViewController()      → la pantalla de la app
 *   IosEntryKt.openUrl(url: "selah://connect?host=…")  → enlace del QR / control web
 */

private object App {
    val store = IosStore()
    val linkHost = MutableStateFlow<String?>(null)
    val client by lazy { RemoteClient(MainScope()).also { it.useStore(store) } }
}

fun MainViewController(): UIViewController {
    // Capturas para la App Store en el simulador: SIMCTL_CHILD_SELAH_DEMO=1 abre directo la demostración.
    if (platform.Foundation.NSProcessInfo.processInfo.environment["SELAH_DEMO"] != null) {
        App.store.put("host" to "demo", "name" to "Demostración")
        App.client.startDemo()
    }
    return ComposeUIViewController { SelahRemoto(App.client, App.store, App.linkHost) }
}

/** Enlace selah://connect?host=…&pin=… abierto desde Safari o la cámara (QR). */
fun openUrl(url: String) {
    val link = parseConnectLink(url) ?: return
    App.linkHost.value = App.store.acceptLink(link)
}
