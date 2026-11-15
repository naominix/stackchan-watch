plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 署名固定用のデバッグキーストアを base64 から復元する。
// GitHub Actions の使い捨てランナーでも毎回同じ証明書で署名し、
// APK の上書きインストールを可能にするための仕組み（鍵はデバッグ専用）。
val debugKeystore = file("debug.keystore")
val debugKeystoreB64 = file("debug.keystore.b64")
if (!debugKeystore.exists() && debugKeystoreB64.exists()) {
    val encoded = debugKeystoreB64.readText().replace(Regex("\\s"), "")
    debugKeystore.writeBytes(java.util.Base64.getDecoder().decode(encoded))
}

android {
    namespace = "net.naominix.stackchanwatch"
    compileSdk = 34

    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    defaultConfig {
        applicationId = "net.naominix.stackchanwatch"
        minSdk = 30
        targetSdk = 34
        // CI では Run 番号を versionCode にして、更新として認識されるようにする
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "0.1.0"
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
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
}
