import java.util.Properties
plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin { jvmToolchain(17) }

val appVersion = providers.gradleProperty("selah.version").get()

android {
    namespace = "app.selah.remote"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.selah.remote"
        minSdk = 24 // Android 7: la tablet de la iglesia
        targetSdk = 36 // Google Play exige apuntar a una versión reciente de Android
        // Mismo número que el programa del PC (gradle.properties); 2.3.0 → 20300
        versionName = appVersion
        versionCode = appVersion.split('.').map { it.toInt() }.let { (a, b, c) -> a * 10000 + b * 100 + c }
    }

    // Clave propia de Selah (fuera del repositorio, en ~/.config/selah). Android solo acepta
    // una actualización firmada con la MISMA clave: hay que respaldarla. Sin ella se usa la de depuración.
    val releaseKey = listOf(".config/selah", ".config/proyecta") // la segunda: antes del cambio de nombre
        .map { File(System.getProperty("user.home"), "$it/firma-android.properties") }
        .firstOrNull { it.isFile } ?: File(System.getProperty("user.home"), ".config/selah/firma-android.properties")
    signingConfigs {
        if (releaseKey.isFile) create("selah") {
            val p = Properties().apply { releaseKey.inputStream().use { load(it) } }
            storeFile = File(p.getProperty("storeFile"))
            storePassword = p.getProperty("storePassword")
            keyAlias = p.getProperty("keyAlias")
            keyPassword = p.getProperty("keyPassword")
        }
    }

    // Google Play no acepta (y no debe recibir) un paquete firmado con la clave de depuración.
    if (!releaseKey.isFile) gradle.taskGraph.whenReady {
        if (allTasks.any { it.project == project && it.name.startsWith("bundle") && it.name.endsWith("Release") }) {
            throw GradleException(
                "Falta la clave de firma de Android ($releaseKey). Cópiala del PC antes de generar el .aab para Google Play."
            )
        }
    }

    buildTypes {
        release {
            // Reducir y ofuscar el código (R8): Google Play lo exige desde 2027. Si algo se rompe en la versión
            // publicada, las reglas van en proguard-rules.pro (el mapping.txt se sube a Play para leer los errores).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("selah") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    // Dos variantes de la misma app:
    //  - github: se actualiza sola desde GitHub Releases (para instalar el APK a mano)
    //  - play:   para Google Play, sin actualizador propio (las reglas de Play no lo permiten)
    flavorDimensions += "tienda"
    productFlavors {
        create("github") { dimension = "tienda" }
        create("play") { dimension = "tienda" }
    }

    packaging {
        resources.excludes += setOf("META-INF/INDEX.LIST", "META-INF/io.netty.versions.properties", "META-INF/*.kotlin_module")
    }
}

val ktor = "3.1.3" // cliente; 3.6 trae OkHttp 5, que pide compileSdk 37

dependencies {
    // Pantallas y conexión: compartidas con el iPhone (módulo :remoteUi, Compose Multiplatform)
    implementation(project(":remoteUi"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("io.ktor:ktor-client-okhttp:$ktor")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}
