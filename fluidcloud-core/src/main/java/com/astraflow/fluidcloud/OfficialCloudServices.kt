package com.astraflow.fluidcloud

import com.astraisland.events.Alert
import com.astraisland.events.EventKind
import com.astraisland.events.Instant

/**
 * 系统流体云服务的归类(现行规则「系统事件接入」)。服务号来自系统的流体云服务清单:同一个服务在所有机型上是同一个号。
 * 这里只读出事件种类(只用于分类开关与排位)、时机、来源与名字;卡片内容一律照官方内容树,不在这里整理。
 */
object OfficialCloudServices {
    const val CALL = "268451840"
    /** 天通卫星通话:连接卫星时的卡片照通话的样子(电话符号、通话的绿色) */
    const val SATELLITE_CALL = "268451909"
    const val FLASHLIGHT = "268451919"
    const val MEDIA = "268452006"
    const val TIMER = "268451854"
    const val STOPWATCH = "268451930"
    const val ALARM = "268451917"
    const val GARB_ALARM = "268451937"
    const val GAME_TIMER = "268451922"
    const val SCREEN_RECORDER = "268451849"
    const val SOUND_RECORDER = "268451844"
    const val CHARGE = "268451924"
    const val BATTERY = "268451902"
    const val SCREEN_PROTECTION = "268451928"
    const val DOWNLOAD = "268452010"
    const val FILE_DOWNLOAD = "268451935"
    const val DND = "268452000"
    const val THREE_KEY = "268451918"
    const val EYE_PROTECT = "268451929"
    const val HOTSPOT = "268451843"
    const val GESTURE = "268451927"
    /** 「我的设备」:耳机等设备连上后的卡片 */
    const val MY_DEVICES = "268451845"
    /** 录完后的「已保存」页(录屏的 completePage、录音的 savePage) */
    private val COMPLETION_PAGES = setOf("completePage", "savePage")

    /** 系统界面在别的机型上用来播放音乐的服务号(插件里与 268452006 同样当作音乐)。 */
    private val MEDIA_SERVICES = setOf(MEDIA, "268451910", "268451911", "268451900")
    /** 系统开关的提示:手电筒、勿扰、三段式按键、护眼、个人热点、手势。 */
    private val SWITCH_SERVICES = setOf(FLASHLIGHT, DND, THREE_KEY, EYE_PROTECT, HOTSPOT, GESTURE)
    /** 用麦克风的系统服务:AI 语音摘记、识别背景音乐,按现行规则归入麦克风。 */
    private val MICROPHONE_SERVICES = setOf("268451848", "268452024")
    /** 通话服务号:OPPO 电话、ColorOS 无障碍三方通话、一加电话。 */
    private val CALL_SERVICES = setOf(CALL, "268451940", "268451915")
    /** 设备快连:我的设备、耳机、配件。 */
    private val DEVICE_SERVICES = setOf(MY_DEVICES, "268451912", "268451913")
    /** 一加时钟的计时器与一加录音:和一加手机的 OPPO 对应服务同样排。 */
    private const val ONEPLUS_TIMER = "268451943"
    private const val ONEPLUS_SOUND_RECORDER = "268451941"
    private val RECORDER_SERVICES = setOf(SCREEN_RECORDER, SOUND_RECORDER, ONEPLUS_SOUND_RECORDER)

    private const val SYSTEM_UI = "com.android.systemui"

    /** 其它应用与系统场景的事件属于哪一组(只用来定事件种类):按服务号认,换了新服务号的按服务意图认 */
    enum class Layout {
        /** 外卖、快递提醒、车辆充电进度 */
        TAKEOUT,
        /** 打车 */
        RIDE,
        /** 应用下载、游戏更新、互传发送中 */
        TASK,
        /** 导航与驾车红绿灯提醒 */
        NAVIGATION,
        /** 游戏自己的复活倒计时 */
        COUNTDOWN,
        /** 各种提醒 */
        REMINDER,
        /** 应用自己的通知被系统做成的卡片(服务号以 common: 开头) */
        APP_NOTICE,
        /** 表里没有的服务 */
        UNLISTED,
    }

    /** 外卖:美团外卖、美团拼好饭外卖、京东外卖(两种)、淘宝闪购、淘宝外卖、饿了么;快递提醒、车辆充电进度先按外卖模板排 */
    private val TAKEOUT_SERVICES = setOf("536878029", "536879077", "536879078", "536878631", "536878700", "536878973", "536878642",
        "536877201", "536878265", "536879310", "268452868")
    /** 打车:高德打车、流体云地址上车 */
    private val RIDE_SERVICES = setOf("536877203", "536874945")
    /** 应用下载、游戏更新、互传发送中 */
    private val TASK_SERVICES = setOf(DOWNLOAD, FILE_DOWNLOAD, "268451923", "268451905", "536878801",
        "268451954", "268451955", "268451938", "268452007", "268452008", "268452009", "268452027", "268452050")
    /** 百度地图步行、骑行导航(卡片由场景服务代发):C17 手机上的服务号,服务意图是 NAVIGATION_PROMPT_V5 */
    const val BAIDU_NAVIGATION = "536877940"
    /** 导航:高德驾车、高德骑行步行、百度驾车、百度步行骑行、腾讯地图驾车;驾车红绿灯提醒(高德、百度、腾讯) */
    private val NAVIGATION_LAYOUT_SERVICES = setOf("536878696", "536879184", "536878591", "536879303", "536879377", "536879312", "536879383", BAIDU_NAVIGATION)
    /** 游戏自己的复活倒计时(王者荣耀) */
    private val COUNTDOWN_SERVICES = setOf("536878737")
    /** 支付保护、应用内容已屏蔽、取单码、课程、地铁与各种场景提醒 */
    // 智慧文本/日程、高铁与航班行程、地址识别、考勤打卡、赛事提醒、违章提醒、智慧备车、伴随助手、智能语音、纯净模式、
    // 刷卡提醒、健康、小布记忆、一键闪记、红茶国际上网:都按通用提醒卡片排(系统卡片本身是标题+说明+按钮)。
    private val REMINDER_SERVICES = setOf("268451916", SCREEN_PROTECTION, "268451931", "268452048", "268452047", "536879177",
        "536875808", "536878517", "536878530", "536878968", "536874238", "536874236", "536878942", "536874734", "536879129",
        "536879313", "536879143", "536879144", "536879305", "268452013", "268451904",
        "536879167", "536871053", "536871054", "536871055", "536871056", "536874766", "536878690", "536879314", "536879315",
        "536879178", "268452019", "268452023", "268452046", "268452005", "268451925", "268451926", "268451936", "268452001",
        "268452002", "268452012", "268452016", "268452017", "268452018", "268452022", "268452028", "268452037", "268452038",
        "268452039", "268452040", "268452042", "268452043", "268460003", "268460005", "268460006", "536875277")

    /** 这个事件属于哪一组;表里没有的服务号,服务意图和表里的服务一样时(同一个服务换了新服务号)照那个服务 */
    fun layout(snapshot: CloudSnapshot): Layout {
        val id = snapshot.serviceId
        return when {
            id.startsWith("common:") -> Layout.APP_NOTICE
            id in TAKEOUT_SERVICES -> Layout.TAKEOUT
            id in RIDE_SERVICES -> Layout.RIDE
            id in TASK_SERVICES -> Layout.TASK
            id in NAVIGATION_LAYOUT_SERVICES -> Layout.NAVIGATION
            id in COUNTDOWN_SERVICES -> Layout.COUNTDOWN
            id in REMINDER_SERVICES -> Layout.REMINDER
            else -> INTENT_LAYOUTS[snapshot.intentAction] ?: Layout.UNLISTED
        }
    }

    /**
     * 表里各服务的卡片说明文件写的服务意图 → 组(照手机上这些服务的 config.json 读出)。
     * 只认完全一样的意图,带新版本号的意图(例如快递取件的 FLUID_v3)不算同一个。
     */
    private val INTENT_LAYOUTS: Map<String, Layout> = buildMap {
        fun add(layout: Layout, vararg actions: String) = actions.forEach { put("pantanal.intent.$it", layout) }
        add(Layout.TAKEOUT, "takeout.VIEW_ORDER_PROGRESS_V3", "delivery.FLUID", "new_energy_charging.VEHICLE_CHARGING_STATUS",
            "business.app.export.TAKE_OUT")
        add(Layout.RIDE, "ridehailing.VIEW_ORDER_V3")
        add(Layout.TASK, "business.app.system.MCS_DOWNLOAD_CARD", "business.app.system.UPDATE_PROGRESS",
            "business.app.system.OSHARE_SENDER_PROCESS", "business.app.system.FILEMANAGER_FILE_DOWNLOAD",
            "business.app.system.AI_PAPER", "business.app.system.EXECUTE_ASSISTANT", "business.app.system.MATRIX",
            "business.app.system.MATRIX_IMMEDIATE", "business.app.system.AI_CAMERA_MOVEMENT", "business.app.system.AI_SCREEN_IMAGE",
            "business.app.system.OPLUS_GALLERY_PHOTO_EDITOR_AI_RETOUCH", "business.app.system.BIG_FILE_TRANSPORT",
            "game.UPDATE_PROGRESS")
        add(Layout.NAVIGATION, "prompt.DRIVING_NAVIGATION", "prompt.NAVIGATION_PROMPT", "prompt.NAVIGATION_PROMPT_V5",
            "prompt.NAVIGATION_PROMPT_V6", "business.app.system.NAVI_HANDOFF_CAR",
            "traffic_light.NAVIGATION_TRAFFIC_LIGHT",
            "traffic_light.BAIDU_TRAFFIC_LIGHTS", "traffic_light.TENGXUN_TRAFFIC_LIGHTS")
        add(Layout.COUNTDOWN, "game.GAME_COUNTDOWN")
        add(Layout.REMINDER, "business.app.system.PAY_SCAN", "business.app.system.SCREEN_PROTECTION",
            "business.app.system.SMART_MEAL_PICKUP_CODE", "business.app.system.CALENDAR_ALARM", "business.app.system.CALENDAR_NOTIFICATION",
            "publictransit.SMART_SUBWAY_REMINDER_V2", "caralert.EMERGENCY_ALERT", "internal_xiaobu_memory.MOVIE_PERFORMANCE_MEMORY",
            "internal_xiaobu_memory.MOVIE_PERFORMANCE_MEMORY_V2", "internal_xiaobu_memory.HOTEL_SERVICES_MEMORY",
            "internal_xiaobu_memory.SCENIC_SPOT_TICKET_MEMORY", "internal_xiaobu_memory.SCENIC_SPOT_TICKET_MEMORY_V2",
            "business.app.system.AIMEMORY_WRITE_CLOCK", "violation_reminder.VIOLATION_REMINDER_SERVICE", "system.FLUID_WLAN_ON",
            "system.FLUID_FLASH_OFF", "contentconsult.TEXT_INTENT_IDENTIFY_V2",
            "weather.EXTREME_WEATHER_WARN", "contentconsult.TEXT_INTENT_IDENTIFY", "weather.WEATHER_RAIN_FORECAST",
            "event_services.EVENT_FLUENCY_CLOUD", "video.FLUID_ADDRESS_CAR", "video.FLUID_ADDRESS_RECOGNITION",
            "attendance_reminder.ATTENDANCE_CLOCK", "business.app.system.MCS_DOWNLOAD_SWITCH", "business.app.system.OSHARE_USER_CONFIRM")
    }

    /**
     * 卡片真正的来源应用:外卖这类由系统服务代发的卡片写着原来的应用(dataSourcePkgName);
     * 百度步行骑行导航(536877940)在手机上的卡片数据把来源应用写在 targetPackageName;都没写时就是卡片的包名。
     */
    fun sourcePackage(snapshot: CloudSnapshot): String {
        val keys = if (snapshot.serviceId == BAIDU_NAVIGATION) listOf("dataSourcePkgName", "targetPackageName")
            else listOf("dataSourcePkgName")
        return keys.firstNotNullOfOrNull { key ->
            snapshot.data[key]?.trim()?.takeIf { it.matches(PACKAGE) && it != SYSTEM_UI }
        } ?: snapshot.packageName
    }

    private val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")

    /** 事件种类(对照表 kinds):服务号认,换了新服务号的按服务意图认 */
    fun kind(snapshot: CloudSnapshot): EventKind {
        val id = snapshot.serviceId
        return when {
            id in CALL_SERVICES -> EventKind.CALL
            id == TIMER || id == ONEPLUS_TIMER -> EventKind.TIMER
            id == STOPWATCH -> EventKind.STOPWATCH
            id == ALARM -> EventKind.ALARM
            id == GARB_ALARM -> EventKind.FLASH_ALARM
            id == GAME_TIMER -> EventKind.GAME_TIMER
            completionPage(snapshot) -> EventKind.RECORDING_SAVED
            id == SCREEN_RECORDER -> EventKind.SCREEN_RECORDING
            id == SOUND_RECORDER || id == ONEPLUS_SOUND_RECORDER -> EventKind.SOUND_RECORDING
            id in MICROPHONE_SERVICES -> EventKind.MICROPHONE_SERVICE
            id == CHARGE -> EventKind.CHARGING
            id == BATTERY -> EventKind.BATTERY_TIP
            id in DEVICE_SERVICES -> EventKind.DEVICE
            id in MEDIA_SERVICES || systemMediaNotification(snapshot) -> EventKind.MUSIC
            id in SWITCH_SERVICES -> EventKind.SYSTEM_SWITCH
            id == SATELLITE_CALL -> EventKind.SATELLITE_CALL
            else -> when (layout(snapshot)) {
                Layout.TAKEOUT -> EventKind.ORDER_PROGRESS
                Layout.RIDE -> EventKind.RIDE
                Layout.TASK -> EventKind.TASK_PROGRESS
                Layout.NAVIGATION -> if (trafficLight(snapshot)) EventKind.TRAFFIC_LIGHT else EventKind.NAVIGATION
                Layout.COUNTDOWN -> EventKind.GAME_COUNTDOWN
                Layout.REMINDER -> EventKind.REMINDER
                Layout.APP_NOTICE -> EventKind.APP_NOTICE
                Layout.UNLISTED -> if (parcelPickup(snapshot)) EventKind.PARCEL_PICKUP else EventKind.UNLISTED
            }
        }
    }

    /** 系统界面替播放器发的合成音乐通知(旧格式卡片,通知编号就是音乐服务号)。 */
    private fun systemMediaNotification(snapshot: CloudSnapshot): Boolean =
        snapshot.serviceId.startsWith("common:") && snapshot.packageName == SYSTEM_UI && snapshot.notificationIds.any { it.toString() in MEDIA_SERVICES }

    /**
     * 只亮一下的提示(系统的即时提醒,例如勿扰、响铃模式、手势提示):短时强提醒是短时(3 秒),其余是一般(5 秒)
     * (系统流体云插件给这类提示定的时长,现行规则「系统事件接入」);不是只亮一下的为「不是」。
     */
    fun instant(snapshot: CloudSnapshot): Instant = when {
        !snapshot.instant -> Instant.NO
        snapshot.remindLevel == REMIND_STRONG_SHORT -> Instant.SHORT
        else -> Instant.NORMAL
    }

    /**
     * 这一版系统标了强提醒时卡片要不要自动展开(官方:短时 3 秒、长时 5 秒、一直展开,例如外卖送达、车辆已到达;
     * 现行规则「系统事件接入」);不是强提醒、只亮一下的提示(系统里只有胶囊)为「不要」。
     */
    fun alert(snapshot: CloudSnapshot): Alert = when {
        snapshot.instant -> Alert.NONE
        snapshot.remindLevel == REMIND_STRONG_SHORT -> Alert.SHORT
        snapshot.remindLevel == REMIND_STRONG_LONG -> Alert.LONG
        snapshot.remindLevel == REMIND_STRONG_ALWAYS -> Alert.ALWAYS
        else -> Alert.NONE
    }

    /** 系统的提醒级别:短时强提醒(卡片展开 3 秒)、长时强提醒(5 秒)、一直展开 */
    private const val REMIND_STRONG_SHORT = 11
    private const val REMIND_STRONG_LONG = 12
    private const val REMIND_STRONG_ALWAYS = 13

    /** 录屏、录音录完后的「已保存」页:用系统那份,按通用卡片显示(现行规则「系统事件接入」)。 */
    fun completionPage(snapshot: CloudSnapshot): Boolean =
        snapshot.serviceId in RECORDER_SERVICES && snapshot.pageId.substringAfterLast('/').removeSuffix(".json") in COMPLETION_PAGES

    /** 系统录屏、录音:归哪一类要看卡片此刻是哪一页(录制中还是「已保存」)。 */
    fun recorder(serviceId: String): Boolean = serviceId in RECORDER_SERVICES

    /** 快递提醒服务的「快递取件」(卡片按表里的排法,胶囊和快递提醒一样) */
    private val EXPRESS_PICKUP_SERVICES = setOf("536878933", "268452041")

    fun parcelPickup(snapshot: CloudSnapshot): Boolean = snapshot.serviceId in EXPRESS_PICKUP_SERVICES

    /** 红绿灯提醒(高德、百度、腾讯):两边的秒数按灯的颜色,不套「右边品牌色」;换了新服务号的按服务意图认 */
    val TRAFFIC_LIGHT_SERVICES = setOf("536879377", "536879312", "536879383")
    private val TRAFFIC_LIGHT_INTENTS = setOf("pantanal.intent.traffic_light.NAVIGATION_TRAFFIC_LIGHT", "pantanal.intent.traffic_light.BAIDU_TRAFFIC_LIGHTS",
        "pantanal.intent.traffic_light.TENGXUN_TRAFFIC_LIGHTS")

    fun trafficLight(snapshot: CloudSnapshot): Boolean = snapshot.serviceId in TRAFFIC_LIGHT_SERVICES || snapshot.intentAction in TRAFFIC_LIGHT_INTENTS

    /** 用麦克风的系统服务(AI 语音摘记、识别背景音乐):照麦克风被占用的样子显示(现行规则「展开卡片总表」)。 */
    fun microphone(snapshot: CloudSnapshot): Boolean = snapshot.serviceId in MICROPHONE_SERVICES

    /** 系统音乐卡片背后真正在放的应用(卡片数据或合成通知里写的来源包名);读不到时为 null。 */
    fun mediaPackage(snapshot: CloudSnapshot): String? {
        listOf("dataSourcePkgName", "package", "pkg").forEach { key -> snapshot.data[key]?.takeIf { it.isNotBlank() && it != SYSTEM_UI }?.let { return it } }
        val options = snapshot.notification?.notification?.extras?.getString("oplusLiveAlertOptions") ?: return null
        return runCatching { org.json.JSONObject(options).optString("dataSourcePkgName") }.getOrNull()?.takeIf { it.isNotBlank() && it != SYSTEM_UI }
    }

    /**
     * 系统服务的名字(标准事件的 serviceName):系统登记的服务名;系统界面托管的服务读数据里的名字,再没有时照开关名;
     * 都没有时为空(由星河岛写发卡片的应用名)。
     */
    fun serviceName(snapshot: CloudSnapshot): String {
        validName(snapshot)?.let { return it }
        if (snapshot.packageName == SYSTEM_UI || snapshot.hostPackage == SYSTEM_UI) {
            snapshot.data["name"]?.takeIf { it.isNotBlank() }?.let { return it }
            SYSTEM_NAMES[snapshot.serviceId]?.let { return it }
        }
        return ""
    }

    /** 系统登记的服务名;没登记、还是没翻译的代号(以「{」开头)时为空 */
    private fun validName(snapshot: CloudSnapshot): String? = snapshot.serviceName.takeIf { it.isNotBlank() && !it.startsWith("{") }

    private val SYSTEM_NAMES = mapOf(
        FLASHLIGHT to "手电筒", DND to "勿扰", THREE_KEY to "三段式按键", MEDIA to "音乐",
        HOTSPOT to "个人热点", EYE_PROTECT to "护眼",
    )

    fun ringing(snapshot: CloudSnapshot): Boolean = when (snapshot.serviceId) {
        ALARM -> snapshot.data["alarm_alarm_ring"] == "true"
        TIMER -> snapshot.data["timer_ring"] == "true"
        else -> false
    }
}
