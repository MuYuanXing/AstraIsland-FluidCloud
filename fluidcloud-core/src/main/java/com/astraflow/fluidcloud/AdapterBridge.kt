package com.astraflow.fluidcloud

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.astraflow.fluidcloud.hook.Log
import com.astraisland.events.EventBridge
import com.astraisland.events.SystemEvent
import com.astraisland.events.SystemEventCodec
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
    /** 见面的结果:connected、absent(系统界面里还没有星河岛),或 refused:原因(交给插件的页面) */
    private var onStatus: (String) -> Unit = {}

    private val endpoint = Function<Bundle, Bundle?> { request -> runCatching { handle(request) }.onFailure { Log.e(TAG, "request failed", it) }.getOrNull() }

    fun start(packageName: String, versionCode: Long, onAccepted: () -> Unit, onState: (Bundle) -> Unit, onStatus: (String) -> Unit = {}) {
        this.packageName = packageName
        this.versionCode = versionCode
        this.onAccepted = onAccepted
        this.onState = onState
        this.onStatus = onStatus
        EventBridge.publish(EventBridge.ADAPTER, endpoint)
        hello()
    }

    fun stop() {
        EventBridge.withdraw(EventBridge.ADAPTER, endpoint)
        accepted = false
        paused = false
        onAccepted = {}
        onState = {}
        onStatus = {}
    }

    /** 星河岛收下了接入件 */
    fun connected(): Boolean = accepted

    @Volatile private var paused = false

    /**
     * 插件因为多次出错自动暂停(见 hook.CrashGuard):不读系统流体云,只向星河岛打招呼说明已暂停,星河岛不收它,
     * 把原因报给星流;[onStatus] 收到 refused:paused。
     */
    fun announcePaused(packageName: String, versionCode: Long, onStatus: (String) -> Unit) {
        paused = true
        start(packageName, versionCode, onAccepted = {}, onState = {}, onStatus = onStatus)
    }

    /** 向星河岛打招呼;星河岛不在时等它到了再打(它到了会发 [EventBridge.OP_READY]) */
    private fun hello() {
        val reply = EventBridge.send(EventBridge.ISLAND, EventBridge.request(EventBridge.OP_HELLO) {
            putString(EventBridge.KEY_PACKAGE, packageName)
            putLong(EventBridge.KEY_VERSION_CODE, versionCode)
            if (paused) putBoolean(EventBridge.KEY_PAUSED, true)
        })
        if (reply == null) { main.post { runCatching { onStatus(STATUS_ABSENT) } }; return }
        accepted = !paused && reply.getBoolean(EventBridge.KEY_ACCEPTED, false)
        val status = if (accepted) STATUS_CONNECTED else STATUS_REFUSED + reply.getString(EventBridge.KEY_REASON).orEmpty()
        if (accepted) main.post { runCatching(onAccepted) }
        else Log.w(TAG, "island refused: ${reply.getString(EventBridge.KEY_REASON)}")
        main.post { runCatching { onStatus(status) } }
    }

    private fun handle(request: Bundle): Bundle? = when (request.getString(EventBridge.KEY_OP)) {
        EventBridge.OP_READY -> { hello(); null }
        // 星河岛在系统界面主线程上报来时当场处理
        EventBridge.OP_STATE -> { if (accepted) { if (Looper.myLooper() == Looper.getMainLooper()) onState(request) else main.post { onState(request) } }; null }
        EventBridge.OP_PERFORM -> Bundle().apply {
            putBoolean(EventBridge.KEY_DONE, accepted && OfficialCloudSource.perform(request.getString(EventBridge.KEY_IDENTITY).orEmpty(),
                request.getString(EventBridge.KEY_ACTION)))
        }
        EventBridge.OP_OPENED -> Bundle().apply {
            putBoolean(EventBridge.KEY_DONE, accepted && OfficialCloudSource.openedPage(request.getString(EventBridge.KEY_IDENTITY).orEmpty(),
                request.getString(EventBridge.KEY_ACTION).orEmpty()))
        }
        else -> null
    }

    const val STATUS_CONNECTED = "connected"
    const val STATUS_ABSENT = "absent"
    const val STATUS_REFUSED = "refused:"

    /** 把全部事件和接入件的设置交给星河岛 */
    fun push(events: List<SystemEvent>, settings: FluidCloudSettings) {
        if (!accepted) return
        EventBridge.send(EventBridge.ISLAND, EventBridge.request(EventBridge.OP_EVENTS) {
            putBundle(EventBridge.KEY_EVENTS, SystemEventCodec.encode(events))
            putBundle(EventBridge.KEY_SETTINGS, settings.toBundle())
        })
    }
}
