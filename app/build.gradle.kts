
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mcqrootai"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mcqrootai"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    // Pertahankan sementara karena MainActivity lama masih memakainya
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // OCR
    implementation("com.google.mlkit:text-recognition:16.0.1")

    // AI lokal
    implementation("com.google.mediapipe:tasks-genai:0.10.27")
}
