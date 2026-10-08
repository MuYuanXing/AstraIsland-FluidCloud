package com.astraflow.fluidcloud

import android.content.SharedPreferences
import android.os.Bundle
import com.astraisland.events.EventBridge

/**
 * 插件「流体云事件接入」的设置:接入开关,四类内容的开关(来电与通话、计时与闹钟、实时活动、系统状态),
 * 系统状态里哪几样能作为主岛显示。插件应用写,系统界面里的插件读,再交给星河岛(四类开关管星河岛上这几类内容,
 * 不只是系统流体云送来的)。默认全开。
 */
data class FluidCloudSettings(
    val access: Boolean = true,
    val call: Boolean = true,
    val timer: Boolean = true,
    val live: Boolean = true,
    val status: Boolean = true,
    val mainRecording: Boolean = true,
    val mainCharging: Boolean = true,
    val mainHeadset: Boolean = true,
    val mainSwitch: Boolean = true,
) {
    /** 每一项的键和值(键与交给星河岛的同名) */
    fun toMap(): Map<String, Boolean> = linkedMapOf(
        EventBridge.SETTING_ACCESS to access,
        EventBridge.SETTING_CALL to call,
        EventBridge.SETTING_TIMER to timer,
        EventBridge.SETTING_LIVE to live,
        EventBridge.SETTING_STATUS to status,
        EventBridge.SETTING_MAIN_RECORDING to mainRecording,
        EventBridge.SETTING_MAIN_CHARGING to mainCharging,
        EventBridge.SETTING_MAIN_HEADSET to mainHeadset,
        EventBridge.SETTING_MAIN_SWITCH to mainSwitch,
    )

    /** 交给星河岛的那一份 */
    fun toBundle(): Bundle = Bundle().apply { toMap().forEach { (key, on) -> putBoolean(key, on) } }

    companion object {
        /** 远端设置(插件应用写、系统界面里读)的组名 */
        const val GROUP = "fluidcloud"

        /** 远端设置里记用户点「重新开启」的时刻(插件因为多次出错自动暂停以后);晚于这次暂停时,下一次系统界面启动照常启动 */
        const val KEY_RESUME_AT = "resumeAt"

        /** 设置项的键;与交给星河岛的键同名 */
        val KEYS = listOf(EventBridge.SETTING_ACCESS, EventBridge.SETTING_CALL, EventBridge.SETTING_TIMER, EventBridge.SETTING_LIVE,
            EventBridge.SETTING_STATUS, EventBridge.SETTING_MAIN_RECORDING, EventBridge.SETTING_MAIN_CHARGING,
            EventBridge.SETTING_MAIN_HEADSET, EventBridge.SETTING_MAIN_SWITCH)

        fun read(prefs: SharedPreferences?): FluidCloudSettings {
            fun bool(key: String) = runCatching { prefs?.getBoolean(key, true) }.getOrNull() ?: true
            return FluidCloudSettings(bool(EventBridge.SETTING_ACCESS), bool(EventBridge.SETTING_CALL), bool(EventBridge.SETTING_TIMER),
                bool(EventBridge.SETTING_LIVE), bool(EventBridge.SETTING_STATUS), bool(EventBridge.SETTING_MAIN_RECORDING),
                bool(EventBridge.SETTING_MAIN_CHARGING), bool(EventBridge.SETTING_MAIN_HEADSET), bool(EventBridge.SETTING_MAIN_SWITCH))
        }

    }
}
