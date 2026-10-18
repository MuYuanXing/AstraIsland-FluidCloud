package com.astraflow.fluidcloud

import com.astraflow.fluidcloud.hook.HookHelper
import java.util.concurrent.ConcurrentHashMap

/**
 * 系统界面自己做的提示(手电筒、勿扰、响铃模式):内容由系统界面直接交给卡片引擎,流体云记录和卡片引擎上都读不到。
 * 在系统界面生成提示内容的那一处旁读一份(只读,不改),岛照卡片说明文件和这份内容排卡片
 * (现行规则「系统事件接入」)。读不到这一处时不影响其它系统卡片。
 */
object OfficialCloudSystemTips {
    private const val TAG = "OfficialCloud"
    private const val TIP = "com.oplus.systemui.seedlingservice.tip.NormalTip"
    private const val UI_DATA = "com.oplus.systemui.seedlingservice.datamanager.UiData"
    private const val MAX_TEXT = 64 * 1024
    /** 服务号 → 系统界面最近一次为它生成的提示内容(一层 JSON 文字) */
    private val latest = ConcurrentHashMap<String, String>()
    @Volatile private var installed = false

    fun install(loader: ClassLoader?) {
        if (installed || loader == null) return
        installed = true
        runCatching {
            val data = loader.loadClass(UI_DATA)
            val method = loader.loadClass(TIP).declaredMethods.single {
                it.name == "createOrUpdateCard" && it.parameterTypes.size == 2 && it.parameterTypes[0] == data
            }
            HookHelper.hookMethodBefore(method) { chain ->
                runCatching {
                    val service = OfficialCloudReflection.call(chain.thisObject, "getServiceId") as? String
                    val json = OfficialCloudReflection.call(chain.getArg(0), "build")?.toString()
                    if (!service.isNullOrBlank() && json != null && json.length <= MAX_TEXT) latest[service] = json
                }
                null
            } ?: error("hook unavailable")
            com.astraflow.fluidcloud.hook.Log.i(TAG, "system tip content reader installed")
        }.onFailure { com.astraflow.fluidcloud.hook.Log.w(TAG, "system tip content reader unavailable", it) }
    }

    /** 系统界面最近一次为这个服务生成的提示内容;没有时为 null。 */
    fun data(serviceId: String): ByteArray? = latest[serviceId]?.toByteArray(Charsets.UTF_8)

    fun recordForTest(serviceId: String, json: String) { latest[serviceId] = json }

    fun clear() = latest.clear()
}
