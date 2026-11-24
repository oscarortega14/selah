# Selah Remoto para iPhone / iPad: guía para continuar en la Mac

Este documento tiene **todo el contexto** para seguir creando la app de iPhone desde la Mac
(a mano o con Claude Code: el archivo `CLAUDE.md` de la raíz se carga solo y apunta aquí).

---

> **Nombre nuevo (5 nov 2026):** el proyecto se llamaba *Proyecta* y ahora es **Selah** (bundle
> `app.selah.remote`, framework `SelahRemotoUI`, esquema `selah://` y también `proyecta://`).
> El ícono es un calderón 𝄐 (la "pausa" de la música, como Selah en los Salmos); el SVG está en `desktopApp/icons/`.

## 1. En qué estado está (noviembre 2026, versión 2.4.0)

La app de iPhone **reutiliza la misma interfaz que la de Android**: todas las pantallas viven en el
módulo compartido `remoteUi/` (Compose Multiplatform). Ya está hecho y probado en Android:

| Hecho | Dónde |
|---|---|
| Pantallas (En pantalla, Canciones, Biblia con explorador, Lista, Más), clave, cronómetro, nombre en pantalla… | `remoteUi/src/commonMain/kotlin/app/selah/remote/` |
| Conexión WebSocket con el PC (`RemoteClient`), reconexión, clave guardada por computador | `remoteUi/.../RemoteClient.kt`, `SelahRemoto.kt` |
| Enlaces `selah://connect?host=…&pin=…` (botón "Abrir en la app" del control web) | `parseConnectLink` en `SelahRemoto.kt` |
| Escribir la dirección del PC a mano (si la búsqueda automática no funciona) | `ManualAddress` en `RemoteApp.kt` |
| Avisos cortos ("Agregada a la lista") iguales en ambos sistemas | `Notice.kt` |
| Lo propio de Android (búsqueda UDP, fotos, preferencias) | `remoteUi/src/androidMain/.../Platform.android.kt` |
| **Lo propio del iPhone (sin compilar todavía: se escribió en Linux)** | `remoteUi/src/iosMain/kotlin/app/selah/remote/` |
| Proyecto de Xcode (XcodeGen), app en SwiftUI que muestra la interfaz de Kotlin | `iosApp/project.yml`, `iosApp/SelahRemoto/SelahRemotoApp.swift` |
| Ícono 1024×1024 sin transparencia | `iosApp/SelahRemoto/Assets.xcassets/AppIcon.appiconset/` |
| Pruebas comunes (fecha del próximo domingo, enlaces, clave por computador) | `remoteUi/src/commonTest/` |

**Pendiente en la Mac** (en este orden):

1. ~~Compilar por primera vez `iosMain`~~ **Hecho (5 nov 2026, Xcode 27.2):** compiló sin cambios; las 39
   pruebas comunes pasan en el simulador (`:remoteUi:iosSimulatorArm64Test`, `:shared:iosSimulatorArm64Test`).
2. Probar contra el PC con Selah: **simulador hecho** (iPhone 17 Pro, iOS 26): el enlace `selah://connect`
   conecta con la clave, se eligió una canción, se pasaron diapositivas y Negro.
   **iPhone real hecho** (iPhone 17 Pro Max, firmado con la cuenta individual de pago; el Team ID no va al repo:
   `xcodebuild … -allowProvisioningUpdates DEVELOPMENT_TEAM=<id>`): conecta y controla igual.
   - En Xcode 27 la ventana del simulador se llama **DeviceHub** (`Xcode.app/Contents/Applications/DeviceHub.app`).
   - En el simulador usar la IP de la Mac en el Wi-Fi, no `127.0.0.1` (otro programa puede tener el puerto 8765 local).
   - Aviso del enlazador: `libicu.icudtl_dat.o` (de Compose) está hecho para iOS 17.2 y la app pide 15.0.
     Es solo datos; revisar en un iPhone con iOS 15–17 antes de publicar, o subir el mínimo a 17.
3. ~~Búsqueda automática del PC en iPhone~~ **Hecho con el camino A de §5** (`SubnetScan` en commonMain +
   `discoverServers`/`wifiIpv4` en `Platform.ios.kt`): en un iPhone real encontró en pocos segundos la Mac
   con Selah y el PC con Windows que aún tiene la 2.4 (Proyecta).
4. ~~Subir fotos/videos a Anuncios desde el iPhone~~ **Hecho** (`MediaPicker.ios.kt`, PHPicker; fotos HEIC → JPG,
   videos tal cual). Probado en iPhone real: llegaron un .jpg y un .mov al PC. Build 20401 en TestFlight.
5. Capturas, ficha y envío a App Store Connect / TestFlight (§8).
   **Primer build en TestFlight (5 nov 2026):** app "Selah Remote" creada en App Store Connect (solo iOS);
   build 2.4.0 (20400), solo iPhone, mínimo iOS 17, con `PrivacyInfo.xcprivacy`. Se archivó y subió desde la
   terminal (`xcodebuild archive` + `-exportArchive` con `method=app-store-connect`, `destination=upload`;
   el ExportOptions.plist con el Team ID queda fuera del repo). Cada subida nueva necesita otro
   `CURRENT_PROJECT_VERSION` en `project.yml`. Faltan: capturas 6,9", ficha, video para el revisor.
   **iPad desde la 3.0.1 (30001):** app universal; capturas de iPad 13" con la prueba `SelahRemotoCapturas` (ver CLAUDE.md).

---

## 2. Cómo está armado el proyecto

```
selah/
├── shared/        Kotlin Multiplatform (JVM, Android, iOS): modelos, protocolo, búsqueda, Biblia
├── remoteUi/      Compose Multiplatform (Android + iOS): TODA la app del celular
│   ├── commonMain/   pantallas, RemoteClient, enlaces, avisos
│   ├── androidMain/  OkHttp, UDP, selector de fotos, SharedPreferences
│   └── iosMain/      Darwin (Ktor), NSUserDefaults, IosEntry.kt (puerta para Swift)
├── androidApp/    Solo la Activity de Android + variantes github/play + actualizador (github)
├── iosApp/        Solo la ventana de iPhone (SwiftUI) + project.yml
└── desktopApp/    Programa de Windows (operador, proyección, servidor Ktor, control web)
```

- `settings.gradle.kts` incluye `:remoteUi` donde hay SDK de Android **o en una Mac**; `:androidApp`
  solo con SDK de Android. En la Mac no hace falta el SDK de Android para el iPhone.
- `gradle.properties` tiene `kotlin.native.ignoreDisabledTargets=true`: en Linux/Windows los objetivos
  iOS se saltan sin error.
- Kotlin 2.3.21, Compose Multiplatform 1.10.3, Ktor **cliente 3.1.3** (3.6 trae OkHttp 5, que en Android
  exige compileSdk 37; en iOS se usa el motor Darwin de la misma versión).
- Las funciones de Kotlin de `IosEntry.kt` se ven en Swift como `IosEntryKt.MainViewController()` y
  `IosEntryKt.openUrl(url:)`. El framework se llama `SelahRemotoUI` (estático).

### Protocolo con el PC (no cambia)
- WebSocket `ws://IP:8765/ws`, mensajes JSON `ClientMessage` / `ServerMessage` (`shared/.../Protocol.kt`).
- `GET http://IP:8765/info` → `{"name": "<nombre del PC>"}` (sirve para comprobar una dirección).
- Búsqueda: UDP broadcast a puerto 8766 con `DISCOVERY_REQUEST`; el PC responde `DiscoveryReply`.
- Clave (PIN de 4 o 6 números): `ClientMessage.Login`; el PC bloquea por IP tras varios intentos.
- Subir anuncios: `POST http://IP:8765/anuncios?nombre=…` con cabecera `X-Proyecta-Pin`
  (solo jpg/png/webp/mp4/mov).

---

## 3. Preparar la Mac (una vez)

```bash
# Xcode desde la App Store (16 o más nuevo) y abrirlo una vez para aceptar la licencia
xcode-select --install                 # herramientas de línea de comandos
brew install --cask temurin@21         # Java para correr Gradle (descarga solo el 17 que usa el celular)
brew install xcodegen                  # genera el .xcodeproj a partir de iosApp/project.yml
git clone https://github.com/oscarortega14/selah.git && cd selah
# (o, si ya estaba: git pull)
```

Si Gradle no encuentra Java: `export JAVA_HOME=$(/usr/libexec/java_home -v 21)`
(o el de Android Studio: `/Applications/Android Studio.app/Contents/jbr/Contents/Home`).

## 4. Compilar y correr

```bash
# 1) ¿Compila la parte de iPhone? (lo más probable es que haya que corregir algo en iosMain)
./gradlew :remoteUi:compileKotlinIosSimulatorArm64
./gradlew :remoteUi:linkDebugFrameworkIosSimulatorArm64
./gradlew :remoteUi:iosSimulatorArm64Test      # corre las pruebas comunes en el simulador

# 2) Proyecto de Xcode
cd iosApp && xcodegen && open SelahRemoto.xcodeproj
```

En Xcode:
1. Target **SelahRemoto → Signing & Capabilities → Team**: elegir el equipo de desarrollador
   (o escribir el Team ID en `DEVELOPMENT_TEAM` de `project.yml` para no repetirlo).
2. Elegir un simulador de iPhone y ▶. La fase "Compilar Kotlin (remoteUi)" llama a
   `./gradlew :remoteUi:embedAndSignAppleFrameworkForXcode` (la primera vez tarda varios minutos).
3. En el simulador: la búsqueda automática aún no funciona → "Escribir la dirección del computador"
   y poner la IP del PC (aparece en Selah → "Conectar tablets y celulares"). El simulador usa la red
   de la Mac, así que la Mac debe estar en el mismo Wi-Fi que el PC.
4. iPhone real: conectarlo por cable, activar **Modo de desarrollador** (Ajustes → Privacidad y
   seguridad), elegirlo en Xcode y ▶. La primera vez iOS pregunta "¿Permitir buscar dispositivos en la
   red local?" → **Permitir** (si se niega, no podrá hablar con el PC: Ajustes → Selah Remoto → Red local).
5. Probar el enlace: en Safari del iPhone abrir el control web `http://IP:8765` y tocar "Abrir en la app"
   (o escribir `selah://connect?host=IP` en Safari).

### Problemas probables al compilar `iosMain` por primera vez
- `platform.Foundation.timeIntervalSince1970`: si no compila el import, usar
  `NSDate().timeIntervalSince1970` sin import (o `kotlin.time.Clock.System.now().toEpochMilliseconds()`).
- `NSCalendar.components(...)`: puede pedir `@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)`.
- Si Xcode no encuentra `SelahRemotoUI`: revisar que exista
  `remoteUi/build/xcode-frameworks/Debug/iphonesimulator*/SelahRemotoUI.framework` tras compilar.
- Versión: `project.yml` tiene `MARKETING_VERSION: "2.4.0"` y `CURRENT_PROJECT_VERSION: "20400"`.
  Mantenerlos iguales a `selah.version` de `gradle.properties` (2.4.0 → 20400, como Android).

---

## 5. Búsqueda automática del PC en iPhone

En Android se envía un broadcast UDP a 255.255.255.255:8766. En iOS 14+ **enviar broadcast/multicast
requiere el permiso especial de Apple** `com.apple.developer.networking.multicast`. Por eso
`discoverServers()` en `Platform.ios.kt` devuelve una lista vacía y la persona escribe la dirección.

Dos caminos (se pueden hacer los dos):

**A. Sin pedir nada a Apple: recorrer la subred por HTTP** — **hecho** (5 nov 2026)
- Averiguar la IP propia del iPhone en Wi-Fi (interfaz `en0`) con `getifaddrs` (posix, cinterop).
- Probar `GET http://<prefijo>.1…254:8765/info` en paralelo (≈ 32 a la vez, tiempo límite ≈ 700 ms,
  plugin `HttpTimeout` de Ktor). Los que respondan son PCs con Selah (`DiscoveryReply.name`).
- Solo necesita el permiso de red local que ya está en `Info.plist` (`NSLocalNetworkUsageDescription`).
- Hacer esta lógica en `commonMain` (recibe el prefijo) para poder probarla también en Android, y dejar
  en `iosMain` solo la lectura de la IP.

**B. El permiso de multicast de Apple**
- Pedirlo en https://developer.apple.com/contact/request/networking-multicast (explicar: "la app busca
  en la red local el programa Selah del computador de la iglesia con un broadcast UDP al puerto 8766").
- Cuando lo aprueben: agregar el entitlement al App ID y a un archivo `.entitlements`
  (`project.yml → targets.SelahRemoto.entitlements`), e implementar `discoverServers` con sockets
  posix (`socket`, `setsockopt SO_BROADCAST`, `sendto`, `recvfrom`) o `Network.framework`.

---

## 6. Subir fotos y videos (Anuncios) desde el iPhone

`rememberMediaPicker` en iOS devuelve `null` (el botón no aparece). Para hacerlo:
- Presentar `PHPickerViewController` (fotos y videos) desde el `UIViewController` de Compose
  (`LocalUIViewController.current`).
- Con el resultado, `loadFileRepresentation` → copiar a un archivo temporal → `PickedFile(nombre, tamaño)`
  cuyo `open()` lea el archivo por partes como `ByteReadChannel` (no cargar el video entero en memoria).
- `RemoteClient.uploadAnnouncement(file)` ya hace el resto (es común).
- PHPicker no necesita permiso de fotos; si se usa la cámara hay que agregar `NSCameraUsageDescription`.

---

## 7. Detalles de la app que conviene saber

- La app es siempre oscura (`UIUserInterfaceStyle: Dark`, `darkColorScheme()`).
- La pantalla no se apaga mientras la app está abierta (`isIdleTimerDisabled`), igual que en Android.
- `safeDrawingPadding()` en `SelahRemoto` deja libre la isla dinámica, la barra de inicio y el teclado.
- La clave del PC se guarda en `NSUserDefaults` con prefijo `selah.` y solo se envía al mismo
  computador donde se aceptó (`pinHost`).
- ATS: `NSAllowsLocalNetworking` permite `http://` / `ws://` a la red local. No hay conexiones a internet
  (la versión de iPhone no tiene actualizador propio: se actualiza por la App Store).
- `ITSAppUsesNonExemptEncryption = false`: no hay que responder la pregunta de cifrado en cada envío.

---

## 8. App Store Connect y TestFlight

1. **Identificador**: developer.apple.com → Certificates, IDs & Profiles → Identifiers → App ID
   `app.selah.remote` (el mismo que en Android). Sin capacidades especiales (salvo multicast si lo dan).
2. **App Store Connect → Apps → +**: Nombre "Selah Remoto" (si está tomado: "Selah Remoto Iglesia"),
   idioma principal Español, bundle `app.selah.remote`, SKU `selah-remoto`.
3. **Subir una versión**: en Xcode, destino "Any iOS Device (arm64)" → Product → Archive → Distribute App
   → App Store Connect. (Firmado automático con el Team.)
4. **TestFlight**: los probadores internos (hasta 100, de tu equipo) la reciben al instante sin revisión.
   Externos (hasta 10 000, con enlace público) necesitan una revisión beta corta. **En Apple no existe la
   prueba obligatoria de 12 personas × 14 días de Google Play.**
5. **Ficha**: reutilizar los textos de `docs/play/ficha.md` (nombre, subtítulo ≤ 30 caracteres:
   "Control de letras y Biblia", descripción, palabras clave: iglesia,proyección,letras,biblia,control
   remoto,videobeam,alabanza). URL de privacidad:
   https://oortega14.com/apps/selah/privacy ; URL de soporte: https://oortega14.com/apps/selah/support
6. **Privacidad de la app**: "No se recopilan datos" (todo va solo al PC del usuario en su red local).
7. **Clasificación por edad**: todo "Ninguno" → 4+. Categoría: Productividad (secundaria: Estilo de vida).
8. **Capturas obligatorias**: iPhone 6,9" (1320×2868 o 1290×2796) y, como el proyecto también es para
   iPad (`TARGETED_DEVICE_FAMILY: "1,2"`), iPad 13" (2064×2752). Se pueden sacar del simulador
   (⌘S) conectado al PC de prueba. Si no se quiere iPad al principio: `TARGETED_DEVICE_FAMILY: "1"`.
9. **Notas para el revisor (muy importante)**: la app no hace nada sin el programa del PC, y Apple suele
   rechazar por "no pudimos probarla" (guía 2.1). Poner en *App Review Information → Notes*:
   > Selah Remoto is the remote control for Selah, a free, open-source Windows program
   > (https://github.com/oscarortega14/selah) that projects song lyrics and Bible verses in churches.
   > The app connects only to that program on the same Wi-Fi network; it has no accounts and collects no data.
   > A demo video showing the app controlling the projection is here: <enlace a video en YouTube sin listar>.
   Y grabar ese video corto (pantalla del iPhone + el videobeam/PC cambiando).

---

## 9. Otras tiendas (estado al pasar a la Mac)

- **App Store: publicada el 2026-11-06** → https://apps.apple.com/app/id6831222748 (Apple ID 6831222748).

- **Google Play** (cuenta personal creada después de nov. 2023 → prueba cerrada con **12 probadores
  durante 14 días** antes de producción):
  - Ficha, gráfico y capturas: `docs/play/`. App bundle: `./gradlew :androidApp:bundlePlayRelease`
    (variante `play`, sin actualizador propio).
  - Al crear la app en Play Console elegir **"usar mi propia clave de firma"** (la clave está en
    `~/.config/selah` del PC, fuera del repositorio) y subir con PEPK.
  - Hecho (2026-11-06): app creada, clave propia subida con PEPK, prueba cerrada "Alpha" activa.
    Falta: llegar a 12 probadores con la app instalada, esperar 14 días y pedir acceso a producción.
- **Microsoft Store** (gratis para personas; Microsoft vuelve a firmar el paquete, así Smart App Control
  de Windows deja de bloquear Selah):
  - `./gradlew :desktopApp:packageMsix` en Windows (usa `makeappx` del NuGet Microsoft.Windows.SDK.BuildTools).
  - **Publicada el 2026-11-06:** https://apps.microsoft.com/detail/9NP5ZZWWGLFK (Store ID 9NP5ZZWWGLFK).
    Versión nueva: subir `selah.version`, generar el MSIX y en Partner Center → Selah Remote → **Start update**.
