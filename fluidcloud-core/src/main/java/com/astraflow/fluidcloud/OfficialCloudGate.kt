package com.astraflow.fluidcloud

import android.os.Handler
import android.os.Looper
import android.os.Parcel
import android.os.Parcelable
import com.astraflow.fluidcloud.hook.Log
import io.github.libxposed.api.XposedInterface
import java.lang.ref.WeakReference
import java.lang.reflect.Method

/**
 * 源头拦截的闸门(现行规则「系统事件接入」):接入开且星河岛在管事、此刻能露面时,pantanal UIDataInterceptor.onReceiveUIData
 * 送来的卡片内容在 before 环节截住、不调原方法——系统流体云收不到数据、建不了记录,星河岛成为唯一消费者。
 * 星河岛不接手这一件事、不在管事(关闭接入、关闭岛、暂停、断连、熄屏)或拦截器自身连续出错时,把缓存的原始参数交还系统;
 * 原生流体云不作为兜底,回放只保证「岛没接的事不会两边都没有」,以及拦截这边的故障不给系统弄丢回调。
 *
 * 交还按岛侧事件身份(与星河岛 SystemEvent.identity 同一编号,见 [OfficialCloudSeedlingReader.Entry.identity])挑:[release] 只交还岛报来不接的那几件,
 * 岛还在管事时不会把已接手的重新塞回系统;整闸停用时才全部交还。显示记录有自己的编号,只用于显示隐藏,不参与这里的挑选。
 * 回放经装在原方法上的钩子再调一次原方法,读取与系统处理都照常发生。
 */
object OfficialCloudGate {
    private const val TAG = "OfficialCloud"
    /** 连续出错到这个数就停用拦截,回退到现行的事后过滤(与 CrashGuard 的崩溃暂停同一个量级) */
    private const val MAX_FAILURES = 3
    /** 手上最多留几份:同一件事只留最近一份,同时在跑的卡片远少于这个数 */
    private const val MAX_HELD = 128

    /** 插件的接入开关此刻开着没有(HookEntry 读远端设置后喂进来,和星河岛的管事状态一样按最新一份算) */
    @Volatile private var accessOn = true

    /** 设置变了:接入开关关掉时立刻把手上截住的交还系统 */
    fun onSettings(access: Boolean) {
        accessOn = access
        onGateStateChanged()
    }

    /** 此刻截不截:接入开关开着,且星河岛在管事、此刻能露面(星河岛不在、熄屏时系统流体云照常由系统显示) */
    @Volatile var wanted: () -> Boolean = { accessOn && OfficialCloudSource.interceptsSource() }

    /**
     * 回放:把缓存的那一份经装在原方法上的钩子再调一次原方法——不绕过挂钩直接反射,读取与系统处理都照常发生;
     * 回放期间裁决一律放行(见 [replaying]),不会把同一份内容截两遍。
     */
    @Volatile var replay: (Method, Any?, Array<Any?>) -> Unit = { method, target, args ->
        val hooker = OfficialCloudReaderHooks.hookerOf(method)
        if (hooker != null) hooker.intercept(ChainOf(method, target, args)) else method.invoke(target, *args)
    }

    /** 防崩停用:连续出错后不再截,直到下一次系统界面启动 */
    @Volatile private var disabled = false
    private var failures = 0

    /** 截住的一份:原方法、弱引用的接收对象、拷好的参数(系统可能复用送来的对象,不能只存引用) */
    private class Held(val method: Method, val target: WeakReference<Any>, val args: Array<Any?>, val loader: ClassLoader?)

    /**
     * 手上截住的原始数据,按岛侧事件身份记:岛报来不接这一件时按身份挑出来交还;
     * 同一件事只留最近一份——系统重建的记录也只看最新一份内容。
     */
    private val held = LinkedHashMap<String, Held>()

    /** 正在回放的这一份不能再被截:回放要真的走到原方法(岛还在管事时按编号交还也是走回放) */
    private val replaying = ThreadLocal.withInitial { false }
    private val main by lazy { Handler(Looper.getMainLooper()) }

    /** before 环节的裁决:返回非空表示截住本次调用(不调原方法),非空值即替原方法返回的结果(boolean 回「已消费」)。 */
    fun decide(chain: XposedInterface.Chain): Any? {
        // 回放走的是同一个钩子:这时必须放行到原方法,不能再截一遍
        if (replaying.get() == true) return null
        val method = chain.executable as? Method ?: return null
        val target = chain.thisObject ?: return null
        if (!engaged()) {
            // 没在截(关闭、暂停或防崩停用):手上每一份都补回去,本次照常交给系统
            handBackAll()
            return null
        }
        return try {
            // 截住的内容星流自己必须接得住:接收对象认得出是哪一件事、内容里有卡片数据,否则不截
            val located = OfficialCloudSeedlingReader.locate(target) ?: return null
            if (OfficialCloudReflection.call(chain.getArg(0), "getSeedlingUIData") == null) return null
            // 按岛侧事件身份截住:星河岛报来的接手、拒绝、交还都按这同一个编号
            hold(located.identity, method, target, chain.getArg(0))
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

    private fun hold(identity: String, method: Method, target: Any, arg: Any?) {
        val pending = Held(method, WeakReference(target), arrayOf(copy(arg)), arg?.javaClass?.classLoader)
        synchronized(held) {
            // 同一件事再送来一版:手上的旧内容不再有意义,系统重建时也只该看最新一版
            held.remove(identity)
            held[identity] = pending
            while (held.size > MAX_HELD) held.remove(held.keys.first())
        }
    }

    /**
     * 岛报来它不接这几件事(模板没读好、页里有引擎没实现的节点、这类在插件里关掉、还没读到内容):
     * 把当初截住的原文交还系统,系统自己重新建出这条记录并照常显示。按岛侧事件身份挑,不碰岛已接手的其它事。
     * 在主线程下一轮做——这一步常发生在岛的接手答复里,不在答复里调系统的数据入口。
     */
    fun release(identities: Set<String>) {
        if (identities.isEmpty()) return
        val pending = ArrayList<Held>(identities.size)
        synchronized(held) { for (identity in identities) held.remove(identity)?.let(pending::add) }
        if (pending.isEmpty()) return
        if (Looper.myLooper() == Looper.getMainLooper()) main.post { pending.forEach(::handBackNow) }
        else pending.forEach(::handBackNow)
    }

    /** 手上还截着哪些事(岛侧事件身份);岛报来的接手结果按它挑出要交还的 */
    fun heldIdentities(): Set<String> = synchronized(held) { held.keys.toSet() }

    /** 把手上的每一份都交还系统(不再截、接入关闭、拦截器停用) */
    private fun handBackAll() {
        val pending = synchronized(held) { held.values.toList().also { held.clear() } }
        pending.forEach(::handBackNow)
    }

    /** 交还一份:经装在原方法上的钩子再调一次原方法,不绕过挂钩直接反射。 */
    private fun handBackNow(pending: Held) {
        val owner = pending.target.get() ?: return
        try {
            replaying.set(true)
            replay(pending.method, owner, pending.args.map { restore(it, pending.loader) }.toTypedArray())
            success()
        } catch (failure: Throwable) {
            noteFailure(failure)
        } finally {
            replaying.set(false)
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
        if (fresh) Log.w(TAG, "argument kept by reference (not parcelable): " + value.javaClass.name)
    }

    private fun success() {
        synchronized(this) { failures = 0 }
    }

    private fun noteFailure(failure: Throwable) {
        val tripped = synchronized(this) {
            failures++
            if (failures >= MAX_FAILURES && !disabled) { disabled = true; true } else false
        }
        Log.e(TAG, "source interception failed" + if (tripped) "; intercepting disabled" else "", failure)
        // 停用后把所有缓存交还系统,不留两边都没有的内容
        if (tripped) handBackAll()
    }

    /**
     * 截住时替原方法返回的结果。onReceiveUIData 的 boolean 是「消费掉」语义(C15/C16/C17 三代一致):
     * 截住时回已消费(true),上游不再走别的投递路径重发;
     * 其余形状给中性默认值(框架只认非空才跳过原方法)。
     */
    private fun Method.defaultResult(): Any = when (returnType) {
        java.lang.Boolean.TYPE -> true
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
     * 接入开关或星河岛管事状态变了:不再截时,把手上的每一份都补回去。
     * 岛这一边的状态变化由 [OfficialCloudSource] 在收到接手结果时当场通知(见 [OfficialCloudSource.onIslandState])。
     */
    fun onGateStateChanged() {
        if (engaged()) return
        handBackAll()
    }

    fun resetForTest() {
        synchronized(this) { disabled = false; failures = 0 }
        synchronized(held) { held.clear() }
        synchronized(uncopyable) { uncopyable.clear() }
        accessOn = true
        wanted = { accessOn && OfficialCloudSource.interceptsSource() }
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
