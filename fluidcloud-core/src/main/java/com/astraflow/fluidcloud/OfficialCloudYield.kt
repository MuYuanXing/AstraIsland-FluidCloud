package com.astraflow.fluidcloud

import android.os.Handler
import android.os.Looper
import com.astraflow.fluidcloud.hook.HookHelper
import com.astraflow.fluidcloud.hook.Log
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** 系统显示部分接收当前列表时执行归属;原始记录、内容、显示设置均不修改。重算成功后才确认交接。 */
object OfficialCloudYield {
    const val STATUS_BAR = 8
    const val INTERCEPTOR = "pantanal.app.UIDataInterceptor"
    const val OBSERVER = "pantanal.decision.DecisionObserver"
    const val OPTIONS_EXTRA = "oplusLiveAlertOptions"
    enum class Status(val report: String) {
        UNKNOWN(""), PENDING("pending"), AVAILABLE("available"), PARTIAL("partial"), UNAVAILABLE("unavailable")
    }
    @Volatile var status = Status.UNKNOWN
        private set
    @Volatile private var confirmed: Set<String> = emptySet()
    @Volatile private var effective: Set<String> = emptySet()
    private class Display(val method: Method, val entry: Any) {
        var owner = WeakReference<Any>(null)
    }
    private val displays = linkedMapOf<ClassLoader, Display>()
    private val observedHolders = WeakHashMap<Any, Unit>()
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val refreshing = ThreadLocal.withInitial { false }
    private val failed = linkedSetOf<String>()
    private val noted = linkedSetOf<String>()
    private var deliveries = 0
    private var applied: Set<String>? = null
    private var refreshPosted = false
    private var testDisplay: ((Set<String>) -> Set<String>)? = null

    /** 两个显示入口必须全部找到;合并在同一方法中的入口只安装一次。 */
    fun installDisplay(loader: ClassLoader, renderers: List<Method>, refresh: Method?,
                       unhooked: (Method) -> Boolean): List<XposedInterface.HookHandle> {
        if (renderers.size != 2 || refresh == null || refresh.parameterCount != 2 ||
            refresh.parameterTypes[1] != Boolean::class.javaPrimitiveType) {
            markUnavailable(); return emptyList()
        }
        val entry = refresh.parameterTypes[0].enumConstants?.firstOrNull { (it as? Enum<*>)?.name == "ENTRY_STATUS_BAR" }
        if (entry == null) { markUnavailable(); return emptyList() }
        val handles = mutableListOf<XposedInterface.HookHandle>()
        for (method in renderers.distinct()) {
            if (!unhooked(method)) continue
            val handle = HookHelper.hookArguments(method) { chain ->
                chain.thisObject?.let(::observeDisplay)
                val input = chain.getArg(0) as? List<*> ?: error("native display list missing")
                arrayOf(filter(input))
            }
            if (handle == null) {
                handles.forEach(HookHelper::unhook)
                markUnavailable(); return emptyList()
            }
            handles += handle
        }
        synchronized(displays) { displays[loader] = Display(refresh.apply { isAccessible = true }, entry) }
        synchronized(observedHolders) { observedHolders.keys.toList() }.forEach(::observeDisplay)
        applied = null
        updateStatus(Status.PENDING)
        requestRefresh()
        return handles
    }

    /** 从系统已经创建的监听者取得显示管理对象,不读取静态字段触发系统的提前初始化。 */
    fun observeDisplay(holder: Any) {
        synchronized(observedHolders) { observedHolders[holder] = Unit }
        val targets = synchronized(displays) { displays.values.toList() }
        for (target in targets) {
            val type = target.method.declaringClass
            val direct = if (type.isInstance(holder)) holder else OfficialCloudReflection.fieldOfType(holder, type.name)?.takeIf(type::isInstance)
            val owner = direct ?: OfficialCloudReflection.fields(holder.javaClass).firstNotNullOfOrNull { field ->
                val value = runCatching { field.isAccessible = true; field.get(holder) }.getOrNull()
                value?.let { OfficialCloudReflection.fieldOfType(it, type.name) }?.takeIf(type::isInstance)
            } ?: continue
            if (target.owner.get() !== owner) {
                target.owner = WeakReference(owner)
                applied = null
                if (refreshing.get() != true) requestRefresh()
            }
        }
    }

    /** 最新系统显示列表的副本。只删除本次明确交给星河岛的状态栏事件。 */
    fun filter(input: List<*>): ArrayList<Any?> {
        val output = ArrayList<Any?>(input.size)
        val available = synchronized(displays) { displays.isNotEmpty() } || testDisplay != null
        if (refreshing.get() == true) deliveries++
        for (model in input) {
            if (model == null || !available) { output += model; continue }
            val key = OfficialCloudReflection.nativeKey(model)
            if (key == null) {
                reject(effective, model)
                output += model; continue
            }
            if (key !in effective) { output += model; continue }
            val entrance = displayEntrance(model)
            if (entrance == "ENTRY_STATUS_BAR") continue
            if (entrance == null) {
                reject(setOf(key), model)
            }
            output += model
        }
        if (refreshing.get() != true && applied == null) requestRefresh()
        return output
    }

    private fun reject(keys: Set<String>, model: Any) {
        if (keys.isEmpty()) return
        synchronized(failed) { failed += keys }
        confirmed = confirmed - keys
        applied = null
        val type = model.javaClass.name
        if (synchronized(noted) { noted.size < 64 && noted.add(type) })
            Log.w("OfficialCloud", "native display identity or entrance unreadable on $type; its events remain with the system")
    }

    /** 显示记录自身或其唯一的原始记录持有显示位置;不根据字段短名识别。 */
    private fun displayEntrance(model: Any): String? {
        OfficialCloudReflection.entranceName(model)?.let { return it }
        val key = OfficialCloudReflection.nativeKey(model)
        val raw = OfficialCloudReflection.fields(model.javaClass).mapNotNull { field ->
            runCatching { field.isAccessible = true; field.get(model) }.getOrNull()?.takeIf {
                it !== model && OfficialCloudReflection.nativeKey(it) == key && OfficialCloudReflection.call(it, "getSourceType") != null
            }
        }.singleOrNull() ?: return null
        return OfficialCloudReflection.entranceName(raw)
    }

    fun confirmedKeys(): Set<String> = confirmed
    fun yieldedCount(): Int = confirmed.size

    fun refresh() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (refreshing.get() == true) return
        val desired = OfficialCloudSource.requestedNativeKeys()
        if (applied == desired) return
        effective = desired
        testDisplay?.let { display ->
            runCatching { display(desired) }.onSuccess {
                confirmed = it.intersect(desired); effective = confirmed
                applied = if (confirmed == desired) desired else null
            }.onFailure { confirmed = emptySet(); effective = emptySet(); applied = null }
            return
        }
        val targets = synchronized(displays) { displays.values.distinctBy { it.method } }
        if (targets.isEmpty()) { confirmed = emptySet(); effective = emptySet(); return }
        if (targets.any { it.owner.get() == null }) {
            confirmed = emptySet(); effective = emptySet(); updateStatus(Status.PENDING); return
        }
        deliveries = 0
        synchronized(failed) { failed.clear() }
        refreshing.set(true)
        fun dispatch(): Boolean =
            targets.map { target ->
                runCatching {
                    val before = deliveries
                    val owner = target.owner.get() ?: error("native display owner not ready")
                    target.method.invoke(owner, target.entry, false)
                    check(deliveries - before >= 2) { "native capsule and card displays have not both delivered" }
                }.onFailure { Log.e("OfficialCloud", "native display handover failed", it) }.isSuccess
            }.all { it }
        val success = try { dispatch() } finally { refreshing.set(false) }
        if (success) {
            confirmed = desired - synchronized(failed) { failed.toSet() }
            applied = if (confirmed == desired) desired else null
            updateStatus(if (confirmed == desired) Status.AVAILABLE else Status.PARTIAL)
        } else {
            confirmed = emptySet(); applied = null
            updateStatus(if (success) Status.PENDING else Status.UNAVAILABLE)
        }
        if (confirmed != desired) {
            // 交接未确认的事件由系统显示;撤销本次试行过滤,再按当前真实记录恢复显示。
            effective = confirmed
            refreshing.set(true)
            try { dispatch() } finally { refreshing.set(false) }
        }
    }

    fun retire(loader: ClassLoader) {
        synchronized(displays) { displays.remove(loader) }
        applied = null
        requestRefresh()
    }

    fun markUnavailable() {
        synchronized(displays) { displays.clear() }
        confirmed = emptySet(); effective = emptySet(); applied = null
        updateStatus(Status.UNAVAILABLE)
        requestRefresh()
    }

    private fun updateStatus(next: Status) {
        if (status == next) return
        status = next
        OfficialCloudSource.reportVisibility()
    }

    private fun requestRefresh() {
        synchronized(this) {
            if (refreshPosted) return
            refreshPosted = true
        }
        main.post {
            synchronized(this) { refreshPosted = false }
            OfficialCloudSource.refresh()
        }
    }

    /** 内容与接手规则检查使用的显示执行器;显示入口本身由独立检查覆盖。 */
    fun displayForTest(display: (Set<String>) -> Set<String>) { testDisplay = display; applied = null }

    fun resetForTest() {
        synchronized(displays) { displays.clear() }
        synchronized(failed) { failed.clear() }
        synchronized(observedHolders) { observedHolders.clear() }
        synchronized(noted) { noted.clear() }
        status = Status.UNKNOWN; confirmed = emptySet(); effective = emptySet(); applied = null; testDisplay = null
        refreshPosted = false; deliveries = 0
    }
}
