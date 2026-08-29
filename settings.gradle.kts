// 工程级设置：声明插件与依赖的仓库来源，并纳入各模块
// Kotlin DSL（.kts）配置，适用于 Gradle 8.x

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // 禁止各模块私自声明仓库，统一由这里管理，避免依赖来源不一致
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

// 根工程名称
rootProject.name = "Rwant"

// 纳入 app 主模块（给 AI 的「嘴」本体）
include(":app")
