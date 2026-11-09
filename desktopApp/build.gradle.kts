import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.net.URI
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import java.security.MessageDigest

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

// Java 21 (soporte largo): es el que va dentro del instalador y corre en el PC de la iglesia.
// El celular y lo compartido siguen en 17 (Android no usa este Java).
kotlin { jvmToolchain(21) }

val ktor = "3.6.1"

/** Versión de Selah: va al instalador y a la app (para avisar cuando hay una más nueva). */
val appVersion = providers.gradleProperty("selah.version").get() // un solo número para PC y app: gradle.properties

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("io.ktor:ktor-server-core:$ktor")
    implementation("io.ktor:ktor-server-cio:$ktor")
    implementation("io.ktor:ktor-server-websockets:$ktor")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    implementation("org.slf4j:slf4j-nop:2.0.16")
    // Fondos de video: usa el VLC instalado en el PC (si no está, se ignoran los videos).
    implementation("uk.co.caprica:vlcj:4.8.3")
    // Códigos QR para conectar celulares escaneando (ventana "Conectar dispositivos").
    implementation("com.google.zxing:core:3.5.3")
    // Leer el cancionero de EasyWorship (bases SQLite)
    implementation("org.xerial:sqlite-jdbc:3.47.1.0")
    // Presentaciones: PDF (PDFBox) y PowerPoint .pptx (Apache POI), convertidas a imágenes
    implementation("org.apache.pdfbox:pdfbox:3.0.3")
    implementation("org.apache.poi:poi-ooxml:5.3.0")
    implementation("org.apache.logging.log4j:log4j-to-slf4j:2.24.1") // POI escribe su registro por aquí (en silencio)
    // Salida NDI: llamar a la librería que instala NDI Tools (no se incluye en Selah)
    implementation("net.java.dev.jna:jna:5.15.0")
    // Secuencias en MP3: decodificador puro Java (Java ya lee WAV)
    implementation("com.googlecode.soundlibs:mp3spi:1.9.5.4")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-client-cio:$ktor")
    testImplementation("io.ktor:ktor-client-websockets:$ktor")
}

// --- Clave de la API de Pixabay (buscador de fondos) ---
// No se sube al repositorio: se lee de desktopApp/pixabay.key o de la variable PIXABAY_KEY
// y se escribe en un recurso generado al compilar.
val generatedResources = layout.buildDirectory.dir("generated/selah-resources")
val writeBuildConfig by tasks.registering {
    val keyFile = layout.projectDirectory.file("pixabay.key").asFile
    val key = (System.getenv("PIXABAY_KEY") ?: keyFile.takeIf { it.exists() }?.readText())?.trim().orEmpty()
    inputs.property("pixabayKeyHash", key.hashCode())
    inputs.property("appVersion", appVersion)
    outputs.dir(generatedResources)
    doLast {
        val f = generatedResources.get().file("selah-build.properties").asFile
        f.parentFile.mkdirs()
        f.writeText("pixabay.key=$key\nversion=$appVersion\n")
    }
}
sourceSets.main { resources.srcDir(writeBuildConfig) }

// --- VLC incluido (solo Windows): reproduce los fondos de video sin instalar nada aparte ---
// Se descarga el VLC portable oficial una vez, se verifica su SHA-256 y se extrae solo lo
// necesario para reproducir video (sin interfaz, idiomas, red ni codificadores).
val vlcVersion = "3.0.24"
val vlcSha256 = "fcf30850371ad10c9373cc4f0f4501e7dee49e3e9ae9f20c72fb2661a1ca6323"
val vlcZip = layout.buildDirectory.file("vlc-download/vlc-$vlcVersion-win64.zip")
val appResources = layout.buildDirectory.dir("appResources")

val downloadVlc by tasks.registering {
    outputs.file(vlcZip)
    doLast {
        val zip = vlcZip.get().asFile
        fun sha(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
        if (!zip.exists() || sha(zip) != vlcSha256) {
            zip.parentFile.mkdirs()
            val url = "https://download.videolan.org/pub/videolan/vlc/$vlcVersion/win64/vlc-$vlcVersion-win64.zip"
            logger.lifecycle("Descargando VLC $vlcVersion…")
            URI(url).toURL().openStream().use { input -> zip.outputStream().use { input.copyTo(it) } }
            check(sha(zip) == vlcSha256) { "La firma SHA-256 de VLC no coincide: descarga dañada o alterada" }
        }
    }
}

val prepareVlc by tasks.registering(Sync::class) {
    dependsOn(downloadVlc)
    val keepPluginDirs = listOf("codec", "demux", "video_chroma", "video_output", "video_filter", "packetizer", "stream_filter", "misc", "d3d11", "d3d9")
    from(zipTree(vlcZip)) {
        include("vlc-$vlcVersion/libvlc.dll", "vlc-$vlcVersion/libvlccore.dll", "vlc-$vlcVersion/COPYING.txt", "vlc-$vlcVersion/AUTHORS.txt")
        include(keepPluginDirs.map { "vlc-$vlcVersion/plugins/$it/**" })
        include("vlc-$vlcVersion/plugins/access/libfilesystem_plugin.dll")
        // codificadores (solo sirven para crear video) y subtítulos avanzados
        exclude("**/libx264*", "**/libx265*", "**/libx26410b*", "**/liblibass*", "**/plugins.dat")
        eachFile { path = path.removePrefix("vlc-$vlcVersion/") }
        includeEmptyDirs = false
    }
    into(appResources.map { it.dir("windows/vlc") })
}

compose.desktop {
    application {
        mainClass = "app.selah.desktop.MainKt"
        // El Java que va dentro del instalador: siempre un Temurin 21 completo (trae jpackage), no el que
        // corre Gradle (si Gradle corre con 17, el programa compilado para 21 no arrancaría) ni el de
        // Android Studio (no trae jpackage). Gradle lo descarga solo si no está.
        javaHome = javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(21))
            vendor.set(JvmVendorSpec.ADOPTIUM)
        }.get().metadata.installationPath.asFile.absolutePath
        // Tope de memoria: sin él Java puede crecer hasta 1/4 de la RAM antes de limpiar.
        // Con 512 MB sobra para proyectar con video y la app nunca se infla en un PC modesto.
        jvmArgs += listOf("-Xmx512m", "-XX:MaxMetaspaceSize=192m")
        nativeDistributions {
            appResourcesRootDir.set(appResources)
            targetFormats(TargetFormat.Msi, TargetFormat.Deb, TargetFormat.Dmg)
            packageName = "Selah"
            packageVersion = appVersion
            description = "Proyección de canciones y Biblia con control remoto"
            vendor = "Selah"
            copyright = "MIT License"
            // Se incluye el entorno Java completo: el PC de la iglesia no necesita instalar nada más.
            includeAllModules = true
            linux { iconFile.set(project.file("src/main/resources/web/icons/icon-512.png")) }
            // Mac: ./gradlew :desktopApp:packageDmg (en una Mac). Sale para el procesador de esa Mac
            // (Apple Silicon o Intel). Sin certificado "Developer ID" macOS pide permiso la primera vez.
            macOS {
                bundleID = "app.selah.desktop"
                iconFile.set(project.file("icons/selah.icns"))
                dockName = "Selah"
                appCategory = "public.app-category.productivity"
                infoPlist {
                    // macOS pide permiso para la red local: sin él, los celulares no encuentran ni controlan Selah.
                    extraKeysRawXml = """
                        <key>NSLocalNetworkUsageDescription</key>
                        <string>Selah usa la red local para que los celulares y tablets de la iglesia lo encuentren y controlen la proyección.</string>
                    """.trimIndent()
                }
            }
            windows {
                iconFile.set(project.file("icons/selah.ico"))
                menuGroup = "Selah"
                dirChooser = true
                shortcut = true
                perUserInstall = false
                // Fijo para siempre: así una versión nueva reemplaza a la anterior al instalarse.
                upgradeUuid = "7f0c7a52-3b1e-4c58-9f0e-2b6f3a9d8e41"
            }
        }
    }
}

// El VLC incluido entra en el instalador de Windows y en `run` (para probar). En Mac o Linux no hace
// falta descargarlo: allí se usa el VLC instalado (si lo hay) para los fondos de video.
if (System.getProperty("os.name").startsWith("Windows")) {
    tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(prepareVlc) }
}

// En Windows las pruebas usan el mismo VLC incluido que la app empaquetada.
if (System.getProperty("os.name").startsWith("Windows")) {
    tasks.withType<Test>().configureEach {
        dependsOn(prepareVlc)
        systemProperty("compose.application.resources.dir", appResources.get().dir("windows").asFile.absolutePath)
        System.getenv("SELAH_VIDEO")?.let { environment("SELAH_VIDEO", it) }
    }
}

// --- Microsoft Store (MSIX) -------------------------------------------------------------------
// La tienda vuelve a firmar el MSIX con un certificado de Microsoft: así Windows (incluido el
// Control inteligente de aplicaciones) confía en Selah sin comprar un certificado propio.
//   gradlew.bat :desktopApp:packageMsix     (solo en Windows; necesita makeappx.exe del SDK de Windows)
val packageMsix by tasks.registering {
    group = "distribution"
    description = "Arma Selah-x.y.z.msix para subir a Microsoft Store"
    dependsOn("createDistributable")
    val appImage = layout.buildDirectory.dir("compose/binaries/main/app/Selah")
    val out = layout.buildDirectory.dir("msix")
    val icon = layout.projectDirectory.file("src/main/resources/web/icons/icon-512.png")
    val props = listOf("msix.identityName", "msix.publisher", "msix.publisherDisplayName", "msix.displayName")
        .associateWith { providers.gradleProperty(it).get() }
    inputs.dir(appImage); inputs.file(icon); inputs.property("props", props); inputs.property("version", appVersion)
    outputs.dir(out)
    doLast {
        val root = out.get().asFile.apply { deleteRecursively(); mkdirs() }
        val layoutDir = File(root, "layout")
        appImage.get().asFile.copyRecursively(layoutDir)

        // Iconos que pide Windows/la tienda, hechos a partir del ícono de la app
        val assets = File(layoutDir, "Assets").apply { mkdirs() }
        val src = ImageIO.read(icon.asFile)
        fun png(name: String, w: Int, h: Int, iconSize: Int = minOf(w, h)) {
            val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
            img.createGraphics().apply {
                setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                drawImage(src, (w - iconSize) / 2, (h - iconSize) / 2, iconSize, iconSize, null)
                dispose()
            }
            ImageIO.write(img, "png", File(assets, name))
        }
        png("StoreLogo.png", 50, 50)
        png("Square44x44Logo.png", 44, 44)
        png("Square44x44Logo.targetsize-256_altform-unplated.png", 256, 256)
        png("Square150x150Logo.png", 150, 150)
        png("Wide310x150Logo.png", 310, 150, 120)

        val version = appVersion.split('.').let { (it + listOf("0", "0", "0")).take(3) }.joinToString(".") + ".0"
        val desc = "Letras de canciones y Biblia en el videobeam, con control desde celulares y tablets"
        fun esc(s: String) = s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")
        File(layoutDir, "AppxManifest.xml").writeText(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <Package xmlns="http://schemas.microsoft.com/appx/manifest/foundation/windows10"
                     xmlns:uap="http://schemas.microsoft.com/appx/manifest/uap/windows10"
                     xmlns:rescap="http://schemas.microsoft.com/appx/manifest/foundation/windows10/restrictedcapabilities"
                     IgnorableNamespaces="uap rescap">
              <Identity Name="${esc(props.getValue("msix.identityName"))}" Publisher="${esc(props.getValue("msix.publisher"))}" Version="$version" ProcessorArchitecture="x64" />
              <Properties>
                <DisplayName>${esc(props.getValue("msix.displayName"))}</DisplayName>
                <PublisherDisplayName>${esc(props.getValue("msix.publisherDisplayName"))}</PublisherDisplayName>
                <Logo>Assets\StoreLogo.png</Logo>
                <Description>$desc</Description>
              </Properties>
              <Dependencies>
                <TargetDeviceFamily Name="Windows.Desktop" MinVersion="10.0.17763.0" MaxVersionTested="10.0.26100.0" />
              </Dependencies>
              <Resources>
                <Resource Language="es-ES" />
              </Resources>
              <Applications>
                <Application Id="Selah" Executable="Selah.exe" EntryPoint="Windows.FullTrustApplication">
                  <uap:VisualElements DisplayName="${esc(props.getValue("msix.displayName"))}" Description="$desc" BackgroundColor="transparent"
                                      Square150x150Logo="Assets\Square150x150Logo.png" Square44x44Logo="Assets\Square44x44Logo.png">
                    <uap:DefaultTile Wide310x150Logo="Assets\Wide310x150Logo.png" />
                  </uap:VisualElements>
                </Application>
              </Applications>
              <Capabilities>
                <Capability Name="internetClientServer" />
                <Capability Name="privateNetworkClientServer" />
                <rescap:Capability Name="runFullTrust" />
              </Capabilities>
            </Package>
            """.trimIndent(),
        )

        // makeappx.exe: del SDK de Windows, o del paquete NuGet Microsoft.Windows.SDK.BuildTools
        val makeappx = (listOfNotNull(System.getenv("MAKEAPPX")?.let(::File)) +
            listOfNotNull(System.getenv("LOCALAPPDATA")?.let { File(it, "selah-herramientas") }, System.getenv("LOCALAPPDATA")?.let { File(it, "proyecta-herramientas") } /* antes del cambio de nombre */, File("C:/Program Files (x86)/Windows Kits/10/bin"))
                .flatMap { d -> d.walkTopDown().filter { it.name.equals("makeappx.exe", true) && it.path.contains("x64") }.toList() })
            .firstOrNull { it.isFile } ?: error("No encontré makeappx.exe (SDK de Windows). Define MAKEAPPX con su ruta.")
        val msix = File(root, "Selah-$appVersion.msix")
        val p = ProcessBuilder(makeappx.absolutePath, "pack", "/d", layoutDir.absolutePath, "/p", msix.absolutePath, "/o")
            .redirectErrorStream(true).start()
        val log = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { "makeappx falló:\n$log" }
        logger.lifecycle("MSIX listo: $msix")
    }
}
