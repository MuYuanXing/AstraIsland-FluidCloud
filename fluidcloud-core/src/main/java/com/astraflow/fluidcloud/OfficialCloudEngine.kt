package com.astraflow.fluidcloud

import android.content.Context

/**
 * 手机上实际运行的官方卡片引擎。引擎装在官方服务应用 [HOST] 的资源里,随这个应用更新,不随系统版本走
 * (测试机 ColorOS 17 自带 17.20.25,已被更新到 17.22.8),所以按它的版本号判断,不按系统版本判断。
 * 17 以前的引擎(ColorOS 15 自带 16.0、ColorOS 16 自带 16.57)不读 [ENGINE_17_ATTRS] 这几个属性,右侧排法的数值也不同。
 */
object OfficialCloudEngine {
    private const val HOST = "com.oplus.pantanal.ums"
    private const val ENGINE_17_VERSION = 17_000_000L
    /** 内容树上的标记:这个元素由 17 以前的引擎排版,星河岛照那一版的数值画 */
    const val BEFORE_17 = "engineBefore17"
    private val ENGINE_17_ATTRS = setOf("layoutSize", "d1FillParent", "d1MinWidth")

    @Volatile private var before17 = false

    fun bind(context: Context) {
        before17 = isBefore17(context.packageManager.getPackageInfo(HOST, 0).longVersionCode)
    }

    fun isBefore17(versionCode: Long): Boolean = versionCode < ENGINE_17_VERSION

    /** 内测反馈包:这台手机上官方卡片引擎(服务应用 [HOST])的版本,以及是不是按 17 以前的引擎画 */
    fun describe(context: Context): String = context.packageManager.getPackageInfo(HOST, 0)
        .let { "$HOST ${it.versionName}(${it.longVersionCode}) before17=$before17" }

    /** 元素属性按这台手机的引擎取舍:17 以前的引擎不认的属性去掉,并打上 [BEFORE_17] */
    fun attrs(attrs: Map<String, String>, before17: Boolean = this.before17): Map<String, String> =
        if (before17) attrs - ENGINE_17_ATTRS + (BEFORE_17 to "true") else attrs
}
