package com.astraisland.events

/**
 * 星流与接入件应用(例如插件「流体云事件接入」)之间的约定:星流经接入件应用的 [authority] 取它的状态、交原来的设置。
 * 接入件应用只回答官方星流:签名与接入件相同,或是星流的正式签名([HOST_CERT_SHA256]),自行编译的接入件也能回答官方星流。
 * 设置项的键与见面时交给星河岛的同名([EventBridge.SETTING_ACCESS] 等)。
 */
object AdapterApp {
    /** 插件「流体云事件接入」的包名 */
    const val FLUID_CLOUD_PACKAGE = "com.astraflow.fluidcloud"

    /** 星流的包名 */
    const val HOST_PACKAGE = "com.astraflow.tool"

    /** 星流正式签名证书的 SHA-256(小写十六进制) */
    const val HOST_CERT_SHA256 = "3c5f77034e84b07a75113c5c5c472cdf4a2e1c78b32485a19b996dafa9839245"

    fun authority(adapterPackage: String) = "$adapterPackage.state"

    /** 取状态:回 [KEY_VERSION_CODE] 与 [KEY_ENABLED](接入件在 LSPosed 里启用没有) */
    const val METHOD_STATE = "state"
    const val KEY_VERSION_CODE = "versionCode"
    const val KEY_ENABLED = "enabled"

    /**
     * 交原来的设置(接入开关、四类开关、作为主岛显示四项,键见 [EventBridge]):接入件还没有自己的设置时用上,
     * 用户在接入件里改过就不再用。回 [KEY_RESULT]:[RESULT_APPLIED] 用上了或不再需要,[RESULT_PENDING] 接入件还没启用,先记下、启用后用上。
     */
    const val METHOD_IMPORT = "import"
    const val KEY_RESULT = "result"
    const val RESULT_APPLIED = "applied"
    const val RESULT_PENDING = "pending"

    /**
     * 重新开启:接入件因为多次出错自动暂停后,用户点「重新开启」。接入件记下这一刻,下一次系统界面启动时照常启动。
     * 回 [KEY_RESULT]:[RESULT_APPLIED] 记下了,[RESULT_UNAVAILABLE] 接入件没在 LSPosed 里启用、记不下。
     */
    const val METHOD_RESUME = "resume"
    const val RESULT_UNAVAILABLE = "unavailable"

    /** 接入件应用的设置页(星流从星河岛页打开它) */
    const val ACTION_SETTINGS = "com.astraisland.events.action.ADAPTER_SETTINGS"
}
