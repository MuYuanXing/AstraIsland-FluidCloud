package com.astraflow.fluidcloud

import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.MethodData

/** 按实际接口一次选定整套读法;新接口不完整时不混用旧入口。 */
object OfficialCloudPlatform {
    const val C17_ENTRY = "com.oplus.systemui.plugins.livealert.lib.EntryType"
    enum class Api { LEGACY, C17 }

    /** 方法的结构摘要;不依赖被系统缩短的类名和方法名。 */
    data class MethodShape(
        val ref: OfficialCloudHook.MethodRef,
        val returnType: String,
        val calls: Set<OfficialCloudHook.MethodRef> = emptySet(),
        val strings: Set<String> = emptySet(),
    )

    fun select(c17: Boolean, modern: () -> OfficialCloudHook.PluginScan,
               legacy: () -> OfficialCloudHook.PluginScan): OfficialCloudHook.PluginScan =
        if (c17) modern() else legacy()

    /** C17 将旧的日志文字移到延迟日志对象,按记录管理、显示位置和列表操作识别三个数据入口。 */
    fun scanC17(bridge: DexKitBridge, verified: Boolean, interceptors: List<String>,
                observers: List<String>): OfficialCloudHook.PluginScan {
        val refresh = bridge.findMethod {
            matcher {
                usingStrings("notifyEntryDataChanged skip due to released")
                paramTypes = listOf(C17_ENTRY, "boolean")
                returnType = "void"
            }
        }.singleOrNull()
        val owner = refresh?.let { method ->
            bridge.findClass { matcher { addFieldForType(C17_ENTRY); addFieldForType(method.className) } }.singleOrNull()
        }
        val listeners = c17Listeners(owner?.methods?.filter {
            it.returnTypeName == "void" && it.paramTypeNames == listOf("java.util.List")
        }?.map { it.shape() }.orEmpty())
        // C17 的胶囊和卡片共用一个合并后的回调,但仍需核对两处显示用途。
        val displays = refresh?.invokes?.distinctBy { it.descriptor }?.filter {
            it.returnTypeName == "void" && it.paramTypeNames == listOf("java.util.ArrayList")
        }?.map { it.shape() }.orEmpty()
        return OfficialCloudHook.PluginScan(listeners, verified, interceptors, observers,
            c17Renderers(displays), refresh?.ref(), api = Api.C17)
    }

    fun c17Listeners(methods: List<MethodShape>): Map<CloudChange, OfficialCloudHook.MethodRef?> {
        fun MethodShape.calls(name: String, param: String) = calls.any {
            it.className in setOf("java.util.List", "java.util.ArrayList") && it.name == name && it.params == listOf(param)
        }
        fun kind(method: MethodShape): CloudChange? {
            if (method.returnType != "void" || method.ref.params != listOf("java.util.List")) return null
            val remove = method.calls("removeAll", "java.util.Collection")
            val replace = method.calls("addAll", "java.util.Collection")
            val add = method.calls("add", "java.lang.Object")
            return when {
                remove && replace && !add -> CloudChange.REPLACE
                add && !remove && !replace && method.calls("contains", "java.lang.Object") -> CloudChange.POST
                remove && !replace && !add -> CloudChange.REMOVE
                else -> null
            }
        }
        return CloudChange.entries.associateWith { change -> methods.filter { kind(it) == change }.singleOrNull()?.ref }
    }

    fun c17Renderers(methods: List<MethodShape>): List<OfficialCloudHook.MethodRef> {
        val merged = methods.singleOrNull {
            it.returnType == "void" && it.ref.params == listOf("java.util.ArrayList") &&
                it.strings.containsAll(listOf("CapsulesRepository", "CardsRepository"))
        } ?: return emptyList()
        return List(2) { merged.ref }
    }

    private fun MethodData.ref() = OfficialCloudHook.MethodRef(className, name, paramTypeNames)
    private fun MethodData.shape() = MethodShape(ref(), returnTypeName, invokes.mapTo(linkedSetOf()) { it.ref() }, usingStrings.toSet())
}
