package com.astraflow.fluidcloud

import android.os.Handler
import android.os.Looper
import com.astraflow.fluidcloud.hook.HookHelper
import com.astraflow.fluidcloud.hook.PluginClassLoaderInterceptor
import com.astraflow.fluidcloud.hook.Logger
import io.github.libxposed.api.XposedInterface
import org.luckypray.dexkit.DexKitBridge
import java.io.File
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.security.MessageDigest
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * 在系统流体云插件上装读数据和让位的挂钩(现行规则「系统事件接入」)。接收、模板引擎、服务开关和通知原件均由系统继续维护。
 * - 系统服务的卡片:按 OPPO 接口找挂点(卡片内容、服务名单、撤掉名单监听),由 OfficialCloudSeedlingReader 读,不认插件的日志文字;
 * - 通知变成的胶囊:OPPO 接口里没有,旁读插件自己的数据监听(按监听方法里写日志用的文字找),只读这一种;
 * - 星河岛接手的事让系统胶囊让位用系统自己的「在哪些位置显示」开关(OfficialCloudYield),不改系统内部的方法。
 */
object OfficialCloudHook {
    private const val TAG = "OfficialCloud"
    /**
     * 实际反编译核对过的那一版插件。只在这一版上读通知变成的胶囊里系统已经画好的控件(取控件用的是这一版里的短名字);
     * 别的版本照样接收、照样让位,只是不读这些控件。
     */
    const val VERIFIED_PLUGIN_SHA256 = "ad74975487c99d7a9ed29ebf8f5f644283a3ad096f89b75e2bca3003cca1be7a"
    val anchors = linkedMapOf(
        CloudChange.REPLACE to "EntryDataListener,onDataChanged originData:",
        CloudChange.POST to "EntryDataListener,onDataPosted originData:",
        CloudChange.REMOVE to "EntryDataListener,onDataRemoved originData:",
    )
    /** 装着数据监听的各代插件,按装上的先后 */
    private val generations = mutableListOf<Generation>()
    private val owners = WeakHashMap<Any, String>()
    private val replays = linkedMapOf<String, Replay>()
    private val sequence = AtomicLong()
    private val ownerSequence = AtomicLong()
    private val replaying = ThreadLocal.withInitial { false }
    @Volatile private var registered = false
    private data class Replay(val owner: WeakReference<Any>, val method: Method, var data: List<Any>, val handler: Handler)

    /** 一份插件文件:路径、大小、修改时间都一样就是同一份,在它上面找过的结果照用 */
    data class PluginFile(val path: String, val size: Long, val modified: Long)

    /** 监听方法在插件里的位置(类名、方法名、参数类型),按每个类加载器再换成方法;只记名字,不留住插件的类 */
    data class MethodRef(val className: String, val name: String, val params: List<String>) {
        fun resolve(loader: ClassLoader): Method = Class.forName(className, false, loader)
            .getDeclaredMethod(name, *params.map { Class.forName(it, false, loader) }.toTypedArray())
    }

    /**
     * 在一份插件文件上找到的:三个监听方法(找不到为 null)、是不是核对过的那一版,以及让位要用的两处——
     * 接收卡片内容的类([interceptors],实现 OPPO 接口 UIDataInterceptor)和接收服务名单的类([observers],实现 DecisionObserver)。
     */
    class PluginScan(
        val listeners: Map<CloudChange, MethodRef?>, val verified: Boolean,
        val interceptors: List<String> = emptyList(), val observers: List<String> = emptyList(),
    )

    /**
     * 装着挂钩的一代插件:送来的类加载器、通知胶囊监听者的类(这一份插件文件里找不到时为空)、装的时候读的插件文件、装上的挂钩
     * (三个监听、卡片内容与服务名单两处、撤掉名单监听一处),以及这一代送过数据的监听者编号。
     * 插件重新加载后,新一代收到数据、这一代的监听者和记录管理都已经不在时撤掉([retireOlderThan]),不再留住换下来的插件。
     */
    private class Generation(val loader: ClassLoader, val listener: Class<*>?, val file: PluginFile) {
        val hooks = mutableListOf<XposedInterface.HookHandle>()
        val ownerIds: MutableSet<String> = Collections.synchronizedSet(linkedSetOf())
        /** 挂钩所在的类是哪些类加载器定义的(记录管理是这一代的,就认得出) */
        val definers: Set<ClassLoader> get() = hooks.mapNotNullTo(mutableSetOf()) { it.executable.declaringClass.classLoader }
    }

    /** 找过的插件文件(找不到监听处的也记下),插件重新加载、换一代类加载器时不再重找 */
    private val scans = object : LinkedHashMap<PluginFile, PluginScan>(8, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<PluginFile, PluginScan>?) = size > 8
    }
    /** 在插件文件上找监听处、核对插件文件(检查里换成别的) */
    @Volatile var scanner: (String) -> PluginScan = ::scan
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "AstraFlow-CloudHook").apply { isDaemon = true } }

    fun register() {
        if (registered) return
        registered = true
        PluginClassLoaderInterceptor.registerCallback { loader, path -> install(loader, path) }
        PluginClassLoaderInterceptor.registerSeedlingCallback { loader, path -> install(loader, path) }
        OfficialCloudSystemTips.install(HookHelper.classLoader)
    }

    /**
     * 插件的类加载器就绪(第一次加载或插件重新加载)时装数据监听。找监听处、核对插件文件都在后台线程做,系统界面主线程不等;
     * 同一份插件文件只找一次,找不到的也记下。没装上时把原因写进岛的日志,并报给「系统流体云接入」页(现行规则「系统事件接入」)。
     */
    fun install(loader: ClassLoader, path: String) {
        if (synchronized(generations) { generations.any { it.loader === loader } }) return
        worker.execute { installOn(loader, path) }
    }

    private fun installOn(loader: ClassLoader, path: String) {
        if (synchronized(generations) { generations.any { it.loader === loader } }) return
        val apk = path.substringAfterLast('/')
        val file = File(path)
        val identity = PluginFile(path, file.length(), file.lastModified())
        // 同一个路径的插件文件换了新的一份:按旧文件装上的那几代属于换下来的插件,这里装不上时照样报不可用
        val replaced = synchronized(generations) { generations.any { it.file.path == path && it.file != identity } }
        // 这份文件以前在找代码的途中把系统界面带崩过:不再找,按装不上报(见 CrashGuard)
        val guardKey = "${identity.path}|${identity.size}|${identity.modified}"
        if (synchronized(scans) { scans[identity] } == null && com.astraflow.fluidcloud.hook.CrashGuard.isSkipped(guardKey)) {
            unavailable(apk, "plugin file skipped: an earlier code search on it ended SystemUI", superseded = replaced)
            return
        }
        val scan = synchronized(scans) { scans[identity] } ?: try {
            com.astraflow.fluidcloud.hook.CrashGuard.risky(guardKey) { scanner(path) }.also { found -> synchronized(scans) { scans[identity] = found } }
        } catch (failure: Throwable) {
            // 代码搜索读不了这份插件文件:不记下,插件重新加载时再找
            unavailable(apk, "plugin could not be searched", failure, superseded = replaced)
            return
        }
        runCatching {
            // 同一插件可能有多个加载器(壳/业务分开),在这个加载器上换不出方法按"锚点不在此"处理,不炸安装。
            val methods = scan.listeners.mapValues { (_, ref) -> ref?.let { runCatching { it.resolve(loader) }.getOrNull() } }
            val unresolved = methods.filterValues { it == null }.keys
            val ownerClasses = methods.values.mapNotNull { it?.declaringClass }.distinct()
            // 通知胶囊的监听者:三个监听方法都在同一个类上才算找到;这个类加载器转交给已经装好的那一份时不装第二遍
            val found = ownerClasses.singleOrNull()?.takeIf { unresolved.isEmpty() }
            val shared = found != null && synchronized(generations) { generations.any { it.listener === found } }
            val listener = found?.takeUnless { shared }
            // 系统服务卡片的挂点(OPPO 接口):这个类加载器里换得出、还没有哪一代挂过的那几个类
            val interceptors = scan.interceptors.filter { unhooked(loader, it) }
            val observers = scan.observers.filter { unhooked(loader, it) }
            val manager = unhooked(loader, OfficialCloudSeedlingReader.MANAGER)
            val publicApi = interceptors.isNotEmpty() || observers.isNotEmpty() || manager
            if (listener == null && !publicApi) {
                if (shared) com.astraflow.fluidcloud.hook.Log.i(TAG, "official event listeners already installed for the loader on $apk")
                // 什么都没有:插件的外壳、只管资源的类加载器或别的插件;有监听者的类却听不了:这一代插件听不了
                else unavailable(apk, "listener anchors unresolved: missing=$unresolved owners=${ownerClasses.map { it.name }}",
                    superseded = replaced || ownerClasses.isNotEmpty())
                return
            }
            val verified = scan.verified
            val generation = Generation(loader, listener, identity)
            try {
                if (listener != null) methods.forEach { (change, nullableMethod) ->
                    val method = nullableMethod!!
                    // 交给显示之前:通知变成的胶囊按星河岛的决定改状态栏开关(重放时也改,重放就是为了让系统按新开关重算)
                    val before: (XposedInterface.Chain) -> Any? = { chain ->
                        (chain.getArg(0) as? List<*>)?.let { runCatching { OfficialCloudYield.beforeList(it) } }
                        null
                    }
                    val handle = HookHelper.hookMethod(method, before) { chain, result ->
                        if (!replaying.get()) {
                            val owner = chain.thisObject
                            // 系统服务卡片由 OfficialCloudSeedlingReader 从 OPPO 接口读,这里只读通知变成的胶囊
                            val data = (chain.getArg(0) as? List<*>)?.filterNotNull()?.take(128)?.filter(OfficialCloudReflection::isCommon)
                            val known = owner?.let { synchronized(owners) { owners[it] } }
                            if (owner != null && data != null && (known != null || data.isNotEmpty())) {
                                val ownerId = known ?: synchronized(owners) { owners.getOrPut(owner) { "listener-${ownerSequence.incrementAndGet()}" } }
                                generation.ownerIds += ownerId
                                diagnoseArrival(ownerId, owner, change, data)
                                synchronized(replays) {
                                    if (change == CloudChange.REPLACE) {
                                        replays[ownerId] = Replay(WeakReference(owner), method, data, Handler(Looper.myLooper() ?: Looper.getMainLooper()))
                                    } else replays[ownerId]?.let { replay ->
                                        fun key(value: Any) = OfficialCloudReflection.nativeKey(value) ?: "object:${System.identityHashCode(value)}"
                                        val current = replay.data.associateByTo(linkedMapOf(), ::key)
                                        if (change == CloudChange.REMOVE) data.forEach { current.remove(key(it)) }
                                        else data.forEach { current[key(it)] = it }
                                        replay.data = current.values.toList()
                                    }
                                }
                                OfficialCloudSource.receive(ownerId, nextRevision(), change, data, verified)
                                dropDeadOwners()
                                retireOlderThan(generation)
                            }
                        }
                        result
                    } ?: error("listener hook unavailable")
                    generation.hooks += handle
                }
            } catch (failure: Throwable) {
                generation.hooks.forEach { runCatching { it.unhook() } }
                throw failure
            }
            // 系统服务卡片的读法和让位用同一处挂钩(读到的事才让位);通知胶囊的让位跟着上面的监听
            generation.hooks += OfficialCloudYield.install(loader, interceptors, observers, manager, apk)
            OfficialCloudSource.reportVisibility()
            synchronized(generations) { generations += generation }
            com.astraflow.fluidcloud.hook.Log.i(TAG, "official event listeners installed; notification capsules=${listener != null} reviewed plugin=$verified on $apk")
            if (listener == null && !shared) com.astraflow.fluidcloud.hook.Log.w(TAG, "notification capsules unreadable on $apk: " +
                "listener anchors unresolved: missing=$unresolved owners=${ownerClasses.map { it.name }}")
            Logger.xposedLog(TAG, "official event listeners installed; notification capsules=${listener != null} capsule yield=${OfficialCloudYield.status.report}")
        }.onFailure {
            Logger.e(TAG, "official event listeners unavailable", it)
            // 这个类加载器上有监听者的类却装不上:没装上的正是这一代插件
            unavailable(apk, "install failed", it, superseded = true)
        }
    }

    /** [name] 这个类在 [loader] 里换得出,并且还没有哪一代在它上面挂过(同一个类只挂一次) */
    private fun unhooked(loader: ClassLoader, name: String): Boolean {
        val type = runCatching { Class.forName(name, false, loader) }.getOrNull() ?: return false
        return synchronized(generations) { generations.none { generation -> generation.hooks.any { it.executable.declaringClass === type } } }
    }

    /** 交给 OfficialCloudSource 的每一份数据带的版本号(通知胶囊和系统服务卡片共用,越新越大) */
    fun nextRevision(): Long = sequence.incrementAndGet()

    /**
     * 系统服务卡片的读法从这一代插件的记录管理([loader] 定义的)收到了数据:比它早装上、监听者和记录管理都已经不在的那几代撤掉。
     */
    fun onReaderData(loader: ClassLoader?) {
        val current = synchronized(generations) { generations.lastOrNull { loader != null && loader in it.definers } } ?: return
        retireOlderThan(current)
    }

    /**
     * 数据监听在这个类加载器上没装上:原因写进岛的日志(正式包也看得到)。一代都还没装好,或者没装上的正是新一代插件
     * ([superseded]:这个类加载器上有监听者的类却听不了,或者同一个路径的插件文件换了新的一份)时,把「不可用」报给
     * 「系统流体云接入」页,页面照已有的办法写明(现行规则「系统事件接入」)。已经有一代装好、这里一个监听方法都没有时不改:
     * 插件每次加载都紧跟着送来一个只管资源的类加载器,系统流体云的另一个插件也会送来,它们都没有监听处。
     */
    private fun unavailable(apk: String, reason: String, failure: Throwable? = null, superseded: Boolean = false) {
        val elsewhere = !superseded && synchronized(generations) { generations.isNotEmpty() }
        com.astraflow.fluidcloud.hook.Log.w(TAG, "official event listeners not installed on $apk: $reason" +
            if (elsewhere) " (listening through another loader)" else "", failure)
        if (elsewhere) return
        OfficialCloudYield.markUnavailable()
        OfficialCloudSource.reportVisibility()
    }

    /**
     * 插件重新加载后新一代收到了数据:比它早装上、监听者对象都已经不在的那几代撤掉监听挂钩和让位的挂钩,
     * 它们留下的记录一并撤掉,不再留住换下来的插件。旧一代的监听者还在(还有人用)时不撤。
     */
    private fun retireOlderThan(current: Generation) {
        val managers = OfficialCloudSeedlingReader.liveLoaders()
        val retired = synchronized(generations) {
            val older = generations.subList(0, generations.indexOf(current).coerceAtLeast(0))
                .filter { old -> old.file.path == current.file.path &&
                    synchronized(owners) { old.listener == null || owners.keys.none(old.listener::isInstance) } && old.definers.none(managers::contains) }
            generations.removeAll(older)
            older
        }
        retired.forEach { old ->
            old.hooks.forEach(HookHelper::unhook)
            val ids = synchronized(old.ownerIds) { old.ownerIds.toSet() }
            synchronized(replays) { ids.forEach(replays::remove) }
            if (ids.isNotEmpty()) OfficialCloudSource.dropOwners(ids)
            com.astraflow.fluidcloud.hook.Log.i(TAG, "listeners of a replaced plugin generation removed on ${old.file.path.substringAfterLast('/')}")
        }
    }

    /**
     * 用自己的代码搜索实例在插件文件上找(用完就关;不借插件拦截器那一份,它会在插件重新加载时被关掉)。
     * 让位的两处按 OPPO 接口找:实现了卡片内容接口、服务名单接口的类。
     */
    private fun scan(path: String): PluginScan = DexKitBridge.create(path).use { bridge ->
        val listeners = anchors.mapValues { (_, anchor) ->
            bridge.findMethod { matcher { usingStrings(anchor); paramCount = 1; paramTypes = listOf("java.util.List") } }
                .singleOrNull()?.let { MethodRef(it.className, it.name, it.paramTypeNames) }
        }
        fun implementers(api: String) = runCatching { bridge.findClass { matcher { addInterface(api) } }.map { it.name } }.getOrDefault(emptyList())
        PluginScan(listeners, verified = hash(path) == VERIFIED_PLUGIN_SHA256,
            interceptors = implementers(OfficialCloudYield.INTERCEPTOR), observers = implementers(OfficialCloudYield.OBSERVER))
    }

    /** 检查用:等已经交给后台线程的安装做完 */
    fun awaitInstalls() {
        runCatching { worker.submit {}.get(5, java.util.concurrent.TimeUnit.SECONDS) }
    }

    fun resetForTest() {
        synchronized(generations) { generations.clear() }
        synchronized(owners) { owners.clear() }
        synchronized(replays) { replays.clear() }
        synchronized(scans) { scans.clear() }
        scanner = ::scan
    }

    /**
     * 到货诊断(正式包也写进岛的日志):每个监听者按「变化类型、服务号、卡片引擎在不在、内容树有没有」签名写一行,
     * 签名变了才写。看得出系统送来了什么、卡片排好没有,不用猜。
     */
    private val arrivals = linkedSetOf<String>()
    private val replayCount = AtomicLong()
    @Volatile private var lastReplayLogAt = 0L

    private fun diagnoseArrival(ownerId: String, owner: Any, change: CloudChange, data: List<Any>) {
        runCatching {
            val entry = owner.javaClass.declaredFields.firstOrNull { it.type.isEnum }
                ?.let { field -> field.isAccessible = true; field.get(owner)?.toString() } ?: "?"
            data.filter(OfficialCloudReflection::isOfficial).forEach { model ->
                val line = "$ownerId($entry) $change ${OfficialCloudReflection.describe(model)}"
                val fresh = synchronized(arrivals) { if (arrivals.size > 512) arrivals.clear(); arrivals.add(line.substringBefore(" data=")) }
                if (fresh) com.astraflow.fluidcloud.hook.Log.i(TAG, "arrival: $line")
            }
        }
    }

    /** 监听者对象已经不在了(插件重载后换了新对象):它留下的记录一并撤掉,不留下永远不会结束的卡片。 */
    private fun dropDeadOwners() {
        val dead = synchronized(replays) {
            replays.entries.filter { it.value.owner.get() == null }.map { it.key }.also { gone -> gone.forEach(replays::remove) }
        }
        if (dead.isNotEmpty()) OfficialCloudSource.dropOwners(dead.toSet())
    }

    /** 仅重放最后收到的原列表，促使系统重新计算显示；不删除服务或停止订阅。 */
    fun refreshNativeDisplay() {
        val snapshot = synchronized(replays) { replays.values.toList() }
        dropDeadOwners()
        // 重放次数写进日志(最多一秒一行),看得出是不是在反复促使系统重算
        val count = replayCount.incrementAndGet()
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastReplayLogAt >= 1000) {
            lastReplayLogAt = now
            com.astraflow.fluidcloud.hook.Log.i(TAG, "native display refresh #$count owners=${snapshot.size} hidden=${OfficialCloudSource.hiddenCount()}")
        }
        snapshot.forEach { replay -> replay.handler.post {
            val owner = replay.owner.get() ?: return@post
            replaying.set(true)
            try { runCatching { replay.method.invoke(owner, replay.data) }.onFailure { com.astraflow.fluidcloud.hook.Log.w(TAG, "native display refresh failed", it) } }
            finally { replaying.set(false) }
        } }
    }

    private fun hash(path: String): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        File(path).inputStream().use { input ->
            val bytes = ByteArray(65536)
            while (true) { val read = input.read(bytes); if (read < 0) break; digest.update(bytes, 0, read) }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()
}
