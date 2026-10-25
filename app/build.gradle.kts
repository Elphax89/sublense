plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Jeder GitHub-Build bekommt eine höhere Versionsnummer, damit Updates über die alte App installieren.
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "app.sublens"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.sublens"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        versionName = "0.2.$buildNumber"
    }

    signingConfigs {
        // Fester Schlüssel im Repo, damit jede neue APK das vorhandene SubLens aktualisiert.
        // Für den Play Store einen eigenen, geheimen Schlüssel verwenden.
        create("oss") {
            storeFile = rootProject.file("signing/sublens.jks")
            storePassword = "sublens-oss"
            keyAlias = "sublens"
            keyPassword = "sublens-oss"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("oss")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("oss")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    // Texterkennung (OCR) über Google Play-Dienste – kleine APK, Modelle laden einmalig nach.
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.1")
    implementation("com.google.android.gms:play-services-mlkit-text-recognition-chinese:16.0.1")
    implementation("com.google.android.gms:play-services-mlkit-text-recognition-japanese:16.0.1")
    implementation("com.google.android.gms:play-services-mlkit-text-recognition-korean:16.0.1")
    implementation("com.google.android.gms:play-services-mlkit-text-recognition-devanagari:16.0.1")

    // Spracherkennung + Offline-Übersetzung
    implementation("com.google.mlkit:language-id:17.0.6")
    implementation("com.google.mlkit:translate:17.0.3")
}
