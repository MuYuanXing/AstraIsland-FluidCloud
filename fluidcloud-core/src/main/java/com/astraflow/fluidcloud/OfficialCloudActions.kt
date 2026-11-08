package com.astraflow.fluidcloud

import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.astraisland.events.EventActions
import java.util.concurrent.Executors

/**
 * 系统卡片元素的点按执行(现行规则「系统事件接入」):星河岛交回「page:动作名」,按这张卡片此刻的数据执行官方登记的那个动作——
 * 模板卡片照模板的 actions(message 交给提供方,系统界面进程里的 ContentResolver.call,系统卡片引擎也是这样发的;deeplink 打开登记的页面;
 * 登记成几种做法的按顺序试,与系统卡片引擎一致),直接通知照通知带的打开目标。应用直接给界面的卡片(航班、火车)由界面自己处理点按。
 * 锁屏时只执行写明锁屏显示的元素和整张卡片的点击,与系统锁屏一致。数据不写盘,记录随事件结束撤销。
 */
class OfficialCloudActions(
    private val context: Context,
    private val caller: ProviderCaller = ProviderCaller.system(context),
) {
    /** 把请求交给提供方;返回请求是否已经送达。 */
    fun interface ProviderCaller {
        fun call(authority: String, method: String, extras: Bundle): Boolean

        /** 提供方(或代发它的流体云服务)装在手机上没有;只查登记,不启动它的进程。 */
        fun available(authority: String): Boolean = true

        companion object {
            /** 提供方不在时,按系统卡片引擎的做法转给流体云服务代发。 */
            private const val UMS_ASSISTANT = "com.oplus.pantanal.ums.cardservice.provider.AssistantProvider"

            fun system(context: Context) = object : ProviderCaller {
                override fun call(authority: String, method: String, extras: Bundle): Boolean {
                    val resolver = context.contentResolver
                    val client = resolver.acquireUnstableContentProviderClient(authority)
                    return if (client != null) {
                        try { client.call(method, null, extras); true } finally { client.close() }
                    } else {
                        val assistant = resolver.acquireUnstableContentProviderClient(UMS_ASSISTANT) ?: return false
                        try {
                            assistant.call("clickCallBusinessProvider", null, Bundle(extras).apply { putString("uri", authority); putString("method", method) })
                            true
                        } finally { assistant.close() }
                    }
                }

                override fun available(authority: String): Boolean {
                    val pm = context.packageManager
                    return pm.resolveContentProvider(authority, 0) != null || pm.resolveContentProvider(UMS_ASSISTANT, 0) != null
                }
            }
        }
    }

    /** 提前取得锁屏查询服务，首次按钮操作不等待服务初始化；锁屏状态仍在每次操作时读取。 */
    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    private val records = linkedMapOf<String, CloudRecord>()
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "OfficialCloudAction").apply { isDaemon = true } }

    /** 最近一次按下的元素(这件事 + 动作)这一下打开了页面没有;星河岛按成之后立即来问,决定卡片是否立即收起(现行规则「展开卡片」)。 */
    @Volatile private var lastPress: Pair<String, Boolean>? = null

    fun openedPage(key: String, action: String): Boolean = lastPress?.let { it.first == "$key|$action" && it.second } == true

    /** 记下这件事当前的内容 */
    fun update(record: CloudRecord) {
        records[record.snapshot.key] = record
    }

    fun perform(key: String, action: String): Boolean {
        lastPress = null
        val outcome = performOrReason(key, action)
        // 每一下按键的结果写进岛的日志(正式包也写),看得出按了没反应是哪一步没过
        com.astraflow.fluidcloud.hook.Log.i(TAG, "action $action: ${outcome ?: "sent"}")
        return outcome == null
    }

    /** 执行元素登记的动作;送达返回 null,没送达返回原因。 */
    private fun performOrReason(key: String, id: String): String? {
        if (!OfficialCloudSource.islandActive) return "island not in charge"
        val record = records[key] ?: return "event gone"
        val name = id.removePrefix(EventActions.PAGE_PREFIX)
        val snapshot = record.snapshot
        val locked = keyguard.isDeviceLocked
        snapshot.notification?.let { sbn ->
            if (locked && !lockScreenNode(record.page, name)) return "device locked"
            return if (OfficialDirectPage.perform(sbn, name)) { lastPress = "$key|$id" to true; null } else "intent failed"
        }
        record.views?.let { views ->
            // 整张卡片的点击锁屏时照样执行(打开的页面由系统要求先解锁),其余元素锁屏时不能点
            if (locked && name != record.page.cardClick) return "device locked"
            val action = views.clicks[name] ?: return "no action"
            val way = executeWay(record, action) ?: return "action failed"
            lastPress = "$key|$id" to (way.type == CloudTemplate.TYPE_DEEPLINK)
            return null
        }
        val template = OfficialCloudTemplates.current(context, snapshot.serviceId, snapshot.templateVersion, snapshot.pageId) ?: return "template gone"
        // 整张卡片的点击锁屏时照样执行(打开的页面由系统要求先解锁),其余只执行写明锁屏显示的元素的动作
        if (locked && name != template.cardClick && !template.lockScreenAction(name, snapshot.data)) return "device locked"
        val action = template.action(name, snapshot.data) ?: return "no action"
        val way = executeWay(record, action) ?: return "action failed"
        lastPress = "$key|$id" to (way.type == CloudTemplate.TYPE_DEEPLINK)
        return null
    }

    /** 动作 [name] 是整张卡片的点击,或挂在写明锁屏显示的元素上 */
    private fun lockScreenNode(page: com.astraisland.events.PageNode, name: String): Boolean {
        if (name == page.cardClick) return true
        fun visit(node: com.astraisland.events.PageNode): Boolean =
            (node.events.containsValue(name) && node.attrs["lockscreenCapsuleShow"] == "true") || node.children.any(::visit)
        return visit(page)
    }

    /** 按顺序试登记的几种做法,返回实际用上的那一种;都不行返回 null。 */
    private fun executeWay(record: CloudRecord, action: CloudAction): CloudAction? =
        (listOf(action) + action.alternatives).firstOrNull { way -> executeOne(record, way) }

    private fun executeOne(record: CloudRecord, action: CloudAction): Boolean = when (action.type) {
        CloudTemplate.TYPE_MESSAGE -> callProvider(action.uri, action.method, extras(record, action))
        CloudTemplate.TYPE_DEEPLINK -> startPage(record, action)
        else -> false
    }

    /** 与系统卡片引擎发出的参数一致:登记的参数一律按文字,加上显示位置、服务号与模板版本。 */
    fun extras(record: CloudRecord, action: CloudAction): Bundle = Bundle().apply {
        action.params.forEach { (name, value) -> putString(name, value) }
        if (!containsKey("cardEntrance")) putString("cardEntrance", "STATUS_BAR")
        putString("seedling_preserve_key_service_id", record.snapshot.serviceId)
        putInt("seedling_preserve_key_version_code", record.snapshot.templateVersion.coerceIn(0, Int.MAX_VALUE.toLong()).toInt())
    }

    /**
     * 提供方装在手机上(只查登记,不启动它)就把请求交给后台线程发出,立即返回,不让系统界面停下来等。发出去没被接受的写进日志。
     */
    private fun callProvider(authority: String, method: String, extras: Bundle): Boolean {
        if (authority.isBlank() || method.isBlank() || !caller.available(authority)) return false
        worker.execute {
            if (!caller.call(authority, method, extras)) com.astraflow.fluidcloud.hook.Log.w(TAG, "provider did not take $method")
        }
        return true
    }

    /**
     * 打开登记的页面(nativeapp://动作、网页或页面地址),写法与系统卡片引擎一致;页面打不开时按登记的应用打开首页。
     * 只打开对外开放的页面,或这张卡片来源应用自己的页面。查到能打开的页面就把建请求与打开交给后台线程,立即返回(现行规则「系统事件接入」)。
     */
    private fun startPage(record: CloudRecord, action: CloudAction): Boolean {
        val pm = context.packageManager
        val uri = action.uri.trim()
        val intent = when {
            uri.startsWith(NATIVE_APP) -> Intent(uri.removePrefix(NATIVE_APP)).also { intent ->
                action.data?.takeIf { it.isNotBlank() }?.let { intent.data = Uri.parse(it) }
            }
            uri.startsWith("http://") || uri.startsWith("https://") -> Intent(Intent.ACTION_VIEW, Uri.parse(uri))
            uri.isNotBlank() -> Intent.parseUri(uri, Intent.URI_INTENT_SCHEME)
            else -> null
        }?.takeUnless { it.action == Intent.ACTION_VIEW && it.data == null }?.apply {
            action.params.forEach { (name, value) -> putExtra(name, value) }
            addFlags(if (action.flag == "FLAG_ACTIVITY_NEW_TASK") Intent.FLAG_ACTIVITY_NEW_TASK else Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val owners = setOfNotNull(action.packageName, record.snapshot.packageName, record.snapshot.hostPackage.takeIf { it.isNotBlank() })
        val allowed = intent?.takeIf { candidate ->
            // 与打开页面时的匹配一致(只认带默认类别的页面),不另找别的入口
            val info = pm.resolveActivity(candidate, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo ?: return@takeIf false
            info.exported || info.packageName in owners
        } ?: action.packageName?.let { pm.getLaunchIntentForPackage(it)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) } ?: return false
        val request = record.snapshot.key.hashCode()
        worker.execute { send(PendingIntent.getActivity(context, request, allowed, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)) }
        return true
    }

    /** 用户按下以后发出:允许目标应用响应这一下打开画面 */
    private fun send(pending: PendingIntent) {
        val launch = android.app.ActivityOptions.makeBasic()
            .setPendingIntentBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle()
        pending.send(context, 0, null, null, null, null, launch)
    }

    /** 检查用:等已经交给后台线程的请求都发出去。 */
    fun awaitRequests() {
        worker.submit {}.get(2, java.util.concurrent.TimeUnit.SECONDS)
    }

    fun retain(keys: Set<String>) {
        records.keys.retainAll(keys)
    }

    fun close() {
        retain(emptySet())
        worker.shutdownNow()
    }

    private companion object {
        const val TAG = "OfficialCloud"
        const val NATIVE_APP = "nativeapp://"
    }
}
