package com.astraflow.fluidcloud

import com.astraflow.fluidcloud.hook.HookHelper
import io.github.libxposed.api.XposedInterface
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.WeakHashMap

/**
 * 系统胶囊让位(现行规则「系统事件接入」):星河岛接手的系统事件,把系统流体云自己的「在哪些位置显示」开关里状态栏这一项关掉,
 * 系统就不弹它的胶囊;星河岛不接手时放回原来的开关,由系统照常显示。只动状态栏这一个位置,通知栏、锁屏卡片、息屏不动。
 *
 * 改的都是 OPPO 流体云接口里写明不改名的部分,不认系统内部的短名字:
 * - 服务名单(DecisionObserver.onListChanged):服务刚登记、还没有卡片内容时,系统按服务自带的 NewSeedlingCardOptions.showHostMap 定;
 * - 卡片内容(UIDataInterceptor.onReceiveUIData):内容送到后,系统按内容里的 SeedlingUIData.showHostMap 定;事后改主意时
 *   (例如在星河岛里关掉这一类),把这张卡片最近一份内容原样再交一次,系统按新的开关重算;
 * - 通知变成的胶囊:系统按通知自带的 oplusLiveAlertOptions 里的 showHostMap 定;在系统把名单交给显示之前,改系统读通知得到的那一份
 *   (通知本身不改),事后改主意时重放一次名单。
 * 星流自己读系统本意时按改之前的开关读([original]、OfficialCloudReflection.commonShows)。
 * 卡片内容、服务名单这两处挂钩同时交给系统服务卡片的读法(OfficialCloudSeedlingReader),系统撤掉名单监听的那一处
 * (DecisionManager.unregisterMultiInstanceListCallback)也在这里挂;事件编号和显示位置都按读法认的来,两边对得上。
 */
object OfficialCloudYield {
    private const val TAG = "OfficialCloud"
    /** 状态栏在「在哪些位置显示」里的编号 */
    const val STATUS_BAR = 8
    const val INTERCEPTOR = "pantanal.app.UIDataInterceptor"
    const val OBSERVER = "pantanal.decision.DecisionObserver"
    const val OPTIONS_EXTRA = "oplusLiveAlertOptions"
    private const val STATUS_BAR_ENTRY = "ENTRY_STATUS_BAR"
    private const val ENTRANCE = "pantanal.app.bean.Entrance"
    private const val HOST_MAP = "showHostMap"

    /** 这台手机上能不能让系统胶囊让位:还没定下来为空,装上卡片内容那一处为 available,装不上为 unavailable。 */
    enum class Status(val report: String) { UNKNOWN(""), AVAILABLE("available"), UNAVAILABLE("unavailable") }

    @Volatile var status: Status = Status.UNKNOWN
        private set

    /** 星河岛改过的开关:带着系统原来的那一份,放回时原样放回,读系统本意时按原来的读。 */
    class YieldedHostMap(val original: Map<*, *>?) : HashMap<Any?, Any?>() {
        init {
            original?.let { putAll(it) }
            put(STATUS_BAR, false)
        }
    }

    /** 系统原来的「在哪些位置显示」:星河岛改过的取改之前的那一份。 */
    fun original(map: Any?): Map<*, *>? = if (map is YieldedHostMap) map.original else map as? Map<*, *>

    /**
     * 把 [target](卡片内容 SeedlingUIData 或服务自带的 NewSeedlingCardOptions)里状态栏这一项按 [hide] 关掉或放回原样。
     * 已经是这个样子时不动;返回改没改。
     */
    fun applyHostMap(target: Any, hide: Boolean): Boolean {
        val current = OfficialCloudReflection.call(target, "getShowHostMap")
        if (hide == (current is YieldedHostMap)) return false
        val setter = target.javaClass.methods.firstOrNull { it.name == "setShowHostMap" && it.parameterCount == 1 } ?: run {
            noteOnce("setter:${target.javaClass.name}", "no setShowHostMap on ${target.javaClass.name}; its status bar capsule is left to the system")
            return false
        }
        setter.invoke(target, if (hide) YieldedHostMap(original(current)) else original(current))
        return true
    }

    /** 一张状态栏系统卡片:事件编号、最近一份内容、按哪个决定改的、定默认时用的几项、交内容的那个方法。 */
    private class Card(val key: String, val data: Any, val hide: Boolean, val facts: CloudSnapshot?, val method: Method)

    /** 数据拦截对象 → 它那张卡片。拦截对象随卡片释放,这里不留住它。 */
    private val cards = WeakHashMap<Any, Card>()

    /** 通知变成的胶囊:事件编号 → 按哪个决定改的、定默认时用的几项 */
    private class Common(val hide: Boolean, val facts: CloudSnapshot?)
    private val commons = object : LinkedHashMap<String, Common>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Common>?) = size > 256
    }

    /**
     * 这件事的状态栏胶囊要不要让位:星流读过的按星流定下的(OfficialCloudSource.suppressesNative);
     * 刚送到、还没读过的按它所属的那一类先定(OfficialCloudSource.yieldsByDefault),读过以后两者不同时再改一次。
     */
    private fun decide(key: String, facts: CloudSnapshot?): Boolean =
        if (OfficialCloudSource.decided(key)) OfficialCloudSource.suppressesNative(key)
        else facts != null && OfficialCloudSource.yieldsByDefault(facts)

    /**
     * 在这一代插件上装读系统服务卡片和让位的挂钩:卡片内容那一处([interceptors])、服务名单那一处([observers]),
     * [manager] 为真时再挂撤掉名单监听那一处。返回装上的挂钩,随这一代插件撤掉;装了卡片内容、服务名单却一个卡片内容都没装上时报「不可用」。
     */
    fun install(loader: ClassLoader, interceptors: List<String>, observers: List<String>, manager: Boolean, apk: String): List<XposedInterface.HookHandle> {
        val handles = mutableListOf<XposedInterface.HookHandle>()
        for (name in interceptors) {
            val method = runCatching { Class.forName(name, false, loader).declaredMethods.single { it.name == "onReceiveUIData" && it.parameterCount == 1 } }
                .getOrNull() ?: continue
            HookHelper.hookMethodBefore(method) { chain ->
                // 星河岛改了决定、把最近一份内容再交一次时不再读(内容没变)
                if (!redelivering.get()) runCatching { OfficialCloudSeedlingReader.onUIData(chain.thisObject, chain.getArg(0)) }.onFailure { noteFailure("read content", it) }
                runCatching { onUIData(method, chain.thisObject, chain.getArg(0)) }.onFailure { noteFailure("content", it) }
                null
            }?.let(handles::add)
        }
        val contentHooks = handles.size
        for (name in observers) {
            val method = runCatching { Class.forName(name, false, loader).declaredMethods.single { it.name == "onListChanged" && it.parameterCount == 1 } }
                .getOrNull() ?: continue
            HookHelper.hookMethodBefore(method) { chain ->
                runCatching { OfficialCloudSeedlingReader.onServiceList(chain.thisObject, chain.getArg(0)) }.onFailure { noteFailure("read service list", it) }
                runCatching { onServiceList(chain.thisObject, chain.getArg(0)) }.onFailure { noteFailure("service list", it) }
                null
            }?.let(handles::add)
        }
        val listHooks = handles.size - contentHooks
        // 系统撤掉一个显示位置的名单监听:这个位置的卡片一并结束(类名、方法名都是 OPPO 接口里的)
        val unregister = if (!manager) null else runCatching {
            Class.forName(OfficialCloudSeedlingReader.MANAGER, false, loader).declaredMethods
                .single { it.name == "unregisterMultiInstanceListCallback" && it.parameterCount == 2 }
        }.getOrNull()?.let { method ->
            HookHelper.hookMethodBefore(method) { chain ->
                runCatching { OfficialCloudSeedlingReader.onObserverGone(chain.getArg(0)) }.onFailure { noteFailure("unregister", it) }
                null
            }
        }
        unregister?.let(handles::add)
        if (contentHooks > 0 || listHooks > 0) status = if (contentHooks > 0) Status.AVAILABLE else Status.UNAVAILABLE
        com.astraflow.fluidcloud.hook.Log.i(TAG, "capsule yield on $apk: content=$contentHooks/${interceptors.size} " +
            "service list=$listHooks/${observers.size} unregister=${if (unregister != null) 1 else 0} status=${status.report}")
        return handles
    }

    /** 读不到系统流体云(数据监听装不上):这台手机上也就没法让位 */
    fun markUnavailable() { status = Status.UNAVAILABLE }

    /** 卡片内容送到(系统把内容交给记录之前):状态栏的卡片按星河岛的决定改开关,记下这一份内容,改主意时再交一次。 */
    fun onUIData(method: Method, interceptor: Any?, data: Any?) {
        if (interceptor == null || data == null) return
        val located = OfficialCloudSeedlingReader.locate(interceptor) ?: return
        if (located.entrance != OfficialCloudSeedlingReader.STATUS_BAR) return
        val ui = OfficialCloudReflection.call(data, "getSeedlingUIData") ?: return
        val facts = OfficialCloudReflection.facts(located.service, ui, located.user, located.key)
        val hide = decide(located.key, facts)
        applyHostMap(ui, hide)
        synchronized(cards) { cards[interceptor] = Card(located.key, data, hide, facts, method) }
    }

    /** 服务名单变了(系统把名单交给这个位置的记录管理之前):状态栏的名单里,每个服务自带的开关按星河岛的决定改。 */
    fun onServiceList(observer: Any?, list: Any?) {
        if (observer == null || entranceTypeOf(observer) != STATUS_BAR) return
        (list as? List<*>)?.take(128)?.forEach { service ->
            service ?: return@forEach
            val facts = OfficialCloudReflection.facts(service, null) ?: return@forEach
            val known = OfficialCloudSource.decidedKey("${facts.serviceId}|${facts.instanceId}|", "|${facts.startedAtWallMs}")
            val hide = if (known != null) OfficialCloudSource.suppressesNative(known) else OfficialCloudSource.yieldsByDefault(facts)
            // 服务没带自己的开关时,内容送到时再改(内容那一处)
            val options = OfficialCloudReflection.call(service, "getNewSeedlingCardOptions") ?: return@forEach
            applyHostMap(options, hide)
        }
    }

    /** 名单交给的是哪个显示位置:名单监听者所属的记录管理身上的显示位置(OPPO 接口里的 Entrance)。 */
    private fun entranceTypeOf(observer: Any): Int? {
        for (field in OfficialCloudReflection.fields(observer.javaClass)) {
            val value = runCatching { field.isAccessible = true; field.get(observer) }.getOrNull() ?: continue
            val entrance = if (value.javaClass.name == ENTRANCE) value else OfficialCloudReflection.fieldOfType(value, ENTRANCE)
            (OfficialCloudReflection.call(entrance, "getEntranceType") as? Number)?.let { return it.toInt() }
        }
        return null
    }

    /** 系统把名单交给显示之前:通知变成的胶囊,状态栏的那几条按星河岛的决定改开关。 */
    fun beforeList(models: List<*>) {
        for (model in models) {
            if (model == null || !OfficialCloudReflection.isCommon(model) || OfficialCloudReflection.entranceName(model) != STATUS_BAR_ENTRY) continue
            val key = OfficialCloudReflection.nativeKey(model) ?: continue
            val facts = OfficialCloudReflection.commonFacts(model)
            val hide = decide(key, facts)
            runCatching { applyOptions(model, hide) }.onFailure { noteFailure("notification capsule", it) }
            synchronized(commons) { commons[key] = Common(hide, facts) }
        }
    }

    /** 星河岛改了决定、把最近一份内容再交一次的这一会儿(这一次不当新内容读) */
    private val redelivering = ThreadLocal.withInitial { false }

    /** 系统卡片接收内容的方法(按接收对象的类记下) */
    private val receiveMethods = object : ClassValue<Method?>() {
        override fun computeValue(type: Class<*>): Method? =
            type.declaredMethods.singleOrNull { it.name == "onReceiveUIData" && it.parameterCount == 1 }?.apply { isAccessible = true }
    }

    /**
     * 星流的挂钩装好之前就在的状态栏系统卡片(系统服务卡片的读法在名单里认领到它时交过来):从系统卡片身上找到它的接收对象和
     * 最近一份内容(OPPO 接口里的 UIDataInterceptor 与 PantanalUIData),记下来,星流定下要让位时照样把这一份再交一次,
     * 不用等它下一次更新。
     */
    fun adopt(key: String, card: Any, service: Any, user: Int) {
        val interceptor = OfficialCloudReflection.fieldOfType(card, INTERCEPTOR) ?: return
        if (synchronized(cards) { interceptor in cards }) return
        val data = OfficialCloudReflection.fieldOfType(card, OfficialCloudSeedlingReader.PANTANAL_DATA) ?: return
        val method = receiveMethods.get(interceptor.javaClass) ?: return
        val ui = OfficialCloudReflection.call(data, "getSeedlingUIData") ?: return
        val facts = OfficialCloudReflection.facts(service, ui, user, key)
        val applied = OfficialCloudReflection.call(ui, "getShowHostMap") is YieldedHostMap
        val fresh = synchronized(cards) { if (interceptor in cards) false else { cards[interceptor] = Card(key, data, applied, facts, method); true } }
        if (fresh) requestRefresh()
    }

    private val main by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    private val refreshPosted = java.util.concurrent.atomic.AtomicBoolean()

    /** 在主线程核对一次(同一轮里只排一次) */
    private fun requestRefresh() {
        if (refreshPosted.compareAndSet(false, true)) main.post { refreshPosted.set(false); runCatching { refresh() }.onFailure { noteFailure("refresh", it) } }
    }

    /** 通知变成的胶囊上,系统读通知得到的那份开关:记录身上唯一一个会随通知更新重读的 JSON(另一份是只读的应用配置)。 */
    private val optionFields = object : ClassValue<Field?>() {
        override fun computeValue(type: Class<*>): Field? = OfficialCloudReflection.fields(type)
            .filter { it.type == JSONObject::class.java && !Modifier.isFinal(it.modifiers) }.singleOrNull()?.apply { isAccessible = true }
    }

    /** 星河岛改过的那份开关 → 系统原来写的状态栏那一项([NONE] 为没写;[CREATED] 为原来没有这份开关、星河岛新建的) */
    private val optionOriginals = WeakHashMap<JSONObject, Any>()
    private val NONE = Any()
    private val CREATED = Any()

    private fun applyOptions(model: Any, hide: Boolean) {
        val field = optionFields.get(model.javaClass) ?: run {
            noteOnce("options:${model.javaClass.name}", "notification capsule options not found on ${model.javaClass.name}; its status bar capsule is left to the system")
            return
        }
        val json = field.get(model) as? JSONObject
        synchronized(optionOriginals) {
            val original = json?.let { optionOriginals[it] }
            if (hide) {
                if (original != null) return
                val target = json ?: JSONObject().also { field.set(model, it) }
                optionOriginals[target] = if (json == null) CREATED else json.opt(HOST_MAP) ?: NONE
                // 没有任何位置:系统不在状态栏显示它(这份开关只属于状态栏这一条记录)
                target.put(HOST_MAP, JSONArray())
            } else {
                if (json == null || original == null) return
                optionOriginals.remove(json)
                when (original) {
                    CREATED -> field.set(model, null)
                    NONE -> json.remove(HOST_MAP)
                    else -> json.put(HOST_MAP, original)
                }
            }
        }
    }

    /**
     * 星流改了决定(读到新内容、设置变了、星河岛能不能露面变了):决定变了的系统卡片把最近一份内容再交一次,系统按新的开关重算;
     * 通知变成的胶囊重放一次名单。星流还没读过的不动,等下一份内容送到时再按决定改。
     */
    fun refresh() {
        val redo = synchronized(cards) {
            cards.entries.filter { (_, card) -> OfficialCloudSource.decided(card.key) && OfficialCloudSource.suppressesNative(card.key) != card.hide }
                .map { it.key to it.value }
        }
        for ((interceptor, card) in redo) {
            runCatching {
                val hide = OfficialCloudSource.suppressesNative(card.key)
                OfficialCloudReflection.call(card.data, "getSeedlingUIData")?.let { applyHostMap(it, hide) }
                synchronized(cards) { cards[interceptor] = Card(card.key, card.data, hide, card.facts, card.method) }
                redelivering.set(true)
                try { card.method.invoke(interceptor, card.data) } finally { redelivering.set(false) }
            }.onFailure { noteFailure("redeliver", it) }
        }
        val notificationChanged = synchronized(commons) {
            commons.any { (key, common) -> OfficialCloudSource.decided(key) && OfficialCloudSource.suppressesNative(key) != common.hide }
        }
        if (notificationChanged) OfficialCloudHook.refreshNativeDisplay()
        if (redo.isNotEmpty() || notificationChanged) com.astraflow.fluidcloud.hook.Log.i(TAG,
            "capsule yield changed: cards=${redo.map { "${it.second.key}:${!it.second.hide}" }} notifications=$notificationChanged")
    }

    /** 当前按星河岛的决定关着状态栏的系统卡片与通知胶囊个数(诊断用) */
    fun yieldedCount(): Int = synchronized(cards) { cards.values.count { it.hide } } + synchronized(commons) { commons.values.count { it.hide } }

    private val noted = linkedSetOf<String>()

    private fun noteOnce(key: String, message: String) {
        val fresh = synchronized(noted) { noted.size < 128 && noted.add(key) }
        if (fresh) com.astraflow.fluidcloud.hook.Log.w(TAG, message)
    }

    private fun noteFailure(stage: String, failure: Throwable) {
        val where = failure.stackTrace.firstOrNull { it.className.contains("officialcloud") }
        val fresh = synchronized(noted) { noted.size < 128 && noted.add("$stage|${failure.javaClass.name}|$where") }
        if (fresh) com.astraflow.fluidcloud.hook.Log.e(TAG, "capsule yield $stage failed", failure)
    }

    fun resetForTest() {
        status = Status.UNKNOWN
        refreshPosted.set(false)
        synchronized(cards) { cards.clear() }
        synchronized(commons) { commons.clear() }
        synchronized(optionOriginals) { optionOriginals.clear() }
        synchronized(noted) { noted.clear() }
    }
}
