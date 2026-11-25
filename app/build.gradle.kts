plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.offlinepdftranslator"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.offlinepdftranslator"
        minSdk = 24
        targetSdk = 34
        versionCode = 3
        versionName = "2.1-LLM"
        multiDexEnabled = true
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
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.multidex:multidex:2.0.1")

    // 온디바이스 생성형 AI 엔진 (multi-prefill-seq 호환 최신 버전)
    implementation("com.google.mediapipe:tasks-genai:0.10.24")

    // 오프라인 PDF 텍스트 추출 (PdfBox Android)
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
}
