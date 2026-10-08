package com.astraflow.fluidcloud

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.astraflow.fluidcloud.hook.Logger
import com.astraisland.events.EventBridge
import com.astraisland.events.EventKind
import com.astraisland.events.SystemEvent

/**
 * 插件「流体云事件接入」这一边的来源(现行规则「系统事件接入」):收系统流体云送来的记录,读出内容、翻译成标准事件交给星河岛
 * (contracts/island-system-events.v1.json);星河岛告诉它接手了哪些事,它让系统自己的同一个胶囊让位(OfficialCloudYield);
 * 星河岛上点按钮时交回系统执行(OfficialCloudActions)。上不上岛、怎么摆由星河岛决定。
 * - 星河岛不在、没收下接入件时什么都不藏,系统流体云照常由系统显示。
 * - 已显示完的短提示在接入与该类内容仍开启时继续隐藏；关闭接入或对应分类后交还系统。
 */
object OfficialCloudSource {
    private const val TAG = "OfficialCloud"
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val store = OfficialCloudStore()
    private var context: Context? = null
    private var actions: OfficialCloudActions? = null
    @Volatile private var settings = FluidCloudSettings()
    private var reportStatus: (String) -> Unit = {}
    private val reportedGaps = linkedSetOf<String>()
    private val diagSignatures = linkedSetOf<String>()

    /** 星河岛开着且在管事(星河岛报来的;没连上时为假):按钮只在这时执行 */
    @Volatile var islandActive = false
        private set

    /** 检查用:直接告诉它星河岛在不在管事 */
    fun islandActiveForTest(active: Boolean) { islandActive = active }
    /** 星河岛此刻能露面:系统胶囊只在能露面时为星河岛接手的事让位 */
    @Volatile private var yieldAllowed = false
    /** 星河岛接手的事、亮完的提示、一律藏起的、还在等内容的(事件身份,星河岛报来的) */
    private var handledIdentities: Set<String> = emptySet()
    private var spentIdentities: Set<String> = emptySet()
    private var alwaysIdentities: Set<String> = emptySet()
    private var pendingIdentities: Set<String> = emptySet()
    /** 星河岛此刻显示哪些种类的事(事件种类编号) */
    @Volatile private var shownKinds: Set<String> = emptySet()
    /** 上面几组换成的系统记录编号 */
    @Volatile private var nativeClaims: Set<String> = emptySet()
    @Volatile private var spentClaims: Set<String> = emptySet()
    @Volatile private var alwaysClaims: Set<String> = emptySet()
    /**
     * 已经定下让不让位的系统记录编号:星河岛看过的都算,只有刚送到、还读不到内容的等一小会儿。
     * 没定下的按这件事所属的那一类先定([yieldsByDefault])。
     */
    @Volatile private var decidedKeys: Set<String> = emptySet()

    /**
     * 在系统界面里开始:收下设置、准备执行按钮,向星河岛打招呼。[packageName]、[versionCode] 是插件自己的,星河岛按它核对。
     * [reportStatus] 把「这台手机能不能让系统胶囊让位」报给插件的页面,[reportIsland] 把星河岛收没收下插件报给插件的页面。
     */
    fun bind(context: Context, settings: FluidCloudSettings, packageName: String, versionCode: Long, reportStatus: (String) -> Unit = {},
             reportIsland: (String) -> Unit = {}) {
        check(Looper.myLooper() == Looper.getMainLooper())
        this.context = context
        this.settings = settings
        this.reportStatus = reportStatus
        if (actions == null) actions = runCatching { OfficialCloudActions(context) }
            .onFailure { Logger.e(TAG, "official action receiver unavailable", it) }.getOrNull()
        // 菜品图这类共享位置里的图在后台取,取好后重新交一次(现行规则「展开卡片总表」)
        OfficialCloudSharedImages.bind(context) { refresh() }
        AdapterBridge.start(packageName, versionCode, onAccepted = { synchronize() }, onState = ::onIslandState, onStatus = reportIsland)
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

    fun refresh() { if (Looper.myLooper() == Looper.getMainLooper()) synchronize() else main.post { synchronize() } }

    /**
     * 把「这台手机能不能让系统胶囊让位」报给插件的页面(能;卡片内容那一处装不上或数据监听没装上时不能)。
     * 还没定下来时也报,页面不沿用上一次开机留下的结果。
     */
    fun reportVisibility() {
        val status = OfficialCloudYield.status
        main.post { runCatching { reportStatus(status.report) } }
    }

    fun receive(owner: String, revision: Long, change: CloudChange, models: List<Any>, reviewedPlugin: Boolean) {
        // 参数列表先复制，回到主线程后读取系统已经创建的控件；不在系统回调里绘图或读文件。
        val copy = models.toList()
        main.post {
            runCatching {
                val official = copy.filter(OfficialCloudReflection::isOfficial)
                val records = if (change == CloudChange.REMOVE) emptyList() else official.mapNotNull { model -> captureSafely(model, revision, reviewedPlugin) }
                val removed = official.mapNotNull(OfficialCloudReflection::nativeKey).toSet()
                // 云卡到货诊断:每类新签名写一行(正式包可见),卡片有没有来、排好没有、带不带按钮、数据与模板读到没有一眼可知。
                for (record in records) {
                    val s = record.snapshot
                    val sig = "${s.serviceId}|${s.packageName}|${s.entrance}|${s.rendered}|${record.content.title.take(20)}|${record.content.buttons.size}|${record.content.complete}"
                    if (diagSignatures.add(sig)) {
                        if (diagSignatures.size > 256) diagSignatures.clear()
                        com.astraflow.fluidcloud.hook.Log.i(TAG, "cloud record: svc=${s.serviceId} pkg=${s.packageName} entry=${s.entrance} rendered=${s.rendered} " +
                            "visible=${s.visible} title=${record.content.title.take(24)} " +
                            "buttons=${record.content.buttons.map { "${it.label}:${it.action?.method ?: if (it.pending != null) "pending" else "view"}" }} " +
                            "complete=${record.content.complete} gaps=${record.content.gaps} data=${s.data.keys.take(24)} version=${s.templateVersion} page=${s.pageId} " +
                            "card=${s.nativeCard != null}/${s.nativeSeedling != null} nv=${s.nativeViews.values.sumOf { it.size }}" +
                            // 图读不到时写下图片字段的值(只取前 60 个字),看得出系统给的是网址、文件还是别的
                            (if ("image-unavailable" in record.content.gaps) " images=" + s.data.filterKeys { k -> IMAGE_FIELD.containsMatchIn(k) }
                                .entries.take(4).joinToString(",") { (k, v) -> "$k=${v.take(60)}" } else ""))
                    }
                }
                acceptCaptured(owner, revision, change, records, removed)
                if (change != CloudChange.REMOVE && records.any { !it.content.complete }) {
                    // 内容可能先到，系统的详细控件稍后才创建。只补读本次仍有效、还没读全的对象，读全了的不再读，不重建卡片。
                    val waiting = records.filter { !it.content.complete }.mapTo(HashSet()) { it.snapshot.nativeKey }
                    for (delay in listOf(250L, 1000L)) main.postDelayed({
                        if (waiting.isNotEmpty() && store.revision(owner) == revision) runCatching {
                            val enriched = official.filter { OfficialCloudReflection.nativeKey(it) in waiting }
                                .mapNotNull { model -> captureSafely(model, revision, reviewedPlugin) }
                            enriched.filter { it.content.complete }.forEach { waiting.remove(it.snapshot.nativeKey) }
                            if (store.enrich(owner, revision, enriched)) synchronize()
                        }.onFailure { reportFailure("enrich", it, null) }
                    }, delay)
                }
            }.onFailure {
                // 这一来源读取失败时立即取消接管，原系统恢复显示，不能留下半条胶囊。
                store.removeOwner(owner)
                synchronize()
                reportFailure("receive", it, null)
            }
        }
    }

    /** 一条记录读失败只丢这一条,不连累同一批送来的其它记录;原因写进岛的日志(正式包也看得到)。 */
    private fun captureSafely(model: Any, revision: Long, reviewedPlugin: Boolean): CloudRecord? = try {
        capture(model, revision, reviewedPlugin)
    } catch (failure: Throwable) {
        reportFailure("capture", failure, runCatching { OfficialCloudReflection.describe(model) }.getOrNull())
        null
    }

    private val reportedFailures = linkedSetOf<String>()

    /** 同一处、同一种失败只写一次,带调用栈。 */
    private fun reportFailure(stage: String, failure: Throwable, what: String?) {
        val where = failure.stackTrace.firstOrNull { it.className.contains("officialcloud") } ?: failure.stackTrace.firstOrNull()
        val sig = "$stage|${failure.javaClass.name}|$where"
        val fresh = synchronized(reportedFailures) { if (reportedFailures.size > 128) reportedFailures.clear(); reportedFailures.add(sig) }
        if (fresh) com.astraflow.fluidcloud.hook.Log.e(TAG, "official $stage failed${what?.let { " ($it)" }.orEmpty()}", failure)
    }

    /** 当前藏起的系统胶囊个数(诊断用)。 */
    fun hiddenCount(): Int = (nativeClaims + alwaysClaims + spentClaims).size

    private val unlistedLogged = linkedSetOf<String>()
    private val valueLoggedAt = HashMap<String, Long>()
    private var valueLines = 0

    /**
     * 按「展开卡片总表」排卡片时写的日志(正式包也看得到,现行规则「展开卡片总表」):
     * 表里没有的服务每个写一行;外卖、打车、导航按几项不涉及地址的字段写下系统送来的值(同一服务 5 秒一行、总共最多 400 行),
     * 用来核对系统真实的写法(例如红绿灯秒数怎么写、进度是第几段)。路名、车牌、订单号这类不写。
     */
    private fun noteLayout(snapshot: CloudSnapshot, layout: OfficialCloudServices.Layout, record: CloudRecord) {
        if (OfficialCloudServices.kind(snapshot) in SYSTEM_KINDS) return
        if (layout == OfficialCloudServices.Layout.UNLISTED) {
            if (unlistedLogged.size < 128 && unlistedLogged.add(snapshot.serviceId)) com.astraflow.fluidcloud.hook.Log.i(TAG,
                "service ${snapshot.serviceId} (${snapshot.serviceName.take(24)}) is not in the card table; laid out by the official card structure")
            return
        }
        val fields = VALUE_FIELDS[layout] ?: return
        val now = SystemClock.elapsedRealtime()
        if (valueLines >= 400 || valueLoggedAt[snapshot.serviceId]?.let { now - it < 5_000L } == true) return
        valueLoggedAt[snapshot.serviceId] = now
        valueLines++
        val values = fields.mapNotNull { key -> snapshot.data[key]?.let { "$key=${it.take(24)}" } }
        // 左边主图从哪来:共享位置(写提供方)、说明文件的替代图、说明文件自带的图、卡片数据带来的图
        val picture = record.content.levelImages["D1"]?.let { when {
            it.shared != null -> "shared:" + android.net.Uri.parse(it.shared).authority
            it.substitute -> "substitute"
            it.key.startsWith("template:") -> "template"
            else -> "data"
        } }
        val p = record.content.progress
        com.astraflow.fluidcloud.hook.Log.i(TAG, "card values: svc=${snapshot.serviceId} pkg=${snapshot.packageName} host=${snapshot.hostPackage} " +
            "layout=$layout page=${snapshot.pageId} $values picture=$picture progress=${p?.fraction}/${p?.steps}/${p?.labels} " +
            "light=${record.content.levels["C33"] != null}")
    }

    private val VALUE_FIELDS = mapOf(
        OfficialCloudServices.Layout.NAVIGATION to listOf("distanceWithUnit", "distanceWithUnitCapsule", "operation", "isShowWhere",
            "isHasTrafficLight", "isOs14ShowTrafficLight", "countdownTime", "bigLightName", "smallLightName", "isShowLanes",
            "rightInfo", "percent", "node_labels", "showProgress", "isDoubleLine", "showOverViewOpen", "showOverViewClose"),
        OfficialCloudServices.Layout.TAKEOUT to listOf("cardStatus", "cardStatusDesc", "capsuleTitle", "capsuleCardStatus", "showProgress",
            "fluidPercent", "currStep", "currProgress", "progressTabContent", "isShowFluidButton", "btnSee", "showCapsuleIcon", "projected", "deliver"),
        OfficialCloudServices.Layout.RIDE to listOf("fluid_capsule_left", "fluid_capsule_status", "fluid_pnael_title", "isShowFluidButton",
            "fluidBtnText", "isShowProgress", "fluid_percent", "fluid_percent_step", "progressTabContent"),
    )

    /** 读一条系统记录:数据、模板(按钮登记的动作)、通用内容,再按服务整理。 */
    private fun capture(model: Any, revision: Long, reviewedPlugin: Boolean): CloudRecord? {
        val raw = OfficialCloudReflection.snapshot(model, revision, reviewedPlugin) ?: return null
        val template = context?.takeIf { !raw.serviceId.startsWith("common:") }?.let {
            OfficialCloudTemplates.current(it, raw.serviceId, raw.templateVersion, raw.pageId)
        }
        // 数据按系统卡片引擎的读法:说明文件自带的默认内容打底、提供方送来的覆盖,还没翻译的代号换成当前语言
        // (现行规则「系统事件接入」);读不到模板时代号一律当空。服务意图用来认换了新服务号的老服务(现行规则「展开卡片总表」)
        val intent = context?.takeIf { !raw.serviceId.startsWith("common:") }
            ?.let { OfficialCloudTemplates.intentAction(it, raw.serviceId, raw.templateVersion) }.orEmpty()
        val snapshot = raw.copy(data = template?.scope(raw.data) ?: CloudTemplate.stripKeys(raw.data), intentAction = intent)
        val content = OfficialCloudDecoder.decode(snapshot, template) ?: CloudContent("", "", "", gaps = setOf("content-unavailable"))
        val refined = OfficialCloudServices.refine(snapshot, content, SystemClock.elapsedRealtime())
        return CloudRecord(snapshot.copy(root = null, images = emptyMap()), refined)
    }

    fun acceptCaptured(owner: String, revision: Long, change: CloudChange, records: List<CloudRecord>, removed: Set<String> = emptySet()) {
        if (store.apply(owner, revision, change, records, removed)) synchronize()
    }


    /** 系统自带的通话、计时、录制、充电、耳机、音乐、开关和录完的页面:不按「展开卡片总表」排,不写排法的日志 */
    private val SYSTEM_KINDS = setOf(EventKind.CALL, EventKind.TIMER, EventKind.STOPWATCH, EventKind.ALARM, EventKind.FLASH_ALARM,
        EventKind.GAME_TIMER, EventKind.SCREEN_RECORDING, EventKind.SOUND_RECORDING, EventKind.MICROPHONE_SERVICE, EventKind.CHARGING,
        EventKind.BATTERY_TIP, EventKind.DEVICE, EventKind.MUSIC, EventKind.SYSTEM_SWITCH, EventKind.RECORDING_SAVED)

    /** 监听者对象已经不在了:它留下的记录撤掉,相应的卡片与藏起的系统胶囊一并恢复。 */
    fun dropOwners(owners: Set<String>) {
        main.post { if (store.removeOwners(owners)) synchronize() }
    }

    /**
     * 接入与分类归属均有效时按星河岛的接手结果让位；接入关闭后全部交还系统。
     * 已完成的提示与充电使用同一归属，其余内容还要求星河岛此刻能露面。
     */
    fun suppressesNative(nativeKey: String?): Boolean = nativeKey != null && settings.access && islandActive &&
        (nativeKey in spentClaims || nativeKey in alwaysClaims || (yieldAllowed && nativeKey in nativeClaims))

    /** 这条系统记录星河岛已经定下让不让位了(见 [decidedKeys])。 */
    fun decided(nativeKey: String): Boolean = nativeKey in decidedKeys

    /** 已经定下的记录里,编号以 [prefix] 开头、以 [suffix] 结尾的那一条(服务名单里的服务还不知道是哪个用户的);没有时为 null。 */
    fun decidedKey(prefix: String, suffix: String): String? = decidedKeys.firstOrNull { it.startsWith(prefix) && it.endsWith(suffix) }

    /**
     * 星河岛还没看过的新事件,系统胶囊先按它所属的那一类让不让位:接入开着、星河岛在管事、此刻能露面、星河岛显示这一类;
     * 系统的充电与电池提示一律让位。看过以后按实际情况定,两者不同时再改一次(现行规则「系统事件接入」)。
     */
    fun yieldsByDefault(snapshot: CloudSnapshot): Boolean {
        if (!settings.access || !islandActive) return false
        if (OfficialCloudServices.kind(snapshot).id !in shownKinds) return false
        return OfficialCloudServices.alwaysHidden(snapshot) || yieldAllowed
    }

    /** 星河岛上点了按钮([buttonId])或卡片([buttonId] 为空):按系统登记的动作交回系统执行;送达返回真 */
    fun perform(identity: String, buttonId: String?): Boolean = actions?.perform(identity, buttonId) == true

    /** 刚才按的那颗按钮这一下打开了页面没有 */
    fun openedPage(identity: String, buttonId: String): Boolean = actions?.openedPage(identity, buttonId) == true

    /** 全部记录翻译成标准事件交给星河岛;记下每件事现在能不能点开 */
    private fun synchronize() {
        if (context == null) return
        runCatching {
            val records = store.active()
            val events = ArrayList<SystemEvent>(records.size)
            for (record in records) {
                val openable = actions?.update(record) == true
                val event = runCatching { OfficialCloudTranslator.translate(record, openable) }
                    .onFailure { reportFailure("translate", it, record.snapshot.serviceId) }.getOrNull() ?: continue
                events += event
                noteLayout(record.snapshot, OfficialCloudServices.layout(record.snapshot), record)
                val content = record.content
                if (content.gaps.isNotEmpty()) {
                    val gapKey = record.snapshot.serviceId + ":" + content.gaps.sorted().joinToString(",")
                    if (reportedGaps.size < 128 && reportedGaps.add(gapKey)) com.astraflow.fluidcloud.hook.Log.i(TAG, "service ${record.snapshot.serviceId} content gaps: ${content.gaps}")
                }
            }
            actions?.retain(records.mapTo(HashSet()) { it.snapshot.key })
            AdapterBridge.push(events, settings)
            if (remapClaims()) OfficialCloudYield.refresh()
        }.onFailure { reportFailure("synchronize", it, null) }
    }

    /** 星河岛报来接手情况(主线程上) */
    fun onIslandState(state: Bundle) {
        fun set(key: String) = state.getStringArray(key)?.toSet().orEmpty()
        handledIdentities = set(EventBridge.KEY_HANDLED)
        spentIdentities = set(EventBridge.KEY_SPENT)
        alwaysIdentities = set(EventBridge.KEY_ALWAYS)
        pendingIdentities = set(EventBridge.KEY_PENDING)
        shownKinds = set(EventBridge.KEY_SHOWN_KINDS)
        islandActive = state.getBoolean(EventBridge.KEY_ACTIVE)
        val allowed = state.getBoolean(EventBridge.KEY_YIELD_ALLOWED)
        val changed = allowed != yieldAllowed
        yieldAllowed = allowed
        if (remapClaims() || changed) OfficialCloudYield.refresh()
    }

    /**
     * 星河岛报来的事件身份换成系统记录编号(系统记录会换,同一件事可能有几条);变了返回真。
     * 星河岛还不在、没收下接入件时什么都不藏。
     */
    private fun remapClaims(): Boolean {
        val connected = AdapterBridge.connected()
        fun native(identities: Set<String>) = if (!connected) emptySet() else identities.flatMapTo(linkedSetOf()) { store.nativeKeys(it) }
        val hide = native(handledIdentities)
        val spent = native(spentIdentities)
        val always = native(alwaysIdentities)
        val decided = if (!connected) emptySet() else store.allNativeKeys() - native(pendingIdentities)
        val changed = hide != nativeClaims || spent != spentClaims || always != alwaysClaims || decided != decidedKeys
        nativeClaims = hide; spentClaims = spent; alwaysClaims = always; decidedKeys = decided
        return changed
    }

    /** 卡片数据里放图片的字段名(诊断用) */
    private val IMAGE_FIELD = Regex("(?i)(icon|image|img|logo|pic)")

    fun resetForTest() {
        AdapterBridge.stop()
        actions?.close(); actions = null
        context = null
        settings = FluidCloudSettings()
        reportStatus = {}
        store.clear(); reportedGaps.clear(); diagSignatures.clear()
        islandActive = false; yieldAllowed = false
        handledIdentities = emptySet(); spentIdentities = emptySet(); alwaysIdentities = emptySet(); pendingIdentities = emptySet(); shownKinds = emptySet()
        nativeClaims = emptySet(); spentClaims = emptySet(); alwaysClaims = emptySet(); decidedKeys = emptySet()
        OfficialCloudImages.clear()
        OfficialCloudTemplates.clear()
        OfficialCloudSystemTips.clear()
        OfficialCloudSharedImages.clear()
        unlistedLogged.clear(); valueLoggedAt.clear(); valueLines = 0
    }
}
