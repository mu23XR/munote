import java.net.URI
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// License: SIL OFL 1.1. Unmodified Noto Sans SC TrueType from google/fonts.
// Pin both commit and Git blob SHA-1. This gets packaged into the APK.
val muOcrFontBlob = "fb0637bafbcd804fe32152370a1225990745b4bc"
val muOcrFontUrl = "https://raw.githubusercontent.com/google/fonts/2eb0b48d5f760f62e286216f0859a8c540dbc1bd/ofl/notosanssc/NotoSansSC%5Bwght%5D.ttf"
val muOcrFontFile = layout.buildDirectory.file("generated/muocrFonts/fonts/MuOCR-NotoSansSC.ttf")

val prepareMuOcrFont = tasks.register("prepareMuOcrFont") {
    val output = muOcrFontFile.get().asFile
    outputs.file(output)
    doLast {
        output.parentFile.mkdirs()
        val temp = output.resolveSibling(output.name + ".tmp")
        try {
            val connection = URI(muOcrFontUrl).toURL().openConnection()
            connection.connectTimeout = 30000
            connection.readTimeout = 120000
            connection.getInputStream().use { input ->
                temp.outputStream().use { input.copyTo(it) }
            }
            val md = MessageDigest.getInstance("SHA-1")
            md.update("blob ${temp.length()}\u0000".toByteArray(Charsets.UTF_8))
            temp.inputStream().buffered().use { input ->
                val data = ByteArray(128 * 1024)
                while (true) {
                    val n = input.read(data)
                    if (n == -1) break
                    md.update(data, 0, n)
                }
            }
            val actual = md.digest().joinToString("") { "%02x".format(it) }
            check(actual == muOcrFontBlob) {
                "MuOCR fallback font hash mismatch: ${actual}"
            }
            if (output.exists()) output.delete()
            check(temp.renameTo(output)) { "Cannot save MuOCR font in generated assets" }
        } finally {
            temp.delete()
        }
    }
}

android {
    namespace = "dev.munote.ocrtool"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.munote.ocrtool"
        minSdk = 29
        targetSdk = 34
        versionCode = 7
        versionName = "1.1.1"
    }

    signingConfigs {
        create("test") {
            storeFile = file("../app/keystore/munote-test.keystore")
            storePassword = "munote-test-only"
            keyAlias = "munote-test"
            keyPassword = "munote-test-only"
        }
    }

    sourceSets {
        getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/muocrFonts"))
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("test")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("test")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.work:work-runtime-ktx:2.10.4")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    testImplementation("junit:junit:4.13.2")
    // JVM PDF regression: same incremental-save semantics as PDFBox-Android.
    testImplementation("org.apache.pdfbox:pdfbox:2.0.31")
}

tasks.matching {
    (it.name.startsWith("merge") && it.name.endsWith("Assets")) ||
        it.name.endsWith("UnitTest") || it.name == "preBuild"
}.configureEach {
    dependsOn(prepareMuOcrFont)
}
