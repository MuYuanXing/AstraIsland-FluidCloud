package com.astraflow.fluidcloud

import android.os.Handler
import android.os.Looper
import com.astraflow.fluidcloud.hook.HookHelper
import com.astraflow.fluidcloud.hook.Log
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method

/**
 * 系统服务的数据读取入口,不参与显示设置与显示交接。
 * 卡片内容(onReceiveUIData)带源头拦截(OfficialCloudGate):显示出口装不上的机器上,接入开且星河岛在管事时在 before 环节截住、
 * 不调原方法,系统流体云建不了记录;内容仍由同一个钩子在 after 环节交给星河岛。不截时原方法照常执行。
 * 服务名单(onListChanged)照常执行完再读。
 */
object OfficialCloudReaderHooks {
    private val main by lazy { Handler(Looper.getMainLooper()) }

    /** 装在原方法上的钩子(回放经它调原方法);装上时记,撤除时清 */
    private val gated = java.util.WeakHashMap<Method, XposedInterface.Hooker>()

    internal fun hookerOf(method: Method): XposedInterface.Hooker? = synchronized(gated) { gated[method] }

    fun install(loader: ClassLoader, interceptors: List<String>, observers: List<String>, manager: Boolean,
                unhooked: (Method) -> Boolean): List<XposedInterface.HookHandle> {
        val handles = mutableListOf<XposedInterface.HookHandle>()
        val seen = hashSetOf<Method>()
        fun methodsOf(type: Class<*>, name: String): List<Method> {
            val own = type.declaredMethods.filter { it.name == name && it.parameterCount == 1 && !it.isSynthetic }
            return own.ifEmpty { type.methods.filter { it.name == name && it.parameterCount == 1 && !it.isSynthetic } }
        }
        // 系统回调会先把实际处理投递到主线程;读取必须排在其后。
        fun readLater(owner: Any?, value: Any?, name: String, receive: (Any?, Any?) -> Unit) {
            main.post { runCatching { receive(owner, value) }.onFailure { Log.e("OfficialCloud", "read $name failed", it) } }
        }
        fun install(names: List<String>, name: String, receive: (Any?, Any?) -> Unit) {
            for (className in names) {
                val type = runCatching { Class.forName(className, false, loader) }.getOrNull() ?: continue
                for (method in methodsOf(type, name)) {
                    if (!seen.add(method) || !unhooked(method)) continue
                    val handle = HookHelper.hookMethodAfter(method) { chain, result ->
                        val owner = chain.thisObject
                        val value = chain.getArg(0).let { if (it is List<*>) it.toList() else it }
                        readLater(owner, value, name, receive)
                        result
                    } ?: error("system service reader could not be installed: $method")
                    handles += handle
                }
            }
        }
        /**
         * 卡片内容:同一个钩子里先裁决(before)再读取(after);截住与放行都只读一次,不依赖两个钩子之间的先后。
         * 钩子本体记下来,回放(OfficialCloudGate)经它把截住的那一份再调一次原方法。
         */
        fun installGated(names: List<String>) {
            for (className in names) {
                val type = runCatching { Class.forName(className, false, loader) }.getOrNull() ?: continue
                for (method in methodsOf(type, "onReceiveUIData")) {
                    if (!seen.add(method) || !unhooked(method)) continue
                    val hooker = XposedInterface.Hooker { chain ->
                        val verdict = OfficialCloudGate.decide(chain)
                        val result = if (verdict != null) verdict else chain.proceed()
                        val owner = chain.thisObject
                        val value = chain.getArg(0).let { if (it is List<*>) it.toList() else it }
                        readLater(owner, value, "onReceiveUIData", OfficialCloudSeedlingReader::onUIData)
                        result
                    }
                    val handle = HookHelper.hookWithHooker(method, hooker)
                        ?: error("system service reader could not be installed: $method")
                    handles += handle
                    synchronized(gated) { gated[method] = hooker }
                    OfficialCloudGate.markInstalled()
                }
            }
        }
        try {
            installGated(interceptors)
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

    /** 这一代的挂钩安装失败撤回后调用:忘掉它装上的钩子,源头拦截按剩下的重算 */
    fun forget(loader: ClassLoader) {
        val left = synchronized(gated) {
            gated.keys.removeAll { it.declaringClass.classLoader === loader }
            gated.isNotEmpty()
        }
        OfficialCloudGate.refreshInstalled(left)
    }

    /** 检查用:清掉记下的钩子(下一次 install 重新装) */
    fun resetForTest() {
        synchronized(gated) { gated.clear() }
        OfficialCloudGate.refreshInstalled(false)
    }
}
