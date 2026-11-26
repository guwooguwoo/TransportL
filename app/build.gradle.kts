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
        versionCode = 6
        versionName = "4.0-PureMLKit"
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

    // 1단계 PDF 고해상도 텍스트 추출 (Android 내장 PdfRenderer + Google ML Kit 오프라인 문자 인식)
    implementation("com.google.mlkit:text-recognition:16.0.1")

    // 2단계 초고속·고정밀 오프라인 번역 엔진 (Google ML Kit Translate 단독)
    implementation("com.google.mlkit:translate:17.0.3")
}