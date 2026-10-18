package com.astraflow.fluidcloud.app

import android.app.Application
import com.astraflow.fluidcloud.AdapterBridge
import com.astraflow.fluidcloud.BuildConfig
import com.astraflow.fluidcloud.FluidCloudSettings
import com.astraflow.fluidcloud.OfficialCloudHook
import com.astraflow.fluidcloud.OfficialCloudSource
import com.astraflow.fluidcloud.hook.CrashGuard
import com.astraflow.fluidcloud.hook.HookHelper
import com.astraflow.fluidcloud.hook.Log
import com.astraflow.fluidcloud.hook.Logger
import com.astraflow.fluidcloud.hook.PluginClassLoaderInterceptor
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.io.File

/**
 * 插件在系统界面里的入口(libxposed API 101,由 META-INF/xposed/java_init.list 声明,作用域只有系统界面)。
 * 系统界面一加载:先套上出错保护(见 [CrashGuard]),再装好系统流体云插件类加载器的拦截和数据监听;
 * 系统界面的 Application 建好后读设置,向星河岛打招呼、交事件(OfficialCloudSource)。星河岛不在或不收时什么都不藏,
 * 系统流体云照常由系统显示。插件因为多次出错自动暂停时什么都不装,只向星河岛说明已暂停,系统界面恢复原样。
 */
class HookEntry : XposedModule() {
    @Volatile private var attached = false
    @Volatile private var started = false
    @Volatile private var paused = false
    /** 读设置的那一份要一直留着:设置变化的监听挂在它上面 */
    private var settings: SystemUiSettings? = null

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        HookHelper.init(this)
        Logger.debug = BuildConfig.DEBUG
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        // ColorOS 16 会对同一进程再回调一次(另一个类加载器),只认第一次
        if (param.packageName != PluginStatus.SYSTEM_UI || !param.isFirstPackage || attached) return
        attached = true
        val loader = param.defaultClassLoader
        HookHelper.initTarget(loader)
        Log.i(TAG, "attached to SystemUI, plugin ${BuildConfig.VERSION_NAME}")
        // 出错保护:记录放在系统界面自己的设备加密目录(开机解锁前也读得到);用户点过「重新开启」就照常启动
        runCatching {
            CrashGuard.init(File(param.applicationInfo.deviceProtectedDataDir, GUARD_DIR))
            CrashGuard.installRecorder()
            resumeIfRequested()
        }.onFailure { Log.e(TAG, "crash guard unavailable", it) }
        paused = !CrashGuard.shouldStart()
        if (paused) {
            Log.e(TAG, "paused after repeated SystemUI crashes; nothing is hooked until it resumes")
        } else {
            runCatching { loadCodeSearch() }.onFailure { Log.e(TAG, "code search library not loaded", it) }
            runCatching { PluginClassLoaderInterceptor.register(loader) }.onFailure { Log.e(TAG, "plugin class loader interception failed", it) }
            runCatching { OfficialCloudHook.register() }.onFailure { Log.e(TAG, "fluid cloud hooks failed", it) }
        }
        runCatching { hookApplication(loader) }.onFailure { Log.e(TAG, "SystemUI application hook failed", it) }
    }

    /** 用户点过「重新开启」(插件应用记在远端设置里),而且是在这次暂停以后点的:清掉记录,这一次照常启动 */
    private fun resumeIfRequested() {
        val requested = runCatching { getRemotePreferences(FluidCloudSettings.GROUP).getLong(FluidCloudSettings.KEY_RESUME_AT, 0L) }.getOrDefault(0L)
        if (requested > 0L && CrashGuard.resumeIfRequested(requested)) Log.i(TAG, "resumed by the user")
    }

    /** 在系统流体云插件文件里找监听处要用的本机库:安装时解压在插件自己的本机库目录里 */
    private fun loadCodeSearch() {
        System.load(File(moduleApplicationInfo.nativeLibraryDir, "libdexkit.so").absolutePath)
    }

    private fun hookApplication(loader: ClassLoader) {
        val appClass = runCatching { loader.loadClass(SYSTEM_UI_APPLICATION) }.getOrNull() ?: Application::class.java
        HookHelper.hookMethodAfter(appClass.getDeclaredMethod("onCreate")) { chain, result ->
            (chain.thisObject as? Application)?.let { app -> runCatching { onSystemUiCreated(app) }.onFailure { Log.e(TAG, "start failed", it) } }
            result
        }
    }

    private fun onSystemUiCreated(app: Application) {
        if (started) return
        started = true
        SystemUiStatus.start(app)
        SystemUiStatus.report(PluginStatus.KEY_RUNNING, BuildConfig.VERSION_CODE.toString())
        if (paused) {
            // 暂停中:不读系统流体云,只告诉插件页和星河岛(星河岛再告诉星流)已经暂停
            SystemUiStatus.report(PluginStatus.KEY_PAUSED, (CrashGuard.pausedAt() ?: 0L).toString())
            AdapterBridge.announcePaused(BuildConfig.APPLICATION_ID, BuildConfig.VERSION_CODE.toLong()) {
                SystemUiStatus.report(PluginStatus.KEY_ISLAND, it)
            }
            return
        }
        val remote = SystemUiSettings(this, app) { OfficialCloudSource.onSettingsChanged(it) }
        settings = remote
        OfficialCloudSource.bind(app, remote.current(), BuildConfig.APPLICATION_ID, BuildConfig.VERSION_CODE.toLong(),
            reportStatus = { SystemUiStatus.report(PluginStatus.KEY_CAPSULE, it) },
            reportIsland = { SystemUiStatus.report(PluginStatus.KEY_ISLAND, it) })
        Log.i(TAG, "started")
    }

    private companion object {
        const val TAG = "Entry"
        const val SYSTEM_UI_APPLICATION = "com.android.systemui.SystemUIApplication"
        /** 出错保护的记录放在系统界面设备加密目录下的这个子目录 */
        const val GUARD_DIR = "fluidcloud_guard"
    }
}
