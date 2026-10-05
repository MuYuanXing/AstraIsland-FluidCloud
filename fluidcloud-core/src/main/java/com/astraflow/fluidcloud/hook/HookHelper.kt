package com.astraflow.fluidcloud.hook

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedInterface.HookHandle
import io.github.libxposed.api.XposedInterface.Hooker
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Method

/**
 * 插件在系统界面里装挂钩的唯一入口(libxposed API 101 的拦截器链):异常一律接住,不传给被挂的方法。
 * 前置回调返回非空时跳过原方法、用它当结果;后置回调拿到原方法的结果,返回最终结果。
 */
object HookHelper {
    private const val TAG = "HookHelper"

    /** 插件被框架加载时给的接口 */
    @Volatile var xposedInterface: XposedInterface? = null
        private set

    /** 系统界面的类加载器 */
    @Volatile var classLoader: ClassLoader? = null
        private set

    fun init(xposed: XposedInterface) {
        xposedInterface = xposed
        Log.xposed = xposed
        Logger.xposedInterface = xposed
    }

    fun initTarget(classLoader: ClassLoader) {
        this.classLoader = classLoader
    }

    fun hookMethod(method: Method, beforeCallback: ((Chain) -> Any?)? = null, afterCallback: ((Chain, Any?) -> Any?)? = null): HookHandle? =
        hookExecutable(method, beforeCallback, afterCallback)

    fun hookMethodBefore(method: Method, callback: (Chain) -> Any?): HookHandle? = hookExecutable(method, beforeCallback = callback)

    fun hookMethodAfter(method: Method, callback: (Chain, Any?) -> Any?): HookHandle? = hookExecutable(method, afterCallback = callback)

    fun hookConstructor(constructor: Constructor<*>, beforeCallback: ((Chain) -> Any?)? = null, afterCallback: ((Chain, Any?) -> Any?)? = null): HookHandle? =
        hookExecutable(constructor, beforeCallback, afterCallback)

    fun unhook(handle: HookHandle?) {
        runCatching { handle?.unhook() }.onFailure { Logger.e(TAG, "unhook failed", it) }
    }

    private fun hookExecutable(executable: Executable, beforeCallback: ((Chain) -> Any?)? = null,
                               afterCallback: ((Chain, Any?) -> Any?)? = null): HookHandle? {
        val xi = xposedInterface ?: run { Logger.e(TAG, "hookExecutable: xposedInterface is null"); return null }
        if (beforeCallback == null && afterCallback == null) return null
        return runCatching {
            xi.hook(executable)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept(Hooker { chain ->
                    var shortCircuit: Any? = null
                    var skip = false
                    if (beforeCallback != null) {
                        runCatching { beforeCallback(chain)?.let { shortCircuit = it; skip = true } }
                            .onFailure { Logger.e(TAG, "beforeHook failed: ${executable.declaringClass.name}", it) }
                    }
                    val result = if (skip) shortCircuit else chain.proceed()
                    if (afterCallback != null) {
                        runCatching { afterCallback(chain, result) }
                            .onFailure { Logger.e(TAG, "afterHook failed: ${executable.declaringClass.name}", it) }
                            .getOrDefault(result)
                    } else result
                })
        }.onFailure { Logger.e(TAG, "hookExecutable failed: ${executable.declaringClass.name}", it) }.getOrNull()
    }
}
