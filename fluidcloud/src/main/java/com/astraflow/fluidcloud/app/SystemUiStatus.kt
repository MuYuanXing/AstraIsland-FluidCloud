package com.astraflow.fluidcloud.app

import android.app.BroadcastOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.astraflow.fluidcloud.hook.Log

/**
 * 系统界面里的插件把状态报给插件应用(见 [PluginStatus]):相同的不重复发;插件应用打开设置页时请它再报一遍。
 * 只报状态,不带系统流体云的内容。都在系统界面主线程上调用。
 */
internal object SystemUiStatus {
    private const val TAG = "Status"
    private val last = LinkedHashMap<String, String>()
    private var context: Context? = null

    fun start(context: Context) {
        this.context = context
        runCatching {
            context.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    if (intent.action == PluginStatus.ACTION_REQUEST) last.toMap().forEach { (key, value) -> send(key, value) }
                }
            }, IntentFilter(PluginStatus.ACTION_REQUEST), PluginStatus.PERMISSION, null, Context.RECEIVER_EXPORTED)
        }.onFailure { Log.e(TAG, "status request receiver not registered", it) }
    }

    fun report(key: String, value: String) {
        if (last[key] == value) return
        last[key] = value
        send(key, value)
    }

    private fun send(key: String, value: String) {
        val ctx = context ?: return
        runCatching {
            val intent = Intent(PluginStatus.ACTION_STATUS)
                .setPackage(PluginStatus.PLUGIN_PACKAGE)
                // 插件应用装好后可能还没打开过,照样送到
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                .putExtra(PluginStatus.EXTRA_KEY, key)
                .putExtra(PluginStatus.EXTRA_VALUE, value)
            // 带上发送方身份,插件应用据此只认系统界面发来的
            ctx.sendBroadcast(intent, null, BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle())
        }.onFailure { Log.w(TAG, "status $key not sent: ${it.message}") }
    }
}
