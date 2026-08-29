// app 模块构建脚本：给 AI 的「嘴」（悬浮球 + 气泡 + TTS/ASR + MCP 通道）主体

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    // 命名空间：生成 R 类与 BuildConfig 的包名，须与源码包结构一致
    namespace = "com.hnnrry.rwant"

    // 编译 SDK 版本（Android 14）
    compileSdk = 34

    defaultConfig {
        // 应用唯一标识（包名），安装后不可更改
        applicationId = "com.hnnrry.rwant"

        // 最低支持 Android 8.0：覆盖悬浮窗 + TTS/ASR 所需能力
        minSdk = 26
        // 目标 SDK 版本（Android 14）
        targetSdk = 34

        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            // 发布包暂不开启混淆；后续接入 R8 规则后再打开
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        // Java 源码与字节码目标版本：17（AGP 8.x 要求 JDK 17）
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        // 与上方 compileOptions 保持一致，避免 Kotlin/Java 字节码版本不匹配
        jvmTarget = "17"
    }

    buildFeatures {
        // 开启 ViewBinding，布局控件类型安全访问
        viewBinding = true
    }
}

dependencies {
    // 核心扩展（Context/View 等的 Kotlin 扩展函数）
    implementation("androidx.core:core-ktx:1.13.1")
    // 兼容库（Activity/AppCompat 组件）
    implementation("androidx.appcompat:appcompat:1.7.0")
    // Material 组件库
    implementation("com.google.android.material:material:1.12.0")
    // 约束布局
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    // Kotlin 协程 Android 调度器（Main 线程调度）
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
