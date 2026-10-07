plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.tae.apprecommend"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tae.apprecommend"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    // CI가 빌드할 때마다 새 디버그 키를 만들면 업데이트 설치가 서명 충돌로 실패해서,
    // 저장소에 고정된 디버그 키로 서명한다. (디버그 전용, 비밀 아님)
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
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
}

dependencies {
    implementation(libs.glance.appwidget)
    implementation(libs.work.runtime.ktx)
    implementation(libs.activity.ktx)
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.ktx)
}
