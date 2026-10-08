package com.astraflow.fluidcloud.app

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import com.astraflow.fluidcloud.BuildConfig
import com.astraisland.events.AdapterApp
import java.security.MessageDigest

/**
 * 星流向插件取状态、请它重新开启(约定见 [AdapterApp]);只回答官方星流:签名与插件相同,
 * 或是星流的正式签名(自行编译的插件也回答官方星流)。
 * 取状态时插件进程可能刚被这一问叫起来,模块框架稍后才交来服务:最多等 [ENABLED_WAIT_MS],
 * 插件没在 LSPosed 里启用时就等满这么久。
 */
class StateProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (!fromHost()) return null
        return when (method) {
            AdapterApp.METHOD_STATE -> Bundle().apply {
                putLong(AdapterApp.KEY_VERSION_CODE, BuildConfig.VERSION_CODE.toLong())
                putBoolean(AdapterApp.KEY_ENABLED, PluginSettings.awaitEnabled(ENABLED_WAIT_MS))
            }
            AdapterApp.METHOD_RESUME -> Bundle().apply {
                val done = PluginSettings.awaitEnabled(ENABLED_WAIT_MS) && PluginSettings.requestResume()
                putString(AdapterApp.KEY_RESULT, if (done) AdapterApp.RESULT_APPLIED else AdapterApp.RESULT_UNAVAILABLE)
            }
            else -> null
        }
    }

    private fun fromHost(): Boolean {
        val context = context ?: return false
        val caller = runCatching { callingPackage }.getOrNull() ?: return false
        if (caller != AdapterApp.HOST_PACKAGE) return false
        val packageManager = context.packageManager
        return packageManager.checkSignatures(caller, context.packageName) == PackageManager.SIGNATURE_MATCH ||
            AdapterApp.HOST_CERT_SHA256 in signers(packageManager, caller)
    }

    /** 安装包签名证书的 SHA-256(小写十六进制);读不到时为空 */
    private fun signers(packageManager: PackageManager, pkg: String): Set<String> = runCatching {
        packageManager.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners.orEmpty()
            .mapTo(HashSet()) { signer -> MessageDigest.getInstance("SHA-256").digest(signer.toByteArray()).joinToString("") { "%02x".format(it) } }
    }.getOrDefault(emptySet())

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    private companion object {
        const val ENABLED_WAIT_MS = 2_000L
    }
}
