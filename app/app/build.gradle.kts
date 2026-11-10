import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.Properties
import java.time.format.DateTimeFormatter

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.sios"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "com.sios"
        minSdk = 31
        targetSdk = 36
        // Cada compilación, un número mayor (minutos desde 2026): las actualizaciones siempre avanzan.
        // Los scripts pasan -PcodigoVersion (la caché de configuración de Gradle guardaría la hora).
        val minutos = providers.gradleProperty("codigoVersion").orNull?.toInt()
            ?: ((System.currentTimeMillis() - 1767225600000L) / 60000L).toInt()
        versionCode = 1000 + minutos
        // Nombre legible: la fecha de la compilación («0.1 · 2026.11.08»).
        val fecha = LocalDateTime.of(2026, 1, 1, 0, 0).plusMinutes(minutos.toLong())
        versionName = "0.1 · " + fecha.format(DateTimeFormatter.ofPattern("yyyy.MM.dd"))
        // SIOS es una GSI de arm64: sin las librerías nativas de otras arquitecturas.
        ndk { abiFilters += "arm64-v8a" }
    }

    // Clave de firma de SIOS: ~/.sios/firma.properties (o la ruta de SIOS_FIRMA). Nunca va en el repo.
    // Sin ella (quien compila por su cuenta), se firma con la clave de pruebas de Android.
    val firma = File(System.getenv("SIOS_FIRMA") ?: (System.getProperty("user.home") + "/.sios/firma.properties"))
    if (firma.isFile) {
        val p = Properties().apply { firma.inputStream().use { load(it) } }
        signingConfigs.create("sios") {
            storeFile = file(p.getProperty("storeFile"))
            storePassword = p.getProperty("storePassword")
            keyAlias = p.getProperty("keyAlias")
            keyPassword = p.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            // Para la ROM: optimizada y firmada con la clave de SIOS (la misma que los overlays y Termux).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("sios") ?: signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:5.5.1")
    implementation("com.squareup.okhttp3:okhttp-sse:5.5.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    // Leer texto de fotos, capturas y PDF (memoria del móvil, capturas, escáner): PP-OCRv5 de PaddleOCR
    // sobre el ONNX Runtime de abajo, sin librerías cerradas. Los modelos los baja la tarea «modelosOcr».
    // QR para compartir widgets y rutinas.
    implementation("com.google.zxing:core:3.5.3")
    // Copias de seguridad en la carpeta que elija el usuario (Drive incluido).
    implementation("androidx.documentfile:documentfile:1.1.0")
    // «Oye Claude»: reconocimiento de voz en el propio móvil, sin conexión (Vosk, Apache 2.0).
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
    // Cámara de Claude.
    implementation("androidx.camera:camera-camera2:1.5.1")
    implementation("androidx.camera:camera-lifecycle:1.5.1")
    implementation("androidx.camera:camera-view:1.5.1")
    implementation("androidx.camera:camera-video:1.5.1")
    implementation("androidx.camera:camera-extensions:1.5.1")
    // camera-view y credentials traen fragment 1.2.5 (antes lo subía Play Services a 1.3.6): la API de
    // resultados de actividad necesita al menos la 1.3.
    implementation("androidx.fragment:fragment:1.3.6")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    // Memoria de pantalla: base de datos cifrada (SQLCipher, con búsqueda de texto completo FTS5).
    implementation("net.zetetic:sqlcipher-android:4.20.0")
    implementation("androidx.sqlite:sqlite:2.7.0")
    // Memoria de pantalla: la «huella» de las imágenes y de la consulta (CLIP en ONNX Runtime, en el
    // móvil). Solo el motor va en el APK; los pesos se descargan al activar la función.
    // Fijada en 1.28.0: desde la 1.29 la librería trae telemetría de Microsoft (1DS, Java y nativa),
    // inaceptable en una función privada. No subir de versión sin comprobarlo.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.28.0")
    // Bóveda: proveedor de contraseñas del Credential Manager de Android 14+ (Apache 2.0, sin telemetría).
    // Solo la librería base: sin «credentials-play-services-auth», que sería la parte de Google.
    implementation("androidx.credentials:credentials:1.6.0")
}

// ---------- Modelos del OCR (PP-OCRv5 de PaddleOCR, Apache 2.0) ----------
// Van dentro del APK (unos 13 MB) para que el móvil lea texto sin descargar nada. No se guardan en el
// repositorio: se bajan de Hugging Face (repositorios oficiales de PaddlePaddle) en una revisión fija,
// se comprueba el SHA-256 de cada uno y se guardan en la caché de Gradle (las compilaciones siguientes
// no necesitan red). El diccionario de caracteres sí está en el repositorio (assets/ocr/latin.txt).
abstract class ModelosOcr : DefaultTask() {
    /** nombre del archivo en assets/ocr → "url sha256". */
    @get:Input abstract val modelos: MapProperty<String, String>
    @get:Internal abstract val cache: DirectoryProperty
    @get:OutputDirectory abstract val salida: DirectoryProperty

    @TaskAction
    fun bajar() {
        val dir = salida.get().asFile.resolve("ocr").apply { mkdirs() }
        for ((nombre, valor) in modelos.get()) {
            val (url, sha) = valor.split(" ")
            val guardado = cache.get().asFile.resolve(sha)
            if (!guardado.isFile || sha256(guardado) != sha) {
                guardado.parentFile.mkdirs()
                val parte = File(guardado.path + ".parte")
                logger.lifecycle("OCR: descargando $nombre")
                URI(url).toURL().openStream().use { e -> parte.outputStream().use { e.copyTo(it) } }
                check(sha256(parte) == sha) { "OCR: $nombre no coincide con su SHA-256 ($url)" }
                parte.renameTo(guardado)
            }
            guardado.copyTo(dir.resolve(nombre), overwrite = true)
        }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { e -> val b = ByteArray(1 shl 16); while (true) { val n = e.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

val modelosOcr = tasks.register<ModelosOcr>("modelosOcr") {
    val hf = "https://huggingface.co/PaddlePaddle"
    modelos.put("deteccion.onnx", "$hf/PP-OCRv5_mobile_det_onnx/resolve/e6f4fa85f00e168c862bc462aebca69eef9b3d3d/inference.onnx a431985659dc921974177a95adcfbb90fd9e51989a5e04d70d0b75f597b6e61d")
    modelos.put("reconocimiento.onnx", "$hf/latin_PP-OCRv5_mobile_rec_onnx/resolve/89d3a50e2c27e2e7cceeab0e944c25c807d5db4f/inference.onnx 7888113072263cb471b93f66dd5e2ad70548dc526fa1ace760d0d973dd121498")
    cache.set(File(gradle.gradleUserHomeDir, "caches/sios-ocr"))
    salida.set(layout.buildDirectory.dir("generated/modelosOcr"))
}

androidComponents {
    onVariants { variante -> variante.sources.assets?.addGeneratedSourceDirectory(modelosOcr, ModelosOcr::salida) }
}
