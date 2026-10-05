package com.astraflow.fluidcloud

import com.astraisland.events.Alert
import com.astraisland.events.Details
import com.astraisland.events.EventKind
import com.astraisland.events.Instant
import com.astraisland.events.SwitchKind

/**
 * 系统自带服务的归类与内容整理(现行规则「系统事件接入」)。服务号来自系统的流体云服务清单:
 * 同一个服务在所有机型上是同一个号。这里只读出归类、时间、按钮用途和名字,不写标题、不画卡片、不排位
 * (标题、说明怎么写由星河岛按标准事件决定)。
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
    private val MEDIA_SERVICES = setOf(MEDIA, "268451910", "268451911")
    /** 系统开关的提示:手电筒、勿扰、三段式按键、护眼、个人热点、手势。 */
    private val SWITCH_SERVICES = setOf(FLASHLIGHT, DND, THREE_KEY, EYE_PROTECT, HOTSPOT, GESTURE)
    /** 用麦克风的系统服务:AI 语音摘记、识别背景音乐,按现行规则归入麦克风。 */
    private val MICROPHONE_SERVICES = setOf("268451848", "268452024")
    private val TIMER_SERVICES = setOf(TIMER, STOPWATCH, ALARM, GARB_ALARM, GAME_TIMER)
    private val RECORDER_SERVICES = setOf(SCREEN_RECORDER, SOUND_RECORDER)

    private const val CLOCK_PROVIDER = "com.oplus.alarmclock.provider.communicate"
    /** 秒表计次按钮在卡片说明文件里的位置代号 */
    private const val LAP_BUTTON = "E1"
    private const val SYSTEM_UI = "com.android.systemui"

    /**
     * 「展开卡片总表」里其它应用与系统场景的事件怎么排(现行规则「展开卡片总表」)。按服务号认,不按内容猜;
     * 计时、系统开关、录制、通话、音乐、充电这些系统自带的仍按 [kind] 与各自的整理。
     */
    enum class Layout {
        /** 外卖模板:进度卡片,左边菜品图,标题写骑手或商家在做什么,正文写预计送达(快递、车辆充电先按它排) */
        TAKEOUT,
        /** 打车:进度卡片,标题写司机在做什么,说明写车型和车牌 */
        RIDE,
        /** 应用下载、游戏更新、互传发送中:进度卡片,只在文字里写了百分比时也按它画进度条 */
        TASK,
        /** 导航与驾车红绿灯提醒:通用卡片,开车、骑行、步行一个样子,红绿灯秒数放大写在标题右边 */
        NAVIGATION,
        /** 游戏自己的复活倒计时:计时卡片 */
        COUNTDOWN,
        /** 各种提醒:通用卡片 */
        REMINDER,
        /** 应用自己的通知被系统做成的卡片(旧格式,服务号以 common: 开头):同应用自己发的实时活动,有进度用进度卡片,其它用通用卡片 */
        APP_NOTICE,
        /** 表里没有:按官方卡片的结构排(底部是进度条的用进度卡片,其余用通用卡片),在日志里记下服务号等用户看 */
        UNLISTED,
    }

    /** 外卖:美团外卖、美团拼好饭外卖、京东外卖(两种)、淘宝闪购、淘宝外卖、饿了么;快递提醒、车辆充电进度先按外卖模板排 */
    private val TAKEOUT_SERVICES = setOf("536878029", "536879077", "536879078", "536878631", "536878700", "536878973", "536878642",
        "536877201", "536878265", "536879310")
    /** 打车:高德打车 */
    private val RIDE_SERVICES = setOf("536877203")
    /** 应用下载、游戏更新、互传发送中 */
    private val TASK_SERVICES = setOf(DOWNLOAD, "268451923", "268451905")
    /** 导航:高德驾车、高德骑行步行、百度驾车、腾讯地图驾车;驾车红绿灯提醒(高德、百度、腾讯) */
    private val NAVIGATION_LAYOUT_SERVICES = setOf("536878696", "536879184", "536878591", "536879303", "536879377", "536879312", "536879383")
    /** 游戏自己的复活倒计时(王者荣耀) */
    private val COUNTDOWN_SERVICES = setOf("536878737")
    /** 支付保护、应用内容已屏蔽、取单码、课程、地铁与各种场景提醒 */
    private val REMINDER_SERVICES = setOf("268451916", SCREEN_PROTECTION, "268451931", "268452048", "268452047", "536879177",
        "536875808", "536878517", "536878530", "536878968", "536874238", "536874236", "536878942", "536874734", "536879129",
        "536879313", "536879143", "536879144", "536879305", "268452013", "268451904")

    /**
     * 这个事件在「展开卡片总表」里的排法;系统自带的计时、开关、录制、通话等不走这里(见 [kind])。
     * 表里没有的服务号,卡片说明文件写的服务意图和表里的服务一样时(同一个服务换了新服务号)照那个服务的排法。
     */
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
     * 表里各服务的卡片说明文件写的服务意图 → 排法(照手机上这些服务的 config.json 读出):新服务号的意图和其中一个一样时照它排
     * (新服务号照老号画法)。只认完全一样的意图,带新版本号的意图(例如快递取件的 FLUID_v3)不算同一个。
     */
    private val INTENT_LAYOUTS: Map<String, Layout> = buildMap {
        fun add(layout: Layout, vararg actions: String) = actions.forEach { put("pantanal.intent.$it", layout) }
        add(Layout.TAKEOUT, "takeout.VIEW_ORDER_PROGRESS_V3", "delivery.FLUID", "new_energy_charging.VEHICLE_CHARGING_STATUS")
        add(Layout.RIDE, "ridehailing.VIEW_ORDER_V3")
        add(Layout.TASK, "business.app.system.MCS_DOWNLOAD_CARD", "business.app.system.UPDATE_PROGRESS",
            "business.app.system.OSHARE_SENDER_PROCESS")
        add(Layout.NAVIGATION, "prompt.DRIVING_NAVIGATION", "prompt.NAVIGATION_PROMPT_V6", "traffic_light.NAVIGATION_TRAFFIC_LIGHT",
            "traffic_light.BAIDU_TRAFFIC_LIGHTS", "traffic_light.TENGXUN_TRAFFIC_LIGHTS")
        add(Layout.COUNTDOWN, "game.GAME_COUNTDOWN")
        add(Layout.REMINDER, "business.app.system.PAY_SCAN", "business.app.system.SCREEN_PROTECTION",
            "business.app.system.SMART_MEAL_PICKUP_CODE", "business.app.system.CALENDAR_ALARM", "business.app.system.CALENDAR_NOTIFICATION",
            "publictransit.SMART_SUBWAY_REMINDER_V2", "caralert.EMERGENCY_ALERT", "internal_xiaobu_memory.MOVIE_PERFORMANCE_MEMORY",
            "internal_xiaobu_memory.SCENIC_SPOT_TICKET_MEMORY", "violation_reminder.VIOLATION_REMINDER_SERVICE", "system.FLUID_WLAN_ON",
            "weather.EXTREME_WEATHER_WARN", "contentconsult.TEXT_INTENT_IDENTIFY", "weather.WEATHER_RAIN_FORECAST",
            "event_services.EVENT_FLUENCY_CLOUD", "video.FLUID_ADDRESS_CAR", "video.FLUID_ADDRESS_RECOGNITION",
            "attendance_reminder.ATTENDANCE_CLOCK", "business.app.system.MCS_DOWNLOAD_SWITCH", "business.app.system.OSHARE_USER_CONFIRM")
    }

    /**
     * 这个事件的卡片要不要把挤在小字里的几项拆成并排的明细、怎么拆(「改好以后的样子」页,现行规则「展开卡片总表」):
     * 电影、景点门票按服务号或它们专有的服务意图认(换了新服务号照老号),课程只认服务号(同一个意图也发普通日程)。
     */
    fun detailsRecipe(snapshot: CloudSnapshot): Details? =
        DETAILS_RECIPES[snapshot.serviceId] ?: DETAILS_INTENTS[snapshot.intentAction]

    private val DETAILS_RECIPES = mapOf(
        "536878517" to Details.MOVIE, "536879199" to Details.MOVIE,
        "536878530" to Details.SCENIC, "536879212" to Details.SCENIC,
        "268452047" to Details.COURSE)
    private val DETAILS_INTENTS = mapOf(
        "pantanal.intent.internal_xiaobu_memory.MOVIE_PERFORMANCE_MEMORY" to Details.MOVIE,
        "pantanal.intent.internal_xiaobu_memory.MOVIE_PERFORMANCE_MEMORY_V2" to Details.MOVIE,
        "pantanal.intent.internal_xiaobu_memory.SCENIC_SPOT_TICKET_MEMORY" to Details.SCENIC,
        "pantanal.intent.internal_xiaobu_memory.SCENIC_SPOT_TICKET_MEMORY_V2" to Details.SCENIC)

    /**
     * 系统卡片上只有图标、没有字的按钮叫什么:高德骑行步行导航的全览按钮(开着全览时是退出全览)。
     * 没有名字的按钮不显示(现行规则「系统事件接入」),这里只补系统按钮本来的意思。
     */
    fun iconButtonLabel(action: CloudAction?, data: Map<String, String>): String? = when (action?.method) {
        "gaodeBwnaviOverview" -> if (data["showOverViewClose"]?.trim() == "true") "退出全览" else "全览"
        // 系统录屏卡片的两个开关只画图标,朗读文字(「录制系统声音按钮 已选中」)只给读屏;星河岛上写它们是什么
        "audioSysClick" -> "系统声音"
        "audioMicClick" -> "麦克风"
        else -> null
    }

    /** 卡片真正的来源应用:外卖这类由系统服务代发的卡片写着原来的应用(dataSourcePkgName);没写时就是卡片的包名。 */
    fun sourcePackage(snapshot: CloudSnapshot): String =
        snapshot.data["dataSourcePkgName"]?.trim()?.takeIf { it.matches(PACKAGE) && it != SYSTEM_UI } ?: snapshot.packageName

    private val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")

    /** 事件种类(对照表 kinds):服务号认,换了新服务号的按服务意图认 */
    fun kind(snapshot: CloudSnapshot): EventKind {
        val id = snapshot.serviceId
        return when {
            id == CALL -> EventKind.CALL
            id == TIMER -> EventKind.TIMER
            id == STOPWATCH -> EventKind.STOPWATCH
            id == ALARM -> EventKind.ALARM
            id == GARB_ALARM -> EventKind.FLASH_ALARM
            id == GAME_TIMER -> EventKind.GAME_TIMER
            completionPage(snapshot) -> EventKind.RECORDING_SAVED
            id == SCREEN_RECORDER -> EventKind.SCREEN_RECORDING
            id == SOUND_RECORDER -> EventKind.SOUND_RECORDING
            id in MICROPHONE_SERVICES -> EventKind.MICROPHONE_SERVICE
            id == CHARGE -> EventKind.CHARGING
            id == BATTERY -> EventKind.BATTERY_TIP
            id == MY_DEVICES -> EventKind.DEVICE
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
     * 系统的充电胶囊与电池提示(电量不足、省电建议、省电已开启、电池已充满):一律藏起、不上岛,岛没显示时也藏;
     * 岛自己的充电按岛原来的规矩,不做低电量提醒(现行规则「系统事件接入」)。
     */
    fun alwaysHidden(snapshot: CloudSnapshot): Boolean = snapshot.serviceId == CHARGE || snapshot.serviceId == BATTERY

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
    private val EXPRESS_PICKUP_SERVICES = setOf("536878933")

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

    /** 卡片说明里写的来源名:系统登记的服务名,系统界面托管的服务不写「系统界面」;都没有时写发卡片的应用名。 */
    fun sourceName(snapshot: CloudSnapshot, appLabel: () -> String): String = serviceName(snapshot).ifBlank { appLabel() }

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

    /** 系统开关的名字(手电筒照数据里写的名字);认不出时为空 */
    fun switchName(snapshot: CloudSnapshot): String? = when (snapshot.serviceId) {
        FLASHLIGHT -> snapshot.data["name"]?.takeIf { it.isNotBlank() } ?: SYSTEM_NAMES.getValue(FLASHLIGHT)
        in SWITCH_SERVICES -> SYSTEM_NAMES[snapshot.serviceId] ?: validName(snapshot)
        else -> null
    }

    /** 系统开关的状态那句(手电筒的数据里写着);没有时为空 */
    fun switchState(snapshot: CloudSnapshot): String? =
        if (snapshot.serviceId == FLASHLIGHT) snapshot.data["lightDes"]?.takeIf { it.isNotBlank() } else null

    /** 哪个系统开关;不是系统开关时为空 */
    fun switchKind(snapshot: CloudSnapshot): SwitchKind? = SWITCH_KINDS[snapshot.serviceId]

    private val SWITCH_KINDS = mapOf(FLASHLIGHT to SwitchKind.FLASHLIGHT, DND to SwitchKind.DO_NOT_DISTURB, THREE_KEY to SwitchKind.THREE_KEY,
        EYE_PROTECT to SwitchKind.EYE_PROTECT, HOTSPOT to SwitchKind.HOTSPOT, GESTURE to SwitchKind.GESTURE)

    private val SYSTEM_NAMES = mapOf(
        FLASHLIGHT to "手电筒", DND to "勿扰", THREE_KEY to "三段式按键", MEDIA to "音乐",
        HOTSPOT to "个人热点", EYE_PROTECT to "护眼",
    )

    /**
     * 按服务整理卡片内容:时间、按钮用途和系统卡片没登记的按钮动作。通用读法已经读出的内容作为起点,这里只补系统服务特有的部分;
     * 其它服务原样返回。[nowElapsedMs] 用来把只有文字的时间换成会走的计时。标题、说明怎么写由星河岛按标准事件决定。
     */
    fun refine(snapshot: CloudSnapshot, content: CloudContent, nowElapsedMs: Long): CloudContent = when (snapshot.serviceId) {
        STOPWATCH -> stopwatch(snapshot, content, nowElapsedMs)
        TIMER -> timer(snapshot, content, nowElapsedMs)
        ALARM -> alarm(snapshot, content, nowElapsedMs)
        GARB_ALARM -> garbAlarm(content)
        SCREEN_RECORDER -> if (completionPage(snapshot)) content else screenRecorder(snapshot, content, nowElapsedMs)
        SOUND_RECORDER -> if (completionPage(snapshot)) content else soundRecorder(snapshot, content, nowElapsedMs)
        else -> content
    }

    /**
     * 秒表:系统每秒送一次「分:秒」文字,没有起点;岛按收到时的时长往后走。暂停时停住。
     * 按钮:走着时是暂停与计次,停着时是继续与复位(系统卡片走着时的两个按钮没有登记动作,按时钟应用处理同样两个请求的办法补上)。
     */
    private fun stopwatch(snapshot: CloudSnapshot, content: CloudContent, now: Long): CloudContent {
        val running = stopwatchRunning(snapshot, content)
        val elapsed = duration(snapshot.data["stopwatch_time"]) ?: return content
        val received = snapshot.receivedAtElapsedMs.takeIf { it > 0 } ?: now
        val timer = if (running) CloudTimer(received - elapsed, countdown = false, elapsedClock = true)
            else CloudTimer(received - elapsed, countdown = false, elapsedClock = true, running = false, frozenMs = elapsed)
        val control = CloudAction(CloudTemplate.TYPE_MESSAGE, CLOCK_PROVIDER, "controlOperate")
        val function = CloudAction(CloudTemplate.TYPE_MESSAGE, CLOCK_PROVIDER, "functionOperate")
        // 计次照官方是只画图标的按钮:用官方的计次图标画成灰底圆钮(「改好以后的样子」页)
        val lapIcon = content.buttonIcons[LAP_BUTTON]
        val buttons = if (running) listOf(
            CloudButton("stopwatch:control", "暂停", action = control, role = CloudRole.PAUSE),
            CloudButton("stopwatch:function", "计次", action = function, icon = lapIcon, round = lapIcon != null),
        ) else listOf(
            CloudButton("stopwatch:control", "继续", action = control, role = CloudRole.RESUME),
            CloudButton("stopwatch:function", "复位", action = function, role = CloudRole.STOP),
        )
        return content.copy(timer = timer, buttons = buttons)
    }

    /** 秒表在走(假为暂停):数据写着时照它,没写时看卡片里的计时 */
    fun stopwatchRunning(snapshot: CloudSnapshot, content: CloudContent): Boolean =
        snapshot.data["stopwatch_running"]?.let { it == "true" } ?: (content.timer?.running == true)

    /** 计时器:系统送的是剩余时间文字;按收到时的剩余时间倒数,暂停时停住。按钮按模板,用途按当前操作认。 */
    private fun timer(snapshot: CloudSnapshot, content: CloudContent, now: Long): CloudContent {
        val method = snapshot.data["timer_operate_method"].orEmpty()
        val ringing = snapshot.data["timer_ring"] == "true"
        val remaining = duration(snapshot.data["timer_time"])
        val received = snapshot.receivedAtElapsedMs.takeIf { it > 0 } ?: now
        val timer = when {
            // 时间到了:停在 00:00,放大写(「改好以后的样子」页)
            ringing -> CloudTimer(received, countdown = true, elapsedClock = true, running = false, frozenMs = 0L)
            remaining == null -> null
            method == "pauseTimer" -> CloudTimer(received + remaining, countdown = true, elapsedClock = true)
            else -> CloudTimer(received + remaining, countdown = true, elapsedClock = true, running = false, frozenMs = remaining)
        }
        val buttons = content.buttons.map { button ->
            when (button.action?.method) {
                "operateTimer" -> button.copy(role = if (method == "pauseTimer") CloudRole.PAUSE else CloudRole.RESUME)
                "cancelTimer" -> button.copy(role = CloudRole.STOP)
                else -> button
            }
        }
        return content.copy(timer = timer ?: content.timer, buttons = buttons)
    }

    /** 闹钟:响铃时是到点提醒(稍后提醒、关闭);稍后提醒期间按剩余时间倒数。 */
    private fun alarm(snapshot: CloudSnapshot, content: CloudContent, now: Long): CloudContent {
        val ringing = snapshot.data["alarm_alarm_ring"] == "true"
        val received = snapshot.receivedAtElapsedMs.takeIf { it > 0 } ?: now
        val timer = if (ringing) null else duration(snapshot.data["alarm_time"])?.let { CloudTimer(received + it, countdown = true, elapsedClock = true) }
        val buttons = content.buttons.map { if (it.action?.method == "cancelAlarm") it.copy(role = CloudRole.STOP) else it }
        return content.copy(timer = timer ?: content.timer.takeIf { !ringing }, buttons = buttons)
    }

    /** 秒抢闹钟:只有关闭(closeGarbAlarm)是停止,别的按钮各按各的动作,不并成一颗(「改好以后的样子」页)。 */
    private fun garbAlarm(content: CloudContent): CloudContent =
        content.copy(buttons = content.buttons.map { if (it.action?.method == "closeGarbAlarm") it.copy(role = CloudRole.STOP) else it })

    fun ringing(snapshot: CloudSnapshot): Boolean = when (snapshot.serviceId) {
        ALARM -> snapshot.data["alarm_alarm_ring"] == "true"
        TIMER -> snapshot.data["timer_ring"] == "true"
        else -> false
    }

    /**
     * 录屏:时间按卡片数据里的计时(起点按开机时钟,停住时是暂停);暂停或继续、完成按当前状态认用途。
     * 系统声音、麦克风两个开关照样留着:录制中的系统卡片不单独上岛,这两个开关补进星河岛自己的录屏卡片(现行规则「系统事件接入」归类)。
     */
    private fun screenRecorder(snapshot: CloudSnapshot, content: CloudContent, now: Long): CloudContent {
        val received = snapshot.receivedAtElapsedMs.takeIf { it > 0 } ?: now
        val timer = snapshot.data["chronometerValue"]?.let { OfficialCloudDecoder.parseTimer(mapOf("chronometer" to it), received) } ?: content.timer
        val paused = timer?.running == false
        val buttons = content.buttons.map { button ->
            when (button.action?.method) {
                "pauseOrResumeClick" -> button.copy(label = if (paused) "继续" else "暂停", role = if (paused) CloudRole.RESUME else CloudRole.PAUSE)
                "finishClick" -> button.copy(role = CloudRole.STOP)
                else -> button
            }
        }
        return content.copy(timer = timer, buttons = buttons)
    }

    /** 录音:时间是分开的文字(时、分、秒),录着时往后走;按钮是暂停或继续、保存(红色停止)与标记。 */
    private fun soundRecorder(snapshot: CloudSnapshot, content: CloudContent, now: Long): CloudContent {
        val running = snapshot.data["recordInProgress"]?.let { it == "true" }
        val parts = listOf("dayText", "hourText", "minuteText", "secondText").map { snapshot.data[it]?.filter(Char::isDigit)?.toLongOrNull() }
        val elapsed = if (parts.all { it == null }) null else parts.map { it ?: 0L }.let { (d, h, m, s) -> ((d * 24 + h) * 60 + m) * 60_000L + s * 1000L }
        val received = snapshot.receivedAtElapsedMs.takeIf { it > 0 } ?: now
        val timer = elapsed?.let { if (running == false) CloudTimer(received - it, false, true, running = false, frozenMs = it) else CloudTimer(received - it, false, true) }
        val buttons = content.buttons.map { button ->
            when (button.action?.method) {
                "pauseOrResume", "startOrPause" -> button.copy(role = if (running == false) CloudRole.RESUME else CloudRole.PAUSE)
                "saveAudio" -> button.copy(role = CloudRole.STOP)
                else -> button
            }
        }
        return content.copy(timer = timer ?: content.timer, buttons = buttons)
    }

    /**
     * 「分:秒」「时:分:秒」「天:时:分:秒」文字换成毫秒(系统按本地数字写,先换成阿拉伯数字);读不出时返回 null。
     */
    fun duration(text: String?): Long? {
        if (text.isNullOrBlank()) return null
        val digits = text.map { c -> if (Character.isDigit(c)) Character.forDigit(Character.getNumericValue(c), 10) else c }.joinToString("")
        val parts = digits.split(Regex("[^0-9]+")).filter { it.isNotEmpty() }.map { it.toLongOrNull() ?: return null }
        if (parts.size !in 2..4) return null
        var seconds = 0L
        val units = when (parts.size) { 2 -> listOf(60L, 1L); 3 -> listOf(3600L, 60L, 1L); else -> listOf(86_400L, 3600L, 60L, 1L) }
        parts.forEachIndexed { i, v -> seconds += v * units[i] }
        return seconds * 1000L
    }
}
