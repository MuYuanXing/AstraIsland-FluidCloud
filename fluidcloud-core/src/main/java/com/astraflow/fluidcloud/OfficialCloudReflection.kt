package com.astraflow.fluidcloud

import android.graphics.Bitmap
import java.lang.reflect.Field
import java.lang.reflect.Modifier

/** 只读取已加载对象；不初始化第二套厂商引擎，不调用 parseDataByEngine 改写系统回调。 */
object OfficialCloudReflection {
    const val SEEDLING = "com.oplus.seedling.sdk.seedling.ISeedling"
    const val SERVICE_INFO = "pantanal.decision.ServiceInfo"
    private const val MAX_NODES = 512
    private const val MAX_TEXT = 16_384
    private const val MAX_DATA = 256 * 1024
    private const val UI_DATA_KEY = "ui_data"
    private val ENVELOPE_KEYS = setOf("timestamp", "card_unique_key", "options")

    /**
     * 方法按类记下(同名、同样多参数的几个):系统判定胶囊显示、读卡片时会频繁调到这里,不能每次都翻一遍方法表。
     * 记在类自己身上(ClassValue),插件重新加载后旧插件的类照样能被回收。
     */
    private val methodLists = object : ClassValue<java.util.concurrent.ConcurrentHashMap<String, List<java.lang.reflect.Method>>>() {
        override fun computeValue(type: Class<*>) = java.util.concurrent.ConcurrentHashMap<String, List<java.lang.reflect.Method>>()
    }

    /** 字段按类记下,记法同上。 */
    private val fieldLists = object : ClassValue<List<Field>>() {
        override fun computeValue(type: Class<*>): List<Field> = generateSequence(type) { it.superclass }
            .takeWhile { it != Any::class.java }.flatMap { it.declaredFields.asSequence() }
            .filterNot { Modifier.isStatic(it.modifiers) }.toList()
    }

    fun call(target: Any?, name: String, vararg args: Any?): Any? {
        if (target == null) return null
        return runCatching {
            val candidates = methodLists.get(target.javaClass).getOrPut("$name/${args.size}") {
                target.javaClass.methods.filter { it.name == name && it.parameterCount == args.size }.onEach { it.isAccessible = true }
            }
            // 带参数的按实参的类型挑出对得上的那一个
            val method = candidates.firstOrNull { method ->
                args.indices.all { i -> args[i] == null || boxed(method.parameterTypes[i]).isInstance(args[i]) }
            } ?: return null
            method.invoke(target, *args)
        }.getOrNull()
    }

    private fun boxed(type: Class<*>): Class<*> = when (type) {
        Integer.TYPE -> Integer::class.java
        java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
        java.lang.Long.TYPE -> java.lang.Long::class.java
        else -> type
    }

    fun fields(type: Class<*>): List<Field> = fieldLists.get(type)

    fun fieldOfType(target: Any, name: String): Any? = fields(target.javaClass)
        .firstOrNull { it.type.name == name }?.let { field -> runCatching { field.isAccessible = true; field.get(target) }.getOrNull() }

    /**
     * 系统流体云的一条记录:系统服务卡片来自新读法(OfficialCloudSeedlingReader.Entry,只读 OPPO 接口);
     * 通知变成的胶囊来自系统流体云插件的数据监听(SOURCE_COMMON)。插件自己的系统服务记录不再读。
     */
    fun isOfficial(model: Any): Boolean = if (model is OfficialCloudSeedlingReader.Entry) true else when (val type = call(model, "getSourceType")?.toString()) {
        "SOURCE_SEEDLING" -> false
        "SOURCE_COMMON" -> fields(model.javaClass).any { it.type == android.service.notification.StatusBarNotification::class.java }
        else -> { noteOtherType(model, type); false } // 标准通知继续由岛现有通知来源接收。
    }

    /** 认不出的记录类型(包括读不到类型)每种写一行进岛的日志(正式包也看得到),系统换了写法时看得出为什么没上岛。 */
    private val otherTypes = linkedSetOf<String>()

    private fun noteOtherType(model: Any, type: String?) {
        val line = "${model.javaClass.name}:${type ?: "unreadable"}"
        val fresh = synchronized(otherTypes) { otherTypes.size < 64 && otherTypes.add(line) }
        if (fresh) com.astraflow.fluidcloud.hook.Log.i("OfficialCloud", "record type ${type ?: "unreadable"} on ${model.javaClass.name} is not read as a system event")
    }

    fun nativeKey(model: Any): String? = if (model is OfficialCloudSeedlingReader.Entry) model.key
        else (call(model, "getKey") as? String)?.takeIf { it.isNotBlank() && it.length <= 1024 }

    /**
     * 到货诊断用的一句话:服务号、卡片引擎在不在、内容树有没有、数据有多大。只读字段,不调用会改状态的方法。
     * 通知转成的系统卡片写成 common:包名。
     */
    fun describe(model: Any): String {
        if (call(model, "getSourceType")?.toString() == "SOURCE_COMMON") {
            val sbn = fieldOfType(model, "android.service.notification.StatusBarNotification") as? android.service.notification.StatusBarNotification
            return "common:${sbn?.packageName}:${sbn?.id}"
        }
        if (model !is OfficialCloudSeedlingReader.Entry) return "${model.javaClass.name} (not read)"
        return "${text(call(model.service, "getServiceId"))} engine=${model.seedling != null} tree=${call(model.ui, "getRootNode") != null} " +
            "data=${(call(model.ui, "getData") as? ByteArray)?.size ?: 0}"
    }

    /** 通知变成的系统胶囊(不是系统服务推送的卡片)。 */
    fun isCommon(model: Any): Boolean = call(model, "getSourceType")?.toString() == "SOURCE_COMMON"

    /** 这条记录在哪个显示位置(系统流体云插件自己的 ENTRY_STATUS_BAR、ENTRY_NOTIFICATION、ENTRY_AOD);读不到时为 null。 */
    fun entranceName(model: Any): String? = fields(model.javaClass).filter { it.type.isEnum }.firstNotNullOfOrNull { field ->
        runCatching { field.isAccessible = true; (field.get(model) as? Enum<*>)?.name }.getOrNull()?.takeIf { it.startsWith("ENTRY_") }
    }

    /** 显示位置的编号,与系统「在哪些位置显示」开关里写的一致:状态栏 8、通知栏 16、息屏 4。 */
    fun entranceCode(name: String?): Int? = when (name) {
        "ENTRY_STATUS_BAR" -> OfficialCloudYield.STATUS_BAR
        "ENTRY_NOTIFICATION" -> 16
        "ENTRY_AOD" -> 4
        else -> null
    }

    /**
     * 系统流体云定这件事在这个位置([entry])显不显示(或锁屏时显不显示,[lockScreen]),读法与系统流体云插件一致:
     * 还没有卡片内容时看服务自带的「在哪些位置显示」(没写这个位置时,服务类别 2 的显示,锁屏同样);有了内容看内容里写的,
     * 内容没写这个位置时看内容的 shouldShow。星河岛让位时改过的开关按系统原来的读(现行规则「系统事件接入」)。
     */
    fun seedlingShows(service: Any?, ui: Any?, entry: Int?, lockScreen: Boolean): Boolean {
        val getter = if (lockScreen) "getLockScreenShowHostMap" else "getShowHostMap"
        val fromOptions = entry?.let { OfficialCloudYield.original(call(call(service, "getNewSeedlingCardOptions"), getter))?.get(it) } as? Boolean
        if (ui == null) return fromOptions ?: ((call(service, "getServiceCategory") as? Number)?.toInt() == SERVICE_CATEGORY_DEFAULT_SHOWN)
        val fromData = entry?.let { OfficialCloudYield.original(call(ui, getter))?.get(it) } as? Boolean
        return fromData ?: (call(ui, "getShouldShow") as? Boolean ?: false)
    }

    /** 还没写显示位置时照样显示的服务类别(系统流体云插件的写法) */
    private const val SERVICE_CATEGORY_DEFAULT_SHOWN = 2

    /**
     * 通知变成的系统胶囊按通知自带的 oplusLiveAlertOptions 定,读法与系统流体云插件一致:没写 showHostMap 时各个位置都显示,
     * 没写 lockScreenShowHostMap 时锁屏不显示;写了就看里面有没有这个位置。读的是通知里的原文,不受星河岛让位影响。
     */
    fun commonShows(sbn: android.service.notification.StatusBarNotification, entry: Int?, lockScreen: Boolean): Boolean {
        val options = sbn.notification?.extras?.getString(OfficialCloudYield.OPTIONS_EXTRA)
            ?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }
        val hosts = options?.optJSONArray(if (lockScreen) "lockScreenShowHostMap" else "showHostMap") ?: return !lockScreen
        return entry != null && (0 until hosts.length()).any { hosts.opt(it) == entry }
    }

    /**
     * 刚送到、星流还没读过的系统服务记录:只取定「默认让不让位」要用的几项(服务号、来源应用、录屏录音此刻是哪一页),
     * 不读图、不读内容树。[ui] 是刚送到的卡片内容(还没交给记录),没有时为 null。
     */
    fun facts(service: Any, ui: Any?, user: Int = 0, nativeKey: String = ""): CloudSnapshot? {
        val serviceId = text(call(service, "getServiceId")).takeIf { it.isNotBlank() } ?: return null
        val options = call(service, "getNewSeedlingCardOptions")
        val pkg = text(call(ui, "getDataSourcePkgName")).ifBlank {
            text(call(options, "getDataSourcePkgName")).ifBlank { text(call(service, "getHostPackage")) }
        }
        val raw = if (OfficialCloudServices.recorder(serviceId)) call(ui, "getData") as? ByteArray else null
        return CloudSnapshot(nativeKey, user, pkg, serviceId, text(call(service, "getServiceInstanceId")),
            (call(service, "getTimeStamp") as? Number)?.toLong() ?: 0L, 0, hostPackage = text(call(service, "getHostPackage")), pageId = page(raw))
    }

    /** 通知变成的系统胶囊:定「默认让不让位」要用的几项。 */
    fun commonFacts(model: Any): CloudSnapshot? {
        val sbn = fieldOfType(model, SBN) as? android.service.notification.StatusBarNotification ?: return null
        return CloudSnapshot(nativeKey(model) ?: return null, (call(model, "getUserId") as? Number)?.toInt() ?: 0, sbn.packageName,
            "common:${sbn.packageName}:${sbn.id}", sbn.key, 0, 0, notificationIds = setOf(sbn.id), notification = sbn)
    }

    private const val SBN = "android.service.notification.StatusBarNotification"

    fun snapshot(model: Any, revision: Long, reviewedPlugin: Boolean): CloudSnapshot? {
        if (!isOfficial(model)) return null
        if (model !is OfficialCloudSeedlingReader.Entry) return if (isCommon(model)) commonSnapshot(model, revision, reviewedPlugin) else null
        val service = model.service
        val ui = model.ui
        val options = call(service, "getNewSeedlingCardOptions") ?: call(service, "getSeedlingCardOptions")
        val pkg = text(call(ui, "getDataSourcePkgName")).ifBlank {
            text(call(options, "getDataSourcePkgName")).ifBlank { text(call(service, "getHostPackage")) }
        }
        if (!pkg.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))) return null
        val user = model.user.takeIf { it >= 0 } ?: return null
        val serviceId = text(call(service, "getServiceId")).takeIf { it.isNotBlank() } ?: return null
        val instance = text(call(service, "getServiceInstanceId")).ifBlank { text(call(service, "getInstanceId")) }
        val nativeKey = model.key
        val time = (call(service, "getTimeStamp") as? Number)?.toLong() ?: return null
        val nodes = intArrayOf(0)
        val root = node(call(ui, "getRootNode"), nodes, 0) ?: remoteNode(call(ui, "getRemoteViewUIData"))
        val images = linkedMapOf<String, CloudImage>()
        var imageBudget = 8 * 1024 * 1024
        // 图片内容和上次一样时沿用上次拷好的那一份,不再拷贝、不再算指纹(导航、秒表每秒送一次)
        (call(ui, "getRawDataMap") as? Map<*, *>)?.entries?.take(64)?.forEach { (key, data) ->
            val bytes = call(data, "getByteArray") as? ByteArray
            if (bytes != null && bytes.size <= OfficialCloudImages.MAX_BYTES && bytes.size <= imageBudget) {
                imageBudget -= bytes.size
                val uri = text(call(data, "getUri"))
                val image = OfficialCloudImages.copyOf("$nativeKey|raw|$key", uri.ifBlank { key.toString() }, bytes)
                images[key.toString()] = image
                if (uri.isNotBlank()) images[uri] = image
            }
        }
        val remote = call(ui, "getRemoteViewUIData")
        (call(remote, "getImageDataMap") as? Map<*, *>)?.entries?.take(64)?.forEach { (key, data) ->
            (call(data, "getData") as? ByteArray)?.takeIf { it.size <= OfficialCloudImages.MAX_BYTES && it.size <= imageBudget }?.let {
                imageBudget -= it.size
                images[key.toString()] = OfficialCloudImages.copyOf("$nativeKey|remote|$key", key.toString(), it)
            }
        }
        val ids = ((call(ui, "getNotificationIdList") ?: call(options, "getNotificationIdList")) as? Collection<*>)
            .orEmpty().take(64).mapNotNull { (it as? Number)?.toInt() }.toSet()
        val entrance = model.entrance
        val entry = entranceCode(entrance)
        // 卡片引擎可能比内容晚建好:稍后补读时再从系统卡片身上取一次
        val seedling = model.seedling ?: call(model.card, "getInnerCard")
        // 卡片数据:记录上那份读不到时,用卡片引擎自己持有的那一份;提供方还没送数据时,和卡片引擎一样用发起时带来的初始数据;
        // 系统界面自己做的提示(手电筒、勿扰、响铃模式)这几处都没有,用系统界面生成提示时的那一份
        val sent = (call(ui, "getData") as? ByteArray)?.takeIf { it.isNotEmpty() }
            ?: (call(call(seedling, "getUIData"), "getData") as? ByteArray)?.takeIf { it.isNotEmpty() }
        val initial = if (sent == null) initData(service) else null
        val raw = sent ?: initial ?: OfficialCloudSystemTips.data(serviceId)
        // 初始数据是服务发起那一刻写下的(服务时间戳是墙上时钟):按那一刻算收到的时间,稍后重读时倒计时不从头算
        val nowElapsed = android.os.SystemClock.elapsedRealtime()
        val wallNow = System.currentTimeMillis()
        val receivedAt = initial?.let { time.takeIf { it in 1..wallNow }?.let { nowElapsed - (wallNow - it) } } ?: nowElapsed
        val (instant, remindLevel) = instantFacts(service, ui, options)
        val shake = shouldShake(ui, options)
        return CloudSnapshot(nativeKey, user, pkg, serviceId, instance, time, revision, entrance,
            text(call(ui, "getTitle")), text(call(ui, "getDes")), root, images,
            (call(ui, "getIcon") as? Bitmap)?.takeUnless { it.isRecycled }?.let { CloudImage("icon:$nativeKey:${it.generationId}", bitmap = it) },
            ids, seedlingShows(service, ui, entry, lockScreen = false),
            seedlingShows(service, ui, entry, lockScreen = true),
            nativeCard = model.card,
            nativeSeedling = seedling,
            sourceUpdatedAtMs = revision,
            ended = (call(ui, "getControlAction") as? Number)?.toInt() == 1,
            data = data(raw),
            hostPackage = text(call(service, "getHostPackage")),
            serviceName = ((call(service, "getExtras") as? Map<*, *>)?.get("serviceTitleName") as? String).orEmpty().take(64),
            templateVersion = (call(service, "getVersionCode") as? Number)?.toLong() ?: text(call(seedling, "getUpkVersion")).toLongOrNull() ?: 0L,
            // 页面按卡片数据写明的 options.pageId(卡片引擎也按它选页);不去问卡片引擎,免得在它排版前提前触发它的内部初始化
            pageId = page(raw),
            receivedAtElapsedMs = receivedAt,
            rendered = root != null,
            instant = instant, remindLevel = remindLevel, shake = shake)
    }

    /** 这一版数据要不要胶囊抖一下:卡片数据(或发起提示时的选项)里的扩展动作表写了 shouldShake 为真 */
    fun shouldShake(ui: Any?, options: Any?): Boolean =
        ((call(ui, "getExtensibleActionMap") ?: call(options, "getExtensibleActionMap")) as? Map<*, *>)?.get(SHOULD_SHAKE)
            ?.let { it == true || it.toString().equals("true", ignoreCase = true) } == true

    private const val SHOULD_SHAKE = "shouldShake"

    /**
     * 系统怎么定这条提示亮多久,读法与系统流体云插件一致(现行规则「系统事件接入」):服务类型是 2 的只亮一下;
     * 提醒级别先看卡片数据上的,卡片数据还没有时看发起提示时的选项。
     */
    fun instantFacts(service: Any?, ui: Any?, options: Any?): Pair<Boolean, Int> =
        ((call(service, "getSeedlingType") as? Number)?.toInt() == SEEDLING_TYPE_INSTANT) to
            ((call(ui, "getRemindLevel") ?: call(options, "getRemindLevel")) as? Number ?: 0).toInt()

    /** 系统的服务类型:只亮一下的提示(SeedlingType.IMMEDIATE) */
    private const val SEEDLING_TYPE_INSTANT = 2

    /**
     * 卡片数据,读法与系统卡片引擎一致(现行规则「系统事件接入」):提供方送来的是
     * {timestamp, card_unique_key, options, ui_data} 这样一份文字,模板里的 {{字段}} 按 ui_data 里的字段代入;
     * ui_data 可以是对象,也可以是再包一层的文字。没有 ui_data 的旧格式按第一层读(去掉 timestamp、card_unique_key、options)。
     * 只取一层,大小有上限,读不到时为空。
     */
    fun data(raw: ByteArray?): Map<String, String> {
        val json = envelope(raw) ?: return emptyMap()
        val fields = when (val ui = json.opt(UI_DATA_KEY)) {
            is org.json.JSONObject -> ui
            is String -> runCatching { org.json.JSONObject(ui) }.getOrNull()
            else -> null
        }?.let(CloudTemplate::flatten) ?: CloudTemplate.flatten(json).filterKeys { it !in ENVELOPE_KEYS }
        return fields.entries.take(256).associate { (k, v) -> k.take(100) to v.take(MAX_TEXT) }
    }

    /**
     * 提供方还没送数据时,卡片引擎先用发起时带来的初始数据画卡片,读法与卡片引擎一致:服务附加项里写了 uidata_from_business 的原样用;
     * 否则取 systemInitData 里的 initData,照引擎的写法包成 {options:{pageId}, ui_data}。都没有时为空。
     * (例如闹钟稍后提醒:时钟应用发起时把剩余时间写在初始数据里,不读它就只剩说明文件里的占位内容「00:00」「上午」。)
     */
    fun initData(service: Any?): ByteArray? {
        val extras = call(service, "getExtras") as? Map<*, *> ?: return null
        extras[BUSINESS_UI_DATA_KEY]?.toString()?.takeIf { it.isNotBlank() }?.let { return it.toByteArray(Charsets.UTF_8) }
        val init = (extras[INIT_DATA_KEY] as? String)?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }
            ?.optString("initData")?.takeIf { it.isNotBlank() && it != "null" } ?: return null
        val page = extras["pageId"]?.toString()
        val envelope = if (page == null) "{\"ui_data\":$init}" else "{\"options\":{\"pageId\":${org.json.JSONObject.quote(page)}},\"ui_data\":$init}"
        return envelope.toByteArray(Charsets.UTF_8)
    }

    private const val INIT_DATA_KEY = "systemInitData"
    private const val BUSINESS_UI_DATA_KEY = "uidata_from_business"

    /** 卡片数据里写明的模板页(options.pageId,系统卡片引擎按它选页面);没有时为空。 */
    fun page(raw: ByteArray?): String = envelope(raw)?.optJSONObject("options")?.optString("pageId").orEmpty().take(64)

    private fun envelope(raw: ByteArray?): org.json.JSONObject? {
        if (raw == null || raw.isEmpty() || raw.size > MAX_DATA) return null
        return runCatching { org.json.JSONObject(String(raw, Charsets.UTF_8)) }.getOrNull()
    }

    /**
     * 通知变成的系统胶囊。系统已经画好的胶囊控件只在核对过的那一版插件上读([reviewedPlugin],取控件用的是那一版里的短名字),
     * 别的版本只读通知本身。
     */
    private fun commonSnapshot(model: Any, revision: Long, reviewedPlugin: Boolean): CloudSnapshot? {
        val sbn = fieldOfType(model, SBN) as? android.service.notification.StatusBarNotification ?: return null
        val user = (call(model, "getUserId") as? Number)?.toInt()?.takeIf { it >= 0 } ?: return null
        val notification = sbn.notification
        val extras = notification.extras
        val title = extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT)?.toString()
            ?: extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString().orEmpty()
        val views = linkedMapOf<Int, MutableList<android.view.View>>()
        if (reviewedPlugin) (call(model, "j") as? Collection<*>)?.take(16)?.forEach { wrapper ->
            val size = when (call(wrapper, "l")?.toString()) { "VIEW_SIZE_LG" -> 9; "VIEW_SIZE_MD" -> 8; "VIEW_SIZE_SM" -> 7; else -> null }
            val view = call(wrapper, "n", false) as? android.view.View
            if (size != null && view != null) views.getOrPut(size) { mutableListOf() }.add(view)
        }
        val entrance = entranceName(model) ?: "UNKNOWN"
        val entry = entranceCode(entrance)
        return CloudSnapshot(nativeKey(model) ?: return null, user, sbn.packageName,
            "common:${sbn.packageName}:${sbn.id}", sbn.key, 0, revision,
            entrance = entrance,
            title = title, description = text,
            icon = (notification.getLargeIcon() ?: notification.smallIcon)?.let { CloudImage("notification:${sbn.key}", platform = it) },
            notificationIds = setOf(sbn.id),
            visible = commonShows(sbn, entry, lockScreen = false),
            lockScreenAllowed = commonShows(sbn, entry, lockScreen = true),
            nativeModel = model, notification = sbn, nativeViews = views,
            sourceUpdatedAtMs = revision,
            receivedAtElapsedMs = android.os.SystemClock.elapsedRealtime())
    }

    private fun node(value: Any?, count: IntArray, depth: Int): CloudNode? {
        if (value == null) return null
        if (depth > 20 || ++count[0] > MAX_NODES) return CloudNode("truncated")
        val props = (call(value, "getProps") as? Map<*, *>)?.entries?.take(64)?.associate {
            text(it.key).take(100) to text(it.value)
        }.orEmpty()
        return CloudNode(text(call(value, "getType")), text(call(value, "getLevel")), props,
            (call(value, "getChildren") as? Collection<*>)?.take((MAX_NODES - count[0] + 1).coerceAtLeast(1))?.mapNotNull { node(it, count, depth + 1) }.orEmpty())
    }

    private fun remoteNode(remote: Any?): CloudNode? {
        if (remote == null) return null
        val children = mutableListOf<CloudNode>()
        (call(remote, "getTextDataMap") as? Map<*, *>)?.entries?.take(64)?.forEach { (key, data) ->
            children += CloudNode("text", key.toString(), mapOf("text" to text(call(data, "getText"))))
        }
        (call(remote, "getImageDataMap") as? Map<*, *>)?.keys?.take(64)?.forEach {
            children += CloudNode("image", it.toString(), mapOf("src" to it.toString()))
        }
        (call(remote, "getProgressDataMap") as? Map<*, *>)?.entries?.take(16)?.forEach { (key, data) ->
            children += CloudNode("progress", key.toString(), mapOf("percent" to text(call(data, "getProgressPercent"))))
        }
        return children.takeIf { it.isNotEmpty() }?.let { CloudNode("remote", children = it) }
    }

    fun text(value: Any?): String = value?.toString()?.take(MAX_TEXT).orEmpty()
}
