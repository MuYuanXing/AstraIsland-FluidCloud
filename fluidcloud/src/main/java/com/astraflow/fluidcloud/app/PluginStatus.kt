package com.astraflow.fluidcloud.app

import com.astraflow.fluidcloud.BuildConfig

/**
 * 系统界面里的插件与插件应用之间报状态的约定:系统界面里的插件把状态发给插件应用(只认系统界面发来的),
 * 插件应用打开设置页时请它再报一遍(只收持有 [PERMISSION] 的发送方)。
 */
object PluginStatus {
    const val PLUGIN_PACKAGE = BuildConfig.APPLICATION_ID
    const val SYSTEM_UI = "com.android.systemui"
    const val PERMISSION = "com.astraflow.fluidcloud.permission.INTERNAL"

    const val ACTION_STATUS = "com.astraflow.fluidcloud.action.STATUS"
    const val ACTION_REQUEST = "com.astraflow.fluidcloud.action.REQUEST_STATUS"
    const val EXTRA_KEY = "key"
    const val EXTRA_VALUE = "value"

    /** 插件已在系统界面里运行:值是插件的版本号 */
    const val KEY_RUNNING = "running"
    /** 星河岛收没收下插件:connected、absent(系统界面里没有星河岛),或 refused:原因 */
    const val KEY_ISLAND = "island"
    /** 系统流体云的胶囊能不能藏:available、unavailable;还没定下来时为空 */
    const val KEY_CAPSULE = "capsule"
    /** 插件因为多次出错自动暂停:值是暂停的时刻(墙上时间,毫秒) */
    const val KEY_PAUSED = "paused"
    val KEYS = setOf(KEY_RUNNING, KEY_ISLAND, KEY_CAPSULE, KEY_PAUSED)
}
