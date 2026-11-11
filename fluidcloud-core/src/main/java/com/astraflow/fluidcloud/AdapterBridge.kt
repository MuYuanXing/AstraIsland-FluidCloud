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
    /** 见面的结果:connected、testing(按机主允许的本机测试收下)、absent(系统界面里还没有星河岛),或 refused:原因(交给插件的页面) */
    private var onStatus: (String) -> Unit = {}
    /** 最近一次交给星河岛的接入件设置:停止时用它送「全部事件为空」,岛那边的四类开关不变 */
    private var lastSettings: Bundle? = null

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
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { stop() }; return }
        val wasAccepted = accepted
        // 交还用的回调先取出来:下面会把字段清掉,清完就不能再读。
        val callback = onState
        // 先使本次握手失效:停止途中星河岛发回的接手决定不能再改这边的显示。
        accepted = false
        if (wasAccepted) {
            // 停止时先交还(现行规则「系统事件接入」):把「全部事件为空」交给星河岛,岛同步撤下这些事件;
            // 接入件随后恢复原生显示,最后才清掉回调。
            lastSettings?.let { settings ->
                EventBridge.send(EventBridge.ISLAND, EventBridge.request(EventBridge.OP_EVENTS) {
                    putBundle(EventBridge.KEY_EVENTS, SystemEventCodec.encode(emptyList()))
                    putBundle(EventBridge.KEY_SETTINGS, Bundle(settings))
                    putStringArray(EventBridge.KEY_ALIVE, emptyArray())
                    putStringArray(EventBridge.KEY_ENDED, emptyArray())
                })
            }
            // 空接手决定是无需事件身份的释放:系统胶囊与卡片按系统自己的名单恢复显示。
            runCatching { callback(Bundle()) }.onFailure { Log.e(TAG, "release on stop failed", it) }
        }
        EventBridge.withdraw(EventBridge.ADAPTER, endpoint)
        lastSettings = null
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
        val wasAccepted = accepted
        accepted = !paused && reply.getBoolean(EventBridge.KEY_ACCEPTED, false)
        val status = when {
            !accepted -> STATUS_REFUSED + reply.getString(EventBridge.KEY_REASON).orEmpty()
            reply.getBoolean(EventBridge.KEY_LOCAL_TEST) -> STATUS_TESTING
            else -> STATUS_CONNECTED
        }
        if (accepted) main.post { runCatching(onAccepted) }
        else {
            Log.w(TAG, "island refused: ${reply.getString(EventBridge.KEY_REASON)}")
            // 收下过、现在不再收下(机主停止了本机测试):当作星河岛什么都不接手,系统胶囊全部交还系统
            if (wasAccepted) main.post { runCatching { onState(Bundle()) } }
        }
        main.post { runCatching { onStatus(status) } }
    }

    private fun handle(request: Bundle): Bundle? = when (request.getString(EventBridge.KEY_OP)) {
        EventBridge.OP_READY -> { hello(); null }
        // 星河岛在系统界面主线程上报来时当场处理
        EventBridge.OP_STATE -> {
            // 星河岛在系统界面主线程上报来,当场处理。星河岛不再收下插件时报的「不在管事」不带亮完的提示这一组:
            // 先让系统胶囊全部交还系统,再使本次握手失效(现行规则「系统事件接入」)。
            if (accepted) {
                onState(request)
                if (!request.getBoolean(EventBridge.KEY_ACTIVE) && !request.containsKey(EventBridge.KEY_SPENT)) accepted = false
            }
            null
        }
        EventBridge.OP_PERFORM -> Bundle().apply {
            putBoolean(EventBridge.KEY_DONE, accepted && OfficialCloudSource.perform(request.getString(EventBridge.KEY_IDENTITY)!!,
                request.getString(EventBridge.KEY_ACTION)!!))
        }
        EventBridge.OP_OPENED -> Bundle().apply {
            putBoolean(EventBridge.KEY_DONE, accepted && OfficialCloudSource.openedPage(request.getString(EventBridge.KEY_IDENTITY)!!,
                request.getString(EventBridge.KEY_ACTION)!!))
        }
        else -> null
    }

    const val STATUS_CONNECTED = "connected"
    /** 签名与星流不同(例如自行编译的安装包),星河岛按机主在星流里允许的本机测试收下了插件 */
    const val STATUS_TESTING = "testing"
    const val STATUS_ABSENT = "absent"
    const val STATUS_REFUSED = "refused:"

    /** 把全部事件和接入件的设置交给星河岛 */
    fun push(events: List<SystemEvent>, settings: FluidCloudSettings, alive: Set<String>, ended: Set<String>) {
        if (!accepted) return
        val settingsBundle = settings.toBundle()
        // 停止时交还的是「全部事件为空」,接入件的设置照旧:岛上的四类开关不能因为一次停止被改掉。
        lastSettings = Bundle(settingsBundle)
        EventBridge.send(EventBridge.ISLAND, EventBridge.request(EventBridge.OP_EVENTS) {
            putBundle(EventBridge.KEY_EVENTS, SystemEventCodec.encode(events))
            putBundle(EventBridge.KEY_SETTINGS, settingsBundle)
            putStringArray(EventBridge.KEY_ALIVE, alive.toTypedArray())
            putStringArray(EventBridge.KEY_ENDED, ended.toTypedArray())
        })
    }
}
