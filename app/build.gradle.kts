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
        minSdk = 24
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
    implementation("com.google.firebase:firebase-appcheck-debug")
    implementation("com.google.firebase:firebase-appcheck-playintegrity")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // On-device multilingual language model (downloaded separately from the APK).
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.0")
}
