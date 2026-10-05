package com.astraflow.fluidcloud.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences

/** 收系统界面里的插件报来的状态(见 [PluginStatus]),记下来给设置页显示;只认系统界面发来的。 */
class StatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != PluginStatus.ACTION_STATUS || sentFromPackage != PluginStatus.SYSTEM_UI) return
        val key = intent.getStringExtra(PluginStatus.EXTRA_KEY)?.takeIf { it in PluginStatus.KEYS } ?: return
        val value = intent.getStringExtra(PluginStatus.EXTRA_VALUE)?.take(160) ?: return
        StatusStore.prefs(context).edit().putString(key, value).apply()
    }
}

/** 系统界面里的插件报来的状态,设置页读它、听它的变化 */
object StatusStore {
    private const val PREFS = "status"

    fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 设置页打开时:先清掉上一次的,再请系统界面里的插件重新报一遍(插件没在系统界面里运行时就收不到回报) */
    fun refresh(context: Context) {
        prefs(context).edit().clear().apply()
        context.sendBroadcast(Intent(PluginStatus.ACTION_REQUEST).setPackage(PluginStatus.SYSTEM_UI))
    }
}
