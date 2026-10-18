package com.astraflow.fluidcloud.app

import android.content.SharedPreferences
import com.astraflow.fluidcloud.FluidCloudSettings
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 插件应用这一边的设置:写进模块框架托管的远端设置(系统界面里的插件读同一份,改了立即生效)。
 * 插件在 LSPosed 里启用后,模块框架才把服务交给插件应用;没交来时设置页写明原因,开关不能拨。
 */
object PluginSettings {
    private val bound = CountDownLatch(1)
    @Volatile private var remote: SharedPreferences? = null
    private val mutableEnabled = MutableStateFlow(false)
    /** 插件在 LSPosed 里启用了(模块框架交来了服务,拿得到远端设置) */
    val enabled: StateFlow<Boolean> = mutableEnabled.asStateFlow()
    private val mutableSettings = MutableStateFlow(FluidCloudSettings())
    val settings: StateFlow<FluidCloudSettings> = mutableSettings.asStateFlow()

    fun start() {
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                runCatching { service.getRemotePreferences(FluidCloudSettings.GROUP) }.getOrNull()?.let(::connect)
            }
            override fun onServiceDied(service: XposedService) = disconnect()
        })
    }

    /** 拿到了远端设置:读取插件自己的设置。 */
    @Synchronized
    internal fun connect(prefs: SharedPreferences) {
        remote = prefs
        mutableSettings.value = FluidCloudSettings.read(prefs)
        mutableEnabled.value = true
        bound.countDown()
    }

    @Synchronized
    private fun disconnect() {
        remote = null
        mutableEnabled.value = false
    }

    /** 等模块框架交来服务,最多等 [timeoutMs];插件没在 LSPosed 里启用时不会交来 */
    fun awaitEnabled(timeoutMs: Long): Boolean {
        if (remote != null) return true
        bound.await(timeoutMs, TimeUnit.MILLISECONDS)
        return remote != null
    }

    /** 用户改设置;没启用(拿不到远端设置)时不改,返回假 */
    @Synchronized
    fun update(change: FluidCloudSettings.() -> FluidCloudSettings): Boolean {
        val prefs = remote ?: return false
        val next = mutableSettings.value.change()
        if (!write(prefs, next)) return false
        mutableSettings.value = next
        return true
    }

    /** 用户点「重新开启」:记下这一刻,下一次系统界面启动时照常启动;没启用(拿不到远端设置)时记不下,返回假 */
    fun requestResume(now: Long = System.currentTimeMillis()): Boolean {
        val prefs = remote ?: return false
        return runCatching { prefs.edit().putLong(FluidCloudSettings.KEY_RESUME_AT, now).commit() }.getOrDefault(false)
    }

    private fun write(prefs: SharedPreferences, values: FluidCloudSettings): Boolean = runCatching {
        val editor = prefs.edit()
        values.toMap().forEach { (key, on) -> editor.putBoolean(key, on) }
        editor.commit()
    }.getOrDefault(false)

    /** 检查用:回到还没拿到服务的样子 */
    @Synchronized
    internal fun resetForTest() {
        remote = null
        mutableEnabled.value = false
        mutableSettings.value = FluidCloudSettings()
    }
}
