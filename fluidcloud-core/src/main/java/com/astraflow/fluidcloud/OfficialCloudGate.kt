package com.astraflow.fluidcloud

import android.os.Parcel
import android.os.Parcelable
import com.astraflow.fluidcloud.hook.Log
import io.github.libxposed.api.XposedInterface
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.WeakHashMap

/**
 * 源头拦截的闸门(现行规则「系统事件接入」):接入开且星河岛在管事时,pantanal UIDataInterceptor.onReceiveUIData
 * 送来的卡片内容在 before 环节截住、不调原方法——系统流体云收不到数据、建不了记录,星河岛成为唯一消费者。
 * 星河岛不接手、接入关闭或拦截器自身连续出错时,把缓存的原始参数交还系统;原生流体云不作为兜底,
 * 回放只保证拦截这边的故障不给系统弄丢回调。
 */
object OfficialCloudGate {
    private const val TAG = "OfficialCloud"
    /** 连续出错到这个数就停用拦截,回退到现行的事后过滤(与 CrashGuard 的崩溃暂停同一个量级) */
    private const val MAX_FAILURES = 3

    /** 插件的接入开关此刻开着没有(HookEntry 读远端设置后喂进来,和星河岛的管事状态一样按最新一份算) */
    @Volatile private var accessOn = true

    /** 设置变了:接入开关关掉时立刻把各接收对象手上错过系统的最近一份补回去 */
    fun onSettings(access: Boolean) {
        accessOn = access
        onGateStateChanged()
    }

    /** 此刻截不截:接入开关开着,且星河岛在管事(星河岛不在、没收下时系统流体云照常由系统显示) */
    @Volatile var wanted: () -> Boolean = { accessOn && OfficialCloudSource.islandActive }
    /**
     * 回放:把缓存的那一份经装在原方法上的钩子再调一次原方法——不绕过挂钩直接反射,读取与系统处理都照常发生;
     * 回放裁决为「不截」(engaged 已为假),不会把同一份内容截两遍。
     */
    @Volatile var replay: (Method, Any?, Array<Any?>) -> Unit = { method, target, args ->
        val hooker = OfficialCloudReaderHooks.hookerOf(method)
        if (hooker != null) hooker.intercept(ChainOf(method, target, args)) else method.invoke(target, *args)
    }

    /** 防崩停用:连续出错后不再截,直到下一次系统界面启动 */
    @Volatile private var disabled = false
    private var failures = 0

    /** 一个接收对象错过系统的最近一份:原方法、弱引用的接收对象、拷好的参数(系统可能复用送来的对象,不能只存引用) */
    private class Missed(val method: Method, val target: WeakReference<Any>, val args: Array<Any?>, val loader: ClassLoader?)

    /** 接收对象由系统持有;弱键弱引用,它没了这份也不用补 */
    private val missed = WeakHashMap<Any, Missed>()

    /** before 环节的裁决:返回非空表示截住本次调用(不调原方法)。 */
    fun decide(chain: XposedInterface.Chain): Any? {
        val method = chain.executable as? Method ?: return null
        val target = chain.thisObject ?: return null
        if (!engaged()) {
            // 没在截(关闭、暂停或防崩停用):先把它错过系统的那一份补回去,本次照常交给系统
            handBack(target)
            return null
        }
        return try {
            // 截住的内容星流自己必须接得住:接收对象认得出、内容里有卡片数据,否则不截
            if (!OfficialCloudSeedlingReader.readable(target, chain.getArg(0))) return null
            remember(method, target, chain.getArg(0))
            success()
            method.defaultResult()
        } catch (failure: Throwable) {
            noteFailure(failure)
            null
        }
    }

    private fun engaged(): Boolean {
        if (disabled) return false
        val on = try { wanted() } catch (failure: Throwable) { noteFailure(failure); false }
        return on
    }

    private fun remember(method: Method, target: Any, arg: Any?) {
        synchronized(missed) { missed[target] = Missed(method, WeakReference(target), arrayOf(copy(arg)), arg?.javaClass?.classLoader) }
    }

    /** 把这个接收对象错过系统的最近一份补回去;补不回的记一次拦截器错误。 */
    private fun handBack(target: Any) {
        val pending = synchronized(missed) { missed.remove(target) } ?: return
        val owner = pending.target.get() ?: return
        try {
            replay(pending.method, owner, pending.args.map { restore(it, pending.loader) }.toTypedArray())
            success()
        } catch (failure: Throwable) {
            noteFailure(failure)
        }
    }

    /** 拷得动的(Parcelable)拷成包裹字节,回放时再转回对象;拷不动的只能留引用,每种类型写一行日志。 */
    private fun copy(value: Any?): Any? = when (value) {
        null -> null
        is Parcelable -> write(value) ?: value.also(::uncopyable)
        else -> value.also(::uncopyable)
    }

    private fun write(value: Parcelable): ByteArray? = runCatching {
        val parcel = Parcel.obtain()
        try {
            parcel.writeParcelable(value, 0)
            parcel.marshall()
        } finally {
            parcel.recycle()
        }
    }.getOrNull()

    private fun restore(value: Any?, loader: ClassLoader?): Any? = when (value) {
        is ByteArray -> runCatching {
            val parcel = Parcel.obtain()
            try {
                parcel.unmarshall(value, 0, value.size)
                parcel.setDataPosition(0)
                parcel.readParcelable<Parcelable>(loader)
            } finally {
                parcel.recycle()
            }
        }.getOrElse { failure -> throw IllegalStateException("parcelled argument unreadable", failure) } as Any?
        else -> value
    }

    private val uncopyable = linkedSetOf<String>()

    private fun uncopyable(value: Any) {
        val fresh = synchronized(uncopyable) { uncopyable.size < 16 && uncopyable.add(value.javaClass.name) }
        if (fresh) Log.w(TAG, "argument kept by reference (not parcelable): ${value.javaClass.name}")
    }

    private fun success() {
        synchronized(this) { failures = 0 }
    }

    private fun noteFailure(failure: Throwable) {
        val tripped = synchronized(this) {
            failures++
            if (failures >= MAX_FAILURES && !disabled) { disabled = true; true } else false
        }
        Log.e(TAG, "source interception failed${if (tripped) "; intercepting disabled" else ""}", failure)
        // 停用后把所有缓存交还系统,不留两边都没有的内容
        if (tripped) synchronized(missed) { missed.keys.toList() }.forEach(::handBack)
    }

    /** 截住时替原方法返回的结果:void 与引用类型给 Unit(框架只认非空才跳过原方法),基本类型给默认值 */
    private fun Method.defaultResult(): Any = when (returnType) {
        java.lang.Boolean.TYPE -> false
        java.lang.Character.TYPE -> ' '
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        else -> Unit
    }

    /**
     * 接入开关或星河岛管事状态变了:不再截时,把各接收对象手上错过系统的最近一份补回去。
     * 在主线程上被调(设置变化、岛的状态都在主线程处理),系统自己的处理也投递在主线程,补发排在后面,先后不乱。
     */
    fun onGateStateChanged() {
        if (engaged()) return
        synchronized(missed) { missed.keys.toList() }.forEach(::handBack)
    }

    fun resetForTest() {
        synchronized(this) { disabled = false; failures = 0 }
        synchronized(missed) { missed.clear() }
        synchronized(uncopyable) { uncopyable.clear() }
        accessOn = true
        wanted = { accessOn && OfficialCloudSource.islandActive }
        replay = { method, target, args ->
            val hooker = OfficialCloudReaderHooks.hookerOf(method)
            if (hooker != null) hooker.intercept(ChainOf(method, target, args)) else method.invoke(target, *args)
        }
    }
}

/** 回放用的链:把缓存的参数带给装在原方法上的钩子,原方法照原样执行 */
private class ChainOf(private val method: Method, private val target: Any?, private val args: Array<Any?>) : XposedInterface.Chain {
    override fun getExecutable(): java.lang.reflect.Executable = method
    override fun getThisObject(): Any? = target
    override fun getArgs(): List<Any?> = args.toList()
    override fun getArg(index: Int): Any? = args[index]
    override fun proceed(): Any? = method.invoke(target, *args)
    override fun proceed(args: Array<Any?>): Any? = method.invoke(target, *args)
    override fun proceedWith(result: Any): Any = result
    override fun proceedWith(result: Any, args: Array<Any?>): Any = result
}
