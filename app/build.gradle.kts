plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val ciVersionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
val ciVersionName = System.getenv("VERSION_NAME")?.takeIf { it.isNotBlank() } ?: "1.0.$ciVersionCode"
val keystorePath = System.getenv("SIGNING_KEYSTORE_PATH")
val keystoreFile = if (!keystorePath.isNullOrBlank()) file(keystorePath) else null
val hasCustomSigning = keystoreFile != null && keystoreFile.exists()

android {
    namespace = "dm1sh.hedgehog"
    compileSdk = 35

    defaultConfig {
        applicationId = "dm1sh.hedgehog"
        minSdk = 24
        targetSdk = 34
        versionCode = ciVersionCode
        versionName = ciVersionName

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    signingConfigs {
        if (hasCustomSigning) {
            create("persistent") {
                storeFile = keystoreFile
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (hasCustomSigning) {
                signingConfigs.getByName("persistent")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable = true
            if (hasCustomSigning) {
                signingConfig = signingConfigs.getByName("persistent")
            }
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Networking & WebSocket for Online Edge TTS + Voice Catalog Download
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Offline Embedded TTS (Microsoft Cognitive Services Speech SDK + jump3r LAME MP3 encoder)
    implementation("com.microsoft.cognitiveservices.speech:client-sdk-embedded:1.40.0")
    implementation("de.sciss:jump3r:1.0.5")

    // XML / XHTML DOM parser for FB2 & EPUB footnote inlining
    implementation("org.jsoup:jsoup:1.18.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
