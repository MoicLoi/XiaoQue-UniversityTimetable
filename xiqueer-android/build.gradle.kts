// 根构建文件。插件版本统一在 settings.gradle.kts 的 pluginManagement 里声明,
// 这样 :protocol(纯 JVM 库)和 :app(Android)能共用同一个 Kotlin 版本。
plugins {
    id("com.android.application") apply false
    id("org.jetbrains.kotlin.jvm") apply false
    id("org.jetbrains.kotlin.plugin.compose") apply false
}
