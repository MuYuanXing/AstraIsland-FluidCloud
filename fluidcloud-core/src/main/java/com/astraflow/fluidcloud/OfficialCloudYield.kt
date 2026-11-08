package com.astraflow.fluidcloud

import android.os.Handler
import android.os.Looper
import com.astraflow.fluidcloud.hook.HookHelper
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * 系统自己的流体云让位(现行规则「系统事件接入」):系统显示部分接收当前列表时过滤一遍,原始记录、内容、显示设置都不改。
 * 状态栏那一份里,[OfficialCloudSource.hides] 说藏的记录不交给系统画;不论记录从哪条数据门进来都一样,
 * 没经过数据门的记录在这里就地读取交给星河岛。藏不藏的条件变了(星河岛管不管事、能不能露面、接入与分类开关),
 * 请系统按新条件重算一次列表。
 */
object OfficialCloudYield {
    const val STATUS_BAR = 8
    const val INTERCEPTOR = "pantanal.app.UIDataInterceptor"
    const val OBSERVER = "pantanal.decision.DecisionObserver"
    const val OPTIONS_EXTRA = "oplusLiveAlertOptions"
    private const val ENTRY_STATUS_BAR = "ENTRY_STATUS_BAR"

    /** 插件设置页的「系统胶囊能不能藏」:还没装好、能藏(找到了显示入口)、藏不了(这台手机的系统认不出显示入口) */
    enum class Status(val report: String) { UNKNOWN(""), AVAILABLE("available"), UNAVAILABLE("unavailable") }
    @Volatile var status = Status.UNKNOWN
        private set

    /** 一个显示管理对象:重算列表的方法、状态栏那一份的入口值,以及系统已经建好的管理对象 */
    private class Display(val method: Method, val entry: Any) {
        var owner = WeakReference<Any>(null)
    }
    private val displays = linkedMapOf<ClassLoader, Display>()
    private val observedHolders = WeakHashMap<Any, Unit>()
    private val main by lazy { Handler(Looper.getMainLooper()) }

    /** 两个显示入口(胶囊、卡片)都要找到;合并在同一方法里的只装一次 */
    fun installDisplay(loader: ClassLoader, renderers: List<Method>, refresh: Method?,
                       unhooked: (Method) -> Boolean): List<XposedInterface.HookHandle> {
        // 资源/服务读取加载器没有显示接口
        if (renderers.isEmpty() && refresh == null) return emptyList()
        if (renderers.size != 2 || refresh == null || refresh.parameterCount != 2 ||
            refresh.parameterTypes[1] != Boolean::class.javaPrimitiveType) {
            markUnavailable(); return emptyList()
        }
        val entry = refresh.parameterTypes[0].enumConstants?.firstOrNull { (it as? Enum<*>)?.name == ENTRY_STATUS_BAR }
        if (entry == null) { markUnavailable(); return emptyList() }
        val handles = mutableListOf<XposedInterface.HookHandle>()
        for (method in renderers.distinct()) {
            if (!unhooked(method)) continue
            val handle = HookHelper.hookArguments(method) { chain ->
                chain.thisObject?.let(::observeDisplay)
                arrayOf(filter(chain.getArg(0) as List<*>))
            } ?: error("native display interceptor could not be installed: $method")
            handles += handle
        }
        synchronized(displays) { displays[loader] = Display(refresh.apply { isAccessible = true }, entry) }
        updateStatus(Status.AVAILABLE)
        synchronized(observedHolders) { observedHolders.keys.toList() }.forEach(::observeDisplay)
        // 安装在后台线程上进行;重算交给主线程
        main.post(::refresh)
        return handles
    }

    /** 从系统已经创建的监听者取得显示管理对象(不读静态字段,不触发系统的提前初始化);换了对象就按现在的条件重算一次 */
    fun observeDisplay(holder: Any) {
        synchronized(observedHolders) { observedHolders[holder] = Unit }
        val targets = synchronized(displays) { displays.values.toList() }
        for (target in targets) {
            val type = target.method.declaringClass
            val direct = if (type.isInstance(holder)) holder else OfficialCloudReflection.fieldOfType(holder, type.name)?.takeIf(type::isInstance)
            val owner = direct ?: OfficialCloudReflection.fields(holder.javaClass).firstNotNullOfOrNull { field ->
                field.isAccessible = true
                field.get(holder)?.let { OfficialCloudReflection.fieldOfType(it, type.name) }?.takeIf(type::isInstance)
            } ?: continue
            if (target.owner.get() !== owner) {
                target.owner = WeakReference(owner)
                main.post(::refresh)
            }
        }
    }

    /**
     * 系统显示列表的副本:状态栏那一份里该藏的不交给系统;没经过数据门的就地读取交给星河岛。
     * 认不出身份、读不出内容树的记录交还系统(藏了系统那份而星河岛画不出来,用户就什么都看不到)。
     */
    fun filter(input: List<*>): ArrayList<Any?> {
        val output = ArrayList<Any?>(input.size)
        val present = HashSet<String>(input.size)
        var statusBar = false
        for (model in input) {
            if (model == null) { output += null; continue }
            val key = OfficialCloudReflection.nativeKey(model)
            if (displayEntrance(model, key) != ENTRY_STATUS_BAR) { output += model; continue }
            statusBar = true
            if (key == null) {
                // 认不出身份的记录:读不了也就不藏,交给系统照常显示
                output += model
                continue
            }
            present += key
            // 没经过数据门的记录就地读一次交给星河岛;读不出内容树的,整理后按结果交还系统
            if (!OfficialCloudSource.known(key)) OfficialCloudSource.receiveFromDisplay(model)
            if (!OfficialCloudSource.hides(key)) output += model
        }
        if (statusBar) OfficialCloudSource.noteDisplayList(present)
        return output
    }

    /** 显示记录自身或它唯一的原始记录写着显示位置 */
    private fun displayEntrance(model: Any, key: String?): String? {
        OfficialCloudReflection.entranceName(model)?.let { return it }
        if (key == null) return null
        val raw = OfficialCloudReflection.fields(model.javaClass).mapNotNull { field ->
            field.isAccessible = true
            field.get(model)?.takeIf { it !== model && OfficialCloudReflection.nativeKey(it) == key && OfficialCloudReflection.call(it, "getSourceType") != null }
        }.singleOrNull() ?: return null
        return OfficialCloudReflection.entranceName(raw)
    }

    private val noted = linkedSetOf<String>()

    /** 请系统按现在的条件重算状态栏那一份(主线程) */
    fun refresh() {
        check(Looper.myLooper() == Looper.getMainLooper())
        val targets = synchronized(displays) { displays.values.distinctBy { it.method } }
        for (target in targets) {
            val owner = target.owner.get() ?: continue
            target.method.invoke(owner, target.entry, false)
        }
    }

    fun retire(loader: ClassLoader) {
        synchronized(displays) { displays.remove(loader) }
    }

    fun markUnavailable() {
        synchronized(displays) { displays.clear() }
        updateStatus(Status.UNAVAILABLE)
    }

    private fun updateStatus(next: Status) {
        if (status == next) return
        status = next
        OfficialCloudSource.reportVisibility()
        // 出口装好后由出口藏:源头拦截不再截,把出口装好之前截住的那一份交还系统再由出口过滤
        main.post(OfficialCloudGate::onGateStateChanged)
    }

    /** 检查用:直接设显示出口的状态 */
    fun statusForTest(next: Status) = updateStatus(next)

    fun resetForTest() {
        synchronized(displays) { displays.clear() }
        synchronized(observedHolders) { observedHolders.clear() }
        synchronized(noted) { noted.clear() }
        status = Status.UNKNOWN
    }
}
