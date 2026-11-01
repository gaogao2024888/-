plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.wxq.assist"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.wxq.assist"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    // 固定签名。用 Gradle 自动生成的 debug.keystore 的话，CI 每次构建都会
    // 新造一把密钥，签名一变 Android 就当成另一个应用，只能卸载重装，
    // 设置和校准数据全丢。固定下来才能覆盖安装保留数据。
    signingConfigs {
        create("stable") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "wxqassist"
            keyAlias = "wxqassist"
            keyPassword = "wxqassist"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("stable")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("stable")
        }
    }

    buildFeatures {
        buildConfig = true
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    // 中文 OCR，模型随 APK 打包，完全离线
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
}
