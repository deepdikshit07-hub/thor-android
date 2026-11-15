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

val thorVersionCode = providers.gradleProperty("thorVersionCode").orNull?.toIntOrNull() ?: 2
val thorVersionName = providers.gradleProperty("thorVersionName").orNull ?: "2.1.0"
val releaseKeystorePath = providers.gradleProperty("thorKeystorePath").orNull

android {
    namespace = "com.thor.assistant"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.thor.assistant"
        minSdk = 24
        targetSdk = 35
        versionCode = thorVersionCode
        versionName = thorVersionName
        ndk { abiFilters += setOf("arm64-v8a") }
    }

    signingConfigs {
        create("release") {
            if (releaseKeystorePath != null) {
                storeFile = file(releaseKeystorePath)
            }
            storePassword = providers.gradleProperty("thorStorePassword").orNull
            keyAlias = providers.gradleProperty("thorKeyAlias").orNull
            keyPassword = providers.gradleProperty("thorKeyPassword").orNull
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
}

dependencies {
    implementation(platform("com.google.firebase:firebase-bom:35.1.0"))
    implementation(files("libs/sherpa-onnx-2.0.1-arm64.aar"))
    implementation("com.google.firebase:firebase-ai")
    implementation("com.google.firebase:firebase-appcheck-debug")
    implementation("com.google.firebase:firebase-appcheck-playintegrity")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
}
