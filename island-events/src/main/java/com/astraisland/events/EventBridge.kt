package com.astraisland.events

import android.os.Bundle
import java.util.function.Function

/**
 * 星河岛与接入件在系统界面进程里见面、交换的约定(contracts/island-system-events.v1.json 的 bridge 一节)。
 * 两边的类互不相认,各在 System.getProperties() 里放一个处理包裹的函数(系统自带的 Function,收发 Bundle)。
 * 接入件到了或星河岛到了,接入件向星河岛打招呼(带包名、版本);星河岛核对通过才收它的事件。
 *
 * - 接入件 → 星河岛:[OP_HELLO] 打招呼,[OP_EVENTS] 全部事件和接入件的设置。
 * - 星河岛 → 接入件:[OP_READY] 星河岛到了,[OP_STATE] 星河岛接手了哪些事,[OP_PERFORM] 点按钮或卡片,
 *   [OP_OPENED] 刚才那一下打开了页面没有。
 */
object EventBridge {
    /** 见面与交换的版本;两边不一样时不交换 */
    const val VERSION = 1

    /** System.getProperties() 里放两边入口的键 */
    const val ISLAND = "com.astraisland.events.island"
    const val ADAPTER = "com.astraisland.events.adapter"

    const val KEY_OP = "op"
    const val KEY_VERSION = "version"

    // ── 接入件 → 星河岛 ──
    /**
     * 打招呼:[KEY_PACKAGE]、[KEY_VERSION_CODE]、[KEY_VERSION];回 [KEY_ACCEPTED] 和不收时的 [KEY_REASON]。
     * 接入件因为多次出错自动暂停时带 [KEY_PAUSED],星河岛不收它,原因写 [REASON_PAUSED]。
     */
    const val OP_HELLO = "hello"
    const val KEY_PACKAGE = "package"
    const val KEY_VERSION_CODE = "versionCode"
    const val KEY_PAUSED = "paused"
    const val REASON_PAUSED = "paused"
    const val KEY_ACCEPTED = "accepted"
    const val KEY_REASON = "reason"
    /** 全部事件([KEY_EVENTS],编码见 SystemEventCodec)和接入件的设置([KEY_SETTINGS]) */
    const val OP_EVENTS = "events"
    const val KEY_EVENTS = "events"
    const val KEY_SETTINGS = "settings"

    // ── 星河岛 → 接入件 ──
    /** 星河岛到了(接入件先到时由星河岛发):接入件收到后打招呼 */
    const val OP_READY = "ready"
    /**
     * 星河岛接手了哪些事(都是事件身份):[KEY_HANDLED] 接手的(上了星河岛,或星河岛上有同一件事的通知卡、音乐、耳机卡),
     * [KEY_SPENT] 只亮一下、已经亮完的,[KEY_ALWAYS] 一律藏起的(充电胶囊、电池提示),[KEY_PENDING] 刚送到、还在等内容的;
     * [KEY_ACTIVE] 星河岛开着且在管事,[KEY_YIELD_ALLOWED] 星河岛此刻能露面,[KEY_SHOWN_KINDS] 星河岛此刻显示哪些种类的事
     * (事件种类编号;刚送到、还没读出内容的新事件,系统胶囊按它先定让不让位)。
     */
    const val OP_STATE = "state"
    const val KEY_HANDLED = "handled"
    const val KEY_SPENT = "spent"
    const val KEY_ALWAYS = "always"
    const val KEY_PENDING = "pending"
    const val KEY_ACTIVE = "active"
    const val KEY_YIELD_ALLOWED = "yieldAllowed"
    const val KEY_SHOWN_KINDS = "shownKinds"
    /** 点按钮或卡片:[KEY_IDENTITY]、[KEY_ACTION](按钮编号,点卡片时不填);回 [KEY_DONE] */
    const val OP_PERFORM = "perform"
    /** 刚才按的那颗按钮这一下打开了页面没有:[KEY_IDENTITY]、[KEY_ACTION];回 [KEY_DONE] */
    const val OP_OPENED = "opened"
    const val KEY_IDENTITY = "identity"
    const val KEY_ACTION = "action"
    const val KEY_DONE = "done"

    // ── 接入件的设置(包在 [KEY_SETTINGS] 里) ──
    /** 接入开关 */
    const val SETTING_ACCESS = "access"
    /** 四类内容的开关:来电与通话、计时与闹钟、实时活动、系统状态 */
    const val SETTING_CALL = "call"
    const val SETTING_TIMER = "timer"
    const val SETTING_LIVE = "live"
    const val SETTING_STATUS = "status"
    /** 系统状态里哪几样能作为主岛显示:录制与隐私、充电、音频设备、系统开关 */
    const val SETTING_MAIN_RECORDING = "mainRecording"
    const val SETTING_MAIN_CHARGING = "mainCharging"
    const val SETTING_MAIN_HEADSET = "mainHeadset"
    const val SETTING_MAIN_SWITCH = "mainSwitch"

    fun request(op: String, fill: Bundle.() -> Unit = {}): Bundle = Bundle().apply { putString(KEY_OP, op); putInt(KEY_VERSION, VERSION); fill() }

    fun publish(side: String, endpoint: Function<Bundle, Bundle?>) {
        System.getProperties()[side] = endpoint
    }

    fun withdraw(side: String, endpoint: Function<Bundle, Bundle?>) {
        System.getProperties().remove(side, endpoint)
    }

    /** 另一边的入口;还没到时为空 */
    @Suppress("UNCHECKED_CAST")
    fun find(side: String): Function<Bundle, Bundle?>? = System.getProperties()[side] as? Function<Bundle, Bundle?>

    /** 交给另一边;另一边不在或出错时为空(一边出错不连累另一边) */
    fun send(side: String, request: Bundle): Bundle? = runCatching { find(side)?.apply(request) }.getOrNull()
}
