package com.astraflow.fluidcloud

import android.graphics.Bitmap
import android.view.View
import java.lang.ref.WeakReference

/** 星流扩展的输入事实；不携带厂商字号、动画或排位参数。 */
data class CloudNode(
    val type: String,
    val level: String = "",
    val props: Map<String, String> = emptyMap(),
    val children: List<CloudNode> = emptyList(),
)

/**
 * 卡片里的一张图。[symbol]:卡片说明文件自带的图标(按系统符号画,不当封面、头像);
 * [animated]:动画文件([bytes] 是动画内容),[playing] 为假时停在第一帧(现行规则「系统事件接入」)。
 */
data class CloudImage(
    val key: String, val bytes: ByteArray? = null, val bitmap: Bitmap? = null, val platform: android.graphics.drawable.Icon? = null,
    val symbol: Boolean = false, val animated: Boolean = false, val playing: Boolean = true,
    /**
     * 放在卡片所属应用自己的共享位置的图(content:// 地址,例如外卖的菜品图):在后台读,读好之前没有图;
     * 只读 [owners] 这几个应用自己的共享位置(现行规则「展开卡片总表」)。
     */
    val shared: String? = null,
    val owners: Set<String> = emptySet(),
    /** 说明文件在数据没给图时写的替代图(alt),例如外卖卡片的外卖袋图 */
    val substitute: Boolean = false,
)

/**
 * 计时。[countdown] 为真时 [baseMs] 是结束时刻,否则是起点;[elapsedClock] 为真时按开机时钟,否则按墙上时钟。
 * [running] 为假时计时停住,显示 [frozenMs](例如秒表、录屏已暂停)。
 */
data class CloudTimer(
    val baseMs: Long,
    val countdown: Boolean,
    val elapsedClock: Boolean,
    val running: Boolean = true,
    val frozenMs: Long? = null,
)

/**
 * 进度。[fraction] 是整条的进度;系统卡片的进度按阶段分成 [steps] 段(阶段名在 [labels],比段数多一个),
 * 数据给的是当前第几段和这一段走了多少,这里已换算成整条的进度(现行规则「展开卡片总表」)。
 */
data class CloudProgress(val fraction: Float?, val labels: List<String> = emptyList(), val steps: Int? = null,
    /** 官方进度条的颜色(见 [OfficialCloudColor.barTint]);没写、写成白色时为空 */
    val tint: Int? = null)

/**
 * 系统卡片自己登记的动作(卡片模板里的 actions,已代入这张卡片的数据,现行规则「系统事件接入」)。
 * [type] 是 message(交给提供方处理)或 deeplink(打开页面);[uri] 对 message 是提供方地址,对 deeplink 是 nativeapp:// 页面。
 * [alternatives]:登记成几种做法的列表时,这一种打不开按顺序试的其余做法(与系统卡片引擎一致)。
 */
data class CloudAction(
    val type: String,
    val uri: String,
    val method: String = "",
    val params: Map<String, String> = emptyMap(),
    val data: String? = null,
    val packageName: String? = null,
    val flag: String? = null,
    val alternatives: List<CloudAction> = emptyList(),
)

/** 岛上按钮的用途,决定画成什么:暂停、继续、停止是圆钮或红色停止,其余是文字按钮。 */
enum class CloudRole { PAUSE, RESUME, STOP, OTHER }

/**
 * 卡片上的一个按钮。[round]:官方画成只有图标的圆形按钮(说明文件里 type 是 circle),[icon] 是它的图标;
 * 星河岛照已定的规矩画成右边的灰底圆钮(现行规则「展开卡片总表」)。
 */
data class CloudButton(
    val id: String,
    val label: String,
    val target: WeakReference<View>? = null,
    val pending: android.app.PendingIntent? = null,
    val action: CloudAction? = null,
    val role: CloudRole = CloudRole.OTHER,
    val icon: CloudImage? = null,
    val round: Boolean = false,
    /** 官方给文字按钮写的底色(例如打车的「路线」蓝色实底、密码本的「保存」蓝色淡底) */
    val fill: Int? = null,
    /** 官方写成开关的按钮开没开(AI 语音摘记的录音、摘要、字幕);不是开关时为空 */
    val checked: Boolean? = null,
    /** 在官方卡片顶部那一行里(AI 语音摘记的设置、关闭) */
    val top: Boolean = false,
    /** 官方把卡片上的小进度环做成可以点的(互传、远程文件传输点环取消):点环执行,不另画按钮 */
    val onRing: Boolean = false,
)

/**
 * 卡片上的一个小标签(说明文件里的小块):小图标和字,有一样就算。
 * [block] 是官方标签小块的位置代号:G1、G2 跟着说明那一行,G3、G4 跟着下一行。
 */
data class CloudTag(val icon: CloudImage?, val text: String?, val block: String = "G1")

/** 卡片里的小进度环(说明文件里展开卡片中的进度小部件):走了多少、什么颜色、环中间的图 */
data class CloudRing(val fraction: Float, val color: Int? = null, val inner: CloudImage? = null)

data class CloudSnapshot(
    val nativeKey: String,
    val userId: Int,
    val packageName: String,
    val serviceId: String,
    val instanceId: String,
    val startedAtWallMs: Long,
    val revision: Long,
    val entrance: String = "ENTRY_STATUS_BAR",
    val title: String = "",
    val description: String = "",
    val root: CloudNode? = null,
    val images: Map<String, CloudImage> = emptyMap(),
    val icon: CloudImage? = null,
    val notificationIds: Set<Int> = emptySet(),
    val visible: Boolean = true,
    val lockScreenAllowed: Boolean = false,
    val nativeCard: Any? = null,
    val nativeSeedling: Any? = null,
    val nativeModel: Any? = null,
    val notification: android.service.notification.StatusBarNotification? = null,
    val nativeViews: Map<Int, List<View>> = emptyMap(),
    val sourceUpdatedAtMs: Long = revision,
    val ended: Boolean = false,
    /** 卡片数据里的业务字段(提供方送来的原始数据,只取第一层,值一律转成文字);模板里 {{字段}} 按它代入。 */
    val data: Map<String, String> = emptyMap(),
    /** 提供这张卡片的系统服务所在应用(系统界面托管的服务就是 com.android.systemui)。 */
    val hostPackage: String = "",
    /** 系统登记的服务显示名(例如「手电筒」);读不到时为空。 */
    val serviceName: String = "",
    /** 卡片模板的版本号,找模板文件与发动作时都要用;读不到时为 0。 */
    val templateVersion: Long = 0,
    /** 卡片当前显示的是模板里的哪一页;读不到时按首页。 */
    val pageId: String = "",
    /** 收到这份数据时的开机时钟,只有文字时间的计时从这一刻往后走。 */
    val receivedAtElapsedMs: Long = 0,
    /** 系统卡片引擎已经把卡片内容排好(读得到内容树);刚送来、还没排好的记录为假。 */
    val rendered: Boolean = false,
    /** 系统把这条提示标成只亮一下(即时提醒,例如勿扰、响铃模式、手势提示),亮满规定的时长就删掉(现行规则「系统事件接入」)。 */
    val instant: Boolean = false,
    /** 系统给这条提示的提醒级别(11 是短时强提醒);只亮一下的提示按它定亮多久。 */
    val remindLevel: Int = 0,
    /**
     * 卡片说明文件写的服务意图(config.json 里 intent 的 action,例如外卖是 pantanal.intent.takeout.VIEW_ORDER_PROGRESS_V3);
     * 同一个服务换了新服务号时意图不变,新号照老号的排法(现行规则「展开卡片总表」)。读不到时为空。
     */
    val intentAction: String = "",
    /** 这一版数据要求胶囊抖一下(提供方送来的 shouldShake,只对里程碑、即时提醒事件有效,见 OPPO 文档 13330) */
    val shake: Boolean = false,
) {
    /**
     * 事件身份,与系统流体云给这件事的编号一致(服务号、服务实例、用户、服务的时间戳,时间戳标识服务的一次生命周期)。
     * 不含来源应用:内容送到前后来源应用会从托管服务换成真正的应用,换了也还是同一件事;通知栏与状态栏的 size 不参与身份。
     */
    val key: String get() = "$userId|$serviceId|$instanceId|$startedAtWallMs"
}

/**
 * 系统胶囊一边的一样东西,照说明文件里的先后(现行规则「系统事件接入」):一段字(带说明文件写的颜色,读不出时为空;
 * 此刻为空的字也记下,表示那里是写字的位置)、一张图(图标或会动的图)、一个进度环。
 */
sealed interface CapsulePart {
    /** [timer]:这段字是计时(说明文件写了计时或到点时刻),画的时候按计时走,字本身只是占位 */
    data class Text(val text: String, val color: Int? = null, val timer: Boolean = false) : CapsulePart
    /** [image] 为空:说明文件在这里放了图,但这张图读不到(数据没给、文件找不到) */
    /** [spin]:说明文件写成一直转的小图标(小布执行中) */
    data class Picture(val image: CloudImage?, val spin: Boolean = false) : CapsulePart
    /** [inner]:进度环中间的图(例如车辆充电的闪电、生成完成的对勾) */
    data class Ring(val progress: CloudProgress, val color: Int? = null, val inner: CloudImage? = null) : CapsulePart
}

/**
 * 展开卡片里的一段字和它在说明文件里的位置代号(去掉星号,例如 B1 是官方最显眼的那句、C2 是它下面的小字、
 * C10 是卡片顶部那一行)与所在的块([Block]),照说明文件的先后(现行规则「系统事件接入」)。
 * [size] 是说明文件写的字号档(fontSize1~9,越大字越大;没写时为空);[parts] 是分几段写的一句里看得见的各段(一整段写的为空);
 * [color] 是整句的颜色(没写时为空),[gradient] 是整句写成渐变时的各个颜色和位置(0~1)。
 */
data class CloudText(val level: String, val text: String, val block: Block, val size: Int? = null, val parts: List<Part> = emptyList(),
    val color: Int? = null, val gradient: List<Pair<Float, Int>> = emptyList()) {
    /** 展开卡片的三块:顶部那一行、中间、底部 */
    enum class Block { TOP, CENTER, BOTTOM }

    /** 分几段写的一句里的一段和它的颜色(官方把一句里的几个字写淡一些时,例如导航的「右转进入」) */
    data class Part(val text: String, val color: Int?)
}

/**
 * 系统胶囊左边、右边各放了什么(照说明文件的先后;胶囊里不在两边的按左边算;老版本用的兼容胶囊不算)。
 * [absent]:照说明文件排出了卡片,但这一页没写胶囊(官方文档:胶囊里没有图时显示服务图标)。
 */
data class CloudCapsule(
    val leading: List<CapsulePart> = emptyList(), val trailing: List<CapsulePart> = emptyList(), val absent: Boolean = false,
)

data class CloudContent(
    val title: String,
    val body: String,
    val compactText: String,
    val compactLabel: String? = null,
    val compactImage: CloudImage? = null,
    /** 卡片左边的身份图(卡片模板里的服务图标);不当大图铺满卡片。 */
    val hero: CloudImage? = null,
    val progress: CloudProgress? = null,
    val timer: CloudTimer? = null,
    val buttons: List<CloudButton> = emptyList(),
    val complete: Boolean = false,
    val gaps: Set<String> = emptySet(),
    /** 点卡片时系统卡片自己的点击动作;没有时按通知的打开目标或系统卡片的点击处理。 */
    val cardAction: CloudAction? = null,
    /**
     * 系统胶囊右边自己写着一段字(说明文件里胶囊右边有正在显示的文字,此刻为空也算):胶囊右边一律显示字,
     * 不因为卡片里有进度就换成进度环(否则导航胶囊右边有时是信息、有时是下载进度环)。
     */
    val capsuleText: Boolean = false,
    /** 系统胶囊右边自己的进度(胶囊里的进度小部件);有它才在胶囊右边画进度环。 */
    val trailingProgress: CloudProgress? = null,
    /** 系统胶囊右边自己的图(例如红绿灯、重连图标、会动的风险提示);胶囊右边没有字、没有进度时画它。 */
    val trailingImage: CloudImage? = null,
    /**
     * 说明文件给每一项标的位置代号(去掉星号)下的文字和图:B1 是卡片大字、C2 是大字下面那一行、D1 是主图、D18 是标志、
     * C33 与 D25 是红绿灯秒数与灯。「展开卡片总表」按事件把它们放到卡片的各个位置(现行规则「展开卡片总表」)。
     */
    val levels: Map<String, String> = emptyMap(),
    val levelImages: Map<String, CloudImage> = emptyMap(),
    /** 文字里写的百分比;只有应用下载、游戏更新、互传用它画进度条(现行规则「展开卡片总表」) */
    val textPercent: Float? = null,
    /** 系统胶囊左右两边各放了什么、什么颜色(照说明文件排出来的;系统只给了排好的画面时为空)。 */
    val capsule: CloudCapsule = CloudCapsule(),
    /** 展开卡片里带位置代号的字(照说明文件排出来的;系统只给了排好的画面时为空),卡片的主次照它排 */
    val texts: List<CloudText> = emptyList(),
    /**
     * 官方给卡片中间那一块标的排法(说明文件 center 的 category):access-code、pickup-code 是取码类,mirror 是对称(比分),
     * common、text-highlight 是普通的;没写时为空(现行规则「展开卡片总表」)。
     */
    val centerLayout: String = "",
    /** 展开卡片里的小进度环(游戏更新、互传发送中这类);胶囊里的进度环不算 */
    val ring: CloudRing? = null,
    /** 小标签(说明文件里的小块,例如取单码引导的「体验版」、地铁的线路图标),照先后 */
    val tags: List<CloudTag> = emptyList(),
    /** 官方写在标题旁的小图(例如完成时的绿色对勾) */
    val titleMark: CloudImage? = null,
    /** 说明文件写成一直转的小图标(小布执行中最下面那句前面的图标):卡片上放在正文前面转圈;[spinnerInner] 是它正中间不转的小图 */
    val spinner: CloudImage? = null,
    val spinnerInner: CloudImage? = null,
    /** 只画图标的按钮的图标,按位置代号(不管系统给没给动作;秒表的计次按钮由星河岛补动作,图标用这里的) */
    val buttonIcons: Map<String, CloudImage> = emptyMap(),
    /** 展开卡片顶部那一行的第一张图(位置代号 D8、D9,例如降雨的定位图标、天通卫星的信号图标):画在来源条上 */
    val headerImage: CloudImage? = null,
    /** 放在卡片底部的大图或动画(取单码引导的大图、互传收到文件的预览、天通卫星的方向动画) */
    val largeImage: CloudImage? = null,
    /** 官方铺在展开卡片上的底色(渐变取最浓的颜色)和底图;星河岛不铺,只让卡片上沿的光边带一点这个颜色 */
    val backgroundColor: Int? = null,
    val backgroundImage: CloudImage? = null,
)

enum class CloudChange { REPLACE, POST, REMOVE }
data class CloudRecord(val snapshot: CloudSnapshot, val content: CloudContent)
