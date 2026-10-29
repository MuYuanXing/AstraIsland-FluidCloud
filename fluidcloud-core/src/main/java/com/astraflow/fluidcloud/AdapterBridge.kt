package com.astraflow.fluidcloud

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.astraflow.fluidcloud.hook.Log
import com.astraisland.events.EventBridge
import com.astraisland.events.SystemEvent
import com.astraisland.events.SystemEventCodec
import java.util.UUID
import java.util.function.Function

/**
 * 接入件这一边的见面入口(见 [EventBridge]):向星河岛打招呼,星河岛收下后把全部事件和接入件的设置交过去;
 * 星河岛把接手情况、用户点的按钮交回来。星河岛不在、没收下时什么都不交,系统流体云照常由系统显示。
 */
object AdapterBridge {
    private const val TAG = "AdapterBridge"
    private val main by lazy { Handler(Looper.getMainLooper()) }
    @Volatile private var accepted = false
    @Volatile private var packageName = ""
    @Volatile private var versionCode = 0L
    /** 星河岛收下后要做的事(把现有的事件交过去) */
    private var onAccepted: () -> Unit = {}
    /** 星河岛报来接手情况(在主线程上处理) */
    private var onState: (Bundle) -> Unit = {}
    /** 见面的结果:connected、testing(按机主允许的本机测试收下)、absent(系统界面里还没有星河岛),或 refused:原因(交给插件的页面) */
    private var onStatus: (String) -> Unit = {}
    /** 本次启动的会话标识:每次 [start] 换新的,不靠序号跨启动比大小。 */
    @Volatile private var eventSession = ""
    /** 已经执行过的接手决定的序号 */
    private var latestStateEpoch = -1L
    /** 星河岛收下的是哪个会话 */
    private var latestEventSession: String? = null

    private val endpoint = Function<Bundle, Bundle?> { request -> runCatching { handle(request) }.onFailure { Log.e(TAG, "request failed", it) }.getOrNull() }

    fun start(packageName: String, versionCode: Long, onAccepted: () -> Unit, onState: (Bundle) -> Unit, onStatus: (String) -> Unit = {}) {
        // 状态整次在主线程改:后台线程只投递,不在半路改一半。
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { start(packageName, versionCode, onAccepted, onState, onStatus) }
            return
        }
        // 换绑定或暂停时先用旧回调交还,不能等一个可能不再被接受的新会话。
        if (accepted) release(this.onState)
        this.packageName = packageName
        this.versionCode = versionCode
        this.onAccepted = onAccepted
        this.onState = onState
        this.onStatus = onStatus
        // 旧会话交还完才换新会话:上一场的迟到事件与回执不能落进这一场。
        eventSession = UUID.randomUUID().toString()
        latestStateEpoch = -1L
        latestEventSession = null
        EventBridge.publish(EventBridge.ADAPTER, endpoint)
        hello()
    }

    fun stop() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { stop() }; return }
        // 先同步交还原生显示,再撤入口;不能在停止时留下已隐藏的胶囊。
        if (accepted) release(onState)
        EventBridge.withdraw(EventBridge.ADAPTER, endpoint)
        accepted = false
        eventSession = ""
        paused = false
        onAccepted = {}
        onState = {}
        onStatus = {}
        latestStateEpoch = -1L
        latestEventSession = null
    }

    private fun release(callback: (Bundle) -> Unit) {
        accepted = false
        // 捕获这一场要交还的会话与回调:投递到主线程后不能再读可能已经换掉的字段。
        val oldSession = eventSession
        val action = Runnable {
            // 先让星河岛撤下旧卡片并失效该会话,再恢复原生胶囊;后续旧状态不能重新确认。
            EventBridge.send(EventBridge.ISLAND, EventBridge.request(EventBridge.OP_DISCONNECT) {
                putString(EventBridge.KEY_SESSION, oldSession)
            })
            // 空接手决定是无需会话的释放:系统胶囊与卡片照常由系统显示。
            runCatching { callback(Bundle()) }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) action.run() else main.post(action)
    }

    /** 星河岛收下了接入件 */
    fun connected(): Boolean = accepted

    @Volatile private var paused = false

    /**
     * 插件因为多次出错自动暂停(见 hook.CrashGuard):不读系统流体云,只向星河岛打招呼说明已暂停,星河岛不收它,
     * 把原因报给星流;[onStatus] 收到 refused:paused。
     */
    fun announcePaused(packageName: String, versionCode: Long, onStatus: (String) -> Unit) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { announcePaused(packageName, versionCode, onStatus) }
            return
        }
        paused = true
        start(packageName, versionCode, onAccepted = {}, onState = {}, onStatus = onStatus)
    }

    /** 向星河岛打招呼;星河岛不在时等它到了再打(它到了会发 [EventBridge.OP_READY]) */
    private fun hello() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { hello() }; return }
        val session = eventSession.takeIf { it.isNotEmpty() } ?: return
        val acceptedCallback = onAccepted
        val stateCallback = onState
        val statusCallback = onStatus
        fun postCurrent(action: () -> Unit) { main.post { if (eventSession == session) runCatching(action) } }
        val reply = EventBridge.send(EventBridge.ISLAND, EventBridge.request(EventBridge.OP_HELLO) {
            putString(EventBridge.KEY_PACKAGE, packageName)
            putString(EventBridge.KEY_SESSION, eventSession)
            putLong(EventBridge.KEY_VERSION_CODE, versionCode)
            if (paused) putBoolean(EventBridge.KEY_PAUSED, true)
        })
        if (eventSession != session) return
        if (reply == null) {
            if (accepted) release(stateCallback)
            postCurrent { statusCallback(STATUS_ABSENT) }
            return
        }
        // 同版本也必须回显本次会话;否则不能把没有授权来源的事件当成已连接。
        accepted = !paused && reply.getBoolean(EventBridge.KEY_ACCEPTED, false) &&
            reply.getString(EventBridge.KEY_SESSION) == eventSession
        val status = when {
            !accepted -> STATUS_REFUSED + reply.getString(EventBridge.KEY_REASON).orEmpty()
            reply.getBoolean(EventBridge.KEY_LOCAL_TEST) -> STATUS_TESTING
            else -> STATUS_CONNECTED
        }
        // 收下即算这一场已经生效:即使还没交过事件,之后按本场会话发来的接手决定与释放也要能执行完。
        if (accepted) latestEventSession = eventSession
        if (accepted) postCurrent { if (accepted) acceptedCallback() }
        else {
            Log.w(TAG, "island refused: ${reply.getString(EventBridge.KEY_REASON)}")
            // 不收下的答复本身即是释放:不能依赖另一条可能被旧会话拒绝的通知。
            postCurrent { if (!accepted) stateCallback(Bundle()) }
        }
        postCurrent { statusCallback(status) }
    }

    private fun handle(request: Bundle): Bundle? = when (request.getString(EventBridge.KEY_OP)) {
        EventBridge.OP_READY -> { hello(); null }
        // 星河岛在系统界面主线程上报来时当场处理
        EventBridge.OP_STATE -> {
            // 显示交接只在主线程同步执行;异步投递不能作为已经执行的答复。
            val epoch = request.getLong(EventBridge.KEY_EPOCH, -1L)
            val session = request.getString(EventBridge.KEY_SESSION)
            // 旧会话、旧批次都不能再放行内容或回执,只有递增的当前一批算数。
            if (accepted && epoch >= 0 && session == latestEventSession && Looper.myLooper() == Looper.getMainLooper() &&
                epoch > latestStateEpoch) {
                latestStateEpoch = epoch
                // 先完成原生恢复,再使当前会话失效;新握手仍可显式重新接管。
                onState(request)
                if (session != eventSession || epoch != latestStateEpoch) null else {
                    if (request.getBoolean(EventBridge.KEY_RELEASE)) accepted = false
                    Bundle().apply {
                        putString(EventBridge.KEY_SESSION, session)
                        putLong(EventBridge.KEY_EPOCH, epoch)
                        putStringArray(EventBridge.KEY_CONFIRMED, OfficialCloudSource.confirmedIdentities().toTypedArray())
                    }
                }
            } else null
        }
        EventBridge.OP_PERFORM -> Bundle().apply {
            // 同版本的动作必须明确属于当前会话;缺失、陌生或旧会话一律拒绝。
            val fresh = request.getString(EventBridge.KEY_SESSION) == eventSession
            val page = request.getString(EventBridge.KEY_TEMPLATE_PAGE)
            val action = request.getString(EventBridge.KEY_TEMPLATE_ACTION)
            val resolved = templateAction(request)
            putBoolean(EventBridge.KEY_DONE, fresh && accepted && if (page != null && action != null) {
                resolved != null && OfficialCloudSource.performTemplate(request.getString(EventBridge.KEY_IDENTITY).orEmpty(), page, action, resolved)
            } else OfficialCloudSource.perform(request.getString(EventBridge.KEY_IDENTITY).orEmpty(), request.getString(EventBridge.KEY_ACTION)))
        }
        EventBridge.OP_OPENED -> Bundle().apply {
            val fresh = request.getString(EventBridge.KEY_SESSION) == eventSession
            putBoolean(EventBridge.KEY_DONE, fresh && accepted && OfficialCloudSource.openedPage(request.getString(EventBridge.KEY_IDENTITY).orEmpty(),
                request.getString(EventBridge.KEY_ACTION).orEmpty()))
        }
        else -> null
    }

    /**
     * 星河岛代入好的模板动作:几种做法按顺序排,第一种不行再试下一种(与系统卡片引擎一致)。
     * 类型只认 message 与 deeplink,message 必须有提供方地址;都不合规时为空,这一下不执行。
     */
    fun templateAction(request: Bundle): CloudAction? {
        val ways = request.getParcelableArrayList(EventBridge.KEY_TEMPLATE_RESOLVED, Bundle::class.java).orEmpty().mapNotNull { way ->
            val type = way.getString(EventBridge.ACTION_TYPE).orEmpty()
            val uri = way.getString(EventBridge.ACTION_URI).orEmpty()
            if (type != CloudTemplate.TYPE_MESSAGE && type != CloudTemplate.TYPE_DEEPLINK) return@mapNotNull null
            if (type == CloudTemplate.TYPE_MESSAGE && uri.isBlank()) return@mapNotNull null
            val params = way.getBundle(EventBridge.ACTION_PARAMS)?.let { bundle ->
                bundle.keySet().sorted().associateWith { bundle.getString(it).orEmpty() }
            }.orEmpty()
            CloudAction(type, uri, way.getString(EventBridge.ACTION_METHOD).orEmpty(), params,
                way.getString(EventBridge.ACTION_DATA), way.getString(EventBridge.ACTION_PACKAGE), way.getString(EventBridge.ACTION_FLAG))
        }
        return ways.firstOrNull()?.let { if (ways.size > 1) it.copy(alternatives = ways.drop(1)) else it }
    }

    const val STATUS_CONNECTED = "connected"
    /** 签名与星流不同(例如自行编译的安装包),星河岛按机主在星流里允许的本机测试收下了插件 */
    const val STATUS_TESTING = "testing"
    const val STATUS_ABSENT = "absent"
    const val STATUS_REFUSED = "refused:"

    /** 把全部事件和接入件的设置交给星河岛 */
    fun push(events: List<SystemEvent>, settings: FluidCloudSettings, alive: Set<String>) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            val session = eventSession
            val snapshot = events.toList()
            val living = alive.toSet()
            main.post { if (session == eventSession) push(snapshot, settings, living) }
            return
        }
        if (!accepted) return
        // 主线程上同步交出:星河岛按到达顺序处理,只认这一场会话
        latestEventSession = eventSession
        EventBridge.send(EventBridge.ISLAND, EventBridge.request(EventBridge.OP_EVENTS) {
            putString(EventBridge.KEY_SESSION, eventSession)
            putBundle(EventBridge.KEY_EVENTS, SystemEventCodec.encode(events))
            putBundle(EventBridge.KEY_SETTINGS, settings.toBundle())
            putStringArray(EventBridge.KEY_ALIVE, alive.toTypedArray())
        })
    }
}
