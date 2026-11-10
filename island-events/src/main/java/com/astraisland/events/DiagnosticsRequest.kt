package com.astraisland.events

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import java.io.File

/**
 * 内测反馈包(开发手册「排查」的「内测反馈包」):星流请系统界面里的星河岛和接入件,各自把此刻的运行状态写成一份文字。
 *
 * 星流发 [ACTION],带 [EXTRA_TOKEN];接收方要求发送方持有星流的 signature 级权限 [PERMISSION],别的应用发不进来。
 * 收到的一方在系统界面设备加密区的缓存目录 [dir] 下写 `<token>-<part>.txt`:先写临时文件再改名,星流用 root
 * 等到文件出现再读走、删掉。不回广播,所以没有可以冒充的回执;系统界面里没有这一方(没加载、旧版本)时文件不会出现,
 * 星流照实写进反馈包。
 */
object DiagnosticsRequest {
    const val ACTION = "${AdapterApp.HOST_PACKAGE}.island.internal.DUMP_DIAGNOSTICS"

    /** 与星流「重新启用星河岛」同一个 signature 级权限:只有与星流同签名的应用持有 */
    const val PERMISSION = "${AdapterApp.HOST_PACKAGE}.island.permission.RESET_BREAKER"
    const val EXTRA_TOKEN = "token"

    const val PART_ISLAND = "island"
    const val PART_PLUGIN = "plugin"

    /**
     * 构建标识:构建脚本随每次打包生成(不改版本号),app 与插件的 manifest 各写一份 metadata
     * ([METADATA_BUILD_ID]),运行状态正文里各写一行([BUILD_ID_LINE]),反馈包按标识核对
     * 「系统界面里正在运行的」与「当前安装的」是不是同一次构建。没有标识时不判相同。
     */
    const val METADATA_BUILD_ID = "com.astraflow.diagnostics.BUILD_ID"

    /** 运行状态正文里构建标识行的前缀:buildId=<标识> */
    const val BUILD_ID_LINE = "buildId="

    /** 系统界面设备加密区缓存目录下放这两份的子目录名(星流用 root 在系统界面的数据目录下找它) */
    const val DIR = "astraflow-diagnostics"

    /** 旧文件留多久:星流读到一半退出时留下的,下一次收到请求时删掉 */
    private const val STALE_MS = 10 * 60_000L
    private val TOKEN = Regex("[0-9A-Za-z_-]{8,64}")

    /** 写文件的目录:系统界面设备加密区的缓存目录,星河岛与接入件都在系统界面进程里,两边算出的是同一处 */
    fun dir(context: Context): File = File(context.createDeviceProtectedStorageContext().cacheDir, DIR)

    const val COMPLETE = "complete"
    const val FAILED = "failed"

    fun fileName(token: String, part: String) = "$token-$part.txt"
    fun statusName(token: String, part: String) = "$token-$part.status"
    fun resourceName(token: String, part: String) = "$token-$part"

    /**
     * 在系统界面里登记接收:收到请求时先在 [handler] 所在线程上调 [collect] 抓下此刻的状态(只取引用、复制可变的集合),
     * 再在一条后台线程上排成文字、写文件,不占画面那一帧。排文字时出错,把出错经过写进这份文件,不让后台线程的异常
     * 带崩系统界面,也不悄悄缺一份。
     */
    fun register(context: Context, handler: Handler, part: String, collect: () -> ((File) -> String)) {
        context.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val token = intent.getStringExtra(EXTRA_TOKEN)?.takeIf(TOKEN::matches) ?: return
                val format = try { collect() } catch (failure: Exception) {
                    { _: File -> throw failure }
                }
                Thread({ write(context, token, part, format) }, "AstraDiagnostics-$part").start()
            }
        }, IntentFilter(ACTION), PERMISSION, handler, Context.RECEIVER_EXPORTED)
    }

    private fun write(context: Context, token: String, part: String, format: (File) -> String) {
        try {
            val dir = dir(context).apply { check(isDirectory || mkdirs()) { "无法创建诊断目录" } }
            val now = System.currentTimeMillis()
            dir.listFiles()?.filter { now - it.lastModified() > STALE_MS }?.forEach { it.deleteRecursively() }
            val resources = File(dir, resourceName(token, part)).apply { check(mkdirs()) { "无法创建资源目录" } }
            var status = COMPLETE
            val text = try {
                format(resources)
            } catch (failure: Exception) {
                status = "$FAILED\n${failure.stackTraceToString()}"
                "生成这一部分时出错：\n" + failure.stackTraceToString()
            }
            val errors = File(resources, "errors.txt")
            if (errors.isFile) status = "$FAILED\n${errors.readText()}"
            val target = File(dir, fileName(token, part))
            val temp = File(dir, target.name + ".tmp")
            temp.writeText(text)
            File(dir, statusName(token, part)).writeText(status)
            check(temp.renameTo(target)) { "无法完成诊断文件" }
        } catch (failure: Exception) {
            val detail = "诊断文件写入失败：" + failure.stackTraceToString()
            runCatching {
                val directory = dir(context)
                File(directory, statusName(token, part)).writeText(FAILED + "\n" + detail)
                File(directory, fileName(token, part)).writeText(detail)
            }.onFailure { android.util.Log.e("AstraDiagnostics", "诊断失败经过无法写入: $part", it) }
            android.util.Log.e("AstraDiagnostics", "诊断文件写入失败: $part", failure)
        }
    }

    private val SECRET_KEY = Regex(
        "token|password|passwd|secret|session|vault|hmac|cookie|auth|credential|device_?id|device_?key|signature|private",
        RegexOption.IGNORE_CASE,
    )

    /**
     * 一项设置写进反馈包时的样子:登录凭据、设备身份这类只写有没有和多长,整张图片的数据只写多长,其余原样。
     * 星河岛(系统界面读到的设置)和星流(App 自己的设置)写设置时都用它。
     */
    fun settingText(key: String, value: Any?): String {
        val text = when (value) {
            null -> return "null"
            is Set<*> -> value.joinToString(",", "[", "]")
            else -> value.toString()
        }
        return when {
            key.contains("base64", ignoreCase = true) -> "<图片数据，${text.length} 字>"
            SECRET_KEY.containsMatchIn(key) -> if (text.isEmpty()) "<空>" else "<已隐藏，${text.length} 字>"
            else -> text
        }
    }
}
