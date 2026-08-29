// 工程级构建脚本：只声明插件及其版本，不做模块级配置
// 版本组合：AGP 8.5.2 + Kotlin 2.0.21（均为官方稳定版，互相兼容）
// 要求：Gradle >= 8.7、JDK 17

plugins {
    // Android Gradle Plugin（应用模块）
    id("com.android.application") version "8.5.2" apply false

    // Kotlin Android 插件（2.0.x，K2 编译器）
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}
