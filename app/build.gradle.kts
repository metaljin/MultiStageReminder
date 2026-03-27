plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-kapt") // 必须：用于处理 Room 数据库注解 [cite: 184, 187]
}

android {
    namespace = "com.reminder.multistage"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.reminder.multistage"
        minSdk = 26
        targetSdk = 34 // 建议改为 34 以匹配 compileSdk
        versionCode = 1
        versionName = "1.0"
    }

    // 必须：你在 MainActivity.kt 中使用了 ActivityMainBinding [cite: 186]
    buildFeatures {
        viewBinding = true
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
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0") // 解决主题找不到的问题 
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // 必须：添加 Room 数据库依赖 
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    kapt("androidx.room:room-compiler:$roomVersion")

    // 必须：添加协程和 Lifecycle 依赖（支持你代码中的 Flow 和 lifecycleScope） [cite: 188, 191]
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")
}
