package com.astraflow.fluidcloud.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import com.astraflow.fluidcloud.FluidCloudSettings
import com.astraflow.fluidcloud.hook.Log
import io.github.libxposed.api.XposedInterface

/**
 * 系统界面里读插件的设置:插件应用写、模块框架跨进程托管的那一份,变了就交给 [onChange]。
 * 拿不到时用默认值,不阻塞;不定时重试,亮屏、解锁时再接一次(模块框架在系统界面里没有「设置可用」的通知)。
 */
internal class SystemUiSettings(
    private val xposed: XposedInterface,
    private val context: Context,
    private val onChange: (FluidCloudSettings) -> Unit,
) {
    @Volatile private var prefs: SharedPreferences? = null
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> onChange(current()) }
    private var retry: BroadcastReceiver? = null

    init {
        if (!connect()) waitForSystemEvents()
    }

    fun current(): FluidCloudSettings = FluidCloudSettings.read(prefs)

    @Synchronized
    private fun connect(): Boolean {
        if (prefs != null) return true
        val acquired = runCatching { xposed.getRemotePreferences(FluidCloudSettings.GROUP) }
            .onFailure { Log.w(TAG, "settings unavailable: ${it.message}") }.getOrNull() ?: return false
        if (runCatching { acquired.registerOnSharedPreferenceChangeListener(listener) }.isFailure) return false
        prefs = acquired
        return true
    }

    private fun waitForSystemEvents() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (!connect()) return
                runCatching { context.unregisterReceiver(this) }
                retry = null
                Log.i(TAG, "settings connected on ${intent.action}")
                onChange(current())
            }
        }
        retry = receiver
        runCatching {
            context.registerReceiver(receiver, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }, Context.RECEIVER_NOT_EXPORTED)
        }.onFailure { Log.e(TAG, "system event receiver not registered", it) }
    }

    private companion object {
        const val TAG = "Settings"
    }
}
