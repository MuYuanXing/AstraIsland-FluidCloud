plugins {
    alias(libs.plugins.android.library)
}

// 插件「流体云事件接入」的核心:读系统流体云送来的内容并翻译成星河岛的标准事件、藏起系统自己的同一个胶囊、
// 把按钮交回系统执行,以及在系统界面里拦下流体云插件的类加载器。只给插件应用(:fluidcloud)用;
// 星流的单测通过 testImplementation 带上它,把接入与星河岛摆放两边连起来核对(contracts/island-system-events.v1.json)。
android {
    namespace = "com.astraflow.fluidcloud.core"
    compileSdk = 37

    defaultConfig {
        minSdk = 35
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = false
    }

    lint {
        val enforceErrors = (project.findProperty("enforceLintErrors") as? String)?.toBoolean() == true
        abortOnError = enforceErrors
        warningsAsErrors = false
        checkReleaseBuilds = true
        ignoreWarnings = true
    }
}

dependencies {
    api(project(":island-events"))
    // libxposed API 101 — compileOnly,运行时由框架提供
    compileOnly(libs.libxposed.api)
    // DexKit — 在系统流体云插件文件里找监听处
    implementation(libs.dexkit)
    // 卡片说明文件里的矢量图标按路径画(PathParser)
    implementation(libs.core.ktx)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
