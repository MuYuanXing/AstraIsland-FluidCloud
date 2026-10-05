package com.astraflow.fluidcloud

import com.astraisland.events.ButtonUse
import com.astraisland.events.CallState
import com.astraisland.events.CapsuleItem
import com.astraisland.events.CardLayout
import com.astraisland.events.EventButton
import com.astraisland.events.EventCapsule
import com.astraisland.events.EventCard
import com.astraisland.events.EventImage
import com.astraisland.events.EventKind
import com.astraisland.events.EventProgress
import com.astraisland.events.EventRing
import com.astraisland.events.EventTag
import com.astraisland.events.EventText
import com.astraisland.events.EventTextPart
import com.astraisland.events.EventTimer
import com.astraisland.events.ImageRole
import com.astraisland.events.LampColor
import com.astraisland.events.NotificationMark
import com.astraisland.events.SystemEvent
import com.astraisland.events.TextBlock
import com.astraisland.events.TextRole
import java.util.IdentityHashMap

/**
 * 系统流体云的一条记录翻译成星河岛的标准事件(对照表 contracts/island-system-events.oppo.v1.json):服务号、服务意图换成事件种类,
 * 说明文件的位置代号换成文字和图的角色,数据字段换成各种类的内容项,按钮的动作换成用途。文字原样给;
 * 标题、说明怎么写,卡片怎么排,由星河岛按标准事件决定。
 */
object OfficialCloudTranslator {
    /** [openable]:点卡片能交回系统执行(由动作那边按这条记录判断) */
    fun translate(record: CloudRecord, openable: Boolean): SystemEvent {
        val snapshot = record.snapshot
        val content = record.content
        val kind = OfficialCloudServices.kind(snapshot)
        val pictures = Pictures()
        val data = snapshot.data
        return SystemEvent(
            identity = snapshot.key,
            userId = snapshot.userId,
            revision = snapshot.revision,
            kind = kind,
            packageName = snapshot.packageName,
            sourcePackage = OfficialCloudServices.sourcePackage(snapshot),
            serviceName = OfficialCloudServices.serviceName(snapshot),
            lockScreen = snapshot.lockScreenAllowed,
            alert = OfficialCloudServices.alert(snapshot),
            instant = OfficialCloudServices.instant(snapshot),
            shake = snapshot.shake,
            noPicture = snapshot.serviceId in NO_CARD_IMAGE_SERVICES || snapshot.intentAction in NO_CARD_IMAGE_INTENTS ||
                "${snapshot.serviceId}:${snapshot.pageId.substringAfterLast('/').removeSuffix(".json")}" in NO_CARD_IMAGE_PAGES,
            openable = openable,
            relatedNotifications = snapshot.notificationIds,
            notificationMark = snapshot.serviceId.takeUnless { it.startsWith("common:") }?.let { NotificationMark(FLUID_SERVICE_EXTRA, it) },
            complete = content.complete,
            fingerprint = listOf(snapshot.data, snapshot.title, snapshot.description, content.title, content.body, content.compactText).hashCode(),
            callState = if (kind == EventKind.CALL) callState(snapshot, content) else null,
            ringing = OfficialCloudServices.ringing(snapshot),
            running = when (kind) {
                EventKind.TIMER -> data["timer_operate_method"].orEmpty() == "pauseTimer"
                EventKind.STOPWATCH -> OfficialCloudServices.stopwatchRunning(snapshot, content)
                else -> null
            },
            remainingMs = if (kind == EventKind.TIMER) OfficialCloudServices.duration(data["timer_time"]) else null,
            elapsedMs = if (kind == EventKind.STOPWATCH) OfficialCloudServices.duration(data["stopwatch_time"]) else null,
            timerName = data["timer_description"].takeIf { kind == EventKind.TIMER },
            timerStatus = data["timer_status_description"].takeIf { kind == EventKind.TIMER },
            stopwatchNote = data["stopwatch_description"].takeIf { kind == EventKind.STOPWATCH },
            alarmLabel = data["alarm_description"].takeIf { kind == EventKind.ALARM },
            alarmTime = data["alarm_time"].takeIf { kind == EventKind.ALARM },
            halfDay = content.levels["C1"].takeIf { kind == EventKind.ALARM },
            gamePackage = gamePackage(snapshot),
            recordStateText = content.levels[RECORDING_STATE]?.trim()?.takeIf { it.isNotEmpty() && kind == EventKind.SCREEN_RECORDING },
            switch = OfficialCloudServices.switchKind(snapshot),
            switchName = OfficialCloudServices.switchName(snapshot),
            switchState = OfficialCloudServices.switchState(snapshot),
            mediaPackage = if (kind == EventKind.MUSIC) OfficialCloudServices.mediaPackage(snapshot) else null,
            details = OfficialCloudServices.detailsRecipe(snapshot),
            card = card(content, kind, pictures),
        )
    }

    /**
     * 来电还是通话中:卡片按钮里有「接听」、没有「免提」,或通知标着全屏来电时是来电(对照表 kinds.call.callState)。
     * 正在呼叫读不出来,不填。
     */
    private fun callState(snapshot: CloudSnapshot, content: CloudContent): CallState {
        val ringingExtra = snapshot.notification?.notification?.let {
            it.fullScreenIntent != null || it.extras?.getBoolean("in_full_screen_mode") == true } == true
        return if (OfficialCloudCallButtons.incoming(content.buttons, ringingExtra)) CallState.INCOMING else CallState.ONGOING
    }

    /**
     * 游戏计时、游戏复活倒计时的游戏:数据里写着是哪个游戏时给出(星河岛左边和胶囊左边用这个游戏的图标,不用系统的计时标志)。
     */
    private fun gamePackage(snapshot: CloudSnapshot): String? {
        if (snapshot.serviceId != OfficialCloudServices.GAME_TIMER && OfficialCloudServices.layout(snapshot) != OfficialCloudServices.Layout.COUNTDOWN) return null
        return GAME_PACKAGE_KEYS.firstNotNullOfOrNull { key -> snapshot.data[key]?.trim()?.takeIf { PACKAGE.matches(it) } }
    }

    private fun card(content: CloudContent, kind: EventKind, pictures: Pictures): EventCard {
        val mirror = content.centerLayout == MIRROR
        val firstTexts = LinkedHashMap<TextRole, String>()
        content.levels.forEach { (level, text) -> firstTexts.putIfAbsent(textRole(level, mirrorCenter = false), text) }
        val images = LinkedHashMap<ImageRole, EventImage>()
        content.levelImages.forEach { (level, image) -> IMAGE_ROLES[level]?.let { role -> pictures[image]?.let { images.putIfAbsent(role, it) } } }
        return EventCard(
            title = content.title,
            body = content.body,
            capsuleText = content.compactText,
            capsuleLabel = content.compactLabel,
            capsuleWritesText = content.capsuleText,
            capsuleImage = pictures[content.compactImage],
            capsuleRightImage = pictures[content.trailingImage],
            capsuleRightProgress = content.trailingProgress?.let(::progress),
            capsule = EventCapsule(content.capsule.leading.map { item(it, pictures) }, content.capsule.trailing.map { item(it, pictures) },
                content.capsule.absent),
            picture = pictures[content.hero],
            progress = content.progress?.let(::progress),
            timer = content.timer?.let { EventTimer(it.baseMs, it.countdown, it.elapsedClock, it.running, it.frozenMs) },
            buttons = content.buttons.map { button ->
                EventButton(button.id, button.label, use(button, kind), pictures[button.icon], button.round, button.fill, button.checked,
                    topRow = button.top, required = button.id.endsWith(REQUIRED_MARK), onRing = button.onRing)
            },
            texts = content.texts.map { t ->
                EventText(textRole(t.level, mirrorCenter = mirror && t.block == CloudText.Block.CENTER), t.text, block(t.block),
                    big = (t.size ?: 0) >= BIG_SIZE, parts = t.parts.map { EventTextPart(it.text, it.color) }, color = t.color, gradient = t.gradient)
            },
            firstTexts = firstTexts,
            images = images,
            percentInText = content.textPercent,
            layout = LAYOUTS[content.centerLayout],
            ring = content.ring?.let { EventRing(it.fraction, it.color, pictures[it.inner]) },
            tags = content.tags.map { EventTag(pictures[it.icon], it.text, ownLine = it.block !in SIDE_TAG_BLOCKS) },
            titleMark = pictures[content.titleMark],
            spinner = pictures[content.spinner],
            spinnerInner = pictures[content.spinnerInner],
            headerImage = pictures[content.headerImage],
            largeImage = pictures[content.largeImage],
            backgroundColor = content.backgroundColor,
            backgroundImage = pictures[content.backgroundImage],
            lampColor = lampColor(content.levelImages[NAVIGATION_LAMP] ?: content.trailingImage),
        )
    }

    /** 按钮的用途:通话按按钮上的字认(接听、拒接、挂断、免提),别的按系统卡片登记的动作认好的用途 */
    private fun use(button: CloudButton, kind: EventKind): ButtonUse = if (kind == EventKind.CALL) when (OfficialCloudCallButtons.roleOf(button.label)) {
        OfficialCloudCallButtons.Role.ANSWER -> ButtonUse.ANSWER
        OfficialCloudCallButtons.Role.DECLINE -> ButtonUse.DECLINE
        OfficialCloudCallButtons.Role.HANGUP -> ButtonUse.HANG_UP
        OfficialCloudCallButtons.Role.SPEAKER -> ButtonUse.SPEAKER
        OfficialCloudCallButtons.Role.OTHER -> ButtonUse.OTHER
    } else when (button.role) {
        CloudRole.PAUSE -> ButtonUse.PAUSE
        CloudRole.RESUME -> ButtonUse.RESUME
        CloudRole.STOP -> ButtonUse.STOP
        CloudRole.OTHER -> ButtonUse.OTHER
    }

    private fun item(part: CapsulePart, pictures: Pictures): CapsuleItem = when (part) {
        is CapsulePart.Text -> CapsuleItem.Text(part.text, part.color, part.timer)
        is CapsulePart.Picture -> CapsuleItem.Picture(pictures[part.image], part.spin)
        is CapsulePart.Ring -> CapsuleItem.Ring(progress(part.progress), part.color, pictures[part.inner])
    }

    private fun progress(p: CloudProgress) = EventProgress(p.fraction, p.labels, p.steps, p.tint)

    private fun block(block: CloudText.Block): TextBlock = when (block) {
        CloudText.Block.TOP -> TextBlock.TOP
        CloudText.Block.CENTER -> TextBlock.CENTER
        CloudText.Block.BOTTOM -> TextBlock.BOTTOM
    }

    /** 位置代号对应的文字角色(对照表 levels.text、levels.mirror);对称卡片中间一块按对称的一栏读 */
    private fun textRole(level: String, mirrorCenter: Boolean): TextRole =
        (if (mirrorCenter) MIRROR_ROLES[level] else null) ?: TEXT_ROLES[level] ?: if (level.startsWith("B")) TextRole.LARGE else TextRole.OTHER

    /** 红绿灯的颜色:按灯的图的文件名(说明文件里 big_red_light 这类名字)认红、黄、绿;认不出时为空 */
    private fun lampColor(image: CloudImage?): LampColor? {
        val name = image?.key?.substringAfterLast('/')?.lowercase() ?: return null
        return when {
            "red" in name -> LampColor.RED
            "yellow" in name -> LampColor.YELLOW
            "green" in name -> LampColor.GREEN
            else -> null
        }
    }

    /** 一件事里同一张图只换一次:同一个对象换出来还是同一个(星河岛按对象认同一张图) */
    private class Pictures {
        private val done = IdentityHashMap<CloudImage, EventImage>()
        operator fun get(image: CloudImage?): EventImage? = image?.let { done.getOrPut(it) { OfficialCloudImages.event(it) } }
    }

    private val TEXT_ROLES = mapOf("B1" to TextRole.CORE, "C1" to TextRole.CORE_LABEL, "C2" to TextRole.SECONDARY, "C3" to TextRole.TERTIARY,
        "C4" to TextRole.LABEL, "C15" to TextRole.HINT, "C24" to TextRole.EXTRA_A, "C25" to TextRole.EXTRA_B, "C40" to TextRole.COLORED,
        "B3" to TextRole.FIGURE, "C10" to TextRole.HEADER, "C11" to TextRole.HEADER, "C33" to TextRole.LIGHT_SECONDS,
        "B5" to TextRole.RIGHT_LAMP_SECONDS, "B6" to TextRole.LEFT_LAMP_SECONDS)
    private val MIRROR_ROLES = mapOf("C1" to TextRole.LEFT_NAME, "C2" to TextRole.RIGHT_NAME, "B1" to TextRole.LEFT_SCORE,
        "B2" to TextRole.RIGHT_SCORE, "B3" to TextRole.MATCH_INFO, "C7" to TextRole.MATCH_STATUS, "C4" to TextRole.COMPETITION,
        "C3" to TextRole.STAGE, "C5" to TextRole.LEFT_NOTE, "C6" to TextRole.RIGHT_NOTE)
    private val IMAGE_ROLES = mapOf("D1" to ImageRole.MAIN, "D" to ImageRole.TITLE_MARK, "D2" to ImageRole.DESCRIPTION_ICON,
        "D6" to ImageRole.RIGHT_LOGO, "D7" to ImageRole.CENTER_ICON, "D8" to ImageRole.TOP_ROW_FIRST, "D9" to ImageRole.TOP_ROW_SECOND,
        "D12" to ImageRole.PROGRESS_MARKER, "D18" to ImageRole.CORNER_LOGO, "D19" to ImageRole.CODE_IMAGE, "D20" to ImageRole.CODE_OVERLAY,
        "D21" to ImageRole.BOTTOM_PICTURE, "D24" to ImageRole.LANES, NAVIGATION_LAMP to ImageRole.NAVIGATION_LAMP,
        "D27" to ImageRole.RIGHT_LAMP, "D28" to ImageRole.LEFT_LAMP)
    private val LAYOUTS = mapOf("common" to CardLayout.PLAIN, "text-highlight" to CardLayout.PLAIN, "access-code" to CardLayout.CODE,
        "pickup-code" to CardLayout.PICKUP_CODE, "mirror" to CardLayout.MIRROR, "guide1" to CardLayout.GUIDE)
    private const val MIRROR = "mirror"
    /** 导航到路口时的红绿灯图的位置代号 */
    private const val NAVIGATION_LAMP = "D25"
    /** 系统录屏卡片写状态字(「录制中」)的位置代号 */
    private const val RECORDING_STATE = "C10"
    /** 系统必有的元素位置代号后面带的记号(例如 AI 语音摘记的关闭 E20*) */
    private const val REQUIRED_MARK = "*"
    /** 跟着说明那一行的标签小块(另外的 G3、G4 单独一行) */
    private val SIDE_TAG_BLOCKS = setOf("G1", "G2")
    /** 说明文件的字号档 7 是 26 号字,比标题大一截 */
    private const val BIG_SIZE = 7
    /** 通知附加信息里标着流体云服务号的那一项 */
    const val FLUID_SERVICE_EXTRA = "op_fluid_serviceId"
    private val GAME_PACKAGE_KEYS = listOf("packageName", "targetPackageName", "gamePackageName", "gamePkg", "pkgName")
    private val PACKAGE = Regex("""[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+""")

    /**
     * 系统卡片左边没有图的服务(照「改好以后的样子」页):智慧地铁、支付宝地铁、智慧车控、抢票加速、驾车红绿灯提醒(高德、百度、腾讯)、
     * VIP 模式、密码本保存,以及风险应用提醒的诈骗电话那一页;这些卡片左边不放图,不拿胶囊上的图顶替。换了新服务号的地铁、红绿灯提醒
     * 按服务意图认
     */
    private val NO_CARD_IMAGE_SERVICES = setOf("536879177", "536879034", "268452019", "268452025", "536879377", "536879312", "536879383",
        "268451906", "268451936")
    private val NO_CARD_IMAGE_PAGES = setOf("268452005:fraud")
    private val NO_CARD_IMAGE_INTENTS = setOf("pantanal.intent.publictransit.SMART_SUBWAY_REMINDER_V2", "pantanal.intent.traffic_light.NAVIGATION_TRAFFIC_LIGHT",
        "pantanal.intent.traffic_light.BAIDU_TRAFFIC_LIGHTS", "pantanal.intent.traffic_light.TENGXUN_TRAFFIC_LIGHTS")
}
