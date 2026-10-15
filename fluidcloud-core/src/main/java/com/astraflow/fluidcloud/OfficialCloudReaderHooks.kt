package com.astraflow.fluidcloud

import android.os.Handler
import android.os.Looper
import com.astraflow.fluidcloud.hook.HookHelper
import com.astraflow.fluidcloud.hook.Log
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method

/** 系统服务的数据读取入口,不参与显示设置与显示交接。 */
object OfficialCloudReaderHooks {
    private val main by lazy { Handler(Looper.getMainLooper()) }

    fun install(loader: ClassLoader, interceptors: List<String>, observers: List<String>, manager: Boolean,
                unhooked: (Method) -> Boolean): List<XposedInterface.HookHandle> {
        val handles = mutableListOf<XposedInterface.HookHandle>()
        val seen = hashSetOf<Method>()
        fun install(names: List<String>, name: String, receive: (Any?, Any?) -> Unit) {
            for (className in names) {
                val type = runCatching { Class.forName(className, false, loader) }.getOrNull() ?: continue
                val own = type.declaredMethods.filter { it.name == name && it.parameterCount == 1 && !it.isSynthetic }
                val methods = own.ifEmpty { type.methods.filter { it.name == name && it.parameterCount == 1 && !it.isSynthetic } }
                for (method in methods) {
                    if (!seen.add(method) || !unhooked(method)) continue
                    val handle = HookHelper.hookMethodAfter(method) { chain, result ->
                        val owner = chain.thisObject
                        val value = chain.getArg(0).let { if (it is List<*>) it.toList() else it }
                        // 系统回调会先把实际处理投递到主线程;读取必须排在其后。
                        main.post { runCatching { receive(owner, value) }.onFailure { Log.e("OfficialCloud", "read $name failed", it) } }
                        result
                    } ?: error("system service reader could not be installed: $method")
                    handles += handle
                }
            }
        }
        try {
            install(interceptors, "onReceiveUIData", OfficialCloudSeedlingReader::onUIData)
            install(observers, "onListChanged", OfficialCloudSeedlingReader::onServiceList)
            if (manager) runCatching {
                Class.forName(OfficialCloudSeedlingReader.MANAGER, false, loader).methods
                    .firstOrNull { it.name == "unregisterMultiInstanceListCallback" && it.parameterCount == 2 && unhooked(it) }
            }.getOrNull()?.let { method ->
                val handle = HookHelper.hookMethodAfter(method) { chain, result ->
                    val observer = chain.getArg(0)
                    main.post { OfficialCloudSeedlingReader.onObserverGone(observer) }
                    result
                } ?: error("system service release reader could not be installed: $method")
                handles += handle
            }
        } catch (failure: Throwable) {
            handles.forEach(HookHelper::unhook)
            throw failure
        }
        return handles
    }
}
