import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val secretsFile = rootProject.file("secrets.properties")
val secrets = Properties().apply {
    if (secretsFile.exists()) {
        val text = secretsFile.readText(Charsets.UTF_8).removePrefix("\uFEFF")
        text.reader().use { load(it) }
    }
}

android {
    namespace = "com.sarvam.guide"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.sarvam.guide"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "SARVAM_API_KEY", "\"${secrets.getProperty("SARVAM_API_KEY", "")}\"")
        buildConfigField("String", "GEMINI_API_KEY", "\"${secrets.getProperty("GEMINI_API_KEY", "")}\"")

        // Sarvam + model configuration (matches Phase 0 spike)
        buildConfigField("String", "STT_MODEL", "\"saaras:v3\"")
        buildConfigField("String", "STT_MODE", "\"codemix\"")
        buildConfigField("String", "TTS_MODEL", "\"bulbul:v3\"")
        buildConfigField("String", "TTS_SPEAKER", "\"priya\"")
        buildConfigField("String", "CHAT_MODEL", "\"sarvam-105b-conversations\"")
        buildConfigField("String", "VISION_MODEL", "\"gemini-3.5-flash-lite\"")
        buildConfigField("String", "LANG_CODE", "\"te-IN\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
    aaptOptions {
        noCompress("wav")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("com.alphacephei:vosk-android:0.3.47")
}