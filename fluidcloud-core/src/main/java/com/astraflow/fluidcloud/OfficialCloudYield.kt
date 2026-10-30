package com.astraflow.fluidcloud

import android.os.Handler
import android.os.Looper
import com.astraflow.fluidcloud.hook.HookHelper
import com.astraflow.fluidcloud.hook.Log
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * 系统显示部分接收当前列表时执行归属；原始记录、内容、显示设置均不修改。
 * 出口默认拦截:接入开且星河岛在管事时状态栏列表默认全部隐藏,记录从哪条数据门进来都一样,未知的门也盖住;
 * 未经过数据门的记录在出口就地读取送星河岛。接入关闭、岛断开或显示交接不可用时交还系统。
 * 目标、实际过滤和已确认分开记录：只有当前显示管理对象完成胶囊与卡片两条入口的重算才确认;
 * 通知变成的胶囊先藏不等接手确认;接手确认照旧只认星河岛报来的接手结果。
 * 管理对象未就绪、执行失败或回滚失败都不得沿用旧确认；显示恢复后主动让岛重新核对。
 */
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
    /** 已完成交接后结束的事件不交还系统，继续排除原生显示部分残留的同一条旧记录。 */
    private var finished: Set<String> = emptySet()
    private class Display(val method: Method, val entry: Any, val consumers: Map<Method, Int>) {
        var owner = WeakReference<Any>(null)
    }
    private val displays = linkedMapOf<ClassLoader, Display>()
    private val observedHolders = WeakHashMap<Any, Unit>()
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val refreshing = ThreadLocal.withInitial { false }
    private val failed = linkedSetOf<String>()
    private val noted = linkedSetOf<String>()
    private val deliveries = hashMapOf<Method, MutableSet<Any>>()
    /** 最近一次算出的接手目标(出口默认拦阵容外判定用) */
    @Volatile private var lastDesired: Set<String> = emptySet()
    /** 最近一次完整执行成功的目标；空或部分成功都不能拿它跳过下一次执行。 */
    private var applied: Set<String>? = null
    /** 最近一次完整执行成功时仍然有效的过滤集合；失败回滚只保留其中仍由岛接手的事件。 */
    private var lastApplied: Set<String>? = null
    private var refreshPosted = false
    private var testDisplay: ((Set<String>) -> Set<String>)? = null
    /** 安装失败或停用是显式状态，不能被测试执行器或空目标重算改写为可用。 */
    private var unavailable = false
    private var retryAttempt = 0
    private var retryPosted = false
    private val retry = Runnable {
        retryPosted = false
        OfficialCloudSource.refresh()
    }

    /** 两个显示入口必须全部找到；合并在同一方法中的入口只安装一次。 */
    fun installDisplay(loader: ClassLoader, renderers: List<Method>, refresh: Method?,
                       unhooked: (Method) -> Boolean): List<XposedInterface.HookHandle> {
        // 资源/服务读取加载器没有显示接口，不否定另一加载器已经完成的显示交接。
        if (renderers.isEmpty() && refresh == null) return emptyList()
        if (renderers.size != 2 || refresh == null || refresh.parameterCount != 2 ||
            refresh.parameterTypes[1] != Boolean::class.javaPrimitiveType) {
            markUnavailable(); return emptyList()
        }
        val entry = refresh.parameterTypes[0].enumConstants?.firstOrNull { (it as? Enum<*>)?.name == "ENTRY_STATUS_BAR" }
        if (entry == null) { markUnavailable(); return emptyList() }
        val handles = mutableListOf<XposedInterface.HookHandle>()
        for (method in renderers.distinct()) {
            if (!unhooked(method)) continue
            val handle = HookHelper.hookArguments(method, afterCallback = { chain ->
                if (refreshing.get() == true) chain.thisObject?.let { owner ->
                    deliveries.getOrPut(method) { java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>()) }.add(owner)
                }
            }) { chain ->
                chain.thisObject?.let(::observeDisplay)
                val input = chain.getArg(0) as? List<*> ?: error("native display list missing")
                arrayOf(filter(input))
            }
            if (handle == null) {
                handles.forEach(HookHelper::unhook)
                error("native display interceptor could not be installed: $method")
            }
            handles += handle
        }
        synchronized(displays) { displays[loader] = Display(refresh.apply { isAccessible = true }, entry, renderers.groupingBy { it }.eachCount()) }
        unavailable = false
        synchronized(observedHolders) { observedHolders.keys.toList() }.forEach(::observeDisplay)
        invalidate(); resetRetries()
        updateStatus(Status.PENDING)
        requestRefresh()
        return handles
    }

    /** 从系统已经创建的监听者取得显示管理对象，不读取静态字段触发系统的提前初始化。 */
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
                // 新对象可能已按未过滤列表建立视图；旧对象的执行结果对新对象无效。
                invalidate(); resetRetries()
                if (refreshing.get() != true) requestRefresh()
            }
        }
    }

    /**
     * 最新系统显示列表的副本。接入开且星河岛在管事时状态栏份默认全部隐藏(出口默认拦截):
     * 记录从哪条数据门进来都一样,未知的门也盖住;不在接手名单里的就地读取送星河岛。
     * 接入关闭、岛断开、显示交接不可用或身份读不出时交还系统。
     */
    fun filter(input: List<*>): ArrayList<Any?> {
        val output = ArrayList<Any?>(input.size)
        val available = testDisplay != null || synchronized(displays) {
            displays.isNotEmpty() && displays.values.all { it.owner.get() != null }
        }
        val blocking = available && status != Status.UNAVAILABLE && OfficialCloudSource.exitBlocking()
        // 源头已经交还系统、岛还没接回来的事:放行,不藏也不在出口就地重读(那条记录就是系统自己建的)
        val released = OfficialCloudSource.releasedNativeKeys()
        for (model in input) {
            if (model == null || !available) { output += model; continue }
            val key = OfficialCloudReflection.nativeKey(model)
            // 身份读不到不是显示执行失败：保留系统显示，也不能因此撤销其它事件的已执行交接。
            if (key == null) { output += model; continue }
            val entrance = displayEntrance(model, key)
            // 出口默认拦截:状态栏份不论来源一律隐藏;不经数据门进来(不在执行名单)的就地读取。
            // 例外放行:接手名单里但显示执行未确认的(星河岛明确不接或执行不支持,显式让位)、
            // 分类关闭的(用户指定由系统显示)。
            val failedNow = synchronized(failed) { key in failed }
            val handedBack = failedNow || (key in lastDesired && key !in confirmed) || key in released
            val categoryClosed = !OfficialCloudSource.kindAllowed(key)
            if (blocking && entrance == "ENTRY_STATUS_BAR" && !handedBack && !categoryClosed) {
                if (key !in effective) OfficialCloudSource.receiveFromDisplay(model)
                continue
            }
            // 未在默认拦截时维持名单语义:只有星河岛接手确认过的才隐藏。
            if (key !in effective) { output += model; continue }
            when (entrance) {
                "ENTRY_STATUS_BAR" -> continue
                null -> {
                    reject(setOf(key), model)
                    output += model
                }
                else -> output += model
            }
        }
        // 系统自行重绘也是恢复信号；待重试不能屏蔽这条真实的恢复路径。
        // 由 refresh() 驱动的原生调用不在这里递归请求。
        if (refreshing.get() != true && (applied == null || retryPosted)) requestRefresh()
        return output
    }

    private fun reject(keys: Set<String>, model: Any) {
        if (keys.isEmpty()) return
        synchronized(failed) { failed += keys }
        confirmed = confirmed - keys
        effective = effective - keys
        applied = null
        val type = model.javaClass.name
        if (synchronized(noted) { noted.size < 64 && noted.add(type) })
            Log.w("OfficialCloud", "native display identity or entrance unreadable on $type; its events remain with the system")
    }

    /** 显示记录自身或其唯一的原始记录持有显示位置；不根据字段短名识别。 */
    private fun displayEntrance(model: Any, key: String): String? {
        OfficialCloudReflection.entranceName(model)?.let { return it }
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
        val oldConfirmed = confirmed
        val oldEffective = effective
        try {
            if (unavailable) {
                confirmed = emptySet(); effective = finished; applied = null; lastApplied = null
                updateStatus(Status.UNAVAILABLE)
                return
            }
            val desired = OfficialCloudSource.requestedNativeKeys()
            lastDesired = desired
            val prehidden = OfficialCloudSource.prehiddenNativeKeys()
            val alive = OfficialCloudSource.aliveNativeKeys()
            finished = (finished + (confirmed - alive)) - alive
            // 预隐藏的记录不进 confirmed:接手确认照旧只认 desired,岛侧语义不变
            val target = desired + finished + prehidden
            // 纯内容测试执行器不能证明显示入口可用；空目标也不能在这里跳过能力判定。
            if (applied == target && !retryPosted && testDisplay == null) {
                confirmed = desired
                updateStatus(Status.AVAILABLE)
                resetRetries()
                return
            }
            effective = target
            testDisplay?.let { display ->
                runCatching { display(target) }.onSuccess {
                    confirmed = it.intersect(desired); effective = it.intersect(target)
                    // 执行器没显示成的就是这一轮没执行成:同样记为失败,出口默认拦截不拦它们
                    synchronized(failed) { failed += target - it }
                    applied = if (effective == target) target else null
                    if (applied != null) lastApplied = target
                    // 内容测试执行器的空目标不能证明原生显示入口可用；保持未定而不是沿用旧结果。
                    updateStatus(if (applied == null) Status.PARTIAL else if (target.isEmpty() && testDisplay != null) Status.UNKNOWN else Status.AVAILABLE)
                    if (applied != null) resetRetries() else scheduleRetry(target)
                }.onFailure {
                    Log.e("OfficialCloud", "native display handover failed", it)
                    // 只撤下这次未确认的在途事件；旧事件必须重新执行成功才继续确认。
                    val rollback = lastApplied.orEmpty().intersect(desired) + finished
                    confirmed = emptySet(); applied = null
                    val restored = runCatching { display(rollback) }.getOrNull()
                    if (restored == rollback) {
                        effective = rollback; lastApplied = rollback
                        confirmed = desired.intersect(rollback)
                    } else {
                        effective = finished; lastApplied = null
                    }
                    updateStatus(Status.UNAVAILABLE); scheduleRetry(target)
                }
                return
            }
            val targets = synchronized(displays) { displays.values.distinctBy { it.method } }
            if (targets.isEmpty()) {
                confirmed = emptySet(); effective = finished; applied = null; lastApplied = null
                // 没有目标不等于执行失败：尚无事件时保持未定；等待新显示对象时保持 pending。
                // 明确的安装/执行失败由 unavailable 分支报告，不能在这里被“没有目标”改写。
                updateStatus(if (status == Status.UNKNOWN) Status.UNKNOWN else Status.PENDING)
                return
            }
            if (targets.any { it.owner.get() == null }) {
                // 新对象尚未出现时，旧对象对新视图的结果无效；但不要让下一次执行把已结束事件复活。
                confirmed = emptySet(); effective = finished; applied = null; lastApplied = null
                updateStatus(Status.PENDING); scheduleRetry(target); return
            }
            synchronized(failed) { failed.clear() }
            fun dispatch(next: Set<String>): Boolean {
                effective = next
                refreshing.set(true)
                try {
                    return targets.map { target ->
                        runCatching {
                            deliveries.clear()
                            val owner = target.owner.get() ?: error("native display owner not ready")
                            target.method.invoke(owner, target.entry, false)
                            check(target.owner.get() === owner) { "native display owner changed during handover" }
                            check(target.consumers.all { (method, count) -> (deliveries[method]?.size ?: 0) >= count }) {
                                "native capsule and card consumers have not both completed"
                            }
                        }.onFailure { Log.e("OfficialCloud", "native display handover failed", it) }.isSuccess
                    }.all { it }
                } finally {
                    refreshing.set(false); deliveries.clear()
                }
            }
            if (dispatch(target)) {
                effective = target - synchronized(failed) { failed.toSet() }
                lastApplied = effective
                confirmed = desired.intersect(effective)
                applied = if (effective == target) target else null
                updateStatus(if (applied != null) Status.AVAILABLE else Status.PARTIAL)
                if (applied != null) {
                    resetRetries()
                } else {
                    // 胶囊/卡片实际过滤不一致时，先按有完整执行证据的列表回滚，再等待有界重试。
                    val partial = effective
                    if (!dispatch(partial) || effective != partial) {
                        confirmed = emptySet()
                        effective = (lastApplied.orEmpty().intersect(desired) + finished).also { lastApplied = it }
                        applied = null
                        updateStatus(Status.UNAVAILABLE)
                    } else {
                        // 这部分已经由两条入口重新执行过，可作为下一次失败的回滚基线；
                        // applied 仍为空，未识别的事件继续按有界退避重试。
                        lastApplied = partial
                        confirmed = desired.intersect(partial)
                    }
                    scheduleRetry(target)
                }
            } else {
                // 只回滚本次未确认的在途事件：其它事件沿用上一条完整执行成功、且仍有效的交接。
                // 回滚失败不能把试探性的 effective 当成功，也不能确认任何在途事件。
                val rollback = (lastApplied.orEmpty().intersect(desired) + finished)
                confirmed = emptySet(); applied = null
                updateStatus(Status.UNAVAILABLE)
                if (dispatch(rollback) && effective == rollback) {
                    effective = rollback; lastApplied = rollback
                    confirmed = desired.intersect(rollback)
                } else {
                    confirmed = emptySet(); effective = finished; applied = null; lastApplied = null
                }
                scheduleRetry(target)
            }
        } finally {
            if (confirmed != oldConfirmed || effective != oldEffective) OfficialCloudSource.onDisplayReconciled()
        }
    }

    /** 新记录、新归属或新显示管理对象到来时，重新给恢复机会；普通重试不清空退避进度。 */
    fun resetRetries() {
        retryAttempt = 0
        if (retryPosted) main.removeCallbacks(retry)
        retryPosted = false
    }

    private fun scheduleRetry(target: Set<String>) {
        if ((target + effective).isEmpty() || retryPosted || retryAttempt >= RETRY_DELAYS.size) return
        val delay = RETRY_DELAYS[retryAttempt++]
        retryPosted = true
        main.postDelayed(retry, delay)
    }

    private fun invalidate() {
        val changed = confirmed.isNotEmpty() || effective != finished
        confirmed = emptySet(); effective = finished; applied = null; lastApplied = null
        // 新管理对象可能仍显示未过滤列表；先撤下岛内旧确认，再重新执行两条原生入口。
        if (changed) OfficialCloudSource.onDisplayReconciled()
    }

    fun retire(loader: ClassLoader) {
        synchronized(displays) { displays.remove(loader) }
        invalidate(); resetRetries(); requestRefresh()
    }

    fun markUnavailable() {
        synchronized(displays) { displays.clear() }
        unavailable = true
        invalidate(); resetRetries()
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

    /** 内容与接手规则检查使用的显示执行器；显示入口本身由独立检查覆盖。 */
    fun displayForTest(display: (Set<String>) -> Set<String>) {
        testDisplay = display
        unavailable = false
        applied = null
        resetRetries()
        scheduleRetry(OfficialCloudSource.requestedNativeKeys())
    }

    fun resetForTest() {
        resetRetries()
        synchronized(displays) { displays.clear() }
        synchronized(failed) { failed.clear() }
        synchronized(observedHolders) { observedHolders.clear() }
        synchronized(noted) { noted.clear() }
        status = Status.UNKNOWN; confirmed = emptySet(); effective = emptySet(); finished = emptySet(); applied = null; lastApplied = null; testDisplay = null; unavailable = false
        refreshPosted = false; deliveries.clear()
    }

    private val RETRY_DELAYS = longArrayOf(150L, 500L, 1_500L, 4_000L)
}
