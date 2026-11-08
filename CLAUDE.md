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

## En main después de la 2.6.0 (sin publicar; sería la 2.7.0: funciones nuevas)
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
- Mac: `packageDmg` (Apple Silicon). Falta: certificado "Developer ID Application" (solo lo crea el titular de la
  cuenta de Apple) + notarización, y armar también para Mac Intel si alguien lo necesita.

