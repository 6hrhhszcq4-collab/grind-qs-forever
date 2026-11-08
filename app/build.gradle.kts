// app 模块构建脚本：Android 应用本体
import java.io.FileInputStream
import java.util.Properties

// 签名密码不写进源码：放在本机的 local.properties（这个文件不随项目发给别人）。
// dist\签名信息.txt 另存了一份，丢了就没法再发升级包。
val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        FileInputStream(file).use { load(it) }
    }
}
// 正式签名文件的位置固定：app\release.keystore
val releaseKeystore = rootProject.file("app/release.keystore")

// 内置默认源配置：从本机 local.properties 读，不进仓库
val defaultSourceBaseUrl = localProps.getProperty("shuaati.defaultSource.baseUrl") ?: ""
val defaultSourceUser = localProps.getProperty("shuaati.defaultSource.user") ?: ""
val defaultSourcePass = localProps.getProperty("shuaati.defaultSource.pass") ?: ""
val defaultSourcePath = localProps.getProperty("shuaati.defaultSource.path") ?: ""
// 应用自身的更新检查仓库（GitHub Releases），也可在 local.properties 覆盖
val githubRepo = localProps.getProperty("shuaati.githubRepo") ?: "6hrhhszcq4-collab/grind-qs-forever"

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    // 包名（命名空间）
    namespace = "com.shuaati.app"
    // 用 API 36 编译
    compileSdk = 36
    // 本机只装了 Build Tools 36.1.0，显式指定，避免 AGP 去找默认的 35.0.0
    buildToolsVersion = "36.1.0"

    defaultConfig {
        applicationId = "com.shuaati.app"
        // 最低支持安卓 9（API 28）
        minSdk = 28
        // 目标版本与编译版本一致
        targetSdk = 36
        // 版本号可以改，签名不能换——继续用 app/release.keystore，否则覆盖安装会失败。
        versionCode = 7
        versionName = "1.6"

        // 应用自身更新检查仓库
        buildConfigField("String", "GITHUB_REPO", "\"$githubRepo\"")
    }

    // 两个 flavor：一个带内置源配置，一个不带；界面与按钮完全一致。
    flavorDimensions += "edition"
    productFlavors {
        create("community") {
            dimension = "edition"
            buildConfigField("boolean", "HAS_BUILTIN_SOURCE", "false")
            buildConfigField("String", "DEFAULT_SOURCE_BASE_URL", "\"\"")
            buildConfigField("String", "DEFAULT_SOURCE_USER", "\"\"")
            buildConfigField("String", "DEFAULT_SOURCE_PASS", "\"\"")
            buildConfigField("String", "DEFAULT_SOURCE_PATH", "\"\"")
            buildConfigField("String", "EDITION", "\"community\"")
        }
        create("internal") {
            dimension = "edition"
            buildConfigField("boolean", "HAS_BUILTIN_SOURCE", "true")
            buildConfigField("String", "DEFAULT_SOURCE_BASE_URL", "\"$defaultSourceBaseUrl\"")
            buildConfigField("String", "DEFAULT_SOURCE_USER", "\"$defaultSourceUser\"")
            buildConfigField("String", "DEFAULT_SOURCE_PASS", "\"$defaultSourcePass\"")
            buildConfigField("String", "DEFAULT_SOURCE_PATH", "\"$defaultSourcePath\"")
            buildConfigField("String", "EDITION", "\"internal\"")
        }
    }

    // 调试签名改用项目内的密钥文件：本机沙箱不允许写用户目录下的 .android
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }

        // 07：正式签名。alias 与两个密码写在 local.properties 里（不写进源码），
        // 这里读不到就留空，assembleRelease 会直接报错，而不是悄悄出一个未签名的包。
        create("release") {
            storeFile = releaseKeystore
            storePassword = localProps.getProperty("shuaati.storePassword")
            keyAlias = localProps.getProperty("shuaati.keyAlias") ?: "shuaati"
            keyPassword = localProps.getProperty("shuaati.keyPassword")
        }
    }

    buildTypes {
        release {
            // 11：按用户要求开启 R8 —— 代码压缩 + 混淆 + 资源收缩。
            // 出问题时的崩溃堆栈用 app/build/outputs/mapping/release/mapping.txt 还原（报告里写了路径）。
            // 保护规则见 app/proguard-rules.pro：只保留「按名字被系统调用」的入口与行号信息，
            // 应用自己的类一律参与混淆。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 07：装 release 包必须是这个正式签名，不用 debug 签名冒充
            signingConfig = signingConfigs.getByName("release")
        }
    }

    // Java 与 Kotlin 都按 Java 17 字节码编译（JDK 21 向下兼容）
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // 开启 Compose；v1.5 起要读 BuildConfig 里的版本/源配置
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    // Compose 统一版本清单
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))

    // Activity 的 Compose 入口
    implementation("androidx.activity:activity-compose:1.9.3")
    // Compose 基础布局与绘图
    implementation("androidx.compose.foundation:foundation")
    // Compose 界面组件
    implementation("androidx.compose.ui:ui")
    // Material 3 组件
    implementation("androidx.compose.material3:material3")
}
