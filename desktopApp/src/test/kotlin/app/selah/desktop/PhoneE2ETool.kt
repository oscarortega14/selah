package app.selah.desktop

import app.selah.shared.Role
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import kotlin.test.Test

/**
 * Servidor de Selah sin ventanas para probar la app del celular de punta a punta (emulador o iPad), sin cerrar
 * el Selah que esté abierto: escucha en otro puerto y aprueba como músico a cada celular nuevo que entre.
 * Usa una COPIA de una carpeta Selah:
 *   SELAH_E2E_DIR=/copia/Selah SELAH_E2E_PORT=8785 SELAH_E2E_SECONDS=900 ./gradlew :desktopApp:test --tests '*PhoneE2ETool*'
 * Con el emulador de Android: adb reverse tcp:8765 tcp:8785 y en la app la dirección 127.0.0.1.
 */
class PhoneE2ETool {
    @Test
    fun serve() {
        val dir = System.getenv("SELAH_E2E_DIR")?.let(::File) ?: return
        val port = System.getenv("SELAH_E2E_PORT")?.toIntOrNull() ?: 8785
        val seconds = System.getenv("SELAH_E2E_SECONDS")?.toLongOrNull() ?: 900
        val library = Library(dir).also { it.reload() }
        val music = MusicLibrary(dir).also { it.refresh(library.catalog.value.songs) }
        val team = TeamStore(File(dir, "equipo.json"))
        val commands = Commands.create(library)
        val server = RemoteServer(commands, library, port, pin = MutableStateFlow("4334"), church = MutableStateFlow(ChurchFeatures.ALL), music = music, team = team)
        server.start()
        println("E2E: Selah escuchando en el puerto $port")
        val end = System.currentTimeMillis() + seconds * 1000
        while (System.currentTimeMillis() < end) {
            for (p in team.pending.value) {
                team.approve(p.idHash, Role.MUSICIAN, "Piano")
                println("E2E: aprobado como músico: ${p.name}")
            }
            Thread.sleep(500)
        }
        server.stop()
    }
}
