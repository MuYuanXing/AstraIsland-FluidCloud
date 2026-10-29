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
    private class Translated(val snapshot: CloudSnapshot, val content: CloudContent, val openable: Boolean, val imageRevision: Long, val event: SystemEvent)
    private val translated = HashMap<String, Translated>()
    private val applyingIslandState = ThreadLocal.withInitial { false }
    private var displayRefreshPosted = false

    /** 星河岛开着且在管事(星河岛报来的;没连上时为假):按钮只在这时执行 */
    @Volatile var islandActive = false
        private set

    /** 检查用:直接告诉它星河岛在不在管事 */
    fun islandActiveForTest(active: Boolean) { islandActive = active }
    /** 星河岛此刻能露面:系统胶囊只在能露面时为星河岛接手的事让位 */
    @Volatile private var yieldAllowed = false
    /** 星河岛接手的事、亮完的提示、一律藏起的(事件身份,星河岛报来的) */
    private var handledIdentities: Set<String> = emptySet()
    private var spentIdentities: Set<String> = emptySet()
    private var alwaysIdentities: Set<String> = emptySet()
    /** 上面几组换成的系统记录编号 */
    @Volatile private var nativeClaims: Set<String> = emptySet()
    @Volatile private var spentClaims: Set<String> = emptySet()
    @Volatile private var alwaysClaims: Set<String> = emptySet()
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
        }
        if (Looper.myLooper() == Looper.getMainLooper()) apply.run() else main.post(apply)
    }

    fun refresh() { if (Looper.myLooper() == Looper.getMainLooper()) synchronize() else main.post { synchronize() } }

    /**
     * 把原生显示交接的实际执行状态报给插件页面,数据读取入口不作为成功依据。
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
                val records = if (change == CloudChange.REMOVE) emptyList() else official.mapNotNull { model ->
                    captureSafely(model, revision, reviewedPlugin)
                        ?: OfficialCloudReflection.nativeKey(model)?.let { store.previous(owner, it) }
                }
                val removed = official.mapNotNull(OfficialCloudReflection::nativeKey).toSet()
                // 云卡到货诊断:每类新签名写一行(正式包可见),卡片有没有来、排好没有、带不带按钮、数据与模板读到没有一眼可知。
                for (record in records) {
                    val s = record.snapshot
                    // 手电筒这类开关卡:开/关状态与两个开关图的取值写进签名和同一行日志——每换一态各写一行,
                    // 用户在系统里关掉手电筒后看最近一行就知道岛收到的数据是什么(正式包也写)。
                    val switchValues = SWITCH_DIAG_KEYS.mapNotNull { key -> s.data[key]?.let { "$key=${it.take(80)}" } }
                    val sig = "${s.serviceId}|${s.packageName}|${s.entrance}|${s.rendered}|${record.content.title.take(20)}|${record.content.buttons.size}|${record.content.complete}|${switchValues.joinToString(",")}"
                    if (diagSignatures.add(sig)) {
                        if (diagSignatures.size > 256) diagSignatures.clear()
                        com.astraflow.fluidcloud.hook.Log.i(TAG, "cloud record: svc=${s.serviceId} pkg=${s.packageName} entry=${s.entrance} rendered=${s.rendered} " +
                            "visible=${s.visible} title=${record.content.title.take(24)} " +
                            "buttons=${record.content.buttons.map { "${it.label}:${it.action?.method ?: if (it.pending != null) "pending" else "view"}" }} " +
                            "complete=${record.content.complete} gaps=${record.content.gaps} data=${s.data.keys.take(24)} version=${s.templateVersion} page=${s.pageId} " +
                            "card=${s.nativeCard != null}/${s.nativeSeedling != null} nv=${s.nativeViews.values.sumOf { it.size }}" +
                            (if (switchValues.isNotEmpty()) " switch=" + switchValues.joinToString(",") else "") +
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
    fun hiddenCount(): Int = OfficialCloudYield.yieldedCount()
    fun requestedNativeKeys(): Set<String> = (nativeClaims + alwaysClaims + spentClaims).filterTo(linkedSetOf(), ::suppressesNative)

    /**
     * 通知变成的胶囊在接入开且星河岛已连时先藏(现行规则「系统事件接入」):这类事件没有系统数据入口,
     * 等接手确认再藏会先漏出原生胶囊;岛不在、没收下或接入关时集合为空,全部交还系统。
     */
    fun prehiddenNativeKeys(): Set<String> =
        if (settings.access && islandActive && AdapterBridge.connected()) store.commonNativeKeys() else emptySet()

    /**
     * 出口默认拦截的总开关(现行规则「系统事件接入」):记录要上屏必须进显示列表,接入开且星河岛在管事、
     * 此刻能露面时状态栏列表默认全部隐藏——不论记录从哪条数据门进来,未知的门也一并盖住。
     * 接入关闭、星河岛不在、息屏等不露面的时刻全部交还系统(与接手让位同一露面条件)。
     */
    fun exitBlocking(): Boolean = settings.access && islandActive && AdapterBridge.connected() && yieldAllowed

    /** 出口上截下的记录就地读取;同一条重复进列表不重送,解析不了的数据只记一行日志。 */
    fun receiveFromDisplay(model: Any) {
        main.post {
            runCatching {
                val raw = OfficialCloudReflection.fields(model.javaClass).mapNotNull { field ->
                    runCatching { field.isAccessible = true; field.get(model) }.getOrNull()
                }.firstOrNull { it !== model && OfficialCloudReflection.call(it, "getSourceType") != null } ?: model
                if (OfficialCloudReflection.nativeKey(raw) == null) return@post
                val record = capture(raw, OfficialCloudHook.nextRevision(), reviewedPlugin = false) ?: run {
                    if (notedUnreadable.add(raw.javaClass.name)) com.astraflow.fluidcloud.hook.Log.w(TAG,
                        "display-exit record unreadable on ${raw.javaClass.name}; hidden without reading")
                    return@post
                }
                acceptCaptured("display-exit", OfficialCloudHook.nextRevision(), CloudChange.REPLACE, listOf(record))
            }.onFailure { reportFailure("display-exit", it, OfficialCloudReflection.describe(model)) }
        }
    }

    private val notedUnreadable = linkedSetOf<String>()

    /**
     * 这一类内容此刻归不归星河岛接(与星河岛四类开关同义):分类关闭的事件不上岛,
     * 出口默认拦截对它们放行,交还系统显示。
     */
    fun kindAllowed(nativeKey: String): Boolean {
        val kind = store.kindOf(nativeKey) ?: return true
        return when (kind) {
            EventKind.CALL, EventKind.SATELLITE_CALL -> settings.call
            EventKind.TIMER, EventKind.STOPWATCH, EventKind.ALARM, EventKind.FLASH_ALARM,
            EventKind.GAME_TIMER, EventKind.GAME_COUNTDOWN -> settings.timer
            EventKind.SCREEN_RECORDING, EventKind.SOUND_RECORDING, EventKind.RECORDING_SAVED,
            EventKind.MICROPHONE_SERVICE, EventKind.SYSTEM_SWITCH, EventKind.TASK_PROGRESS,
            EventKind.ORDER_PROGRESS, EventKind.PARCEL_PICKUP, EventKind.RIDE, EventKind.NAVIGATION,
            EventKind.TRAFFIC_LIGHT, EventKind.REMINDER, EventKind.APP_NOTICE, EventKind.UNLISTED,
            EventKind.MUSIC -> settings.live
            EventKind.CHARGING, EventKind.BATTERY_TIP, EventKind.DEVICE -> settings.status
            else -> settings.live
        }
    }

    fun aliveNativeKeys(): Set<String> = store.aliveNativeKeys()
    fun confirmedIdentities(): Set<String> {
        val done = OfficialCloudYield.confirmedKeys()
        return (handledIdentities + spentIdentities + alwaysIdentities).filterTo(linkedSetOf()) { identity ->
            val keys = store.nativeKeys(identity)
            keys.isNotEmpty() && keys.all { it in done }
        }
    }

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
        // 原版模板的位置与原始数据一并交给星河岛:用不用模板显示由星河岛决定(版本目录必须与数据的模板版本一致)
        val templateDir = context?.takeIf { !raw.serviceId.startsWith("common:") }?.let { ctx ->
            OfficialCloudTemplates.directory(OfficialCloudTemplates.root(ctx), raw.serviceId, raw.templateVersion)
                ?.takeIf { raw.templateVersion <= 0 || it.name == raw.templateVersion.toString() }?.absolutePath
        }.orEmpty()
        val template = context?.takeIf { !raw.serviceId.startsWith("common:") }?.let {
            OfficialCloudTemplates.current(it, raw.serviceId, raw.templateVersion, raw.pageId)
        }
        // 数据按系统卡片引擎的读法:说明文件自带的默认内容打底、提供方送来的覆盖,还没翻译的代号换成当前语言
        // (现行规则「系统事件接入」);读不到模板时代号一律当空。服务意图用来认换了新服务号的老服务(现行规则「展开卡片总表」)
        val intent = context?.takeIf { !raw.serviceId.startsWith("common:") }
            ?.let { OfficialCloudTemplates.intentAction(it, raw.serviceId, raw.templateVersion) }.orEmpty()
        val snapshot = raw.copy(data = template?.scope(raw.data) ?: CloudTemplate.stripKeys(raw.data), intentAction = intent,
            templateDir = templateDir, templateData = raw.data)
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

    /** 星河岛上点了按钮([buttonId])或卡片([buttonId] 为空):按系统登记的动作交回系统执行;送达返回真 */
    fun perform(identity: String, buttonId: String?): Boolean = actions?.perform(identity, buttonId) == true

    /**
     * 原版模板的按钮动作:星河岛已按这张卡片当前的数据代入好([action]),这里只核对是当前活动、当前页面,再执行。
     */
    fun performTemplate(identity: String, pageId: String, actionName: String, action: CloudAction): Boolean {
        val record = store.active().firstOrNull { it.snapshot.key == identity } ?: return false
        fun page(value: String) = value.removePrefix("/").removePrefix("pages/").removeSuffix(".json")
        val currentPage = page(record.snapshot.pageId)
        if (currentPage.isNotEmpty() && currentPage != page(pageId)) return false
        return actions?.executeTemplate(record, action, pageId, actionName) == true
    }

    /** 刚才按的那颗按钮这一下打开了页面没有 */
    fun openedPage(identity: String, buttonId: String): Boolean = actions?.openedPage(identity, buttonId) == true

    /** 全部记录翻译成标准事件交给星河岛;记下每件事现在能不能点开 */
    private fun synchronize() {
        if (context == null) return
        runCatching {
            val records = store.active()
            val imageRevision = OfficialCloudSharedImages.revision
            val events = ArrayList<SystemEvent>(records.size)
            val liveTranslations = HashSet<String>(records.size)
            for (record in records) {
                val nativeKey = record.snapshot.nativeKey
                liveTranslations += nativeKey
                val openable = actions?.update(record) == true
                val cached = translated[nativeKey]?.takeIf {
                    it.snapshot === record.snapshot && it.content === record.content && it.openable == openable &&
                        it.imageRevision == imageRevision
                }
                val event = cached?.event ?: runCatching { OfficialCloudTranslator.translate(record, openable) }
                    .onFailure { reportFailure("translate", it, record.snapshot.serviceId) }.getOrNull()?.also {
                        translated[nativeKey] = Translated(record.snapshot, record.content, openable, imageRevision, it)
                    } ?: continue
                events += event
                noteLayout(record.snapshot, OfficialCloudServices.layout(record.snapshot), record)
                val content = record.content
                if (content.gaps.isNotEmpty()) {
                    val gapKey = record.snapshot.serviceId + ":" + content.gaps.sorted().joinToString(",")
                    if (reportedGaps.size < 128 && reportedGaps.add(gapKey)) com.astraflow.fluidcloud.hook.Log.i(TAG, "service ${record.snapshot.serviceId} content gaps: ${content.gaps}")
                }
            }
            translated.keys.retainAll(liveTranslations)
            actions?.retain(records.mapTo(HashSet()) { it.snapshot.key })
            AdapterBridge.push(events, settings, store.aliveIdentities())
            // 预隐藏集合跟着数据走:通知胶囊新到、结束时重算显示交接,不依赖接手答复
            val prehiddenNow = prehiddenNativeKeys()
            val prehiddenChanged = prehiddenNow != prehiddenClaims
            prehiddenClaims = prehiddenNow
            if (remapClaims() || prehiddenChanged) OfficialCloudYield.resetRetries()
        }.onFailure { reportFailure("synchronize", it, null) }
    }

    /** 星河岛报来接手情况(主线程上) */
    fun onIslandState(state: Bundle) {
        fun set(key: String) = state.getStringArray(key)?.toSet().orEmpty()
        handledIdentities = set(EventBridge.KEY_HANDLED)
        spentIdentities = set(EventBridge.KEY_SPENT)
        alwaysIdentities = set(EventBridge.KEY_ALWAYS)
        islandActive = state.getBoolean(EventBridge.KEY_ACTIVE)
        yieldAllowed = state.getBoolean(EventBridge.KEY_YIELD_ALLOWED)
        if (remapClaims()) OfficialCloudYield.resetRetries()
        applyingIslandState.set(true)
        try { OfficialCloudYield.refresh() } finally { applyingIslandState.set(false) }
    }

    /** 显示管理对象在接手答复之外恢复或失效时，主动把现有事件再交一次，让岛重新核对实际确认。 */
    fun onDisplayReconciled() {
        if (applyingIslandState.get() == true || displayRefreshPosted) return
        displayRefreshPosted = true
        // 不在原生显示重算的调用栈里再次进入岛和桥接；恢复通知合并在下一轮主线程执行。
        main.post { displayRefreshPosted = false; refresh() }
    }

    /** 最近一次 synchronize 算出的预隐藏集合;变了才让显示交接重算 */
    private var prehiddenClaims: Set<String> = emptySet()

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
        val changed = hide != nativeClaims || spent != spentClaims || always != alwaysClaims
        nativeClaims = hide; spentClaims = spent; alwaysClaims = always
        return changed
    }

    /** 卡片数据里放图片的字段名(诊断用) */
    private val IMAGE_FIELD = Regex("(?i)(icon|image|img|logo|pic)")

    /** 开关卡的诊断字段(手电筒):开没开、胶囊与卡片上那张开关图的取值 */
    private val SWITCH_DIAG_KEYS = listOf("isOpen", "smLightSwitchImg", "lgLightSwitchImg")

    fun resetForTest() {
        AdapterBridge.stop()
        actions?.close(); actions = null
        context = null
        settings = FluidCloudSettings()
        reportStatus = {}
        store.clear(); reportedGaps.clear(); diagSignatures.clear(); translated.clear(); displayRefreshPosted = false
        islandActive = false; yieldAllowed = false
        handledIdentities = emptySet(); spentIdentities = emptySet(); alwaysIdentities = emptySet()
        nativeClaims = emptySet(); spentClaims = emptySet(); alwaysClaims = emptySet(); prehiddenClaims = emptySet()
        notedUnreadable.clear()
        OfficialCloudImages.clear()
        OfficialCloudTemplates.clear()
        OfficialCloudSystemTips.clear()
        OfficialCloudSharedImages.clear()
        unlistedLogged.clear(); valueLoggedAt.clear(); valueLines = 0
    }
}
