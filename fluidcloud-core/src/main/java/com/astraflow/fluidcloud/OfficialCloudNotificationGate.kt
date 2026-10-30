package com.astraflow.fluidcloud

import android.os.Parcel
import android.os.Parcelable
import com.astraflow.fluidcloud.hook.CrashGuard
import com.astraflow.fluidcloud.hook.HookHelper
import com.astraflow.fluidcloud.hook.Log
import io.github.libxposed.api.XposedInterface
import org.luckypray.dexkit.DexKitBridge
import java.io.File
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 通知命令汇合点的闸门(现行规则「系统事件接入」):C16/C17 主 apk OplusLiveAlertNotificationsInteractor 的
 * notifyLiveAlerts 分发——通话这类通知侧的 Post/Remove 命令从这里经 com.oplus.card.server.systemui.provider
 * 送给系统流体云插件画胶囊。接入开且星河岛在管事时在 before 环节截住、不调原方法:插件收不到命令,
 * 不画胶囊也不建记录。这是纯隐藏门,不向星河岛转发任何数据(岛有自己的通知来源,转发会双显);
 * 星河岛不接手、接入关闭或拦截器连续出错时,把缓存的原始命令(Parcelable 拷贝)经同一条钩子链交还系统。
 * 主 apk 的类经系统界面宿主类加载器装钩;C15 没有这个族,按结构认不出汇合点时不装,系统流体云照常由系统显示。
 */
object OfficialCloudNotificationGate {
    private const val TAG = "OfficialCloud"
    /** 通知侧命令进系统流体云插件的那条 ContentProvider 通道 */
    private const val PROVIDER_AUTHORITY = "com.oplus.card.server.systemui.provider"
    /** 命令族(LiveAlertAction 的 Post/Remove 密封子类)的参数特征;不写死全名,按结构认 */
    private const val ACTION_PARAM = ".LiveAlertAction"
    /** 连续出错到这个数就停用拦截(与 OfficialCloudGate、CrashGuard 的崩溃暂停同一个量级) */
    private const val MAX_FAILURES = 3

    /** 插件的接入开关此刻开着没有(HookEntry 读远端设置后喂进来) */
    @Volatile private var accessOn = true

    /** 设置变了:接入开关关掉时立刻把各接收对象手上错过系统的最近一份补回去 */
    fun onSettings(access: Boolean) {
        accessOn = access
        onGateStateChanged()
    }

    /** 此刻截不截:接入开关开着,且星河岛在管事、此刻能露面(星河岛不在、熄屏时命令照常交给系统) */
    @Volatile var wanted: () -> Boolean = { accessOn && OfficialCloudSource.interceptsSource() }

    /**
     * 回放:把缓存的那一份经装在原方法上的钩子再调一次原方法——不绕过挂钩直接反射,系统的处理照常发生;
     * 回放期间裁决一律放行(见 [replaying]),不会把同一份命令截两遍。
     */
    @Volatile var replay: (Method, Any?, Array<Any?>) -> Unit = { method, target, args ->
        val hooker = hookerOf(method)
        if (hooker != null) hooker.intercept(CommandChain(method, target, args)) else method.invoke(target, *args)
    }

    /** 防崩停用:连续出错后不再截,直到下一次系统界面启动 */
    @Volatile private var disabled = false
    private var failures = 0

    /** 正在回放的这一份不能再被截:交还时岛可能还在管事,回放必须真的走到原方法 */
    private val replaying = ThreadLocal.withInitial { false }

    /** 一个接收对象错过系统的最近一份:原方法、弱引用的接收对象、拷好的参数(系统可能复用送来的对象,不能只存引用)。
     *  汇合点是静态的 access$ 方法时接收对象在第一个参数上,拷参数时那一格留空,回放时再放回去。 */
    private class Missed(val method: Method, val target: WeakReference<Any>, val args: Array<Any?>, val receiverAt: Int, val loader: ClassLoader?)

    /** 接收对象由系统持有;弱键弱引用,它没了这份也不用补 */
    private val missed = WeakHashMap<Any, Missed>()

    /** 装在原方法上的钩子(回放经它调原方法);装上时记,撤除时清 */
    private val gated = WeakHashMap<Method, XposedInterface.Hooker>()

    private fun hookerOf(method: Method): XposedInterface.Hooker? = synchronized(gated) { gated[method] }

    @Volatile private var installed = false
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "AstraFlow-NotifyGate").apply { isDaemon = true } }

    /** 主 apk 里引用了命令通道的方法(结构匹配在 [selectNotifyMethod] 里,不依赖被系统缩短的名字) */
    @Volatile var searcher: (String) -> List<OfficialCloudHook.MethodRef> = ::search

    /**
     * 在主 apk 的类加载器上装闸门。找汇合点在后台线程做,系统界面主线程不等;找不到(C15 没有这个族、
     * 结构认不出或多处命中)时不装,系统流体云照常由系统显示,只写一行日志。装不上不影响别处的读取与让位。
     */
    fun install(loader: ClassLoader, apkPath: String) {
        if (installed) return
        installed = true
        worker.execute {
            runCatching {
                val file = File(apkPath)
                val identity = apkPath + "|" + file.length() + "|" + file.lastModified()
                // 以前在找代码的途中把系统界面带崩过的文件不再找(见 CrashGuard)
                if (CrashGuard.isSkipped(identity)) {
                    Log.w(TAG, "main apk skipped by the crash guard; notification commands are not intercepted")
                    return@execute
                }
                val candidates = CrashGuard.risky(identity) { searcher(apkPath) }
                val found = selectNotifyMethod(candidates) ?: run {
                    Log.w(TAG, "notification convergence point not found; native notification capsules keep showing")
                    return@execute
                }
                val method = runCatching { found.resolve(loader) }.getOrNull() ?: run {
                    Log.w(TAG, "notification convergence point unresolved on the host loader: " + found)
                    return@execute
                }
                hook(method)
                Log.i(TAG, "notification command gate installed on " + found.className + "." + found.name)
            }.onFailure { Log.e(TAG, "notification command gate unavailable", it) }
        }
    }

    /** 引用了命令通道、又带着 LiveAlertAction 参数的方法才是汇合点(媒体、手电筒的旁路不带这个参数);
     *  多处命中或没有时不猜。 */
    fun selectNotifyMethod(candidates: List<OfficialCloudHook.MethodRef>): OfficialCloudHook.MethodRef? =
        candidates.filter { shape -> shape.params.any { it.endsWith(ACTION_PARAM) } }.singleOrNull()

    private fun search(path: String): List<OfficialCloudHook.MethodRef> =
        DexKitBridge.create(path).use { bridge ->
            bridge.findMethod { matcher { usingStrings(PROVIDER_AUTHORITY) } }
                .map { OfficialCloudHook.MethodRef(it.className, it.name, it.paramTypeNames) }
        }

    /** 把闸门装在找到的方法上;命令参数(LiveAlertAction)在哪一格,回放时不参与拷贝的接收对象在哪一格,都按实际方法定 */
    fun hook(method: Method) {
        if (method.parameterTypes.none { it.name.endsWith(ACTION_PARAM) }) {
            throw IllegalArgumentException("no LiveAlertAction parameter: " + method)
        }
        val hooker = XposedInterface.Hooker { chain ->
            val verdict = decide(chain)
            if (verdict != null) verdict else chain.proceed()
        }
        HookHelper.hookWithHooker(method, hooker) ?: error("notification command gate could not be installed: " + method)
        synchronized(gated) { gated[method] = hooker }
    }

    /** before 环节的裁决:返回非空表示截住本次调用(不调原方法)。汇合点是静态的 access$ 方法时,接收对象在第一个参数上。 */
    fun decide(chain: XposedInterface.Chain): Any? {
        // 回放走的是同一个钩子:这时必须放行到原方法,不能再截一遍
        if (replaying.get() == true) return null
        val method = chain.executable as? Method ?: return null
        val receiver = chain.thisObject ?: chain.getArg(0) ?: return null
        if (!engaged()) {
            // 没在截(关闭、暂停或防崩停用):先把它错过系统的那一份补回去,本次照常交给系统
            handBack(receiver)
            return null
        }
        return try {
            remember(method, receiver, chain.args.toTypedArray())
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

    private fun remember(method: Method, receiver: Any, args: Array<Any?>) {
        val receiverAt = args.indexOfFirst { it === receiver }
        val stored = Array(args.size) { index -> if (index == receiverAt) null else copy(args[index]) }
        val loader = args.firstOrNull { it !== receiver }?.javaClass?.classLoader
        synchronized(missed) { missed[receiver] = Missed(method, WeakReference(receiver), stored, receiverAt, loader) }
    }

    /** 把这个接收对象错过系统的最近一份补回去;补不回的记一次拦截器错误。 */
    private fun handBack(target: Any) {
        val pending = synchronized(missed) { missed.remove(target) } ?: return
        val owner = pending.target.get() ?: return
        try {
            replaying.set(true)
            val args = pending.args.map { restore(it, pending.loader) }.toTypedArray()
            if (pending.receiverAt in args.indices) args[pending.receiverAt] = owner
            replay(pending.method, owner, args)
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
        Log.e(TAG, "notification interception failed" + if (tripped) "; intercepting disabled" else "", failure)
        // 停用后把所有缓存交还系统,不留两边都没有的命令
        if (tripped) synchronized(missed) { missed.keys.toList() }.forEach(::handBack)
    }

    /** 截住时替原方法返回的结果;汇合点两代都是 void,给中性默认值(框架只认非空才跳过原方法)。 */
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
     * 接入开关变了:不再截时,把各接收对象手上错过系统的最近一份补回去。系统送命令的线程不变,
     * 补发走同一条钩子链,先后不乱。
     */
    fun onGateStateChanged() {
        if (engaged()) return
        synchronized(missed) { missed.keys.toList() }.forEach(::handBack)
    }

    fun resetForTest() {
        synchronized(this) { disabled = false; failures = 0 }
        synchronized(missed) { missed.clear() }
        synchronized(gated) { gated.clear() }
        synchronized(uncopyable) { uncopyable.clear() }
        installed = false
        accessOn = true
        wanted = { accessOn && OfficialCloudSource.interceptsSource() }
        replay = { method, target, args ->
            val hooker = hookerOf(method)
            if (hooker != null) hooker.intercept(CommandChain(method, target, args)) else method.invoke(target, *args)
        }
    }

    /** 检查用:等已经交给后台线程的安装做完 */
    fun awaitInstalls() {
        runCatching { worker.submit {}.get(5, TimeUnit.SECONDS) }
    }
}

/** 回放用的链:把缓存的参数带给装在原方法上的钩子,原方法照原样执行 */
private class CommandChain(private val method: Method, private val target: Any?, private val args: Array<Any?>) : XposedInterface.Chain {
    override fun getExecutable(): java.lang.reflect.Executable = method
    override fun getThisObject(): Any? = target
    override fun getArgs(): List<Any?> = args.toList()
    override fun getArg(index: Int): Any? = args[index]
    override fun proceed(): Any? = method.invoke(target, *args)
    override fun proceed(args: Array<Any?>): Any? = method.invoke(target, *args)
    override fun proceedWith(result: Any): Any = result
    override fun proceedWith(result: Any, args: Array<Any?>): Any = result
}
