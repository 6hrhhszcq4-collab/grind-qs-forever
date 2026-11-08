// 根工程构建脚本：只负责声明插件版本，具体配置在 app 模块里
plugins {
    // Android Gradle 插件
    id("com.android.application") version "8.11.0" apply false
    // Kotlin 安卓插件
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    // Kotlin 2.0 起 Compose 编译器改成独立插件
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
