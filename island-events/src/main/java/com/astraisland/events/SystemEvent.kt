package com.astraisland.events

import android.graphics.Bitmap
import android.graphics.drawable.Icon

/**
 * 一件系统事件(标准清单 contracts/island-system-events.v1.json 的 event 一节):接入件把手机系统的实时提示交给星河岛。
 * 显示内容全部在 [page](这一页的官方内容树,见 [PageNode]),星河岛照它摆;事件种类只用于分类开关与排位,
 * 其余几项是时机(自动展开、只亮一下、晃一下)与同一件事的认法。
 */
data class SystemEvent(
    /** 系统给这件事的编号,同一件事前后不变;来源应用变了(代发的系统服务换成真正的应用)也不变 */
    val identity: String,
    /** 这件事属于手机上的哪个用户 */
    val userId: Int,
    /** 这一版的先后顺序,后来的大 */
    val revision: Long,
    /** 事件种类:只用于分类开关与排位 */
    val kind: EventKind,
    /** 发这张卡片的应用(可能是代发的系统服务);认同一件事的通知时用 */
    val packageName: String,
    /** 真正的来源应用(例如美团),不是代它发提示的系统服务 */
    val sourcePackage: String,
    /** 系统服务的名字(例如「手电筒」);系统界面托管的服务不写「系统界面」;读不到时为空 */
    val serviceName: String = "",
    /** 系统允许在锁屏显示 */
    val lockScreen: Boolean = false,
    /** 这一版要不要自动展开卡片 */
    val alert: Alert = Alert.NONE,
    /** 只亮一下就消失的提示 */
    val instant: Instant = Instant.NO,
    /** 这一版要求胶囊晃一下 */
    val shake: Boolean = false,
    /** 点卡片能交回系统执行(官方内容树的整张卡片登记了点击动作) */
    val openable: Boolean = false,
    /** 系统把哪几条通知做成了这张卡片(通知编号),用来认出同一件事 */
    val relatedNotifications: Set<Int> = emptySet(),
    /** 通知附加信息里写着这个名字和值的通知也是同一件事(OPPO:op_fluid_serviceId 写服务号);没有时为空 */
    val notificationMark: NotificationMark? = null,
    /** 这一版内容的指纹:内容变了指纹就变(只亮一下的提示按它认新的一次) */
    val fingerprint: Int = 0,
    /** 通话(call):来电还是通话中(只用于排位) */
    val callState: CallState? = null,
    /** 计时器、闹钟:时间到了正在响铃(只用于排位与自动展开) */
    val ringing: Boolean = false,
    /** 音乐:真正在放歌的应用(交给星河岛自己的音乐时认同一首) */
    val mediaPackage: String? = null,
    /** 这一页的官方内容树(见 [PageNode]) */
    val page: PageNode,
    /** 航班、火车这类出行服务直接送来的胶囊界面(系统卡片引擎类型 REMOTE_VIEW,内容树根的 category 是 remote);别的卡片为空 */
    val capsuleViews: android.widget.RemoteViews? = null,
    /** 同上,展开大卡的界面 */
    val cardViews: android.widget.RemoteViews? = null,
)

/** 通知附加信息里标着同一件事的记号:名字 [extra] 下写着 [value] */
data class NotificationMark(val extra: String, val value: String)

/** 事件种类(清单 kinds 一节);[id] 是清单里的编号 */
enum class EventKind(val id: String) {
    CALL("call"), SATELLITE_CALL("satelliteCall"), TIMER("timer"), STOPWATCH("stopwatch"), ALARM("alarm"),
    FLASH_ALARM("flashAlarm"), GAME_TIMER("gameTimer"), GAME_COUNTDOWN("gameCountdown"),
    SCREEN_RECORDING("screenRecording"), SOUND_RECORDING("soundRecording"), RECORDING_SAVED("recordingSaved"),
    MICROPHONE_SERVICE("microphoneService"), SYSTEM_SWITCH("systemSwitch"), TASK_PROGRESS("taskProgress"),
    ORDER_PROGRESS("orderProgress"), PARCEL_PICKUP("parcelPickup"), RIDE("ride"), NAVIGATION("navigation"),
    TRAFFIC_LIGHT("trafficLight"), REMINDER("reminder"), APP_NOTICE("appNotice"), UNLISTED("unlisted"),
    MUSIC("music"), CHARGING("charging"), BATTERY_TIP("batteryTip"), DEVICE("device");

    companion object {
        fun of(id: String?): EventKind? = entries.firstOrNull { it.id == id }
    }
}

/** 这一版要不要自动展开卡片:不要、短时 3 秒、长时 5 秒、一直展开 */
enum class Alert(val id: String) {
    NONE("none"), SHORT("short"), LONG("long"), ALWAYS("always");

    companion object {
        fun of(id: String?): Alert? = entries.firstOrNull { it.id == id }
    }
}

/** 只亮一下就消失的提示:不是、短时 3 秒、其余 5 秒 */
enum class Instant(val id: String) {
    NO("no"), SHORT("short"), NORMAL("normal");

    companion object {
        fun of(id: String?): Instant? = entries.firstOrNull { it.id == id }
    }
}

enum class CallState(val id: String) {
    INCOMING("incoming"), DIALING("dialing"), ONGOING("ongoing");

    companion object {
        fun of(id: String?): CallState? = entries.firstOrNull { it.id == id }
    }
}

/**
 * 卡片上的一张图,已经读好(清单 card.image)。[symbol]:按系统符号画(单色图标),不是照片;[appShared]:放在来源应用自己
 * 共享位置的照片(例如菜品图);[substitute]:数据没给图时系统卡片自带的替代图。
 * 同一张图在一件事里是同一个对象(编码时只编一次),可以按对象比较。
 */
sealed interface EventImage {
    val symbol: Boolean
    val appShared: Boolean
    val substitute: Boolean

    /** 位图;[identity] 是像素或文件内容的指纹,同一张图同一个(没有时按对象认) */
    class Picture(val bitmap: Bitmap, val identity: String?, override val symbol: Boolean = false,
        override val appShared: Boolean = false, override val substitute: Boolean = false) : EventImage

    /** 系统图标对象(通知里的图标) */
    class Platform(val icon: Icon, override val symbol: Boolean = false,
        override val appShared: Boolean = false, override val substitute: Boolean = false) : EventImage

    /** 会动的图标(动画文件 [json],UTF-8);[playing] 为假时停在第一帧;[loop] 为假时播完一遍停在最后一帧(官方的完成对勾这类)。 */
    class Animation(val identity: String, val playing: Boolean, val json: ByteArray, override val symbol: Boolean = true,
        override val appShared: Boolean = false, override val substitute: Boolean = false, val loopStartFrame: Int? = null,
        val loop: Boolean = true) : EventImage

    /** 会动的图片(动图 WebP 的内容 [bytes]) */
    class Moving(val identity: String, val bytes: ByteArray, override val symbol: Boolean = false,
        override val appShared: Boolean = false, override val substitute: Boolean = false) : EventImage

    /** 那里放了图,但此刻没有可画的:共享位置里的照片还在后台读(读好后接入件再送一版),或者读不出 */
    class Missing(override val symbol: Boolean = false, override val appShared: Boolean = false,
        override val substitute: Boolean = false) : EventImage
}

/**
 * 计时。[countdown] 为真时 [baseMs] 是结束时刻,否则是起点;[elapsedClock] 为真时按开机时钟,否则按墙上时钟。
 * [running] 为假时停住,显示 [frozenMs]。
 */
data class EventTimer(val baseMs: Long, val countdown: Boolean, val elapsedClock: Boolean, val running: Boolean = true, val frozenMs: Long? = null) {
    companion object {
        /**
         * 页面元素里写的计时(官方写法,与系统卡片引擎一致):chronometer 写作「[起点, start 或 stop, 当前秒数]」,起点按开机时钟,
         * 起点晚于现在是倒计时,stop 表示停住、显示当前秒数;count-down-time、countDownTime、alarmTime 是结束时刻
         * (墙上时钟:毫秒、ISO 时间或「年-月-日 时:分:秒」)。没写计时为空。
         */
        fun of(attrs: Map<String, String>, nowElapsedMs: Long): EventTimer? {
            attrs["chronometer"]?.let { value ->
                val parts = value.removeSurrounding("[", "]").split(',').map(String::trim)
                val base = parts.firstOrNull()?.toLongOrNull()?.takeIf { it >= 0 } ?: return@let
                val countdown = base > nowElapsedMs
                if (parts.getOrNull(1) == "start") return EventTimer(base, countdown, true)
                val seconds = parts.getOrNull(2)?.toLongOrNull() ?: Math.round(kotlin.math.abs(base - nowElapsedMs) / 1000.0)
                return EventTimer(base, countdown, true, running = false, frozenMs = seconds * 1000L)
            }
            val raw = (attrs["count-down-time"] ?: attrs["countDownTime"] ?: attrs["alarmTime"])?.trim()?.takeIf { it.isNotBlank() } ?: return null
            val value = raw.removeSurrounding("[", "]").substringBefore(',').trim().trim('"', '\'')
            val epoch = value.toLongOrNull()?.takeIf { it >= 100_000_000_000L }
                ?: runCatching { java.time.Instant.parse(value).toEpochMilli() }.getOrNull()
                ?: runCatching {
                    java.time.LocalDateTime.parse(value, java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                        .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                }.getOrNull()
            return epoch?.let { EventTimer(it, true, false) }
        }
    }
}

/** 星河岛交回接入件的动作编号 */
object EventActions {
    /** 官方内容树里元素登记的动作(见 [PageNode.events]):星河岛交回「page:动作名」,接入件按这张卡片此刻的数据执行这个动作 */
    const val PAGE_PREFIX = "page:"
    fun page(action: String): String = PAGE_PREFIX + action
}
