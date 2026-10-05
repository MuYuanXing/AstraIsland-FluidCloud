package com.astraflow.fluidcloud.app

import android.content.Context
import android.content.SharedPreferences
import com.astraflow.fluidcloud.FluidCloudSettings
import com.astraisland.events.AdapterApp
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
 *
 * 星流交来的原来的设置([import])只在插件还没有自己的设置时用上:用户在插件里改过一次,就以插件里的为准。
 * 插件还没启用时先记在本机,启用后第一次拿到服务时用上。
 */
object PluginSettings {
    /** 远端设置里记「已经有了插件自己的设置」的键(用户改过,或用上了星流交来的) */
    const val KEY_SETTLED = "settled"
    private const val LOCAL_PREFS = "pending_import"
    private const val KEY_PENDING = "pending"

    private val bound = CountDownLatch(1)
    @Volatile private var remote: SharedPreferences? = null
    private val mutableEnabled = MutableStateFlow(false)
    /** 插件在 LSPosed 里启用了(模块框架交来了服务,拿得到远端设置) */
    val enabled: StateFlow<Boolean> = mutableEnabled.asStateFlow()
    private val mutableSettings = MutableStateFlow(FluidCloudSettings())
    val settings: StateFlow<FluidCloudSettings> = mutableSettings.asStateFlow()
    private lateinit var local: SharedPreferences

    fun start(context: Context) {
        local = context.getSharedPreferences(LOCAL_PREFS, Context.MODE_PRIVATE)
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                runCatching { service.getRemotePreferences(FluidCloudSettings.GROUP) }.getOrNull()?.let(::connect)
            }
            override fun onServiceDied(service: XposedService) = disconnect()
        })
    }

    /** 拿到了远端设置:先用上还没用的星流原来的设置,再读出现在的设置 */
    @Synchronized
    internal fun connect(prefs: SharedPreferences) {
        remote = prefs
        applyPendingImport(prefs)
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

    /** 星流交来原来的设置;回 [AdapterApp.RESULT_APPLIED] 或 [AdapterApp.RESULT_PENDING] */
    @Synchronized
    fun import(values: FluidCloudSettings): String {
        val prefs = remote
        if (prefs == null) {
            val editor = local.edit().putBoolean(KEY_PENDING, true)
            values.toMap().forEach { (key, on) -> editor.putBoolean(key, on) }
            editor.apply()
            return AdapterApp.RESULT_PENDING
        }
        if (!prefs.getBoolean(KEY_SETTLED, false) && write(prefs, values)) mutableSettings.value = values
        return AdapterApp.RESULT_APPLIED
    }

    private fun applyPendingImport(prefs: SharedPreferences) {
        if (!local.getBoolean(KEY_PENDING, false)) return
        if (!prefs.getBoolean(KEY_SETTLED, false)) write(prefs, FluidCloudSettings.read(local))
        local.edit().clear().apply()
    }

    private fun write(prefs: SharedPreferences, values: FluidCloudSettings): Boolean = runCatching {
        val editor = prefs.edit()
        values.toMap().forEach { (key, on) -> editor.putBoolean(key, on) }
        editor.putBoolean(KEY_SETTLED, true).commit()
    }.getOrDefault(false)

    /** 检查用:回到还没拿到服务的样子 */
    @Synchronized
    internal fun resetForTest(context: Context) {
        local = context.getSharedPreferences(LOCAL_PREFS, Context.MODE_PRIVATE)
        local.edit().clear().commit()
        remote = null
        mutableEnabled.value = false
        mutableSettings.value = FluidCloudSettings()
    }
}
