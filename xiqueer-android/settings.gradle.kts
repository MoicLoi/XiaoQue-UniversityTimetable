pluginManagement {
    plugins {
        id("com.android.application") version "9.3.0"
        id("com.android.test") version "9.3.0"
        id("org.jetbrains.kotlin.android") version "2.4.10"
        id("org.jetbrains.kotlin.jvm") version "2.4.10"
        id("org.jetbrains.kotlin.plugin.compose") version "2.4.10"
    }
    repositories {
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
    }
}

rootProject.name = "xiqueer-android"

include(":app")

// 协议层与 CLI 共用同一份源码,不复制
include(":protocol")
project(":protocol").projectDir = file("../protocol/kotlin")

// P3a:Macrobenchmark 与 Baseline Profile 生成。
// 单独一个 test 模块 —— 它要被装到设备上跑,不能混进 app 的依赖里。
include(":macrobenchmark")
