package com.astraflow.fluidcloud

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.astraisland.events.EventBridge
import com.astraisland.events.EventKind
import com.astraisland.events.PageNode
import com.astraisland.events.SystemEvent

/**
 * 插件「流体云事件接入」这一边的来源(现行规则「系统事件接入」):收系统流体云送来的记录,读出原始内容整理成官方内容树
 * (模板卡片按模板代入数据,直接通知按通知,航班火车按应用给的界面数据),翻译成标准事件交给星河岛
 * (contracts/island-system-events.v1.json);系统自己的流体云画不画按 [hides] 一条规矩在出口决定(OfficialCloudYield);
 * 星河岛上点按时交回系统执行(OfficialCloudActions)。怎么摆由星河岛决定。
 */
object OfficialCloudSource {
    private const val TAG = "OfficialCloud"
    /** 出口就地读到的记录的来源名 */
    private const val DISPLAY_EXIT = "display-exit"
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val store = OfficialCloudStore()
    private var context: Context? = null
    private var actions: OfficialCloudActions? = null
    @Volatile private var settings = FluidCloudSettings()
    private var reportStatus: (String) -> Unit = {}
    /** 检查用:出口就地读取的每一次投递(正式包为空) */
    @Volatile var displayIntakeObserver: ((Any) -> Unit)? = null

    /** 每件事上一次整理好的结果:记录和共享位置的图都没变时直接用 */
    private class Translated(val snapshot: CloudSnapshot, val imageRevision: Long, val record: CloudRecord, val event: SystemEvent)
    private val translated = HashMap<String, Translated>()

    /** 星河岛开着且在管事(星河岛报来的;没连上时为假):按钮只在这时执行 */
    @Volatile var islandActive = false
        private set

    /** 检查用:直接告诉它星河岛在不在管事 */
    fun islandActiveForTest(active: Boolean) { islandActive = active }
    /** 检查用:直接告诉它星河岛此刻能不能露面 */
    fun yieldAllowedForTest(allowed: Boolean) { yieldAllowed = allowed }
    /** 星河岛此刻能露面(不是熄屏,也不是锁屏时关了锁屏显示) */
    @Volatile var yieldAllowed = false
        private set
    /** 星河岛已经亮完的只亮一下的提示(事件身份) */
    @Volatile private var spentIdentities: Set<String> = emptySet()

    /**
     * 在系统界面里开始:收下设置、准备执行按钮,向星河岛打招呼。[packageName]、[versionCode] 是插件自己的,星河岛按它核对。
     * [reportStatus] 把「这台手机能不能让系统胶囊让位」报给插件的页面,[reportIsland] 把星河岛收没收下插件报给插件的页面。
     */
    fun bind(context: Context, settings: FluidCloudSettings, packageName: String, versionCode: Long, reportStatus: (String) -> Unit = {},
             reportIsland: (String) -> Unit = {}) {
        check(Looper.myLooper() == Looper.getMainLooper())
        this.context = context
        OfficialCloudEngine.bind(context)
        this.settings = settings
        this.reportStatus = reportStatus
        if (actions == null) actions = OfficialCloudActions(context)
        // 菜品图这类共享位置里的图在后台取,取好后重新交一次
        OfficialCloudSharedImages.bind(context) { refresh() }
        AdapterBridge.start(packageName, versionCode, onAccepted = { synchronize() }, onState = ::onIslandState, onStatus = reportIsland)
        islandActive = AdapterBridge.connected()
        reportVisibility()
        synchronize()
    }

    /** 插件的设置变了:交给星河岛(四类开关管星河岛上这几类内容),系统胶囊按新设置重算 */
    fun onSettingsChanged(next: FluidCloudSettings) {
        val apply = Runnable {
            if (next == settings) return@Runnable
            settings = next
            synchronize()
            OfficialCloudYield.refresh()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) apply.run() else main.post(apply)
    }

    fun refresh() {
        val apply = Runnable {
            synchronize()
            OfficialCloudYield.refresh()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) apply.run() else main.post(apply)
    }

    /** 把「系统胶囊能不能让位」报给插件页面;还没定下来时也报,页面不沿用上一次开机留下的结果 */
    fun reportVisibility() {
        val status = OfficialCloudYield.status
        // 显示出口装上就由出口藏;装不上时(ColorOS 15)源头拦截装上也藏得住系统服务卡片。
        // 通知变成的胶囊只有显示出口藏得住,读得了这一类的机器必须显示出口装上,才不会有同一件事出现两次。
        val gateCovers = OfficialCloudGate.installed && !OfficialCloudHook.hasNotificationListeners()
        val report = if (status == OfficialCloudYield.Status.AVAILABLE || gateCovers) OfficialCloudYield.Status.AVAILABLE.report
            else status.report
        main.post { reportStatus(report) }
    }

    /** 一个来源送来的一批记录(参数列表先复制,回到主线程再读) */
    fun receive(owner: String, revision: Long, change: CloudChange, models: List<Any>) {
        val copy = models.toList()
        main.post {
            val official = copy.filter(OfficialCloudReflection::isOfficial)
            val records = if (change == CloudChange.REMOVE) emptyList() else official.mapNotNull { capture(it, revision) }
            acceptCaptured(owner, revision, change, records, official.mapNotNull(OfficialCloudReflection::nativeKey).toSet())
        }
    }

    /**
     * 读一条系统记录的原始事实。模板卡片的数据照系统卡片引擎的读法:说明文件自带的默认内容打底、提供方送来的覆盖,
     * 还没翻译的代号换成当前语言;服务意图用来认换了新服务号的老服务。
     */
    private fun capture(model: Any, revision: Long): CloudSnapshot? {
        val raw = OfficialCloudReflection.snapshot(model, revision) ?: return null
        if (raw.notification != null) return raw
        val context = context ?: return null
        val intent = OfficialCloudTemplates.intentAction(context, raw.serviceId, raw.templateVersion)
        if (raw.remoteData != null) return raw.copy(intentAction = intent)
        val template = OfficialCloudTemplates.current(context, raw.serviceId, raw.templateVersion, raw.pageId)
        return raw.copy(data = template?.scope(raw.data) ?: raw.data, intentAction = intent)
    }

    /**
     * 系统自己的这条流体云此刻藏不藏(现行规则「系统事件接入」):接入开着、星河岛连着并在管事、这一类没关时藏——
     * 星河岛此刻能露面时全部藏;不能露面(熄屏,锁屏时关了锁屏显示)时只藏充电与电池提示(由星河岛自己的充电卡显示)
     * 和星河岛已经亮完的只亮一下的提示。其余交给系统显示。
     */
    fun hides(nativeKey: String): Boolean {
        if (!settings.access || !islandActive || !AdapterBridge.connected() || !kindAllowed(nativeKey)) return false
        // 读不出内容树的记录交还系统显示:藏起系统那份而星河岛又画不出来,用户就什么都看不到
        if (nativeKey in unreadableKeys) return false
        if (yieldAllowed) return true
        val record = store.record(nativeKey) ?: return false
        val kind = OfficialCloudServices.kind(record)
        return kind == EventKind.CHARGING || kind == EventKind.BATTERY_TIP || record.key in spentIdentities
    }

    /** 读到了却整理不出官方内容树、星河岛画不出来的记录(事件身份);这几条不藏,交还系统 */
    @Volatile private var unreadableKeys: Set<String> = emptySet()

    /** 这条系统记录已经从数据门读到了(出口就地读的不算) */
    fun known(nativeKey: String): Boolean = store.record(nativeKey, except = DISPLAY_EXIT) != null

    /** 出口就地读到的记录(没经过数据门的):整批替换时保留所有仍在的记录 */
    private val displayRecords = linkedMapOf<String, CloudSnapshot>()
    /** 非空状态栏列表里暂时缺失的记录;下一轮仍未出现才删除,空列表不参与判断 */
    private val displayAbsent = HashMap<String, Runnable>()
    private const val DISPLAY_REMOVE_DELAY_MS = 1_000L

    /** 出口上没经过数据门的记录就地读取;内容和上一次一样时不重新交 */
    fun receiveFromDisplay(model: Any) {
        main.post {
            val raw = OfficialCloudReflection.fields(model.javaClass).firstNotNullOfOrNull { field ->
                field.isAccessible = true
                field.get(model)?.takeIf { it !== model && OfficialCloudReflection.call(it, "getSourceType") != null }
            } ?: model
            val revision = OfficialCloudHook.nextRevision()
            val record = capture(raw, revision) ?: run {
                if (notedUnreadable.add(raw.javaClass.name)) com.astraflow.fluidcloud.hook.Log.w(TAG,
                    "display-exit record unreadable on ${raw.javaClass.name}")
                return@post
            }
            val previous = displayRecords[record.nativeKey]
            if (previous != null && previous.copy(revision = record.revision, sourceUpdatedAtMs = record.sourceUpdatedAtMs) == record) return@post
            displayRecords[record.nativeKey] = record
            displayAbsent.remove(record.nativeKey)?.let(main::removeCallbacks)
            displayIntakeObserver?.invoke(record)
            acceptCaptured(DISPLAY_EXIT, revision, CloudChange.REPLACE, displayRecords.values.toList())
        }
    }

    /** 非空状态栏列表的缺席只登记延迟清理;真正删除发生在回到主线程之后,不在系统的显示调用里重入 */
    fun noteDisplayList(present: Set<String>) {
        for (key in displayRecords.keys) {
            if (key in present) {
                displayAbsent.remove(key)?.let(main::removeCallbacks)
            } else if (displayAbsent[key] == null) {
                val task = Runnable {
                    displayAbsent.remove(key)
                    if (displayRecords.remove(key) != null) {
                        acceptCaptured(DISPLAY_EXIT, OfficialCloudHook.nextRevision(), CloudChange.REPLACE, displayRecords.values.toList())
                    }
                }
                displayAbsent[key] = task
                main.postDelayed(task, DISPLAY_REMOVE_DELAY_MS)
            }
        }
    }

    private val notedUnreadable = linkedSetOf<String>()

    /**
     * 这一类内容此刻归不归星河岛接:分类关闭的事件不上岛,出口对它放行,交还系统显示。分类与星河岛一致
     * (星河岛按事件种类归成自己的活动种类,再按四类开关放行):音乐交给星河岛自己的音乐,只看接入开关。
     */
    fun kindAllowed(nativeKey: String): Boolean {
        val kind = store.record(nativeKey)?.let(OfficialCloudServices::kind) ?: return true
        return when (kind) {
            EventKind.CALL -> settings.call
            EventKind.TIMER, EventKind.STOPWATCH, EventKind.ALARM, EventKind.FLASH_ALARM, EventKind.GAME_TIMER -> settings.timer
            EventKind.SCREEN_RECORDING, EventKind.SOUND_RECORDING, EventKind.MICROPHONE_SERVICE, EventKind.CHARGING,
            EventKind.BATTERY_TIP, EventKind.DEVICE, EventKind.SYSTEM_SWITCH -> settings.status
            EventKind.MUSIC -> true
            else -> settings.live
        }
    }

    fun acceptCaptured(owner: String, revision: Long, change: CloudChange, records: List<CloudSnapshot>, removed: Set<String> = emptySet()) {
        val applied = store.apply(owner, revision, change, records, removed)
        if (applied && owner != DISPLAY_EXIT) {
            val gone = if (change == CloudChange.REMOVE) removed else records.filter { it.ended }.mapTo(HashSet()) { it.nativeKey }
            for (key in gone) {
                displayRecords.remove(key)
                displayAbsent.remove(key)?.let(main::removeCallbacks)
            }
        }
        if (applied) synchronize()
    }

    /** 监听者对象已经不在了:它留下的记录撤掉,相应的卡片与藏起的系统胶囊一并恢复 */
    fun dropOwners(owners: Set<String>) {
        main.post { if (store.removeOwners(owners)) synchronize() }
    }

    /** 星河岛上点了元素:按这张卡片此刻的数据执行元素登记的动作([action] 是「page:动作名」);送达返回真 */
    fun perform(identity: String, action: String): Boolean = actions?.perform(identity, action) == true

    /** 刚才按的那个元素这一下打开了页面没有 */
    fun openedPage(identity: String, action: String): Boolean = actions?.openedPage(identity, action) == true

    /** 全部记录整理成官方内容树、翻译成标准事件交给星河岛 */
    private fun synchronize() {
        val context = context ?: return
        val imageRevision = OfficialCloudSharedImages.revision
        val events = ArrayList<SystemEvent>()
        val live = HashSet<String>()
        for (snapshot in store.active()) {
            live += snapshot.nativeKey
            val done = translated[snapshot.nativeKey]?.takeIf { it.snapshot === snapshot && it.imageRevision == imageRevision }
                ?: translate(context, snapshot, imageRevision)?.also { translated[snapshot.nativeKey] = it }
                ?: continue
            actions?.update(done.record)
            events += done.event
        }
        translated.keys.retainAll(live)
        // 读到了却整理不出内容树的记录交还系统;还没读到的照旧藏(读到后再按结果定)
        val unreadable = live - translated.keys
        if (unreadable != unreadableKeys) {
            unreadableKeys = unreadable
            // 能不能藏变了:请系统按现在的条件重算状态栏那一份
            main.post { OfficialCloudYield.refresh() }
        }
        actions?.retain(store.active().mapTo(HashSet()) { it.key })
        AdapterBridge.push(events, settings, store.aliveIdentities())
    }

    /**
     * 一条记录整理成官方内容树并翻译成标准事件。系统送来的数据千奇百怪,这一步出错时写一行日志、这一条不上岛,
     * 不连累其它记录,也不让系统界面崩掉;不沿用上一版,不换别的画法(插件里唯一的一处出错拦截)。
     */
    private fun translate(context: Context, snapshot: CloudSnapshot, imageRevision: Long): Translated? = try {
        val views = snapshot.remoteData?.let(OfficialRemotePage::read)
        (views?.let(OfficialRemotePage::page) ?: page(context, snapshot))?.let { page ->
            val record = CloudRecord(snapshot, page, views)
            Translated(snapshot, imageRevision, record, OfficialCloudTranslator.translate(record))
        }
    } catch (failure: Exception) {
        noteOnce("failed:${snapshot.serviceId}:${failure.javaClass.name}") {
            com.astraflow.fluidcloud.hook.Log.e(TAG, "service ${snapshot.serviceId} page ${snapshot.pageId} not readable", failure)
        }
        null
    }

    /** 这条记录这一页的官方内容树:直接通知按通知,其余按模板代入数据(航班火车照界面,见 [translate]) */
    private fun page(context: Context, snapshot: CloudSnapshot): PageNode? {
        snapshot.notification?.let { return OfficialDirectPage.build(it) ?: noteMissing(snapshot, "notification without live alert content") }
        val template = OfficialCloudTemplates.current(context, snapshot.serviceId, snapshot.templateVersion, snapshot.pageId)
            ?: return noteMissing(snapshot, "template not found")
        return OfficialCloudPage.build(snapshot, template) ?: noteMissing(snapshot, "template page has no card")
    }

    private fun noteMissing(snapshot: CloudSnapshot, why: String): PageNode? {
        noteOnce("missing:${snapshot.serviceId}:${snapshot.templateVersion}:${snapshot.pageId}:$why") {
            com.astraflow.fluidcloud.hook.Log.w(TAG, "service ${snapshot.serviceId} v${snapshot.templateVersion} page ${snapshot.pageId}: $why")
        }
        return null
    }

    private val noted = linkedSetOf<String>()

    private fun noteOnce(key: String, write: () -> Unit) {
        if (noted.size < 256 && noted.add(key)) write()
    }

    /**
     * 星河岛报来的情况(主线程):在不在管事、此刻能不能露面、已经亮完的只亮一下的提示;变了就请系统按新条件重算状态栏那一份。
     * 星河岛不再收下插件时报一次「不在管事」,系统胶囊全部交还系统。
     */
    fun onIslandState(state: Bundle) {
        val active = state.getBoolean(EventBridge.KEY_ACTIVE)
        val shown = state.getBoolean(EventBridge.KEY_YIELD_ALLOWED)
        val spent = state.getStringArray(EventBridge.KEY_SPENT)?.toSet().orEmpty()
        if (active == islandActive && shown == yieldAllowed && spent == spentIdentities) return
        islandActive = active
        yieldAllowed = shown
        spentIdentities = spent
        // 星河岛不再接手或不再能露面:源头拦截不再截,把截住的那一份交还系统(现行规则「系统事件接入」)
        OfficialCloudGate.onGateStateChanged()
        OfficialCloudYield.refresh()
    }

    fun resetForTest() {
        AdapterBridge.stop()
        actions?.close(); actions = null
        context = null
        settings = FluidCloudSettings()
        reportStatus = {}
        store.clear(); translated.clear(); noted.clear(); notedUnreadable.clear()
        islandActive = false; yieldAllowed = false; spentIdentities = emptySet()
        displayIntakeObserver = null
        displayAbsent.values.forEach(main::removeCallbacks); displayAbsent.clear()
        displayRecords.clear()
        OfficialCloudImages.clear()
        OfficialCloudTemplates.clear()
        OfficialCloudSystemTips.clear()
        OfficialCloudSharedImages.clear()
    }
}
