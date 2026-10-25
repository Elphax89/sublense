plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Every GitHub build gets a higher version number so updates install over the old app.
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
        // Phone processors (ARM) only – saves a lot of space compared to all architectures.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    signingConfigs {
        // The release key is never stored in the repo. CI decodes it from GitHub Secrets and
        // passes its path and password via environment variables (see .github/workflows/build.yml).
        val keystorePath = System.getenv("SUBLENS_KEYSTORE")
        val keystorePassword = System.getenv("SUBLENS_PASSWORD")
        if (!keystorePath.isNullOrBlank() && !keystorePassword.isNullOrBlank()) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = keystorePassword
                keyAlias = "sublens"
                keyPassword = keystorePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Without a key (local builds) the release APK stays unsigned; debug builds use Android's debug key.
            signingConfigs.findByName("release")?.let { signingConfig = it }
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
    // Text recognition (OCR) via Google Play services – small APK, models download once.
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.1")
    implementation("com.google.android.gms:play-services-mlkit-text-recognition-chinese:16.0.1")
    implementation("com.google.android.gms:play-services-mlkit-text-recognition-japanese:16.0.1")
    implementation("com.google.android.gms:play-services-mlkit-text-recognition-korean:16.0.1")
    implementation("com.google.android.gms:play-services-mlkit-text-recognition-devanagari:16.0.1")

    // QR codes via Google Play services (small APK)
    implementation("com.google.android.gms:play-services-mlkit-barcode-scanning:18.3.1")

    // Camera translation
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    implementation("androidx.activity:activity-ktx:1.9.3")

    // Language identification + offline translation
    implementation("com.google.mlkit:language-id:17.0.6")
    implementation("com.google.mlkit:translate:17.0.3")

    // Unit tests on the build machine (online translation against a mock service); real org.json instead of Android's stub
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
