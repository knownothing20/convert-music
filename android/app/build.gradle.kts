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

    signingConfigs {
        create("release") {
            // 由 CI 从 Secrets 解密出的固定密钥库，保证每次构建签名一致、可以覆盖安装
            val ksPath = System.getenv("KEYSTORE_PATH")
            if (!ksPath.isNullOrBlank()) {
                val f = rootProject.file(ksPath)
                if (f.exists()) {
                    storeFile = f
                    storeType = "PKCS12"
                    storePassword = System.getenv("KEYSTORE_PASSWORD")
                    keyAlias = System.getenv("KEY_ALIAS")
                    keyPassword = System.getenv("KEY_PASSWORD")
                }
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.storeFile?.let {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            signingConfigs.findByName("release")?.storeFile?.let {
                signingConfig = signingConfigs.getByName("release")
            }
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
