# Reglas de R8 para Selah Remoto (versión de tiendas y de GitHub).
# kotlinx.serialization, Ktor, OkHttp y Compose traen sus propias reglas; aquí solo lo que falte.

# Los números de línea en los errores de Play Console (con el mapping.txt).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Ktor y OkHttp mencionan clases opcionales que no están en Android.
-dontwarn org.slf4j.**
-dontwarn java.lang.management.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
