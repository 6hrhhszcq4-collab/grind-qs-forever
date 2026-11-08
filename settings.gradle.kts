// 项目级设置：声明依赖仓库与包含的模块
// 国内网络下优先使用阿里云镜像，失败时再回落到官方仓库
pluginManagement {
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // 只允许在这里声明仓库，模块里不再单独声明
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        google()
        mavenCentral()
    }
}

// 工程名（英文，避免中文路径带来的兼容问题）
rootProject.name = "Shuati"

// 只包含一个 app 模块
include(":app")
