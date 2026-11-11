package com.astraflow.fluidcloud

import android.graphics.drawable.Icon
import com.astraisland.events.CallState
import com.astraisland.events.EventImage
import com.astraisland.events.EventKind
import com.astraisland.events.NotificationMark
import com.astraisland.events.PageNode
import com.astraisland.events.SystemEvent

/**
 * 系统流体云的一条记录交给星河岛的标准事件(contracts/island-system-events.v1.json):事件身份、事件种类(只用于分类开关与排位)、
 * 时机(自动展开、只亮一下、晃一下)和这一页的官方内容树。怎么摆由星河岛照官方内容树决定。
 */
object OfficialCloudTranslator {
    fun translate(record: CloudRecord): SystemEvent {
        val snapshot = record.snapshot
        val page = record.page
        val kind = OfficialCloudServices.kind(snapshot)
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
            openable = page.cardClick != null,
            relatedNotifications = snapshot.notificationIds,
            notificationMark = snapshot.serviceId.takeUnless { it.startsWith("common:") }?.let { NotificationMark(FLUID_SERVICE_EXTRA, it) },
            fingerprint = contentFingerprint(page),
            callState = if (kind == EventKind.CALL) callState(page) else null,
            ringing = OfficialCloudServices.ringing(snapshot),
            mediaPackage = if (kind == EventKind.MUSIC) OfficialCloudServices.mediaPackage(snapshot) else null,
            page = page,
            capsuleViews = record.views?.capsule,
            cardViews = record.views?.card,
        )
    }

    /**
     * 这一页内容的指纹:元素的类型、位置代号、属性、动作、子元素照原样算;图按 [EventImage] 自带的内容身份算,动画另算真实的播放状态。
     * 不能用 page.hashCode():图是普通类、没有值 equals,同一张图每次读出来都是新对象,没变的内容会被当成变了,
     * 只亮一下的提示亮完又重新亮(现行规则「系统事件接入」:内容变了才算新的一次,同一内容再送来不算)。
     */
    private fun contentFingerprint(page: PageNode): Int = hashPage(FNV_OFFSET, page).toInt()

    /** 64 位 FNV-1a 散列的起始值与乘数;每类图的种子(同一种图才互相比内容)。 */
    private const val FNV_OFFSET = -0x340d631b7bdddcdbL
    private const val FNV_PRIME = 0x100000001b3L
    private const val PICTURE = 1L
    private const val PLATFORM = 2L
    private const val ANIMATION = 3L
    private const val MOVING = 4L
    private const val MISSING = 5L

    private fun mix(hash: Long, value: Long): Long = (hash xor value) * FNV_PRIME

    private fun hashPage(hash: Long, node: PageNode): Long {
        var h = mix(hash, node.type.hashCode().toLong())
        h = mix(h, node.level.hashCode().toLong())
        h = mix(h, node.attrs.hashCode().toLong())
        h = mix(h, node.events.hashCode().toLong())
        h = mix(h, node.children.size.toLong())
        for (child in node.children) h = hashPage(h, child)
        // 图按名字与内容身份合起来算,各处互异、与登记次序无关
        var images = node.images.size.toLong()
        for ((name, image) in node.images) images = images xor mix(name.hashCode().toLong(), imageHash(image))
        return mix(h, images)
    }

    /** 图按已有内容身份和显示标记比较；没有内容身份时保留原图片对象的区分。 */
    private fun imageHash(image: EventImage): Long {
        var h = mix(FNV_OFFSET, flags(image).toLong())
        when (image) {
            is EventImage.Picture -> {
                h = mix(h, PICTURE)
                h = mix(h, (image.identity?.hashCode() ?: image.bitmap.hashCode()).toLong())
            }
            is EventImage.Platform -> {
                h = mix(h, PLATFORM)
                h = mix(h, iconIdentity(image.icon).hashCode().toLong())
            }
            is EventImage.Animation -> {
                h = mix(h, ANIMATION)
                h = mix(h, image.identity.hashCode().toLong())
                h = mix(h, if (image.playing) 1L else 0L)
                h = mix(h, (image.loopStartFrame ?: -1).toLong())
                h = mix(h, if (image.loop) 1L else 0L)
            }
            is EventImage.Moving -> {
                h = mix(h, MOVING)
                h = mix(h, image.identity.hashCode().toLong())
            }
            is EventImage.Missing -> h = mix(h, MISSING)
        }
        return h
    }

    /** 图的标记:[symbol] 按系统符号画、[appShared] 来源应用的共享照片、[substitute] 数据没给图时的替代图;都是真实显示状态的一部分。 */
    private fun flags(image: EventImage): Int =
        (if (image.symbol) 1 else 0) or (if (image.appShared) 2 else 0) or (if (image.substitute) 4 else 0)

    /** 资源与地址型图标按自身内容标识比较，其余保留原图标对象的身份。 */
    private fun iconIdentity(icon: Icon): Any = when (icon.type) {
        Icon.TYPE_RESOURCE -> "resource:" + icon.resPackage + ":" + icon.resId
        Icon.TYPE_URI, Icon.TYPE_URI_ADAPTIVE_BITMAP -> "uri:" + icon.uri
        else -> icon
    }

    /**
     * 来电还是通话中(只用于排位:来电排最前、独占、自动展开):通话卡片的按钮里有接听、没有免提时是来电
     * (按按钮的字与读屏说明认;接通后才有免提)。正在呼叫读不出来,算通话中。
     */
    private fun callState(page: PageNode): CallState {
        val labels = buttons(page).map { listOfNotNull(it.attrs["value"], it.attrs["voiceLabel"]).joinToString(" ") }
        if (labels.any { SPEAKER.containsMatchIn(it) }) return CallState.ONGOING
        return if (labels.any { ANSWER.containsMatchIn(it) }) CallState.INCOMING else CallState.ONGOING
    }

    private fun buttons(node: PageNode): List<PageNode> = node.children.flatMap { child -> listOfNotNull(child.takeIf { it.type == "button" }) + buttons(child) }

    private val ANSWER = Regex("""接听|接通|(?i)\banswer\b|(?i)\baccept\b""")
    private val SPEAKER = Regex("""扬声器|免提|(?i)\bspeaker\b|(?i)hands-?free""")

    /** 通知附加信息里标着流体云服务号的那一项 */
    const val FLUID_SERVICE_EXTRA = "op_fluid_serviceId"
}
