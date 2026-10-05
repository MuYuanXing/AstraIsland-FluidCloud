package com.astraflow.fluidcloud.hook

import io.github.libxposed.api.XposedInterface

/** 正式包也写的运行记录:模块框架日志(LSPosed 里看得到)和系统日志各写一份。 */
object Log {
    private const val PREFIX = "FluidCloud"

    @Volatile var xposed: XposedInterface? = null

    fun i(tag: String, msg: String) = write(android.util.Log.INFO, tag, msg, null)
    fun w(tag: String, msg: String, tr: Throwable? = null) = write(android.util.Log.WARN, tag, msg, tr)
    fun e(tag: String, msg: String, tr: Throwable? = null) = write(android.util.Log.ERROR, tag, msg, tr)

    private fun write(level: Int, tag: String, msg: String, tr: Throwable?) {
        val full = if (tr != null) "$msg\n${android.util.Log.getStackTraceString(tr)}" else msg
        runCatching { android.util.Log.println(level, "$PREFIX/$tag", full) }
        val xi = xposed ?: return
        runCatching { if (tr != null) xi.log(level, "$PREFIX/$tag", msg, tr) else xi.log(level, "$PREFIX/$tag", msg) }
    }
}

/** 只在调试包里写的运行记录;正式包一律不写。是不是调试包由插件应用启动时告诉它。 */
object Logger {
    private const val PREFIX = "[FluidCloud]"

    @Volatile var debug = false
    @Volatile var xposedInterface: XposedInterface? = null

    fun d(tag: String, msg: String) { if (debug) runCatching { android.util.Log.d("$PREFIX $tag", msg) } }
    fun i(tag: String, msg: String) { if (debug) runCatching { android.util.Log.i("$PREFIX $tag", msg) } }
    fun w(tag: String, msg: String) { if (debug) runCatching { android.util.Log.w("$PREFIX $tag", msg) } }
    fun e(tag: String, msg: String, throwable: Throwable? = null) { if (debug) runCatching { android.util.Log.e("$PREFIX $tag", msg, throwable) } }

    /** 写进模块框架日志(LSPosed 里看得到),只在调试包里写 */
    fun xposedLog(tag: String, msg: String) {
        if (!debug) return
        runCatching { xposedInterface?.log(android.util.Log.INFO, "$PREFIX $tag", msg) }
    }
}
