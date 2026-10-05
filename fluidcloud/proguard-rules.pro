# 插件「流体云事件接入」的正式包混淆规则

-repackageclasses ''
-renamesourcefileattribute ''

# 入口类:模块框架按 META-INF/xposed/java_init.list 里的类名反射创建
-keep class com.astraflow.fluidcloud.app.HookEntry { *; }

# libxposed API 101:运行时由框架提供(compileOnly)
-keep class io.github.libxposed.api.** { *; }
-dontwarn io.github.libxposed.api.**

# libxposed Service:模块框架按类名调用它的提供方交来服务
-keep class io.github.libxposed.service.** { *; }
-dontwarn io.github.libxposed.service.**

# DexKit:本机库按类名和方法名回调
-keep class org.luckypray.dexkit.DexKitBridge { *; }
-keepclassmembers class org.luckypray.dexkit.** { native <methods>; }
-keep class org.luckypray.dexkit.result.** { *; }
-keep class org.luckypray.dexkit.query.** { *; }
-dontwarn org.luckypray.dexkit.**

# 挂钩工具:R8 可能把单例内联到调用处,打乱挂钩的登记
-keep class com.astraflow.fluidcloud.hook.HookHelper { *; }
-keep class com.astraflow.fluidcloud.hook.PluginClassLoaderInterceptor { *; }

-dontwarn androidx.compose.**
