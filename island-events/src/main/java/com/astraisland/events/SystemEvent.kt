package com.astraisland.events

import android.graphics.Bitmap
import android.graphics.drawable.Icon

/**
 * 一件系统事件(标准清单 contracts/island-system-events.v1.json 的 event 一节):各家手机系统的实时提示由接入件翻译成这里的说法,
 * 摆放只由星河岛按它决定。接入件只填系统真给了的值,清单里没有的不填;文字原样给,不改写、不拼接。
 *
 * 各种类自己的内容项(清单 kinds 一节)放在同一层,只有对应种类才填,见各字段的说明。
 */
data class SystemEvent(
    /** 系统给这件事的编号,同一件事前后不变;来源应用变了(代发的系统服务换成真正的应用)也不变 */
    val identity: String,
    /** 这件事属于手机上的哪个用户 */
    val userId: Int,
    /** 这一版的先后顺序,后来的大 */
    val revision: Long,
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
    /** 系统自己的卡片本来就不在左边放图(例如地铁、红绿灯提醒),不拿别的图顶替 */
    val noPicture: Boolean = false,
    /** 点卡片能交回系统执行(系统登记了点击动作或打开目标) */
    val openable: Boolean = false,
    /** 系统把哪几条通知做成了这张卡片(通知编号),用来认出同一件事 */
    val relatedNotifications: Set<Int> = emptySet(),
    /** 通知附加信息里写着这个名字和值的通知也是同一件事(OPPO:op_fluid_serviceId 写服务号);没有时为空 */
    val notificationMark: NotificationMark? = null,
    /** 卡片内容读全了;读不全时,同一件事的通知卡已在星河岛上就留通知卡 */
    val complete: Boolean = true,
    /** 这一版内容的指纹:内容变了指纹就变(只亮一下的提示按它认新的一次) */
    val fingerprint: Int = 0,
    /** 通话(call):来电、正在呼叫、通话中 */
    val callState: CallState? = null,
    /** 计时器、闹钟:时间到了正在响铃 */
    val ringing: Boolean = false,
    /** 计时器、秒表:在走(假为暂停);读不到时为空 */
    val running: Boolean? = null,
    /** 计时器:剩余时间(毫秒);读不到时为空 */
    val remainingMs: Long? = null,
    /** 秒表:已走的时间(毫秒);读不到时为空 */
    val elapsedMs: Long? = null,
    /** 计时器的名字 */
    val timerName: String? = null,
    /** 计时器的状态说明 */
    val timerStatus: String? = null,
    /** 秒表的说明 */
    val stopwatchNote: String? = null,
    /** 闹钟的名字 */
    val alarmLabel: String? = null,
    /** 闹钟:响铃时写的时刻;没响时是到点前的剩余时间 */
    val alarmTime: String? = null,
    /** 闹钟:12 小时制时的「上午」「下午」 */
    val halfDay: String? = null,
    /** 游戏计时、游戏复活倒计时:是哪个游戏 */
    val gamePackage: String? = null,
    /** 录屏:录制状态那句(例如「录制中」) */
    val recordStateText: String? = null,
    /** 系统开关提示:哪个开关 */
    val switch: SwitchKind? = null,
    /** 系统开关提示:开关的名字 */
    val switchName: String? = null,
    /** 系统开关提示:状态那句(例如「已开启」) */
    val switchState: String? = null,
    /** 音乐:真正在放歌的应用 */
    val mediaPackage: String? = null,
    /** 要拆成并排几格的明细(电影、景点门票、课程) */
    val details: Details? = null,
    val card: EventCard = EventCard(),
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

/** 系统开关:手电筒、勿扰、三段式按键(含响铃模式)、护眼、个人热点、手势提示 */
enum class SwitchKind(val id: String) {
    FLASHLIGHT("flashlight"), DO_NOT_DISTURB("doNotDisturb"), THREE_KEY("threeKey"), EYE_PROTECT("eyeProtect"),
    HOTSPOT("hotspot"), GESTURE("gesture");

    companion object {
        fun of(id: String?): SwitchKind? = entries.firstOrNull { it.id == id }
    }
}

/** 明细:电影(场次、影厅、座位)、景点门票(地址、可用时间)、课程(时间、节次、教室) */
enum class Details(val id: String) {
    MOVIE("movie"), SCENIC("scenic"), COURSE("course");

    companion object {
        fun of(id: String?): Details? = entries.firstOrNull { it.id == id }
    }
}

/**
 * 卡片内容(清单 card 一节):接入件按系统卡片自己标的主次填。认不出种类的事件只用这一部分。
 * 「按顺序」的都照系统卡片说明文件里的先后。
 */
data class EventCard(
    /** 卡片的标题:系统给的标题,没给时是卡片里第一段字 */
    val title: String = "",
    /** 卡片里其余的字,一行一段 */
    val body: String = "",
    /** 胶囊上的字,连成一句 */
    val capsuleText: String = "",
    /** 胶囊左边的字;没有时为空 */
    val capsuleLabel: String? = null,
    /** 系统胶囊右边自己写着字(此刻为空也算):胶囊右边一律显示字 */
    val capsuleWritesText: Boolean = false,
    /** 胶囊上的第一张图(不在右边的);没有时是服务标志或系统登记的服务图标 */
    val capsuleImage: EventImage? = null,
    /** 系统胶囊右边自己的图 */
    val capsuleRightImage: EventImage? = null,
    /** 系统胶囊右边自己的进度 */
    val capsuleRightProgress: EventProgress? = null,
    /** 系统胶囊左边、右边各放了什么 */
    val capsule: EventCapsule = EventCapsule(),
    /** 卡片左边的身份图 */
    val picture: EventImage? = null,
    val progress: EventProgress? = null,
    val timer: EventTimer? = null,
    /** 按钮,按顺序 */
    val buttons: List<EventButton> = emptyList(),
    /** 展开卡片里带角色的字,按顺序 */
    val texts: List<EventText> = emptyList(),
    /** 每个角色的第一段字(胶囊上的也算) */
    val firstTexts: Map<TextRole, String> = emptyMap(),
    /** 每个角色的第一张图(胶囊上的也算) */
    val images: Map<ImageRole, EventImage> = emptyMap(),
    /** 文字里写的百分比(0 到 1) */
    val percentInText: Float? = null,
    /** 系统给卡片中间那一块标的排法;没写或认不出时为空 */
    val layout: CardLayout? = null,
    /** 卡片右边的小进度环 */
    val ring: EventRing? = null,
    /** 小标签,按顺序 */
    val tags: List<EventTag> = emptyList(),
    /** 标题后面的小图(完成的对勾、失败) */
    val titleMark: EventImage? = null,
    /** 一直转圈的小图标和它中间不转的小图 */
    val spinner: EventImage? = null,
    val spinnerInner: EventImage? = null,
    /** 卡片顶部那一行的第一张图 */
    val headerImage: EventImage? = null,
    /** 放在卡片底部的大图或动画 */
    val largeImage: EventImage? = null,
    /** 系统铺在卡片上的颜色(渐变取最浓的)和底图;星河岛不铺,只给卡片上沿的光边取色 */
    val backgroundColor: Int? = null,
    val backgroundImage: EventImage? = null,
    /** 导航到路口时那盏红绿灯的颜色;认不出时为空 */
    val lampColor: LampColor? = null,
)

/** 系统给卡片中间那一块标的排法:普通、取码、取件码、对称(比分)、引导 */
enum class CardLayout(val id: String) {
    PLAIN("plain"), CODE("code"), PICKUP_CODE("pickupCode"), MIRROR("mirror"), GUIDE("guide");

    companion object {
        fun of(id: String?): CardLayout? = entries.firstOrNull { it.id == id }
    }
}

enum class LampColor(val id: String) {
    RED("red"), YELLOW("yellow"), GREEN("green");

    companion object {
        fun of(id: String?): LampColor? = entries.firstOrNull { it.id == id }
    }
}

/** 文字在卡片顶部那一行、中间还是底部 */
enum class TextBlock(val id: String) {
    TOP("top"), CENTER("center"), BOTTOM("bottom");

    companion object {
        fun of(id: String?): TextBlock? = entries.firstOrNull { it.id == id }
    }
}

/** 文字的角色(清单 textRoles、mirrorRoles、trafficRoles) */
enum class TextRole(val id: String) {
    HEADER("header"), CORE("core"), CORE_LABEL("coreLabel"), SECONDARY("secondary"), TERTIARY("tertiary"),
    LABEL("label"), HINT("hint"), EXTRA_A("extraA"), EXTRA_B("extraB"), COLORED("colored"), FIGURE("figure"),
    LARGE("large"), OTHER("other"),
    LEFT_NAME("leftName"), RIGHT_NAME("rightName"), LEFT_SCORE("leftScore"), RIGHT_SCORE("rightScore"),
    MATCH_INFO("matchInfo"), MATCH_STATUS("matchStatus"), COMPETITION("competition"), STAGE("stage"),
    LEFT_NOTE("leftNote"), RIGHT_NOTE("rightNote"),
    LIGHT_SECONDS("lightSeconds"), LEFT_LAMP_SECONDS("leftLampSeconds"), RIGHT_LAMP_SECONDS("rightLampSeconds");

    /** 放大写的字(系统把它排在放大一档的位置):核心那句、放大的数、比分、比赛信息、红绿灯提醒的秒数 */
    val enlarged: Boolean get() = this in ENLARGED

    companion object {
        private val ENLARGED = setOf(CORE, FIGURE, LARGE, LEFT_SCORE, RIGHT_SCORE, MATCH_INFO, LEFT_LAMP_SECONDS, RIGHT_LAMP_SECONDS)

        fun of(id: String?): TextRole? = entries.firstOrNull { it.id == id }
    }
}

/**
 * 卡片上的一段字。[text] 原样;[big] 系统把它写成大字(比标题大一截);[parts] 分几段写的一句里看得见的各段;
 * [color] 整句的颜色,[gradient] 写成渐变时的各个颜色和位置(0~1)。颜色是系统写的原色,没写时为空。
 */
data class EventText(
    val role: TextRole,
    val text: String,
    val block: TextBlock,
    val big: Boolean = false,
    val parts: List<EventTextPart> = emptyList(),
    val color: Int? = null,
    val gradient: List<Pair<Float, Int>> = emptyList(),
)

data class EventTextPart(val text: String, val color: Int?)

/** 图的角色(清单 imageRoles) */
enum class ImageRole(val id: String) {
    MAIN("main"), TITLE_MARK("titleMark"), DESCRIPTION_ICON("descriptionIcon"), RIGHT_LOGO("rightLogo"),
    CENTER_ICON("centerIcon"), TOP_ROW_FIRST("topRowFirst"), TOP_ROW_SECOND("topRowSecond"),
    PROGRESS_MARKER("progressMarker"), CORNER_LOGO("cornerLogo"), CODE_IMAGE("codeImage"), CODE_OVERLAY("codeOverlay"),
    BOTTOM_PICTURE("bottomPicture"), LANES("lanes"), NAVIGATION_LAMP("navigationLamp"), RIGHT_LAMP("rightLamp"),
    LEFT_LAMP("leftLamp");

    companion object {
        fun of(id: String?): ImageRole? = entries.firstOrNull { it.id == id }
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

    /** 会动的图标(动画文件 [json],UTF-8);[playing] 为假时停在第一帧 */
    class Animation(val identity: String, val playing: Boolean, val json: ByteArray, override val symbol: Boolean = true,
        override val appShared: Boolean = false, override val substitute: Boolean = false) : EventImage

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
data class EventTimer(val baseMs: Long, val countdown: Boolean, val elapsedClock: Boolean, val running: Boolean = true, val frozenMs: Long? = null)

/**
 * 进度:[fraction] 整条的进度(0 到 1,读不出时为空);[labels] 阶段名(比段数多一个);[steps] 分几段;
 * [tint] 进度条的颜色(系统写的原色,没写或写成白色时为空)。
 */
data class EventProgress(val fraction: Float?, val labels: List<String> = emptyList(), val steps: Int? = null, val tint: Int? = null)

/** 卡片右边的小进度环:进度、颜色、环中间的图 */
data class EventRing(val fraction: Float, val color: Int? = null, val inner: EventImage? = null)

/** 小标签(小图和字,有一样就算);[ownLine] 为真时单独一行,否则跟着说明那一行 */
data class EventTag(val icon: EventImage?, val text: String?, val ownLine: Boolean = false)

/** 系统胶囊左边、右边各放了什么;[absent]:系统排出了卡片,但这一页没写胶囊 */
data class EventCapsule(val leading: List<CapsuleItem> = emptyList(), val trailing: List<CapsuleItem> = emptyList(), val absent: Boolean = false)

sealed interface CapsuleItem {
    /** 一段字(此刻为空的也记下,表示那里是写字的位置);[timer]:这段字是计时,画的时候按计时走 */
    data class Text(val text: String, val color: Int? = null, val timer: Boolean = false) : CapsuleItem
    /** 一张图;[image] 为空:那里放了图,但读不到;[spin]:一直转 */
    data class Picture(val image: EventImage?, val spin: Boolean = false) : CapsuleItem
    /** 进度环;[inner] 环中间的图 */
    data class Ring(val progress: EventProgress, val color: Int? = null, val inner: EventImage? = null) : CapsuleItem
}

/**
 * 卡片上的一个按钮(清单 buttons 一节)。[id] 由接入件定,点下时原样交回;[round] 只有图标的圆钮;[checked] 写成开关时开没开
 * (不是开关时为空);[fill] 系统给的底色(原色);[topRow] 在卡片顶部那一行;[required] 系统卡片必有的;[onRing] 点小进度环执行它。
 */
data class EventButton(
    val id: String,
    val label: String,
    val use: ButtonUse = ButtonUse.OTHER,
    val icon: EventImage? = null,
    val round: Boolean = false,
    val fill: Int? = null,
    val checked: Boolean? = null,
    val topRow: Boolean = false,
    val required: Boolean = false,
    val onRing: Boolean = false,
)

enum class ButtonUse(val id: String) {
    PAUSE("pause"), RESUME("resume"), STOP("stop"), ANSWER("answer"), DECLINE("decline"), HANG_UP("hangUp"),
    SPEAKER("speaker"), OTHER("other");

    companion object {
        fun of(id: String?): ButtonUse? = entries.firstOrNull { it.id == id }
    }
}

/**
 * 星河岛交回接入件的按钮编号(清单 buttons 一节):一般按钮交回按钮自己的 [EventButton.id];下面这几个是星河岛按用途画的按钮,
 * 接入件按用途找系统卡片上对应的按钮执行;系统卡片没有接听、拒接、挂断按钮时也用这几个编号交回,由接入件按系统原来的办法执行。
 */
object EventActions {
    const val PAUSE = "pause"
    const val RESUME = "resume"
    const val STOP = "stop"
    /** 状态卡片上的「暂停 / 继续」(在走时是暂停,停着时是继续) */
    const val TOGGLE = "toggle"
    const val ANSWER = "answer"
    const val DECLINE = "decline"
    const val HANG_UP = "hangup"
}
