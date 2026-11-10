plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.knownothing20.convertmusic"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.knownothing20.convertmusic"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
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
}

dependencies {
    // WebViewAssetLoader：把私有目录映射成虚拟 https 路径，让网页能 fetch 本地文件
    implementation("androidx.webkit:webkit:1.8.0")
    // DocumentFile：遍历用户选择的目录
    implementation("androidx.documentfile:documentfile:1.0.1")
}
