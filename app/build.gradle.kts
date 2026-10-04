val ciVersionCode = providers.environmentVariable("MUNOTE_VERSION_CODE")
    .orNull
    ?.toIntOrNull()
    ?: 1
val ciVersionName = providers.environmentVariable("MUNOTE_VERSION_NAME")
    .orNull
    ?: "0.2.0-dev"
val releaseKeystore = providers.environmentVariable("MUNOTE_SIGNING_KEYSTORE").orNull
val releaseStorePassword = providers.environmentVariable("MUNOTE_SIGNING_STORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("MUNOTE_SIGNING_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("MUNOTE_SIGNING_KEY_PASSWORD").orNull

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "dev.munote.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.munote.app"
        minSdk = 29
        targetSdk = 36
        versionCode = ciVersionCode
        versionName = ciVersionName
        manifestPlaceholders["appLabel"] = "MuNote"
    }

    signingConfigs {
        create("releasePrivate") {
            if (!releaseKeystore.isNullOrBlank()) {
                storeFile = file(releaseKeystore)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
        create("test") {
            // Public test-only key. Never use this signing config for production/release builds.
            storeFile = file("keystore/munote-test.keystore")
            storePassword = "munote-test-only"
            keyAlias = "munote-test"
            keyPassword = "munote-test-only"
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-test"
            manifestPlaceholders["appLabel"] = "MuNote Test"
            signingConfig = signingConfigs.getByName("test")
        }
        release {
            isMinifyEnabled = true
            manifestPlaceholders["appLabel"] = "MuNote"
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("releasePrivate")
            // The private release key is supplied only by GitHub Actions/runtime environment.
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.08.00"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.3")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("com.google.mlkit:digital-ink-recognition:19.0.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
