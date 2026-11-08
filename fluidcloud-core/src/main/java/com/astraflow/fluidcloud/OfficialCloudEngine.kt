package com.astraflow.fluidcloud

import android.content.Context

/**
 * 手机上实际运行的官方卡片引擎。引擎装在官方服务应用 [HOST] 的资源里,随这个应用更新,不随系统版本走
 * (测试机 ColorOS 17 自带 17.20.25,已被更新到 17.22.8),所以按它的版本号判断,不按系统版本判断。
 * 17 以前的引擎(ColorOS 15 自带 16.0、ColorOS 16 自带 16.57)不读 [ENGINE_17_ATTRS] 这几个属性,右侧排法的数值也不同;
 * ColorOS 15 自带的 16.0 还不认 chronometer、页面级 coeRes 与 coeDrawable,这两代按 [ENGINE_16_57_VERSION] 分开。
 */
object OfficialCloudEngine {
    private const val HOST = "com.oplus.pantanal.ums"
    private const val ENGINE_17_VERSION = 17_000_000L
    private const val ENGINE_16_57_VERSION = 16_057_000L
    /** 内容树上的标记:这个元素由 17 以前的引擎排版,星河岛照那一版的数值画 */
    const val BEFORE_17 = "engineBefore17"
    private val ENGINE_17_ATTRS = setOf("layoutSize", "d1FillParent", "d1MinWidth", "manuallyPlay", "noTransformEffect",
        "themeColor", "progressType", "progressIcon", "indicatorStyle", "ellipsizeEnd", "cardLight", "customBgConfig")
    private val ENGINE_16_ATTRS = setOf("chronometer", "coeRes", "coeDrawable")
    /** 只有 ColorOS 17 的字换值过渡写法;旧引擎遇到这些值按默认 fade */
    private val ENGINE_17_TRANSFORMS = setOf("alphaScaleBlur", "translateAlphaRight", "translateAlphaUpOutDownIn",
        "translateAlphaDownOutUpIn", "translateAlphaDown", "translateAlphaLeft", "odometer", "translateAlphaUp")

    @Volatile private var before17 = false
    @Volatile private var before16 = false

    fun bind(context: Context) {
        val version = context.packageManager.getPackageInfo(HOST, 0).longVersionCode
        before17 = isBefore17(version)
        before16 = isBefore16(version)
    }

    fun isBefore17(versionCode: Long): Boolean = versionCode < ENGINE_17_VERSION

    fun isBefore16(versionCode: Long): Boolean = versionCode < ENGINE_16_57_VERSION

    /** 元素属性按这台手机的引擎取舍:引擎不认的属性去掉,并打上 [BEFORE_17] */
    fun attrs(attrs: Map<String, String>, before17: Boolean = this.before17, before16: Boolean = this.before16): Map<String, String> {
        var out = attrs
        if (before17) {
            out = out - ENGINE_17_ATTRS
            if (out["transformEffect"] in ENGINE_17_TRANSFORMS) out = out - "transformEffect"
        }
        if (before16) out = out - ENGINE_16_ATTRS
        return if (before17) out + (BEFORE_17 to "true") else out
    }

    /**
     * 文字元素 classList 里第一个样式类给的字号(官方 TextLoader):文字元素走跑马灯文字控件,三代都是
     * sm-font 16、lg-font 26、其余 20(C15 `MarqueeEffectTextView:88-98`、C16 同、C17 `b8/a0.java:196-203`);
     * 段(span)走富文字的字号span,三代都是 sm-font 14、xs-font 12、xxs-font 10、xxxs-font 8、md-font 16、
     * top/bottom 10、min/max 8、其余 16(C15 `a6/kh.java:1095-1105`、C17 `e7/jj.java:5329-5346`)。
     * 没写样式类时为空(不覆盖排版默认值)。字号单位与官方一致是 dp。
     */
    fun classFontSize(className: String?, kind: String = "text"): String? {
        if (className == null) return null
        val size = if (kind == "span") when (className) {
            "sm-font" -> 14
            "xs-font" -> 12
            "xxs-font" -> 10
            "xxxs-font" -> 8
            "md-font" -> 16
            "top", "bottom" -> 10
            "min", "max" -> 8
            else -> 16
        } else when (className) {
            "sm-font" -> 16
            "lg-font" -> 26
            else -> 20
        }
        return size.toString()
    }
}
