plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-kapt") // 必须：处理 Room 数据库注解
}

android {
    namespace = "com.reminder.multistage"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.reminder.multistage"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
    }

    // 签名配置：从环境变量读取 GitHub Secrets
    signingConfigs {
        create("release") {
            // 如果环境变量中没有路径，默认找根目录下的 release.jks
            val path = System.getenv("KEY_STORE_PATH") ?: "release.jks"
            storeFile = file(path)
            storePassword = System.getenv("KEY_STORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS")
            keyPassword = System.getenv("KEY_PASSWORD")
        }
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.4" // 请确保与你的 Kotlin 版本匹配
    }

    buildTypes {
        release {
            // --- 开启混淆优化 ---
            isMinifyEnabled = true    // 开启代码混淆
            isShrinkResources = true  // 开启资源缩减（移除无用图片等）
            
            // 引用标准优化规则和你刚才写的自定义规则
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            
            // 应用上面定义的签名
            signingConfig = signingConfigs.getByName("release")
        }
        
        debug {
            // Debug 模式通常不混淆，方便开发调试
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
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    
    // Compose 基础
    val composeBom = platform("androidx.compose:compose-bom:2024.02.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
	implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")

    // Room 和 协程
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    kapt("androidx.room:room-compiler:$roomVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
