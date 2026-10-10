package com.astraflow.fluidcloud

import com.astraflow.fluidcloud.hook.HookHelper
import io.github.libxposed.api.XposedInterface
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference
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
 *   每个名单监听最近一份名单记下,事后改主意时(例如在星河岛里关掉这一类、名单送到那一刻还轮不到让位、或系统在原来的对象上
 *   把开关改写回去)按现在的决定重改开关,并把同一份名单原样再交一次,让系统按新的开关重算;
 * - 卡片内容(UIDataInterceptor.onReceiveUIData):内容送到后,系统按内容里的 SeedlingUIData.showHostMap 定;事后改主意时
 *   把这张卡片最近一份内容原样再交一次,系统按新的开关重算;
 * - 通知变成的胶囊:系统按通知自带的 oplusLiveAlertOptions 里的 showHostMap 定;在系统把名单交给显示之前,改系统读通知得到的那一份
 *   (通知本身不改),事后改主意时重放一次名单。记录上的显示位置这一会儿还认不出时先记下、不改,认得出后再改。
 * 换上去的开关每次核对都按现在的值查,不按「记没记过」:系统在原来的对象上把开关改写回去的,核对时重新盖上(每次收到系统事件
 * 都核对一次,见 [refresh])。星流自己读系统本意时按改之前的开关读([original]、OfficialCloudReflection.commonShows)。
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
     * 已经是这个样子时不动;系统在我们换上的那份里把状态栏改写回来时重新关上。返回实际改没改。
     */
    fun applyHostMap(target: Any, hide: Boolean): Boolean {
        val current = OfficialCloudReflection.call(target, "getShowHostMap")
        if (hide && current is YieldedHostMap)
            return if (current[STATUS_BAR] == false) false else { current.put(STATUS_BAR, false); true }
        if (!hide && current !is YieldedHostMap) return false
        val setter = runCatching { target.javaClass.methods.firstOrNull { it.name == "setShowHostMap" && it.parameterCount == 1 } }
            .getOrNull() ?: run {
            noteOnce("setter:${target.javaClass.name}", "no setShowHostMap on ${target.javaClass.name}; its status bar capsule is left to the system")
            return false
        }
        setter.invoke(target, if (hide) YieldedHostMap(original(current)) else original(current))
        return true
    }

    /** 一张状态栏系统卡片:事件编号、最近一份内容、按哪个决定改的、定默认时用的几项、交内容的那个方法。 */
    private class Card(val key: String, val data: Any, var hide: Boolean, val facts: CloudSnapshot?, val method: Method)

    /** 数据拦截对象 → 它那张卡片。拦截对象随卡片释放,这里不留住它。 */
    private val cards = WeakHashMap<Any, Card>()

    /**
     * 一个名单监听最近一份状态栏名单:交名单的那个方法(为 null 时只能重改、不能再交一次)、名单原样、每个服务定默认时用的几项。
     * 名单随监听者释放,这里不留住它。
     */
    private class ServiceList(val method: Method?, val raw: Any, val services: List<Pair<Any, CloudSnapshot?>>)
    private val serviceLists = WeakHashMap<Any, ServiceList>()

    /** 通知变成的胶囊:事件编号 → 按哪个决定改的、定默认时用的几项、那条记录(位置认不出或被改写回去时核对用)。 */
    private class Common(var hide: Boolean, val facts: CloudSnapshot?, val model: WeakReference<Any>)
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

    /** 名单里这个服务现在该不该让位(名单送到时和事后核对用同一个决定);服务号读不出时不定,内容送到时再改。 */
    private fun hideOf(facts: CloudSnapshot?): Boolean {
        if (facts == null) return false
        val known = OfficialCloudSource.decidedKey("${facts.serviceId}|${facts.instanceId}|", "|${facts.startedAtWallMs}")
        return if (known != null) OfficialCloudSource.suppressesNative(known) else OfficialCloudSource.yieldsByDefault(facts)
    }

    /**
     * 在这一代插件上装读系统服务卡片和让位的挂钩:卡片内容那一处([interceptors])、服务名单那一处([observers]),
     * [manager] 为真时再挂撤掉名单监听那一处。同名方法有重载时挨个都挂,类自己没写时用继承下来的那一个;
     * [unhooked] 回答这个方法是不是还没有哪一代挂过(跨类加载器共享的父类方法只挂一遍,子类的调用一样被拦),同一个方法不挂两遍。
     * 返回装上的挂钩,随这一代插件撤掉;装了卡片内容、服务名单却一个卡片内容都没装上时报「不可用」。
     */
    fun install(loader: ClassLoader, interceptors: List<String>, observers: List<String>, manager: Boolean, apk: String,
                unhooked: (Method) -> Boolean = { true }): List<XposedInterface.HookHandle> {
        val handles = mutableListOf<XposedInterface.HookHandle>()
        val seen = HashSet<java.lang.reflect.Executable>()
        /** 类自己写的同名方法挨个(桥接、合成的副本不算,不然一次调用进两遍);一个都没写时用继承下来的那几个(挂的是父类的方法,子类的调用一样被拦) */
        fun targets(type: Class<*>, name: String, params: Int): List<Method> {
            val own = runCatching { type.declaredMethods.filter { it.name == name && it.parameterCount == params && !it.isSynthetic } }
                .getOrNull().orEmpty()
            return own.ifEmpty { runCatching { type.methods.filter { it.name == name && it.parameterCount == params && !it.isSynthetic } }
                .getOrNull().orEmpty() }
        }
        for (name in interceptors) {
            val type = runCatching { Class.forName(name, false, loader) }.getOrNull()
                ?: run { noteOnce("interceptor:$name", "content receiver $name not found; its cards are left to the system"); continue }
            for (method in targets(type, "onReceiveUIData", 1)) {
                if (!seen.add(method) || !unhooked(method)) continue
                HookHelper.hookMethodBefore(method) { chain ->
                    // 星河岛把最近一份内容再交一次时不再读(内容没变)
                    if (!redelivering.get()) runCatching { OfficialCloudSeedlingReader.onUIData(chain.thisObject, chain.getArg(0)) }.onFailure { noteFailure("read content", it) }
                    runCatching { onUIData(method, chain.thisObject, chain.getArg(0)) }.onFailure { noteFailure("content", it) }
                    null
                }?.let(handles::add)
            }
        }
        val contentHooks = handles.size
        for (name in observers) {
            val type = runCatching { Class.forName(name, false, loader) }.getOrNull()
                ?: run { noteOnce("observer:$name", "service list observer $name not found; its service capsules are left to the system"); continue }
            for (method in targets(type, "onListChanged", 1)) {
                if (!seen.add(method) || !unhooked(method)) continue
                HookHelper.hookMethodBefore(method) { chain ->
                    // 星河岛把最近一份名单再交一次时不再读(名单没变)
                    if (!redelivering.get()) runCatching { OfficialCloudSeedlingReader.onServiceList(chain.thisObject, chain.getArg(0)) }.onFailure { noteFailure("read service list", it) }
                    runCatching { onServiceList(chain.thisObject, chain.getArg(0), method) }.onFailure { noteFailure("service list", it) }
                    null
                }?.let(handles::add)
            }
        }
        val listHooks = handles.size - contentHooks
        // 系统撤掉一个显示位置的名单监听:这个位置的卡片一并结束(类名、方法名都是 OPPO 接口里的)
        val unregister = if (!manager) null else runCatching {
            Class.forName(OfficialCloudSeedlingReader.MANAGER, false, loader).methods
                .firstOrNull { it.name == "unregisterMultiInstanceListCallback" && it.parameterCount == 2 }
                ?.takeIf { seen.add(it) && unhooked(it) }
        }.getOrNull()?.let { method ->
            HookHelper.hookMethodBefore(method) { chain ->
                runCatching { OfficialCloudSeedlingReader.onObserverGone(chain.getArg(0)) }.onFailure { noteFailure("unregister", it) }
                null
            }
        }
        unregister?.let(handles::add)
        // 一张卡片内容都收不到时让不了位;已经能的一代不拿后到的、挂得更少的一代把结果改回去
        if (contentHooks > 0) status = Status.AVAILABLE
        else if (listHooks > 0 && status != Status.AVAILABLE) status = Status.UNAVAILABLE
        com.astraflow.fluidcloud.hook.Log.i(TAG, "capsule yield on $apk: content=$contentHooks/${interceptors.size} " +
            "service list=$listHooks/${observers.size} unregister=${if (unregister != null) 1 else 0} status=${status.report}")
        return handles
    }

    /** 读不到系统流体云(数据监听装不上):这台手机上也就没法让位 */
    fun markUnavailable() { status = Status.UNAVAILABLE }

    /** 卡片内容送到(系统把内容交给记录之前):状态栏的卡片按星河岛的决定改开关,记下这一份内容,改主意时再交一次。 */
    fun onUIData(method: Method, interceptor: Any?, data: Any?) {
        if (interceptor == null || data == null) return
        val located = OfficialCloudSeedlingReader.locate(interceptor) ?: run {
            noteOnce("locate:${interceptor.javaClass.name}",
                "content receiver layout unrecognized on ${interceptor.javaClass.name}; its status bar capsules are left to the system")
            return
        }
        if (located.entrance != OfficialCloudSeedlingReader.STATUS_BAR) return
        val ui = OfficialCloudReflection.call(data, "getSeedlingUIData") ?: run {
            noteOnce("uidata:${data.javaClass.name}", "no SeedlingUIData in ${data.javaClass.name}; the card is left to the system")
            return
        }
        val facts = OfficialCloudReflection.facts(located.service, ui, located.user, located.key)
        val hide = decide(located.key, facts)
        applyHostMap(ui, hide)
        synchronized(cards) { cards[interceptor] = Card(located.key, data, hide, facts, method.apply { isAccessible = true }) }
    }

    /**
     * 服务名单变了(系统把名单交给这个位置的记录管理之前):状态栏的名单里,每个服务自带的开关按星河岛的决定改;
     * 这一份名单记下,事后决定变了按现在的决定重改、把同一份名单再交一次让系统重算([method] 是系统收名单的那个方法)。
     */
    fun onServiceList(observer: Any?, list: Any?, method: Method? = null) {
        if (observer == null || list == null) return
        val entrance = entranceTypeOf(observer)
        if (entrance != STATUS_BAR) {
            if (entrance == null) noteOnce("entrance:${observer.javaClass.name}",
                "observer entrance unrecognized on ${observer.javaClass.name}; its service capsules are left to the system")
            return
        }
        val services = (list as? List<*>)?.take(128)?.mapNotNull { service ->
            service ?: return@mapNotNull null
            val facts = OfficialCloudReflection.facts(service, null)
            if (facts == null) noteOnce("facts:${service.javaClass.name}",
                "service id unreadable on ${service.javaClass.name}; its capsule is left to the system")
            service to facts
        } ?: return
        for ((service, facts) in services) {
            // 服务没带自己的开关时,内容送到时再改(内容那一处)
            val options = OfficialCloudReflection.call(service, "getNewSeedlingCardOptions") ?: run {
                noteOnce("options:${facts?.serviceId}",
                    "service ${facts?.serviceId} has no options to yield through; only its card content can be switched off")
                continue
            }
            applyHostMap(options, hideOf(facts))
        }
        synchronized(serviceLists) { serviceLists[observer] = ServiceList(method?.apply { isAccessible = true }, list, services) }
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

    /**
     * 系统把名单交给显示之前:通知变成的胶囊,状态栏的那几条按星河岛的决定改开关;记录上的显示位置这会儿还认不出时
     * 先记下、不改,下一次核对或重放时认得出再改。
     */
    fun beforeList(models: List<*>) {
        for (model in models) {
            if (model == null || !OfficialCloudReflection.isCommon(model)) continue
            val entrance = OfficialCloudReflection.entranceName(model)
            if (entrance != null && entrance != STATUS_BAR_ENTRY) continue
            val key = OfficialCloudReflection.nativeKey(model) ?: continue
            val facts = OfficialCloudReflection.commonFacts(model)
            val hide = decide(key, facts)
            val applied = entrance == STATUS_BAR_ENTRY &&
                runCatching { applyOptions(model, hide); true }.getOrElse { noteFailure("notification capsule", it); false }
            synchronized(commons) { commons[key] = Common(if (applied) hide else false, facts, WeakReference(model)) }
        }
    }

    /** 星河岛改了决定、把最近一份内容或名单再交一次的这一会儿(这一次不当新内容读) */
    private val redelivering = ThreadLocal.withInitial { false }

    /** 系统卡片接收内容的方法(按接收对象的类记下) */
    private val receiveMethods = object : ClassValue<Method?>() {
        override fun computeValue(type: Class<*>): Method? =
            (type.declaredMethods.firstOrNull { it.name == "onReceiveUIData" && it.parameterCount == 1 && !it.isSynthetic }
                ?: type.methods.firstOrNull { it.name == "onReceiveUIData" && it.parameterCount == 1 && !it.isSynthetic })
                ?.apply { isAccessible = true }
    }

    /**
     * 星流的挂钩装好之前就在的状态栏系统卡片(系统服务卡片的读法在名单里认领到它时交过来):从系统卡片身上找到它的接收对象和
     * 最近一份内容(OPPO 接口里的 UIDataInterceptor 与 PantanalUIData),记下来,星流定下要让位时照样把这一份再交一次,
     * 不用等它下一次更新。找不到能再交一次的那一套时,先把开关按现在的决定改了,等系统自己重算。
     */
    fun adopt(key: String, card: Any, service: Any, user: Int) {
        val interceptor = OfficialCloudReflection.fieldOfType(card, INTERCEPTOR)
        val data = OfficialCloudReflection.fieldOfType(card, OfficialCloudSeedlingReader.PANTANAL_DATA)
        val ui = OfficialCloudReflection.call(data, "getSeedlingUIData")
            ?: OfficialCloudReflection.fieldOfType(card, OfficialCloudSeedlingReader.SEEDLING_UI_DATA) ?: return
        val facts = OfficialCloudReflection.facts(service, ui, user, key)
        if (interceptor == null || data == null) {
            noteOnce("adopt:${card.javaClass.name}",
                "pre-installed card on ${card.javaClass.name} cannot be redelivered; only its switch is changed")
            applyHostMap(ui, decide(key, facts))
            return
        }
        if (synchronized(cards) { interceptor in cards }) return
        val method = receiveMethods.get(interceptor.javaClass) ?: return
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

    /**
     * 把这条记录上的「在哪些位置显示」按 [hide] 置空或放回原来写的;实际改动了返回真。
     * 已经藏过的这一份被系统在原对象上改写回来时重新置空(记下的「原来写的」不动,放回时还是放回系统写的);
     * 系统本来就写空的不算星河岛藏的,放回时也不动它。
     */
    private fun applyOptions(model: Any, hide: Boolean): Boolean {
        val field = optionFields.get(model.javaClass) ?: run {
            noteOnce("options:${model.javaClass.name}", "notification capsule options not found on ${model.javaClass.name}; its status bar capsule is left to the system")
            return false
        }
        val json = field.get(model) as? JSONObject
        synchronized(optionOriginals) {
            if (hide) {
                val target = json ?: JSONObject().also { field.set(model, it) }
                if (optionOriginals.containsKey(target)) {
                    // 已经藏过这份:系统在原对象上改写回来时重新置空
                    val hosts = target.optJSONArray(HOST_MAP)
                    if (hosts != null && hosts.length() == 0) return false
                    target.put(HOST_MAP, JSONArray())
                    return true
                }
                val hosts = target.optJSONArray(HOST_MAP)
                if (hosts != null && hosts.length() == 0) return false
                optionOriginals[target] = if (json == null) CREATED else json.opt(HOST_MAP) ?: NONE
                // 没有任何位置:系统不在状态栏显示它(这份开关只属于状态栏这一条记录)
                target.put(HOST_MAP, JSONArray())
                return true
            }
            if (json == null) return false
            val original = optionOriginals.remove(json) ?: return false
            when (original) {
                CREATED -> field.set(model, null)
                NONE -> json.remove(HOST_MAP)
                else -> json.put(HOST_MAP, original)
            }
            return true
        }
    }

    /**
     * 星流改了决定(读到新内容、设置变了、星河岛能不能露面变了),以及每次收到系统事件后核对一次:
     * 现在该藏而没藏上的按现在的开关重改——系统卡片把最近一份内容再交一次、状态栏名单按现在的决定重改开关再把那一份回放一次、
     * 通知变成的胶囊重放一次名单;系统在原来的对象上把开关改写回去的同样重改。星流还没读过的不动,等下一份送到时再按决定改。
     */
    fun refresh() {
        var redelivered = 0
        val redo = synchronized(cards) {
            cards.entries.filter { (_, card) -> OfficialCloudSource.decided(card.key) }.map { it.key to it.value }
        }
        for ((interceptor, card) in redo) {
            runCatching {
                val hide = OfficialCloudSource.suppressesNative(card.key)
                val changed = OfficialCloudReflection.call(card.data, "getSeedlingUIData")?.let { applyHostMap(it, hide) } == true
                if (changed || hide != card.hide) {
                    card.hide = hide
                    redelivered++
                    redelivering.set(true)
                    try { card.method.invoke(interceptor, card.data) } finally { redelivering.set(false) }
                }
            }.onFailure { noteFailure("redeliver", it) }
        }
        var replayedLists = 0
        val lists = synchronized(serviceLists) { serviceLists.entries.map { it.key to it.value } }
        for ((observer, serviceList) in lists) {
            var changed = false
            for ((service, facts) in serviceList.services) {
                val options = OfficialCloudReflection.call(service, "getNewSeedlingCardOptions") ?: continue
                changed = runCatching { applyHostMap(options, hideOf(facts)) }.getOrDefault(false) || changed
            }
            val method = serviceList.method
            if (!changed || method == null) continue
            replayedLists++
            runCatching {
                redelivering.set(true)
                try { method.invoke(observer, serviceList.raw) } finally { redelivering.set(false) }
            }.onFailure { noteFailure("service list", it) }
        }
        var notificationChanged = false
        synchronized(commons) {
            val entries = commons.entries.iterator()
            while (entries.hasNext()) {
                val (key, common) = entries.next()
                if (!OfficialCloudSource.decided(key)) continue
                val hide = OfficialCloudSource.suppressesNative(key)
                val model = common.model.get()
                val entrance = model?.let { OfficialCloudReflection.entranceName(it) }
                when {
                    // 后来认得出不是状态栏的:撤掉,不再碰
                    entrance != null && entrance != STATUS_BAR_ENTRY -> entries.remove()
                    model == null || entrance == STATUS_BAR_ENTRY -> {
                        if (model != null) runCatching { if (applyOptions(model, hide)) notificationChanged = true }
                            .onFailure { noteFailure("notification capsule", it) }
                        if (common.hide != hide) { common.hide = hide; notificationChanged = true }
                    }
                    // 位置还认不出:不改也不记,下一次再核对
                }
            }
        }
        if (notificationChanged) OfficialCloudHook.refreshNativeDisplay()
        if (redelivered > 0 || replayedLists > 0 || notificationChanged) com.astraflow.fluidcloud.hook.Log.i(TAG,
            "capsule yield changed: redelivered=$redelivered lists=$replayedLists notifications=$notificationChanged")
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
        synchronized(serviceLists) { serviceLists.clear() }
        synchronized(commons) { commons.clear() }
        synchronized(optionOriginals) { optionOriginals.clear() }
        synchronized(noted) { noted.clear() }
    }
}
