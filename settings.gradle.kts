pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // 单元测试使用的 Java 版本本机没有时，由 Gradle 自动下载（见 fluidcloud/build.gradle.kts）
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // 补丁版使用独立版本后缀,官方构件仍从公共仓库解析。
        mavenLocal()
    }
}

rootProject.name = "AstraIsland-FluidCloud"
include(":island-events")
include(":fluidcloud-core")
include(":fluidcloud")
