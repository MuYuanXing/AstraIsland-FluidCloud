package com.astraflow.fluidcloud

import java.lang.reflect.Field
import java.lang.reflect.Modifier

/** 只读取已加载对象；不初始化第二套厂商引擎，不调用 parseDataByEngine 改写系统回调。 */
object OfficialCloudReflection {
    const val SERVICE_INFO = "pantanal.decision.ServiceInfo"
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

    private val typeNames = object : ClassValue<Set<String>>() {
        override fun computeValue(type: Class<*>): Set<String> = buildSet {
            fun addType(current: Class<*>) {
                if (!add(current.name)) return
                current.superclass?.let(::addType)
                current.interfaces.forEach(::addType)
            }
            addType(type)
        }
    }

    private fun value(target: Any, field: Field): Any? = runCatching { field.isAccessible = true; field.get(target) }.getOrNull()

    /** 先使用明确的字段类型;系统合并对象后使用实际对象的公开类型,有歧义时不认领。 */
    fun fieldOfType(target: Any, name: String): Any? {
        val fields = fields(target.javaClass)
        fields.firstOrNull { it.type.name == name }?.let { value(target, it) }?.let { return it }
        val found = java.util.IdentityHashMap<Any, Unit>()
        for (field in fields) {
            if (field.type != Any::class.java && name !in typeNames.get(field.type)) continue
            val item = value(target, field) ?: continue
            if (name in typeNames.get(item.javaClass)) found[item] = Unit
        }
        return found.keys.singleOrNull()
    }

    /**
     * 系统流体云的一条记录:系统服务卡片来自新读法(OfficialCloudSeedlingReader.Entry,只读 OPPO 接口);
     * 通知变成的胶囊来自系统流体云插件的数据监听(SOURCE_COMMON)。插件自己的系统服务记录不再读。
     */
    fun isOfficial(model: Any): Boolean = if (model is OfficialCloudSeedlingReader.Entry) true else when (val type = call(model, "getSourceType")?.toString()) {
        "SOURCE_SEEDLING" -> false
        "SOURCE_COMMON" -> fieldOfType(model, "android.service.notification.StatusBarNotification") is android.service.notification.StatusBarNotification
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

    /** 到货诊断用的一句话:服务号与数据大小;通知转成的系统卡片写成 common:包名:通知编号。只读字段,不调用会改状态的方法。 */
    fun describe(model: Any): String {
        if (call(model, "getSourceType")?.toString() == "SOURCE_COMMON") {
            val sbn = fieldOfType(model, SBN) as? android.service.notification.StatusBarNotification
            return "common:${sbn?.packageName}:${sbn?.id}"
        }
        if (model !is OfficialCloudSeedlingReader.Entry) return "${model.javaClass.name} (not read)"
        return "${text(call(model.service, "getServiceId"))} " +
            "data=${(call(model.ui, "getData") as? ByteArray)?.size ?: 0}"
    }

    /** 通知变成的系统胶囊(不是系统服务推送的卡片)。 */
    fun isCommon(model: Any): Boolean = call(model, "getSourceType")?.toString() == "SOURCE_COMMON"

    /** 这条记录在哪个显示位置(系统流体云插件自己的 ENTRY_STATUS_BAR、ENTRY_NOTIFICATION、ENTRY_AOD);读不到时为 null。 */
    fun entranceName(model: Any): String? {
        fun entrance(field: Field) = (value(model, field) as? Enum<*>)?.name?.takeIf { it.startsWith("ENTRY_") }
        fields(model.javaClass).filter { it.type.isEnum }.firstNotNullOfOrNull(::entrance)?.let { return it }
        return fields(model.javaClass).filter { it.type == Any::class.java }.mapNotNull(::entrance).distinct().singleOrNull()
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
     * 内容没写这个位置时看内容的 shouldShow。显示交接不修改这些原始设置。
     */
    fun seedlingShows(service: Any?, ui: Any?, entry: Int?, lockScreen: Boolean): Boolean {
        val getter = if (lockScreen) "getLockScreenShowHostMap" else "getShowHostMap"
        val fromOptions = entry?.let { (call(call(service, "getNewSeedlingCardOptions"), getter) as? Map<*, *>)?.get(it) } as? Boolean
        if (ui == null) return fromOptions ?: ((call(service, "getServiceCategory") as? Number)?.toInt() == SERVICE_CATEGORY_DEFAULT_SHOWN)
        val fromData = entry?.let { (call(ui, getter) as? Map<*, *>)?.get(it) } as? Boolean
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

    private const val SBN = "android.service.notification.StatusBarNotification"

    /**
     * 一条系统记录的原始事实:系统服务卡片读服务信息与卡片内容(数据、图、应用给的界面数据),通知变成的胶囊读那条通知。
     * 不读系统卡片引擎排好的内容树。
     */
    fun snapshot(model: Any, revision: Long): CloudSnapshot? {
        if (!isOfficial(model)) return null
        if (model !is OfficialCloudSeedlingReader.Entry) return if (isCommon(model)) commonSnapshot(model, revision) else null
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
        val ids = ((call(ui, "getNotificationIdList") ?: call(options, "getNotificationIdList")) as? Collection<*>)
            .orEmpty().take(64).mapNotNull { (it as? Number)?.toInt() }.toSet()
        val entrance = model.entrance
        val entry = entranceCode(entrance)
        // 卡片数据:提供方送来的那一份;还没送时和卡片引擎一样用发起时带来的初始数据;
        // 系统界面自己做的提示(手电筒、勿扰、响铃模式)没有这两份,用系统界面生成提示时的那一份
        val raw = (call(ui, "getData") as? ByteArray)?.takeIf { it.isNotEmpty() } ?: initData(service) ?: OfficialCloudSystemTips.data(serviceId)
        val (instant, remindLevel) = instantFacts(service, ui, options)
        return CloudSnapshot(nativeKey, user, pkg, serviceId, instance, time, revision, entrance,
            images = images,
            notificationIds = ids,
            visible = seedlingShows(service, ui, entry, lockScreen = false),
            lockScreenAllowed = seedlingShows(service, ui, entry, lockScreen = true),
            remoteData = raw.takeIf { serviceId in OfficialRemotePage.SERVICES },
            sourceUpdatedAtMs = revision,
            ended = (call(ui, "getControlAction") as? Number)?.toInt() == 1,
            data = data(raw),
            hostPackage = text(call(service, "getHostPackage")),
            serviceName = ((call(service, "getExtras") as? Map<*, *>)?.get("serviceTitleName") as? String).orEmpty().take(64),
            templateVersion = (call(service, "getVersionCode") as? Number)?.toLong() ?: 0L,
            // 页面按卡片数据写明的 options.pageId(卡片引擎也按它选页)
            pageId = page(raw),
            instant = instant, remindLevel = remindLevel, shake = shouldShake(ui, options))
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

    /** 通知变成的系统胶囊:读那条通知(内容由 OfficialDirectPage 照系统流体云的解析读成内容树)。 */
    private fun commonSnapshot(model: Any, revision: Long): CloudSnapshot? {
        val sbn = fieldOfType(model, SBN) as? android.service.notification.StatusBarNotification ?: return null
        val user = (call(model, "getUserId") as? Number)?.toInt()?.takeIf { it >= 0 } ?: return null
        val entrance = entranceName(model) ?: "UNKNOWN"
        val entry = entranceCode(entrance)
        return CloudSnapshot(nativeKey(model) ?: return null, user, sbn.packageName,
            "common:${sbn.packageName}:${sbn.id}", sbn.key, 0, revision,
            entrance = entrance,
            notificationIds = setOf(sbn.id),
            visible = commonShows(sbn, entry, lockScreen = false),
            lockScreenAllowed = commonShows(sbn, entry, lockScreen = true),
            notification = sbn,
            sourceUpdatedAtMs = revision)
    }

    fun text(value: Any?): String = value?.toString()?.take(MAX_TEXT).orEmpty()
}
