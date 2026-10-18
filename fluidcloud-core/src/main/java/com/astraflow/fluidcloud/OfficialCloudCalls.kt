package com.astraflow.fluidcloud

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.telecom.TelecomManager
import com.astraflow.fluidcloud.hook.Log

/**
 * 系统通话卡片的接听、拒接、挂断与免提,在系统界面进程里交给系统电话服务执行(系统界面自带电话服务要的权限)。
 * 系统卡片按钮自己的打开目标优先;没有时才走这里。只替系统拨号链路发力:来源必须是系统通话组件或默认拨号应用。
 */
object OfficialCloudCalls {
    /** 检查用的替身入口;真机走 [SystemOperations]。 */
    interface Operations {
        fun answer(context: Context, source: String): Boolean
        fun end(context: Context, source: String): Boolean
    }

    @Volatile var operations: Operations = SystemOperations

    fun reset() { operations = SystemOperations; pendingSpeaker = null }

    fun perform(context: Context, actionId: String, source: String): Boolean = when (actionId) {
        "answer" -> operations.answer(context, source)
        "decline", "hangup" -> operations.end(context, source)
        else -> false
    }

    /** 系统通话卡片上的接听、拒接、挂断(卡片自己的包名经常不是拨号应用,按系统通话组件交给电话服务) */
    fun performSystem(context: Context, actionId: String): Boolean = perform(context, actionId, SYSTEM_CALL)

    /** 当前是否走扬声器;刚提交、设备还没回报时,先用刚提交的状态 */
    fun speakerphoneOn(context: Context): Boolean {
        val live = runCatching { context.getSystemService(AudioManager::class.java)?.let(::readSpeaker) == true }.getOrDefault(false)
        val pending = pendingSpeaker
        if (pending == null || pending == live) {
            pendingSpeaker = null
            return live
        }
        return pending
    }

    /** 把系统通话切到扬声器或听筒;请求被接受就返回,不等开关已经翻转 */
    fun setSystemSpeakerphone(context: Context, on: Boolean): Boolean = runCatching {
        context.getSystemService(TelecomManager::class.java) ?: error("telecom unavailable")
        val audio = context.getSystemService(AudioManager::class.java) ?: error("audio unavailable")
        val accepted = if (on) {
            val speaker = audio.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                ?: error("speaker unavailable")
            audio.setCommunicationDevice(speaker)
        } else {
            audio.clearCommunicationDevice()
            true
        }
        if (accepted) pendingSpeaker = on
        accepted
    }.onFailure { Log.w(TAG, "speakerphone failed: ${it.message}") }.getOrDefault(false)

    @Volatile private var pendingSpeaker: Boolean? = null

    private fun readSpeaker(audio: AudioManager): Boolean {
        val current = audio.communicationDevice
        @Suppress("DEPRECATION")
        return if (current != null) current.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else audio.isSpeakerphoneOn
    }

    /** 允许代行通话控制的来源:系统通话组件,或默认拨号应用本身 */
    private fun telephonySource(telecom: TelecomManager, source: String): Boolean {
        val dialer = runCatching { @Suppress("DEPRECATION") telecom.defaultDialerPackage }.getOrNull()
        return dialer == null || dialer == source || source in TELEPHONY_SOURCES
    }

    private const val TAG = "OfficialCloudCalls"
    private const val SYSTEM_CALL = "com.android.incallui"

    /** 来电界面、电信服务、拨号与通讯录 */
    private val TELEPHONY_SOURCES = setOf(
        "com.android.incallui", "com.oplus.incallui", "com.coloros.incallui",
        "com.android.server.telecom", "com.android.phone",
        "com.android.dialer", "com.google.android.dialer", "com.oplus.dialer", "com.coloros.dialer",
        "com.android.contacts", "com.oplus.contacts", "com.coloros.contacts",
    )

    @android.annotation.SuppressLint("MissingPermission")
    private object SystemOperations : Operations {
        override fun answer(context: Context, source: String): Boolean = byTelecom(context, source) {
            @Suppress("DEPRECATION") acceptRingingCall()
            true
        }

        override fun end(context: Context, source: String): Boolean = byTelecom(context, source) {
            @Suppress("DEPRECATION") endCall()
        }

        private fun <T> byTelecom(context: Context, source: String, block: TelecomManager.() -> T): Boolean = runCatching {
            val telecom = context.getSystemService(TelecomManager::class.java) ?: error("telecom unavailable")
            check(telephonySource(telecom, source)) { "not a telephony source: $source" }
            block(telecom) == true
        }.onFailure { Log.w(TAG, "telecom action failed: ${it.message}") }.getOrDefault(false)
    }
}
