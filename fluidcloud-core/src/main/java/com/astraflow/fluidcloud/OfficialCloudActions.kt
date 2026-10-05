package com.astraflow.fluidcloud

import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.astraisland.events.EventKind
import com.astraisland.events.EventActions
import java.util.concurrent.Executors

/**
 * 系统卡片按钮与点卡片的执行(现行规则「系统事件接入」):按卡片模板登记的动作,发出与系统卡片完全相同的请求——
 * message 交给提供方(系统界面进程里的 ContentResolver.call,系统的卡片引擎也是这样发的),deeplink 打开登记的页面;
 * 旧格式卡片用通知自带的操作;通话沿用电话服务。数据不写盘,记录随事件结束撤销。
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
                        val fallback = resolver.acquireUnstableContentProviderClient(UMS_ASSISTANT) ?: return false
                        try {
                            fallback.call("clickCallBusinessProvider", null, Bundle(extras).apply { putString("uri", authority); putString("method", method) })
                            true
                        } finally { fallback.close() }
                    }
                }

                override fun available(authority: String): Boolean = runCatching {
                    val pm = context.packageManager
                    pm.resolveContentProvider(authority, 0) != null || pm.resolveContentProvider(UMS_ASSISTANT, 0) != null
                }.getOrDefault(true)
            }
        }
    }

    private val records = linkedMapOf<String, CloudRecord>()
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "OfficialCloudAction").apply { isDaemon = true } }

    /** 最近一次按下的按钮(这件事 + 按钮编号)这一下打开了页面没有;星河岛按成之后立即来问,决定卡片是否立即收起(现行规则「展开卡片」)。 */
    @Volatile private var lastPress: Pair<String, Boolean>? = null

    /** 这颗按钮刚才这一下打开了页面没有:系统原按钮对着的是页面,或登记的几种做法里实际用上的是打开页面;交给提供方处理的不算(星河岛不知道提供方会不会打开应用)。 */
    fun openedPage(key: String, buttonId: String): Boolean = lastPress?.let { it.first == "$key|$buttonId" && it.second } == true

    /** 记下这件事当前的内容;返回点卡片能不能打开东西。 */
    fun update(record: CloudRecord): Boolean {
        records[record.snapshot.key] = record
        return openable(record)
    }

    fun openable(record: CloudRecord): Boolean = record.content.cardAction != null ||
        record.snapshot.notification?.notification?.contentIntent != null ||
        record.snapshot.nativeCard != null || record.snapshot.nativeSeedling != null

    fun perform(key: String, buttonId: String?): Boolean {
        lastPress = null
        val outcome = runCatching { performOrReason(key, buttonId) }.getOrElse { "failed: ${it.javaClass.simpleName}" }
        // 每一下按键的结果写进岛的日志(正式包也写),看得出按了没反应是哪一步没过
        com.astraflow.fluidcloud.hook.Log.i(TAG, "button ${buttonId ?: "card"}: ${outcome ?: "sent"}")
        return outcome == null
    }

    /** 执行按钮或点卡片;送达返回 null,没送达返回原因。 */
    private fun performOrReason(key: String, buttonId: String?): String? {
        if (!OfficialCloudSource.islandActive) return "island not in charge"
        val record = records[key] ?: return "event gone"
        // 点卡片:锁屏时也照样执行系统卡片自己的点击动作,和星河岛的通知卡片点下去一样,不先看锁屏(现行规则「系统事件接入」)
        if (buttonId == null) return if (open(record)) null else "open failed"
        val call = OfficialCloudServices.kind(record.snapshot) == EventKind.CALL
        val callControl = call && OfficialCloudCallButtons.isCallControl(buttonId, record.content.buttons)
        // 来电多半在锁屏,通话接听/挂断/扬声器不拦。其它按钮只在解锁后执行(锁屏时卡片上本来就不画这些按钮)。
        // 不看系统胶囊此刻显不显示:岛显示着这件事、系统胶囊被别的事挤掉或被岛藏起时,按钮照样要能用
        // (原来要求系统胶囊正在显示,音乐在放时系统只显示一颗胶囊,其余事件的按钮一按就提示失败)
        if (!callControl && context.getSystemService(KeyguardManager::class.java)?.isDeviceLocked != false) return "device locked"
        if (call) return if (performCall(record, buttonId)) null else "call control failed"
        val button = find(record, buttonId) ?: return "button gone"
        button.pending?.let {
            if (!send(it)) return "intent failed"
            lastPress = "$key|$buttonId" to opensPage(it)
            return null
        }
        val action = button.action ?: return "no action"
        val way = executeWay(record, action) ?: return "action failed"
        lastPress = "$key|$buttonId" to (way.type == CloudTemplate.TYPE_DEEPLINK)
        return null
    }

    /** 岛上的按钮编号:模板按钮按原编号;暂停、继续、停止与录制的切换按用途找(这几个编号由卡片排法决定)。 */
    private fun find(record: CloudRecord, id: String): CloudButton? {
        val buttons = record.content.buttons
        buttons.firstOrNull { it.id == id }?.let { return it }
        fun role(r: CloudRole) = buttons.firstOrNull { it.role == r }
        return when (id) {
            EventActions.PAUSE -> role(CloudRole.PAUSE)
            EventActions.RESUME -> role(CloudRole.RESUME)
            EventActions.STOP -> role(CloudRole.STOP)
            EventActions.TOGGLE -> role(CloudRole.PAUSE) ?: role(CloudRole.RESUME)
            else -> null
        }
    }

    /** 点卡片:系统卡片自己的点击动作;旧格式卡片用通知的打开目标;都没有时交给系统卡片引擎处理整张卡的点击。 */
    private fun open(record: CloudRecord): Boolean {
        record.content.cardAction?.let { return execute(record, it) }
        record.snapshot.notification?.notification?.contentIntent?.let { return send(it) }
        val target = record.snapshot.nativeCard ?: record.snapshot.nativeSeedling ?: return false
        val args = linkedMapOf<String, Any>("key_card_size" to 9)
        if (OfficialCloudReflection.call(target, "performCardClickAction", args) == true) return true
        args["key_card_size"] = 7
        return OfficialCloudReflection.call(target, "performCardClickAction", args) == true
    }

    /** 按登记的动作执行;登记了几种做法时按顺序试,前一种打不开再用下一种(与系统卡片引擎一致)。 */
    fun execute(record: CloudRecord, action: CloudAction): Boolean = executeWay(record, action) != null

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
     * 提供方装在手机上(只查登记,不启动它)就把请求交给后台线程发出,立即返回,不让系统界面停下来等
     * (原来最多等 0.4 秒,按钮按下去整个系统界面卡一下)。发出去没被接受的写进日志。
     */
    private fun callProvider(authority: String, method: String, extras: Bundle): Boolean {
        if (authority.isBlank() || method.isBlank()) return false
        if (!caller.available(authority)) return false
        return runCatching {
            worker.execute {
                val taken = runCatching { caller.call(authority, method, extras) }.getOrDefault(false)
                if (!taken) com.astraflow.fluidcloud.hook.Log.w(TAG, "provider did not take $method")
            }
            true
        }.getOrDefault(false)
    }

    /**
     * 打开登记的页面(nativeapp://动作、网页或页面地址),写法与系统卡片引擎一致;页面打不开时按登记的应用打开首页。
     * 只打开对外开放的页面,或这张卡片来源应用自己的页面。这里只查有没有能打开的页面(和交给提供方时只查登记一样),
     * 查到了就把建请求与打开交给后台线程,立即返回,系统界面不停下来等(现行规则「系统事件接入」);没打开的写进日志。
     */
    private fun startPage(record: CloudRecord, action: CloudAction): Boolean {
        val pm = context.packageManager
        val uri = action.uri.trim()
        val intent = when {
            uri.startsWith(NATIVE_APP) -> Intent(uri.removePrefix(NATIVE_APP)).also { intent ->
                action.data?.takeIf { it.isNotBlank() }?.let { intent.data = Uri.parse(it) }
            }
            uri.startsWith("http://") || uri.startsWith("https://") -> Intent(Intent.ACTION_VIEW, Uri.parse(uri))
            uri.isNotBlank() -> runCatching { Intent.parseUri(uri, Intent.URI_INTENT_SCHEME) }.getOrNull()
            else -> null
        }?.takeUnless { it.action == Intent.ACTION_VIEW && it.data == null }?.apply {
            action.params.forEach { (name, value) -> putExtra(name, value) }
            addFlags(if (action.flag == "FLAG_ACTIVITY_NEW_TASK") Intent.FLAG_ACTIVITY_NEW_TASK else Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val owners = setOfNotNull(action.packageName, record.snapshot.packageName, record.snapshot.hostPackage.takeIf { it.isNotBlank() })
        val allowed = intent?.takeIf { candidate ->
            // 与打开页面时的匹配一致(只认带默认类别的页面),不另找别的入口
            val info = runCatching { pm.resolveActivity(candidate, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo }.getOrNull() ?: return@takeIf false
            info.exported || info.packageName in owners
        } ?: action.packageName?.let { pm.getLaunchIntentForPackage(it)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) } ?: return false
        val request = record.snapshot.key.hashCode()
        return runCatching {
            worker.execute {
                val opened = runCatching {
                    send(PendingIntent.getActivity(context, request, allowed, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                }.getOrDefault(false)
                if (!opened) com.astraflow.fluidcloud.hook.Log.w(TAG, "page did not open: ${allowed.component?.packageName ?: allowed.`package` ?: allowed.action}")
            }
            true
        }.getOrDefault(false)
    }

    /** 用户按下以后发出:允许目标应用响应这一下打开画面 */
    private fun send(pending: PendingIntent): Boolean = runCatching {
        @Suppress("DEPRECATION")
        val launch = android.app.ActivityOptions.makeBasic()
            .setPendingIntentBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle()
        pending.send(context, 0, null, null, null, null, launch)
        true
    }.getOrDefault(false)

    /** 这个目标是不是打开页面(按下以后才问,问一次要找系统一趟);问不到时按不是页面处理 */
    private fun opensPage(pending: PendingIntent): Boolean = runCatching { pending.isActivity }.getOrDefault(false)

    /**
     * 通话:接听、拒接、挂断先交给电话服务;扬声器与其它按钮按系统通话卡片登记的动作执行(现行开发规范「内容接入与操作」),
     * 都不行时才点系统原来的按钮。
     */
    private fun performCall(record: CloudRecord, buttonId: String): Boolean {
        val button = record.content.buttons.firstOrNull { it.id == buttonId }
        if (button == null) {
            if (buttonId in setOf("answer", "decline", "hangup")) {
                return OfficialCloudCalls.perform(context, buttonId, record.snapshot.packageName)
            }
            return false
        }
        button.pending?.let { return send(it) }
        val telecom = OfficialCloudCalls
        val role = OfficialCloudCallButtons.roleOf(button.label)
        // 接听、拒接、挂断先交给电话服务(开发规范「内容接入与操作」);电话服务接受请求就返回。
        // 卡片包名经常不是拨号应用，不能拿它把点击挡回去。
        val direct = when (role) {
            OfficialCloudCallButtons.Role.ANSWER -> telecom.performSystem(context, "answer")
            OfficialCloudCallButtons.Role.DECLINE -> telecom.performSystem(context, "decline")
            OfficialCloudCallButtons.Role.HANGUP -> telecom.performSystem(context, "hangup")
            OfficialCloudCallButtons.Role.SPEAKER, OfficialCloudCallButtons.Role.OTHER -> false
        }
        if (direct) return true
        // 扬声器与其它按钮、以及电话服务不接受的接听挂断:按系统通话卡片登记的动作交给来电界面处理(与系统胶囊上按下一样)
        button.action?.let { action -> if (execute(record, action)) { if (role == OfficialCloudCallButtons.Role.SPEAKER) refreshSoon(); return true } }
        // 没有登记动作的扬声器:直接切换通话声音的去向
        if (role == OfficialCloudCallButtons.Role.SPEAKER && telecom.setSystemSpeakerphone(context, !telecom.speakerphoneOn(context))) { refreshSoon(); return true }
        // 最后才点系统原来的按钮
        val view = button.target?.get()?.takeIf { it.isEnabled && it.hasOnClickListeners() } ?: return false
        return runCatching { view.performClick() }.getOrDefault(false)
    }

    /** 扬声器切换后稍等再刷新一次卡片,让按钮的开关颜色跟上。 */
    private fun refreshSoon() {
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ OfficialCloudSource.refresh() }, SPEAKER_REFRESH_MS)
    }

    /** 检查用:等已经交给后台线程的请求都发出去。 */
    fun awaitRequests() {
        runCatching { worker.submit {}.get(2, java.util.concurrent.TimeUnit.SECONDS) }
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
        const val SPEAKER_REFRESH_MS = 500L
    }
}
