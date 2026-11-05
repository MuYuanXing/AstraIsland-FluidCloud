package com.astraflow.fluidcloud

import com.astraisland.events.CallState
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
            fingerprint = page.hashCode(),
            callState = if (kind == EventKind.CALL) callState(page) else null,
            ringing = OfficialCloudServices.ringing(snapshot),
            mediaPackage = if (kind == EventKind.MUSIC) OfficialCloudServices.mediaPackage(snapshot) else null,
            page = page,
            capsuleViews = record.views?.capsule,
            cardViews = record.views?.card,
        )
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
