# Selah — contexto para Claude Code

Programa libre (MIT) para proyectar letras de canciones y la Biblia en iglesias, con control remoto
desde celulares. Repositorio público: github.com/oscarortega14/selah. El dueño habla español;
**la interfaz, los comentarios y los mensajes de commit van en español**, en lenguaje sencillo
(lo usan voluntarios de la iglesia, no técnicos).

**Si estás en la Mac para hacer la app de iPhone: lee `docs/IOS.md` primero** (estado, pasos y pendientes).

## Módulos
- `shared/` — KMP (JVM, Android, iOS): modelos, protocolo WebSocket (`Protocol.kt`), Biblia, búsqueda,
  `Releases` (actualizaciones), `mergeState`.
- `desktopApp/` — Windows (Compose Desktop + servidor Ktor 3.6.1 en el puerto 8765, UDP 8766):
  operador, ventana de proyección, control web/PWA (`resources/web/`), vista de escenario, página OBS.
- `remoteUi/` — Compose Multiplatform (Android + iOS): **toda** la app del celular (pantallas + `RemoteClient`).
  `androidMain`/`iosMain` solo tienen lo de cada sistema (`Platform.*.kt`, `IosEntry.kt`).
- `androidApp/` — la Activity + variantes `github` (se actualiza sola desde GitHub Releases) y `play`
  (Google Play, sin actualizador). minSdk 24 (tablet de la iglesia con Android 7), target/compile 36.
- `iosApp/` — SwiftUI mínimo + `project.yml` (XcodeGen). El .xcodeproj no se versiona.

## Versiones y herramientas
Kotlin 2.3.21, Compose MP 1.10.3, AGP 8.13.2, Gradle 8.14.6. Java: **21 en `desktopApp`** (va dentro del instalador), **17 en `shared`/`remoteUi`/`androidApp`**
(Android no usa ese Java; AGP 8.13 pide 17). Gradle descarga solo los que falten. Ktor **cliente** 3.1.3 en el celular
(3.6 trae OkHttp 5 → compileSdk 37). La versión de todo está en `gradle.properties` (`selah.version`);
Android: versionCode = a*10000+b*100+c. iOS: `iosApp/project.yml` (mantener igual a mano).

## Comandos
```bash
./gradlew test                                   # pruebas (desktop, shared, remoteUi, android)
./gradlew :desktopApp:run                        # programa de escritorio
./gradlew :androidApp:assembleGithubRelease      # APK (variante github)
./gradlew :androidApp:bundlePlayRelease          # AAB para Google Play
./gradlew :desktopApp:packageMsi                 # instalador Windows (en Windows)
./gradlew :desktopApp:packageMsix                # paquete para Microsoft Store (en Windows)
./gradlew :desktopApp:packageDmg                 # instalador para Mac (en una Mac; sin Developer ID → "Abrir igualmente")
./gradlew :remoteUi:linkDebugFrameworkIosSimulatorArm64   # iPhone (solo en Mac)
cd iosApp && xcodegen                            # proyecto de Xcode
```

## Reglas importantes
- **Nunca** subir al repositorio claves ni contraseñas: `~/.config/selah/` (clave Ed25519 de firma
  de actualizaciones, keystore de Android y sus contraseñas), `desktopApp/pixabay.key`, Team ID privado.
- Las actualizaciones del PC se verifican con firma Ed25519 (`tools/Firma.java`, clave pública en
  `desktopApp/.../Updater.kt`). La clave privada vive solo en el PC con Windows/WSL del dueño.
- **Publicar una versión en GitHub Releases, en una tienda o hacer push de etiquetas requiere permiso
  explícito del dueño cada vez.**
- Commits con el correo privado de GitHub del dueño (`…@users.noreply.github.com`), ya configurado.
- No cambiar ajustes de seguridad de los dispositivos de prueba (p. ej. "fuentes desconocidas").
- Cada mejora lleva pruebas; lo visual se comprueba también en un dispositivo real cuando se puede.
- Pruebas adversariales (`Adversarial*Test`): intentan romper Selah (red, archivos, lista/presentador, app del
  celular). Las del celular (`remoteUi/src/androidUnitTest`) levantan un "PC" falso en `[::1]:8765`: correrlas
  con Selah cerrado en ese equipo.
- El PC es la única fuente de verdad: el operador, el control web y las apps mandan `ClientMessage`
  y reciben `ServerMessage.State`. Al agregar algo al protocolo, mantener compatibilidad con apps viejas
  (campos con valor por defecto; `Hello(features)`).

## Seguridad (no romper)
Clave (PIN) con bloqueo por IP + límite global (`PinGuard`), comprobación de Origin/Host, marcos de 64 KB,
10 conexiones por IP, 20 mensajes/s, textos recortados (`NetGuard`), subidas solo jpg/png/webp/mp4/mov con
cuota de 5 GB, `SELAH_UPDATE_URL` solo para localhost.

## Direcciones públicas (las mismas en las 3 tiendas)
- Soporte: https://oortega14.com/apps/selah/support
- Sitio web / marketing: https://oortega14.com/apps/selah
- Código: https://github.com/oscarortega14/selah
- Política de privacidad: https://oortega14.com/apps/selah/privacy
  (páginas en el portafolio: `~/projects/portfolio`, en `src/apps/selah/SelahLanding.tsx` y
  `src/legal/selahContent.ts`, desplegado en Vercel; al aprobarse una tienda, poner su enlace en `STORE`;
  si cambian de dirección, actualizar las fichas de App Store, Play y Microsoft Store)

## Tiendas (estado)
Ver §9 de `docs/IOS.md`. Google Play: app `app.selah.remote` creada, firma propia (misma clave que el APK
de GitHub), prueba cerrada "Alpha" activa desde el 2026-11-06 (12 probadores × 14 días antes de producción). Microsoft Store
(**publicada** el 2026-11-06: https://apps.microsoft.com/detail/9NP5ZZWWGLFK, Store ID 9NP5ZZWWGLFK,
identidad OscarOrtega.SelahRemote; las versiones nuevas se suben con "Start update" en Partner Center), App Store (**publicada** el 2026-11-06: Selah Remote, https://apps.apple.com/app/id6831222748, Apple ID 6831222748;
las versiones nuevas: nuevo build en App Store Connect → nueva versión → revisión).

## Versión 2.5.0 (2026-11-06): lo nuevo y lo que falta
Nuevo (hecho y probado en el PC con Windows, 243 pruebas): importar de EasyWorship 6/7 (`EasyWorshipImport.kt`),
presentaciones PowerPoint/PDF (`Presentations.kt`, carpeta `presentaciones/`, `ClientMessage.ShowPresentation`,
`PlanKind.PRESENTATION` solo para remotos que dicen `FEATURE_PRESENTATIONS` en Hello), reporte CCLI (Historial;
líneas `Autor:` y `CCLI:` en las canciones) y salida NDI (`Ndi.kt`/`NdiOutput.kt`, usa la librería de NDI Tools).
Falta:
- iPhone: compilar en la Mac (remoteUi cambió: lista "Presentaciones" en Más), probar y subir 2.5.0 (20500).
- Android: `bundlePlayRelease` 2.5.0 (versionCode 20500) para Google Play.
- Windows: MSI/MSIX 2.5.0 se arman SOLO en Windows (`packageMsi`, `packageMsix`); el MSI se firma con
  `java tools/Firma.java firmar <msi> <clave ed25519>` y se publica en GitHub Releases (con permiso del dueño);
  el MSIX se sube en Partner Center → Selah Remote → "Start update".
- Sin probar de punta a punta: NDI con un receptor real (instalar NDI Tools) y EasyWorship con una base real.

## Versión 2.6.0 (2026-11-07): demostración del celular, "Más Biblias" y arreglos adversariales
(Primero se armó como 2.5.1 —builds iPhone 20501/20502 subidos y sin usar— y se cambió a 2.6.0 porque trae
funciones nuevas.) Por comentarios de los probadores:
- Celular: pantalla de bienvenida y **modo demostración** sin computador (`DemoServer.kt` atiende las mismas
  órdenes que el PC; `DemoContent.kt`: 6 canciones de letra original y 6 capítulos de la RV1909).
- PC: **"＋ Más Biblias…"** (`BibleDownloads.kt`, `BiblesDialog.kt`): 12 traducciones libres de eBible.org
  (VPL) y agregar desde archivo (.txt VPL, .xml Zefania, .json, .zip). Solo traducciones repartibles gratis.
- 36 bugs de las pruebas adversariales (`Adversarial*Test`), entre ellos la clave del celular que podía
  llegarle a otro PC.
Hecho: iPhone build 2.6.0 (20600) subido a App Store Connect; AAB 2.6.0 (20600) en
`~/Desktop/Selah Remoto - Google Play/`, con textos de "Qué hay de nuevo" para cada tienda.
Estado (2026-11-07): App Store 2.6.0 (20600) enviada a revisión; Google Play AAB 2.6.0 subido a la prueba
cerrada; GitHub Release v2.6.0 publicada desde el PC (MSI firmado y verificado, APK 20600 con la clave 7B:EE…);
MSIX 2.6.0 → Partner Center "Start update".

## Cómo numerar versiones
- 2.6.**x**: solo arreglos o cambios pequeños.
- 2.**x**.0: funciones nuevas que se notan (todo lo anterior sigue funcionando).
- **x**.0.0: algo deja de ser compatible (protocolo con apps viejas, formato de canciones, carpeta).
Android: versionCode = a*10000+b*100+c (no se puede repetir en Play). iPhone: CURRENT_PROJECT_VERSION
distinto en cada subida (p. ej. 20600, 20601… dentro de la misma versión).

## Versión 3.0.1 (2026-11-24): iPad y tablets, desplazamiento automático en el PC
- 3.0.0 aprobada en Microsoft Store, App Store y Google Play (2026-11-23).
- **iPad**: `TARGETED_DEVICE_FAMILY "1,2"` (app universal, un solo build). Desde `WIDE` = 800 dp de ancho
  (`RemoteApp.kt`), el operador ve dos paneles (izquierda: Canciones/Biblia/Lista/Más/Músicos; derecha: siempre
  "En pantalla" + ◀ Negro ▶) y el músico tiene título + todos los controles en una sola fila y sin la fila
  "🎵 Músico" (`MusicianView.kt`). `Readable` centra la búsqueda del PC y "¿Quién usa este celular?" a 640 dp.
  Vale igual para tablets Android (mismo código).
- Capturas de App Store para iPad 13": prueba de interfaz `iosApp/SelahRemotoCapturas` (abre la demostración con
  `SIMCTL_CHILD_SELAH_DEMO=1`/`launchEnvironment`, recorre pestañas, gira el iPad):
  `TEST_RUNNER_CAPTURAS_DIR=carpeta xcodebuild test -project iosApp/SelahRemoto.xcodeproj -scheme SelahRemoto -destination 'platform=iOS Simulator,name=iPad Pro 13-inch (M5)' -only-testing:SelahRemotoCapturas`
  (la horizontal sale girada: `sips -r 270`). `IosEntry.kt` lee `SELAH_DEMO` (solo se puede poner desde Xcode).
- Escritorio: `Modifier.showWhen(key)` (`Ui.kt`) desplaza hasta el avance o el mensaje de "Listo" cuando aparece
  (Llevar/Traer, letra automática junto a la canción, partituras, secuencias, voz guía, fondos).
- 2026-11-25: iPhone/iPad 3.0.1 enviada a revisión en App Store; AAB 3.0.1 (30001, ya con R8: `isMinifyEnabled` +
  `proguard-rules.pro`, Play lo exige desde feb 2027) enviada a revisión en Google Play. GitHub Release v3.0.1 (latest)
  publicada con `Selah-3.0.1.dmg` y `SelahRemoto.apk` (30001, firma 7B:EE…, probado: el actualizador ofuscado ve y
  descarga la versión nueva). **Falta en el PC con Windows:** `packageMsi` 3.0.1, firmarlo con `tools/Firma.java`,
  subir `Selah-3.0.1.msi` y `.msi.sig` al Release v3.0.1 (y quitar de sus notas la línea "se agrega en unos días");
  `packageMsix` 3.0.1 → Partner Center "Start update" (con permiso del dueño). Sin .msi, el actualizador de Windows
  no ofrece nada (sigue en 3.0.0, sin error).

## Versión 3.0.0: publicada en GitHub (2026-11-22), con el .msi
GitHub Release v3.0.0 (latest) con `Selah-3.0.0.dmg`, `SelahRemoto.apk` (30000, firma 7B:EE…), `Selah-3.0.0.msi` y
`Selah-3.0.0.msi.sig` (el .msi se armó y firmó en el PC con Windows el 2026-11-22: 304 pruebas de `desktopApp` bien,
`FIRMA VÁLIDA` con la clave pública de `Updater.kt`; las notas del Release ya no dicen "se agrega pronto").
En ese PC la clave privada está en `~/.config/proyecta/firma-ed25519.key` (WSL; la carpeta conserva el nombre viejo) y
el .msi se arma en la copia `C:\Users\florg\src\proyecta` (sincronizar desde WSL con tar, sin `build/` ni `.git`; borrar
a mano los archivos que el repo ya no tiene). Instalado y probado en ese PC: abre la 3.0.0 con las 38 canciones.
**Problema encontrado (sin arreglar):** en una pantalla de 1536 px de ancho (1920×1080 al 125 %) la barra de arriba
(`OperatorScreen.kt`, `TopBar`) no cabe: "Proyección ▾" y "?" quedan fuera de la ventana desde que se agregaron
"Equipo" y "¿Qué usa tu iglesia?" (en la 2.6.0 sí cabían). Tampoco subido: MSIX de Microsoft Store (con permiso del dueño). Hecho el 2026-11-22 desde la Mac: iPhone 3.0.0 (30000)
subido a App Store Connect (TestFlight, sin enviar a revisión) y AAB 3.0.0 (30000) en ~/Desktop/Selah Remoto - Google Play/
para que el dueño lo suba.

## En main después de la 2.6.0 (sin publicar; será la 3.0.0 — el dueño la subió de 2.7.0 a 3.0.0 por la cantidad de cambios; iPhone 2.7.0 (20700) quedó en TestFlight)
- Carrusel de anuncios (`Presenter.startCarousel/tick`, `ClientMessage.StartCarousel/StopCarousel`,
  `PresentationState.carouselSeconds`, `Library.features` con `FEATURE_CAROUSEL` para que la app solo lo ofrezca
  a un PC que lo entiende).
- Letra para la congregación (`Congregation.kt`, `/letra` + canal de solo lectura `/ws-letra`, QR en
  "Conectar dispositivos" y botón para ponerlo en Anuncios).
- Cultos (`shared/Services.kt`: `WorshipService`, `ServicesConfig`, `suggestSongs`; `ServicesStore.kt` en
  `Selah/cultos.json`; `ServicesUi.kt`: ventana Cultos, "Preparar desde la plantilla", aviso del culto de hoy con
  `LocalWorship`). Línea `Tipo:` en las canciones (`Song.tags`, `Library.setSongTags`, solo en el encabezado).
  `SongSummary.lastSung` + `Library.repeatDays` y `Plan.todayService/todayList` para la app (campos con valor por
  defecto: compatibles con apps y PCs viejos).
- Click de los músicos + letra automática (`shared/Click.kt`: `ClockSync` tipo NTP con Ping/Pong, `autoSlide`,
  `timingFromRecording`; `PresentationState.click: ClickState`; `Presenter.startClick/tick/manualMove/finishRecording`;
  líneas `BPM:`, `Compás:`, `Tiempos:`; sonido: `ClickPlayer.android.kt` (AudioTrack + getTimestamp),
  `ClickPlayer.ios.kt` (AVAudioEngine con hora exacta), `escenario.html` (Web Audio)). El PC NO suena. `FEATURE_CLICK`.
  Probado: emulador Android (pista activa, 0 underruns, ±3-4 ms) y vista de escenario. Falta: oído real con 2 equipos.
- "¿Qué usa tu iglesia?" (`Settings.church: ChurchFeatures`, `MusiciansUi.kt`): interruptores; `RemoteServer.serverFeatures`
  anuncia solo lo encendido (`FEATURE_CLICK`, `FEATURE_SEQUENCES`, `FEATURE_SCORES`, `FEATURE_CHORDS`) y `Commands.gate`
  rechaza lo apagado aunque lo pida una app vieja. Por defecto solo la letra para la congregación.
- Partituras y secuencias (`MusicLibrary.kt`, carpetas `partituras/` y `secuencias/`; `shared/Music.kt`: `matchScores`,
  `scoreFor`, `Transpose`). Páginas por `GET /partitura/paginas` y `/partitura` (convertidas como las presentaciones;
  `Presentations(nested = true)`). Secuencias: `SequencePlayer.kt` (Java Sound + mp3spi, `SequenceMixer` arma click
  izquierda/música derecha), `Presenter.startSequence` usa `ClickState.sequence`/`audible` (sin BPM: 60 "tiempos" por
  minuto = segundos); la tarjeta de sonido corrige `startAt` cada 2 s. Cambiar de canción NO corta la música.
- Secuencias reales (IC Salem, 82 audios, probado 2026-11-16): `parseSequenceName` lee "Canción - 150 BMP - D", "No Bass",
  "Brass ON/OFF"…; `matchSequence` (exacta o por el comienzo → "Revisa estas"); línea `Secuencia:` en la canción para la
  versión elegida, una asignada a mano o `ninguna`. `SequenceAnalysis` detecta el click a la izquierda (rejilla del pulso
  y cobertura; la voz guía no estorba), su tempo, el desfase y si cambia de tempo (entonces los celulares no suenan).
  Con click propio, el tempo es el medido (no el del nombre). `WavReader`: WAV con bloque JUNK, 24 bits y coma flotante
  (el de Java no los abre). Reporte con secuencias reales: `SELAH_REAL_DIR=… --tests '*RealSequencesTest*'`.
- Secuencias en otro aparato (`ChurchFeatures.sequencePlays = false`): `SilentSequenceOutput` lleva el tiempo sin sonar,
  `ClientMessage.SequenceNudge` corre ±ms (`ClickState.following`). `ClickFollower.kt`: escucha el click por una entrada
  de audio (`LineClickInput`), arranca al oír los 3 primeros golpes con el patrón de `AudioAnalysis.clicks` y se corrige
  con la mediana del error (probado con el click real de Salem: 0-4 ms). `ClickState.waiting` mientras espera.
- Tiempos con la voz guía: `GuideVoice.kt` (Vosk por JNA directo: la clase LibVosk del paquete falla en Mac porque su
  dylib no tiene `vosk_recognizer_set_grm`; se usa `vosk_recognizer_new_grm` con lista cerrada de palabras),
  `TimingGuess.kt` (avisos → comienzos, "repetir" repartido parejo, secciones por tipo), `GuideTimings.kt` (+ `VoiceModel`:
  descarga con SHA-256 a `Selah/modelos`), `Library.applySuggestion/undoSuggestion`. Real: 40 de 42 canciones en 98 s.
  Páginas de partitura que siguen la letra en el celular (`pageFor`, correcciones guardadas por partitura).
- Letra automática desde una grabación (2026-11-20): pestaña "Letra automática" en "¿Qué usa tu iglesia?" (solo con el click;
  la ventana va en pestañas: `ChurchTab`, `churchTabs`). `RecordingAudio.kt` (audio a 16 kHz; AAC de .mp4 con javasound-aac,
  `openPcm` pasa primero a PCM propio del decodificador), `WhisperModelFile` (ggml-medium-q5_0, 540 MB; el small se confunde, SHA-256) y `SungLyrics`
  (whisper-jni 1.7.1; SIN la letra como pista: con pista Whisper inventa letra en el instrumental), `BeatTracker.kt`
  (flujo espectral + programación dinámica; tiempos en pulsos para que calcen aunque la banda se acelere), `LyricsAligner.kt`
  (limpia frases inventadas por duración; Viterbi palabra a palabra contra la letra, con vueltas), `RecordingTimings.kt`
  (2 tiempos de anticipación; `Library.applyRecording` = BPM + `applySuggestion`). Real ("A cada instante", video de IC Salem):
  110 BPM, orden correcto, tiempos a ±2 de los hechos a mano, 9 s en la Mac. Vosk NO sirve para canto (5 palabras).
  Prueba real: `SELAH_REAL_RECORDING=… SELAH_REAL_SONG=… SELAH_REAL_MODEL=… --tests '*RealRecordingTest*'`.
  Falta: probar en Windows (whisper-jni_full.dll pide VCRUNTIME140/MSVCP140/VCOMP140; si falla, la pestaña lo dice).
- Llevar a otro computador (2026-11-20): `Transfer.kt` (`PARTS`, .zip con la marca `selah-llevar.txt`; audio/video sin
  comprimir; al traer, solo rutas de las partes conocidas y dentro de la carpeta; lo igual por CRC no se copia) y
  `TransferDialog.kt` (menú "?"). No viajan ajustes.json, equipo.json, modelos ni respaldos.
- Click del director (2026-11-20): `ChurchFeatures.clickByDirector` (true por defecto; `ALL` lo trae en false para las
  pruebas). `StartClick(lyricsOnly)` → `ClickState.lyricsOnly` con `audible = false` (las apps viejas no suenan). El servidor
  (`RemoteServer.touchesBandClick`) rechaza iniciar/cambiar/parar el click que suena si el papel no es DIRECTOR
  (`DIRECTOR_CLICK`). Pasar la diapositiva a mano con la letra automática: `Presenter.lyricShift` (corre solo la letra, no el
  click); "Retomar" lo pone en 0. `escenario.html` ya no suena con `audible: false`.
- Equipo y papeles (`shared/Roles.kt`: `Role`, `Permission`, `ClientMessage.requiredPermission()`; `TeamStore.kt` en
  `Selah/equipo.json` con SHA-256 del código del celular; `TeamUi.kt` ventana "Equipo"; `FEATURE_ROLES`). `Hello(deviceId,
  deviceName)` y `Auth(role, instrument, pending)` con valores por defecto (apps viejas: la clave = operador). El servidor
  rechaza por permiso ("Tu papel (…) no permite eso"); `/partitura` pide `X-Selah-Dispositivo` o la clave. Probado en el
  emulador: esperando → músico (★ Bajo, ve el piano) → director (inicia la secuencia). Sin clave en el PC: todos operador.
- Asistente de inicio (`SetupWizard.kt`, reemplaza a Welcome.kt): `WIZARD_STEPS` con capturas en `resources/guia/` (512 KB;
  PC dibujado con `GuideImagesTest`, celular del emulador con canciones de ejemplo), interruptores de `ChurchFeatures`,
  aviso al saltar si no vio `WIZARD_IMPORTANT`.
- App: modo músico (`MusicianView.kt`, "¿Quién usa este celular?", rol e instrumento guardados en el teléfono),
  pestaña "Músicos" para el operador, `decodeImage` por plataforma. Probado en el emulador Android con Selah en la Mac
  (partitura, acordes, secuencia iniciada desde el celular, ±2 ms). Falta: probar en consola real y en iPhone.
- Mac: `packageDmg` (Apple Silicon). Falta: certificado "Developer ID Application" (solo lo crea el titular de la
  cuenta de Apple) + notarización, y armar también para Mac Intel si alguien lo necesita.

