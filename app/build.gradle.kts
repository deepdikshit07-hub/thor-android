plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget("17"))
    }
}

android {
    namespace = "com.thor.assistant"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.thor.assistant"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }


    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

 
dependencies {
    // Firebase AI Logic: Gemini access through Firebase's secure proxy.
    implementation(platform("com.google.firebase:firebase-bom:35.1.0"))
    implementation("com.google.firebase:firebase-ai")
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-messaging")
    // BiometricPrompt + Android Keystore-backed approval signing.
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("com.google.firebase:firebase-appcheck-debug")
    implementation("com.google.firebase:firebase-appcheck-playintegrity")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // WebSocket transport for bidirectional Gemini Live audio.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // On-device multilingual language model (downloaded separately from the APK).
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.0")
    // LiteRT runtime for WeSpeaker embeddings.
    implementation("com.google.ai.edge.litert:litert:2.1.5")
}
