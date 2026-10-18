plugins {
    alias(libs.plugins.android.library)
}

// 星河岛系统事件:各家手机系统的接入件(例如插件「流体云事件接入」)与星河岛之间交换的标准事件、它的编码和对接约定
// (contracts/island-system-events.v1.json)。星流与接入件各带一份本模块;同一进程里两边的类互不相认,
// 只交换系统自带类型做成的包裹(Bundle、位图、系统图标),见 SystemEventCodec。
android {
    namespace = "com.astraisland.events"
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

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
