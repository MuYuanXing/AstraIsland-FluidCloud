package com.astraflow.fluidcloud

import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Modifier

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
        // 同属 C17 的构建可以合并或拆分显示回调,也可以通过接口分发;只检查本次刷新实际调用的入口。
        val displays = refresh?.invokes?.distinctBy { it.descriptor }?.filter {
            it.returnTypeName == "void" && listParameter(it.paramTypeNames)
        }?.flatMap { method ->
            val implementations = if (Modifier.isAbstract(method.modifiers)) {
                bridge.findClass { matcher { addInterface(method.className) } }.flatMap { type ->
                    type.methods.filter { it.name == method.name && it.paramTypeNames == method.paramTypeNames && it.returnTypeName == "void" }
                }
            } else emptyList()
            listOf(method) + implementations
        }?.distinctBy { it.descriptor }?.map { it.shape() }.orEmpty()
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
        val eligible = methods.filter { it.returnType == "void" && listParameter(it.ref.params) }.distinctBy { it.ref }
        val capsule = eligible.singleOrNull { "CapsulesRepository" in it.strings } ?: return emptyList()
        val card = eligible.singleOrNull { "CardsRepository" in it.strings } ?: return emptyList()
        return listOf(capsule.ref, card.ref)
    }

    private fun listParameter(params: List<String>) = params == listOf("java.util.List") || params == listOf("java.util.ArrayList")

    private fun MethodData.ref() = OfficialCloudHook.MethodRef(className, name, paramTypeNames)
    private fun MethodData.shape() = MethodShape(ref(), returnTypeName, invokes.mapTo(linkedSetOf()) { it.ref() }, usingStrings.toSet())
}
