package com.astraflow.fluidcloud

import io.github.libxposed.api.XposedInterface
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.WeakHashMap

/**
 * 源头拦截(现行规则「系统事件接入」):系统流体云的显示出口装不上的机器(ColorOS 15)靠这一道藏系统流体云。
 * pantanal UIDataInterceptor.onReceiveUIData 送来的卡片内容在 before 环节截住、不调原方法,系统流体云建不了记录;
 * 内容仍由同一个钩子在 after 环节交给星河岛。这一处只用 OPPO 的公开接口,三代都在。
 *
 * 显示出口装上的机器(ColorOS 16、17)由出口藏,这里不截;出口还没装好时先截,装好后把截住的那一份交还系统,由出口过滤。
 * 星河岛此刻能露面时截全部;不能露面(熄屏,锁屏时关了锁屏显示)时只截充电与电池提示,其余交还系统。
 * 接入关闭、星河岛不在管事时不截,并把每个接收对象最近截住的那一份经原方法交还系统。
 */
object OfficialCloudGate {
    /** 插件的接入开关此刻开着没有(HookEntry 读远端设置后喂进来) */
    @Volatile private var accessOn = true

    fun onSettings(access: Boolean) {
        accessOn = access
        onGateStateChanged()
    }

    /** 源头拦截已经装上了;显示出口装不上的机器只要这一道装上就藏得住 */
    @Volatile var installed = false
        private set

    fun markInstalled() { installed = true }

    fun refreshInstalled(hooks: Boolean) { installed = hooks }

    /** 一个接收对象最近被截住的那一份:原方法与参数 */
    private class Missed(val method: Method, val target: WeakReference<Any>, val arg: Any?)

    /** 接收对象由系统持有;弱键,它没了这份也不用交还 */
    private val missed = WeakHashMap<Any, Missed>()

    /** before 环节的裁决:返回非空表示截住本次调用(不调原方法),非空值即替原方法返回的结果 */
    fun decide(chain: XposedInterface.Chain): Any? {
        val method = chain.executable as? Method ?: return null
        val target = chain.thisObject ?: return null
        if (!engaged(target)) {
            handBack(target)
            return null
        }
        // 截住的内容星河岛必须读得出:接收对象认得出、内容里有卡片数据,否则交给系统
        if (!OfficialCloudSeedlingReader.readable(target, chain.getArg(0))) return null
        synchronized(missed) { missed[target] = Missed(method, WeakReference(target), chain.getArg(0)) }
        return method.consumed()
    }

    private fun engaged(target: Any): Boolean {
        if (!accessOn || !OfficialCloudSource.islandActive) return false
        if (OfficialCloudYield.status == OfficialCloudYield.Status.AVAILABLE) return false
        if (OfficialCloudSource.yieldAllowed) return true
        val serviceId = OfficialCloudSeedlingReader.locate(target)?.key?.substringBefore('|') ?: return false
        return serviceId == OfficialCloudServices.CHARGE || serviceId == OfficialCloudServices.BATTERY
    }

    /** 把这个接收对象最近截住的那一份经装在原方法上的钩子再调一次原方法(回放时 engaged 已为假,不会再截) */
    private fun handBack(target: Any) {
        val pending = synchronized(missed) { missed.remove(target) } ?: return
        val owner = pending.target.get() ?: return
        replay(pending.method, owner, arrayOf(pending.arg))
    }

    /** 回放经原方法上的钩子走,读取与系统处理都照常发生 */
    @Volatile var replay: (Method, Any?, Array<Any?>) -> Unit = ::replayThroughHook

    private fun replayThroughHook(method: Method, target: Any?, args: Array<Any?>) {
        val hooker = OfficialCloudReaderHooks.hookerOf(method)
        if (hooker != null) hooker.intercept(ChainOf(method, target, args)) else method.invoke(target, *args)
    }

    /** onReceiveUIData 返回的 boolean 是「已消费」(三代一致):截住时回已消费,上游不再走别的投递路径重发 */
    private fun Method.consumed(): Any = if (returnType == java.lang.Boolean.TYPE) true else Unit

    /** 接入开关、星河岛状态或显示出口状态变了:不再该截的接收对象把最近截住的那一份交还系统(主线程) */
    fun onGateStateChanged() {
        synchronized(missed) { missed.keys.toList() }.forEach { target -> if (!engaged(target)) handBack(target) }
    }

    fun resetForTest() {
        installed = false
        synchronized(missed) { missed.clear() }
        accessOn = true
        replay = ::replayThroughHook
    }
}

/** 回放用的链:把截住的参数带给装在原方法上的钩子,原方法照原样执行 */
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
