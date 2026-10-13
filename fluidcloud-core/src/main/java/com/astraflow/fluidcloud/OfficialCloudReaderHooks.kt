package com.astraflow.fluidcloud

import com.astraflow.fluidcloud.hook.HookHelper
import com.astraflow.fluidcloud.hook.Log
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method

/** 系统服务的数据读取入口,不参与显示设置与显示交接。 */
object OfficialCloudReaderHooks {
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
                    HookHelper.hookMethodBefore(method) { chain ->
                        runCatching { receive(chain.thisObject, chain.getArg(0)) }
                            .onFailure { Log.e("OfficialCloud", "read $name failed", it) }
                        null
                    }?.let(handles::add)
                }
            }
        }
        install(interceptors, "onReceiveUIData", OfficialCloudSeedlingReader::onUIData)
        install(observers, "onListChanged", OfficialCloudSeedlingReader::onServiceList)
        if (manager) runCatching {
            Class.forName(OfficialCloudSeedlingReader.MANAGER, false, loader).methods
                .firstOrNull { it.name == "unregisterMultiInstanceListCallback" && it.parameterCount == 2 && unhooked(it) }
        }.getOrNull()?.let { method ->
            HookHelper.hookMethodBefore(method) { chain ->
                OfficialCloudSeedlingReader.onObserverGone(chain.getArg(0)); null
            }?.let(handles::add)
        }
        return handles
    }
}
