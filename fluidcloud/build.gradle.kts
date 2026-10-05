import java.util.Properties
import org.gradle.api.GradleException

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// 插件「流体云事件接入」:OPPO、一加、真我手机上的 LSPosed 模块,只进系统界面。
// 读系统流体云送来的内容交给星河岛、把系统自己的同一个胶囊藏起来、星河岛上点按钮时交回系统执行(核心在 :fluidcloud-core)。
// 星河岛只收和星流同一把签名的插件:正式包只由星流维护者用正式签名打出。

// 版本:「X.Y.Z」→ X*10000+Y*100+Z(Y、Z 都在 0 到 99 之间);每次在插件商店上架,版本号必须变大。
val pluginVersionName = "1.0.3"
val pluginVersionCode = 10003
val pluginVersionMatch = Regex("""^(\d+)\.(\d{1,2})\.(\d{1,2})$""").matchEntire(pluginVersionName)
    ?: throw GradleException("pluginVersionName must be X.Y.Z: $pluginVersionName")
val (pluginMajor, pluginMinor, pluginPatch) = pluginVersionMatch.destructured
if (pluginMajor.toInt() * 10000 + pluginMinor.toInt() * 100 + pluginPatch.toInt() != pluginVersionCode) {
    throw GradleException("pluginVersionCode $pluginVersionCode does not match $pluginVersionName")
}

// 签名与星流相同:先认 CI 环境变量,再认 local.properties
val ciKeystore = System.getenv("KEYSTORE_FILE")
val ciStorePassword = System.getenv("STORE_PASSWORD")
val ciKeyAlias = System.getenv("KEY_ALIAS")
val ciKeyPassword = System.getenv("KEY_PASSWORD")
val hasCiSigning = listOf(ciKeystore, ciStorePassword, ciKeyAlias, ciKeyPassword).all { !it.isNullOrEmpty() }
val localProps = Properties().apply {
    val localFile = rootProject.file("local.properties")
    if (localFile.exists()) localFile.inputStream().use { load(it) }
}
val hasLocalSigning = listOf("signing.storeFile", "signing.storePassword", "signing.keyAlias", "signing.keyPassword").all(localProps::containsKey)
val hasReleaseSigning = hasCiSigning || hasLocalSigning

android {
    namespace = "com.astraflow.fluidcloud"
    compileSdk = 37

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(if (hasCiSigning) ciKeystore!! else localProps.getProperty("signing.storeFile"))
                storePassword = if (hasCiSigning) ciStorePassword else localProps.getProperty("signing.storePassword")
                keyAlias = if (hasCiSigning) ciKeyAlias else localProps.getProperty("signing.keyAlias")
                keyPassword = if (hasCiSigning) ciKeyPassword else localProps.getProperty("signing.keyPassword")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = false
            }
        }
    }

    defaultConfig {
        applicationId = "com.astraflow.fluidcloud"
        // 最低安卓 15,与星流相同
        minSdk = 35
        targetSdk = 36
        versionCode = pluginVersionCode
        versionName = pluginVersionName
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    // 代码搜索(DexKit)的本机库要解压出来,系统界面才能从插件安装包里加载
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += listOf("DebugProbesKt.bin", "kotlin-tooling-metadata.json", "META-INF/*.version")
        }
    }

    lint {
        val enforceErrors = (project.findProperty("enforceLintErrors") as? String)?.toBoolean() == true
        abortOnError = enforceErrors
        warningsAsErrors = false
        checkReleaseBuilds = true
        ignoreWarnings = true
    }
}

// 缺正式签名材料时不出正式包:星河岛只收和星流同一把签名的插件
val releaseSigningReady = hasReleaseSigning
tasks.configureEach {
    if (name in setOf("assembleRelease", "bundleRelease", "packageRelease")) {
        inputs.property("releaseSigningReady", releaseSigningReady)
        doFirst {
            if (!(inputs.properties["releaseSigningReady"] as Boolean)) throw GradleException("插件正式包缺少正式签名材料，已拒绝继续执行。")
        }
    }
}

tasks.withType<Test>().configureEach {
    // 界面部件上游 compose-hig 依赖的形状库按 Java 21 编译,Java 17 读不了
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
}

dependencies {
    implementation(project(":fluidcloud-core"))
    // libxposed API 101:运行时由框架提供
    compileOnly(libs.libxposed.api)
    // libxposed Service:插件应用写设置、知道自己在 LSPosed 里启用没有
    implementation(libs.libxposed.service)

    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    // 设置页的部件原样用上游 compose-hig(分组列表、开关、打开下一页的行);顶部栏的玻璃取页面画面要用 backdrop
    implementation(libs.compose.hig)
    // 右上角使用说明的问号:上游 compose-hig 的 iOS 样子扩展图标(和星流同一个图形)
    implementation(libs.compose.hig.icons)
    implementation(libs.kyant.backdrop)

    testImplementation(libs.junit4)
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation(libs.libxposed.api)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
