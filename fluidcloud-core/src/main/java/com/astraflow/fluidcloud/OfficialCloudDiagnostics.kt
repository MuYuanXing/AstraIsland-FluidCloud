package com.astraflow.fluidcloud

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.service.notification.StatusBarNotification
import com.astraisland.events.DiagnosticResources
import com.astraisland.events.EventImage
import com.astraisland.events.PageNode
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 内测反馈包的最近到达记录。记录窗口内保留完整数据与显示资源；主线程只保留引用，导出时在后台写文件。 */
object OfficialCloudDiagnostics {
    private const val HISTORY = 150
    private const val HISTORY_BYTES = 16 * 1024 * 1024
    private var retainedBytes = 0L
    private data class Captured(val snapshot: CloudSnapshot, val page: PageNode?, val images: Map<String, EventImage?>)
    private data class Entry(val at: Long, val change: String, val key: String, val captured: Captured?, val bytes: Long = 0)
    private val history = ArrayDeque<Entry>()
    private val prints = HashMap<String, Int>()
    private var removed = 0L
    private var startedAt = System.currentTimeMillis()

    /** 一批记录收下并整理之后，保留稳定的图片引用，卡片消失后仍可导出。 */
    fun note(records: List<CloudSnapshot>, alive: Set<String>, pages: Map<String, PageNode> = emptyMap()) {
        for (snapshot in records) {
            val captured = capture(snapshot, pages[snapshot.nativeKey])
            val print = listOf(snapshot.data, snapshot.pageId, snapshot.templateVersion, snapshot.visible, snapshot.ended,
                snapshot.entrance, snapshot.notification?.let { it.key + it.postTime }, snapshot.remoteData?.contentHashCode(),
                captured.page, captured.images).hashCode()
            val before = prints.put(snapshot.nativeKey, print)
            if (before == print) continue
            add(Entry(System.currentTimeMillis(), if (before == null) "+" else "~", snapshot.nativeKey, captured, size(captured)))
        }
        prints.keys.filter { it !in alive }.forEach { prints.remove(it); add(Entry(System.currentTimeMillis(), "-", it, null)) }
    }

    private fun capture(snapshot: CloudSnapshot, page: PageNode?): Captured = Captured(
        snapshot.copy(data = snapshot.data.toMap(), notification = snapshot.notification?.clone()), page,
        snapshot.images.mapValues { (_, image) -> OfficialCloudImages.event(image) })

    private fun size(captured: Captured): Long {
        val seen = java.util.IdentityHashMap<Any, Boolean>()
        fun image(value: EventImage?): Long = when (value) {
            is EventImage.Picture -> if (seen.put(value.bitmap, true) == null) value.bitmap.allocationByteCount.toLong() else 0
            is EventImage.Animation -> if (seen.put(value.json, true) == null) value.json.size.toLong() else 0
            is EventImage.Moving -> if (seen.put(value.bytes, true) == null) value.bytes.size.toLong() else 0
            else -> 0
        }
        fun page(node: PageNode): Long = node.images.values.sumOf(::image) + node.children.sumOf(::page)
        val s = captured.snapshot
        return s.data.entries.sumOf { (it.key.length + it.value.length).toLong() * 2 } + (s.remoteData?.size ?: 0) +
            s.images.values.sumOf { it.bytes?.size?.toLong() ?: 0L } + captured.images.values.sumOf(::image) + (captured.page?.let(::page) ?: 0L)
    }

    private fun add(entry: Entry) {
        history.addLast(entry)
        retainedBytes += entry.bytes
        while (history.size > 1 && (history.size > HISTORY || retainedBytes > HISTORY_BYTES)) {
            retainedBytes -= history.removeFirst().bytes
            removed++
        }
    }

    private fun text(entry: Entry): String {
        val clock = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US)
        val prefix = "${clock.format(Date(entry.at))} ${entry.change} "
        val snapshot = entry.captured?.snapshot ?: return prefix + entry.key
        return prefix + summary(snapshot) + "\n  data=${snapshot.data}" +
            (snapshot.notification?.let { "\n  通知 " + notificationText(it) } ?: "") +
            (snapshot.remoteData?.let { "\n  出行界面数据=${String(it, Charsets.UTF_8)}" } ?: "")
    }

    fun timeline(): List<String> = history.map(::text)

    /** 当场复制记录列表，在后台按这一时刻的数据写出，导出期间的新事件不会混入。 */
    fun export(context: Context, records: List<Pair<CloudSnapshot, PageNode?>>): (File) -> Unit {
        val entries = history.toList()
        val current = records.map { (snapshot, page) -> capture(snapshot, page) }
        val dropped = removed
        val bytes = retainedBytes
        val since = startedAt
        return { directory ->
            val resources = DiagnosticResources(context, directory)
            val root = JSONObject().put("recordingStartedAt", since).put("exportedAt", System.currentTimeMillis())
                .put("historyLimit", HISTORY).put("historyByteLimit", HISTORY_BYTES).put("retainedEstimatedBytes", bytes).put("olderEntriesDiscarded", dropped)
            root.put("history", JSONArray().apply {
                entries.forEach { entry -> put(JSONObject().put("at", entry.at).put("change", entry.change).put("nativeKey", entry.key)
                    .put("record", entry.captured?.let { record(it, resources) } ?: JSONObject.NULL)) }
            })
            root.put("current", JSONArray().apply { current.forEach { put(record(it, resources)) } })
            File(directory, "records.json").writeText(root.toString(2))
            File(directory, "timeline.txt").writeText("开始记录：$since；较早已移出窗口的记录：$dropped 段\n" + entries.joinToString("\n", transform = ::text))
            resources.finish()
        }
    }

    private fun record(captured: Captured, resources: DiagnosticResources): JSONObject {
        val s = captured.snapshot
        return JSONObject().put("nativeKey", s.nativeKey).put("userId", s.userId).put("packageName", s.packageName)
            .put("serviceId", s.serviceId).put("instanceId", s.instanceId).put("startedAtWallMs", s.startedAtWallMs)
            .put("revision", s.revision).put("entrance", s.entrance).put("visible", s.visible).put("lockScreenAllowed", s.lockScreenAllowed)
            .put("sourceUpdatedAtMs", s.sourceUpdatedAtMs).put("ended", s.ended).put("data", JSONObject(s.data))
            .put("hostPackage", s.hostPackage).put("serviceName", s.serviceName).put("templateVersion", s.templateVersion)
            .put("pageId", s.pageId).put("instant", s.instant).put("remindLevel", s.remindLevel).put("intentAction", s.intentAction)
            .put("shake", s.shake).put("notificationIds", JSONArray(s.notificationIds.toList()))
            .put("remoteData", s.remoteData?.let { resources.bytes(it, "json") } ?: JSONObject.NULL)
            .put("notification", s.notification?.let { notification(it, resources) } ?: JSONObject.NULL)
            .put("images", JSONObject().apply {
                s.images.forEach { (key, image) -> put(key, JSONObject().put("key", image.key).put("shared", image.shared)
                    .put("owners", JSONArray(image.owners.toList())).put("animated", image.animated).put("playing", image.playing)
                    .put("loop", image.loop).put("loopStartFrame", image.loopStartFrame)
                    .put("sourceFile", image.bytes?.let { resources.bytes(it, if (image.animated) "json" else "bin") } ?: JSONObject.NULL)
                    .put("display", captured.images[key]?.let(resources::image) ?: JSONObject.NULL)) }
            })
            .put("page", captured.page?.let(resources::page) ?: JSONObject.NULL)
    }

    private fun notification(sbn: StatusBarNotification, resources: DiagnosticResources): JSONObject {
        val n = sbn.notification
        return JSONObject().put("key", sbn.key).put("id", sbn.id).put("tag", sbn.tag).put("userId", sbn.userId)
            .put("postTime", sbn.postTime).put("ongoing", sbn.isOngoing).put("flags", n.flags).put("category", n.category)
            .put("channelId", n.channelId).put("extras", resources.value(n.extras))
            .put("smallIcon", resources.value(n.smallIcon)).put("largeIcon", resources.value(n.getLargeIcon()))
            .put("contentIntent", resources.value(n.contentIntent)).put("deleteIntent", resources.value(n.deleteIntent))
            .put("fullScreenIntent", resources.value(n.fullScreenIntent))
            .put("actions", JSONArray().apply { n.actions?.forEach { action -> put(JSONObject().put("title", action.title)
                .put("intent", resources.value(action.actionIntent)).put("extras", resources.value(action.extras))
                .put("icon", resources.value(action.getIcon())).put("semanticAction", action.semanticAction)
                .put("remoteInputs", resources.value(action.remoteInputs?.map { input ->
                    JSONObject().put("resultKey", input.resultKey).put("label", input.label).put("choices", resources.value(input.choices))
                        .put("allowFreeFormInput", input.allowFreeFormInput).put("extras", resources.value(input.extras))
                }))) } })
    }

    fun clear() { history.clear(); prints.clear(); removed = 0; retainedBytes = 0; startedAt = System.currentTimeMillis() }

    fun summary(s: CloudSnapshot): String = "${s.nativeKey} svc=${s.serviceId} 「${s.serviceName}」 pkg=${s.packageName} host=${s.hostPackage} " +
        "v=${s.templateVersion} page=${s.pageId.ifEmpty { "(首页)" }} entrance=${s.entrance} visible=${s.visible} ended=${s.ended} " +
        "lock=${s.lockScreenAllowed} instant=${s.instant} remind=${s.remindLevel} shake=${s.shake} intent=${s.intentAction} " +
        "rev=${s.revision} updated=${s.sourceUpdatedAtMs} started=${s.startedAtWallMs} notifications=${s.notificationIds}"

    @Suppress("DEPRECATION")
    fun notificationText(sbn: StatusBarNotification): String {
        val n = sbn.notification
        return buildString {
            append("key=${sbn.key} id=${sbn.id} tag=${sbn.tag} user=${sbn.userId} post=${sbn.postTime} ongoing=${sbn.isOngoing} ")
            append("flags=0x${Integer.toHexString(n.flags)} category=${n.category} channel=${n.channelId} ")
            append("template=${n.extras.getString(Notification.EXTRA_TEMPLATE)} actions=${n.actions?.size ?: 0}")
            for (key in n.extras.keySet().sorted()) append("\n    $key = ${value(n.extras.get(key))}")
        }
    }

    @Suppress("DEPRECATION")
    private fun value(v: Any?): String = when (v) {
        null -> "null"
        is Bitmap -> "Bitmap(${v.width}×${v.height})"
        is Bundle -> v.keySet().sorted().joinToString(", ", "{", "}") { "$it=${value(v.get(it))}" }
        is Array<*> -> v.joinToString(", ", "[", "]") { value(it) }
        else -> v.toString()
    }
}
