package com.astraflow.fluidcloud.hook

import android.os.Handler
import android.os.Looper

import java.util.concurrent.Executors

/**
 * 插件 ClassLoader 拦截器 -- 六层拦截策略
 *
 * SystemUIPlugin.apk 通过 OPlusPluginClassLoader (继承 PathClassLoader) 动态加载。
 * 功能 3 (胶囊背景) 和功能 4 (卡片背景) 需要 Hook Plugin 内部类，
 * 必须延迟到 Plugin ClassLoader 创建后。
 *
 * 六层策略:
 *   S1: Hook BaseDexClassLoader 构造函数 (4参 + API33 新构造)
 *   S2: Hook PathClassLoader 构造函数 (2参 + 3参)
 *   S3: Hook ContextImpl.createApplicationContext
 *   S4: 5秒后兜底搜索 (遍历线程 contextClassLoader + S6-b PluginManager 反射)
 *   S5: Hook DexClassLoader/SeedlingClassLoader/PantaBaseDexClassLoader 构造 (开机启动)
 *   S6-a: Hook PluginManager.gainPluginClassLoader() afterHook (killall 重启)
 *   S6-b: 反射 PluginManager.sInstance.pluginDexClassLoader (最终兜底)
 *
 * 拦截到后:
 *   - Handler.post() 延迟执行 (等待子类构造完成)
 *   - 检查 dexPath 是否包含 "SystemUIPlugin" 或 "SeedlingSdk"
 *   - 触发已注册的延迟 Hook 回调
 *
 * API 101: 所有 Hook 通过 HookHelper (XposedInterface.hook()) 进行
 */
object PluginClassLoaderInterceptor {

    private const val TAG = "PluginInterceptor"
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 回调接口 -- Plugin ClassLoader 就绪时触发
     *
     * 不给代码搜索实例:要在插件文件里搜混淆类的一方自己在后台按需建、用完就关,不占系统界面主线程、不一直开着。
     *
     * @param classLoader Plugin 的 ClassLoader
     * @param apkPath Plugin APK 的路径 (已处理 `:` 分隔的多路径;卡片引擎插件是含卡片数据类的那份 DEX 文件)
     */
    fun interface OnPluginClassLoaderReady {
        fun onReady(classLoader: ClassLoader, apkPath: String)
    }

    private val stateStore = PluginInterceptorStateStore()
    /** 卡片引擎插件挑用哪份 DEX 文件(挑的时候临时建代码搜索实例,查完就关) */
    private val bridgeStore = PluginDexKitBridgeStore(TAG)
    /** 卡片引擎插件挑文件、调用它的回调都在这条后台线程上按捕获先后依次做 */
    private val seedlingWorker = Executors.newSingleThreadExecutor { Thread(it, "AstraFlow-SeedlingDex").apply { isDaemon = true } }
    private val callbackRegistry = PluginInterceptorCallbackRegistry(
        tag = TAG,
        background = { r -> seedlingWorker.execute(r) },
        bridgeStore = bridgeStore,
    )
    private val seedlingCaptureCoordinator = PluginSeedlingCaptureCoordinator(
        tag = TAG,
        isSeedlingCaptured = { isSeedlingCaptured },
        capturedSeedlingClassLoader = { stateStore.capturedSeedlingClassLoader },
        onClassLoaderCreated = ::handleClassLoaderCreated,
        onSeedlingCaptured = ::handleSeedlingCaptured,
    )

    // ======== SeedlingSdk ClassLoader 独立存储 ========

    /** 是否已捕获 SeedlingSdk ClassLoader */
    var isSeedlingCaptured = false
        get() = stateStore.isSeedlingCaptured
        private set

    /**
     * 注册延迟 Hook 回调
     *
     * 如果 Plugin 已捕获，在锁外立即触发回调；
     * 否则等待捕获后触发。
     */
    fun registerCallback(callback: OnPluginClassLoaderReady) {
        callbackRegistry.registerPluginCallback(stateStore, callback)
    }

    /**
     * 注册 SeedlingSdk 延迟 Hook 回调
     *
     * SeedlingSdk 使用独立的 PantaBaseDexClassLoader，与 SystemUIPlugin 的 ClassLoader 不同。
     * StatusBarCapsuleData、Icon、LottieModel 等类在 SeedlingSdk 中，需要此回调获取 SeedlingSdk CL。
     *
     * 如果 SeedlingSdk 已捕获，立即在后台线程执行回调；否则等待捕获后触发。
     */
    fun registerSeedlingCallback(callback: OnPluginClassLoaderReady) {
        callbackRegistry.registerSeedlingCallback(stateStore, callback)
    }

    /**
     * 注册所有拦截策略
     *
     * 由 HookDispatcher.onSystemUILoaded() 调用。
     */
    fun register(classLoader: ClassLoader) {
        // S1: Hook BaseDexClassLoader
        runCatching { hookBaseDexClassLoader() }.onFailure {
            Logger.w(TAG, "S1 (BaseDexClassLoader) hook failed: ${it.message}")
        }

        // S2: Hook PathClassLoader
        runCatching { hookPathClassLoader() }.onFailure {
            Logger.w(TAG, "S2 (PathClassLoader) hook failed: ${it.message}")
        }

        // S3: Hook ContextImpl.createApplicationContext
        runCatching { hookCreateApplicationContext() }.onFailure {
            Logger.w(TAG, "S3 (createApplicationContext) hook failed: ${it.message}")
        }

        // S4: 5秒后兜底搜索
        runCatching { scheduleFallbackSearch() }.onFailure {
            Logger.w(TAG, "S4 (fallback) schedule failed: ${it.message}")
        }

        // S5: Hook DexClassLoader (SeedlingClassLoader 继承自 DexClassLoader)
        runCatching { hookDexClassLoader() }.onFailure {
            Logger.xposedLog(TAG, "S5 (DexClassLoader) hook failed: ${it.message}")
        }

        Logger.i(TAG, "All interception strategies registered (S1-S5, S6 deferred to Plugin capture)")
        Logger.xposedLog(TAG, "All interception strategies registered (S1-S5, S6-a/b deferred)")
    }

    // ======== S1: BaseDexClassLoader ========

    private fun hookBaseDexClassLoader() {
        val baseDexCLClass = Class.forName("dalvik.system.BaseDexClassLoader")

        // 4参构造: (String, File?, String?, ClassLoader)
        val ctor4 = runCatching {
            baseDexCLClass.getDeclaredConstructor(
                String::class.java,
                java.io.File::class.java,
                String::class.java,
                ClassLoader::class.java,
            )
        }.getOrNull()

        if (ctor4 != null) {
            HookHelper.hookConstructor(ctor4, afterCallback = { chain, _ ->
                val dexPath = chain.getArg(0) as? String ?: return@hookConstructor null
                val cl = chain.thisObject as? ClassLoader ?: return@hookConstructor null
                handleClassLoaderCreated(cl, dexPath, "S1_BaseDexClassLoader_4arg")
                null
            })
            Logger.i(TAG, "S1: Hooked BaseDexClassLoader(String, File, String, ClassLoader)")
        }

        // API 33+ 新增构造: (String, String, ClassLoader, ClassLoader[])
        runCatching {
            val classLoaderArrayClass = Class.forName("[Ljava.lang.ClassLoader;")
            val ctorApi33 = baseDexCLClass.getDeclaredConstructor(
                String::class.java,
                String::class.java,
                ClassLoader::class.java,
                classLoaderArrayClass,
            )
            HookHelper.hookConstructor(ctorApi33, afterCallback = { chain, _ ->
                val dexPath = chain.getArg(0) as? String ?: return@hookConstructor null
                val cl = chain.thisObject as? ClassLoader ?: return@hookConstructor null
                handleClassLoaderCreated(cl, dexPath, "S1_BaseDexClassLoader_API33")
                null
            })
            Logger.i(TAG, "S1+: Hooked BaseDexClassLoader API33+ constructor")
        }.onFailure {
            Logger.d(TAG, "S1+: API33+ constructor not found (expected on older devices)")
        }
    }

    // ======== S2: PathClassLoader ========

    private fun hookPathClassLoader() {
        val pathCLClass = Class.forName("dalvik.system.PathClassLoader")

        // 2参: (String, ClassLoader)
        runCatching {
            val ctor2 = pathCLClass.getDeclaredConstructor(
                String::class.java,
                ClassLoader::class.java,
            )
            HookHelper.hookConstructor(ctor2, afterCallback = { chain, _ ->
                val dexPath = chain.getArg(0) as? String ?: return@hookConstructor null
                val cl = chain.thisObject as? ClassLoader ?: return@hookConstructor null
                handleClassLoaderCreated(cl, dexPath, "S2_PathClassLoader_2arg")
                null
            })
        }

        // 3参: (String, String, ClassLoader)
        runCatching {
            val ctor3 = pathCLClass.getDeclaredConstructor(
                String::class.java,
                String::class.java,
                ClassLoader::class.java,
            )
            HookHelper.hookConstructor(ctor3, afterCallback = { chain, _ ->
                val dexPath = chain.getArg(0) as? String ?: return@hookConstructor null
                val cl = chain.thisObject as? ClassLoader ?: return@hookConstructor null
                handleClassLoaderCreated(cl, dexPath, "S2_PathClassLoader_3arg")
                null
            })
        }

        Logger.i(TAG, "S2: Hooked PathClassLoader constructors")
    }

    // ======== S3: ContextImpl.createApplicationContext ========

    private fun hookCreateApplicationContext() {
        val contextImplClass = Class.forName("android.app.ContextImpl")
        val method = contextImplClass.getDeclaredMethod(
            "createApplicationContext",
            android.content.pm.ApplicationInfo::class.java,
            Int::class.javaPrimitiveType,
        )

        HookHelper.hookMethodAfter(method) { chain, result ->
            val appInfo = chain.getArg(0) as? android.content.pm.ApplicationInfo ?: return@hookMethodAfter result
            val sourceDir = appInfo.sourceDir ?: return@hookMethodAfter result

            if (!PluginDexPathResolver.isPluginPath(sourceDir)) return@hookMethodAfter result

            val pluginContext = result as? android.content.Context ?: return@hookMethodAfter result
            Logger.i(TAG, "S3: createApplicationContext captured plugin: $sourceDir")

            // 如果 ClassLoader 尚未捕获，从 Context 获取
            runCatching {
                val cl = pluginContext.classLoader ?: return@runCatching
                handleClassLoaderCreated(cl, sourceDir, "S3_createApplicationContext")
            }

            result // 不修改返回值
        }

        Logger.i(TAG, "S3: Hooked ContextImpl.createApplicationContext")
    }

    // ======== S4: 兜底搜索 ========

    private fun scheduleFallbackSearch() {
        PluginFallbackSearchScheduler.schedule(
            tag = TAG,
            mainHandler = mainHandler,
            stateStore = stateStore,
            onClassLoaderCreated = ::handleClassLoaderCreated,
            onTrySeedlingReflection = ::trySeedlingFromPluginManagerReflection,
        )
    }

    // ======== S5: SeedlingSdk ClassLoader 专项拦截 ========

    /**
     * Hook SeedlingClassLoader / PantaBaseDexClassLoader / DexClassLoader 构造函数
     *
     * SeedlingSdk 使用 com.oplus.seedling.sdk.plugin.classloader.SeedlingClassLoader 加载。
     * 真机日志已确认：SeedlingSdk captured by S5_DexClassLoader: SeedlingClassLoader
     *
     * 策略 (按优先级):
     *   S5a: 直接 Hook SeedlingClassLoader 所有构造函数 (最精确, 真机验证过)
     *   S5b: Hook PantaBaseDexClassLoader (docs/流体云开发手册.md Section 6.3.4)
     *   S5c: 兜底 Hook DexClassLoader (标准回退)
     */
    private fun hookDexClassLoader() {
        seedlingCaptureCoordinator.hookDexClassLoader()
    }

    // ======== S6-a: Hook PluginManager.gainPluginClassLoader() ========

    /**
     * S6-a: 在 SystemUIPlugin ClassLoader 捕获后，Hook PluginManager.gainPluginClassLoader()
     *
     * 解决 killall 重启后 SeedlingClassLoader 不被重新创建的问题。
     * PluginManager (com.oplus.seedling.sdk.plugin.PluginManager) 的 gainPluginClassLoader()
     * 在有缓存时直接返回已有的 DexClassLoader，不创建新实例。
     * afterHook 可以捕获到无论是新创建还是缓存返回的 ClassLoader。
     *
     * 降级策略: 如果 gainPluginClassLoader (private final) Hook 失败（被 R8 混淆），
     * 尝试按返回类型 DexClassLoader + 无参 + 非静态筛选。
     * 再失败则尝试 Hook 公开方法 getSeedlingPluginClassLoader$pantanal_client_release()。
     * 如果都失败，依赖 S6-b 反射兜底。
     */
    private fun hookPluginManagerGainClassLoader(pluginCL: ClassLoader) {
        seedlingCaptureCoordinator.hookPluginManagerGainClassLoader(pluginCL)
    }

    // ======== S6-b: PluginManager 反射兜底 ========

    /**
     * S6-b: 反射 PluginManager 单例获取缓存的 SeedlingClassLoader
     *
     * 作为 S5/S6-a 都失败时的最终兜底。
     * 通过 Kotlin lazy delegate 反射链获取 PluginManager 单例的 pluginDexClassLoader 字段。
     *
     * 反射路径:
     *   PluginManager.sInstance$delegate (static, Kotlin LazyImpl)
     *     -> LazyImpl.getValue() 或 _value 字段
     *       -> PluginManager 实例
     *         -> pluginDexClassLoader 字段 (DexClassLoader)
     *
     * 字段名稳定性 (RESEARCH-v2.md Section 4.4):
     *   - PluginManager 包名: 非混淆 ★★★★★
     *   - pluginDexClassLoader 字段: 非混淆 ★★★★
     *   - sInstance$delegate: Kotlin 编译器标准命名 ★★★★
     */
    private fun trySeedlingFromPluginManagerReflection(pluginCL: ClassLoader) {
        seedlingCaptureCoordinator.trySeedlingFromPluginManagerReflection(pluginCL)
    }

    // ======== 核心处理 ========

    /**
     * 处理 ClassLoader 创建事件 -- 所有策略的统一入口
     *
     * 内置去重: 同一 Plugin 只处理首次捕获。
     * Handler.post() 延迟执行: 等待子类构造函数完全完成。
     */
    private fun handleClassLoaderCreated(cl: ClassLoader, dexPath: String, strategy: String) {
        if (!PluginDexPathResolver.isPluginPath(dexPath)) return

        val cleanApkPath = PluginDexPathResolver.extractPluginApkPath(dexPath)
        val isSeedling = cleanApkPath.contains("SeedlingSdk", ignoreCase = true)

        // 捕获事件走岛内日志通道(发布包里 Logger.xposedLog 不输出,排障离不开这几行)
        Log.i(TAG, "CL created: strategy=$strategy, isSeedling=$isSeedling, path=${cleanApkPath.takeLast(60)}")

        if (isSeedling) {
            handleSeedlingCaptured(cl, cleanApkPath, strategy)
        } else {
            handlePluginCaptured(cl, cleanApkPath, strategy)
        }
    }

    /** 处理 SystemUIPlugin ClassLoader 捕获(含换代重捕:运行期插件卸载重建产生的新 CL 实例) */
    private fun handlePluginCaptured(cl: ClassLoader, cleanApkPath: String, strategy: String) {
        // 仅用于日志措辞的读操作,不在锁内:误差不影响正确性(markPluginCaptured 内部仍原子去重)。
        val wasCaptured = stateStore.isPluginCaptured
        if (!stateStore.markPluginCaptured(cl, cleanApkPath)) {
            Logger.d(TAG, "$strategy: Plugin already captured, ignoring duplicate")
            return
        }

        if (wasCaptured) {
            // 换代重捕:旧 CL 上的全部插件 hook 随旧 Class 对象失效,必须把回调重新打到新 CL 上,
            // 否则层3 canLiveAlert 等门控静默退回系统默认(表现为待机后胶囊消失)。
            Logger.i(TAG, "Plugin ClassLoader recaptured by $strategy: ${cl.javaClass.name} (previous generation replaced)")
            Logger.xposedLog(TAG, "Plugin ClassLoader recaptured by $strategy: re-delivering callbacks to new generation")
        } else {
            Logger.i(TAG, "Plugin captured by $strategy: ${cl.javaClass.name}, apkPath=$cleanApkPath")
            Log.i(TAG, "Plugin captured by $strategy: ${cl.javaClass.name}")
        }

        mainHandler.post {
            callbackRegistry.executePluginCallbacks(cl, strategy, cleanApkPath)
            // S6-a: Hook PluginManager 获取 SeedlingSdk ClassLoader (killall 重启场景)。
            // 无条件武装 — 首个种子 CL 可能是 SDK 壳 (无 ULE 业务类),
            // isSeedlingCaptured 置位后跳过会让真正含 z5.* 的插件 CL 永远送不到。
            // 换代重捕同理:新 plugin CL 的 PluginManager 是新 Class,必须重新武装。
            hookPluginManagerGainClassLoader(cl)
        }
    }

    /** 处理 SeedlingSdk ClassLoader 捕获 */
    private fun handleSeedlingCaptured(cl: ClassLoader, cleanApkPath: String, strategy: String) {
        if (!stateStore.markSeedlingCaptured(cl, cleanApkPath)) {
            if (stateStore.capturedSeedlingClassLoader === cl) {
                Logger.d(TAG, "$strategy: SeedlingSdk already captured, ignoring duplicate")
                return
            }
            // 后到的不同种子 CL (如含 ULE 业务类的新一代) 仍分发给消费者 —
            // 消费者自行按锚点筛选 (v1600 歌词适配器装不上即因首占的是 SDK 壳 CL)。
            Logger.i(TAG, "$strategy: additional SeedlingSdk CL delivering: ${cl.javaClass.name}")
            mainHandler.post {
                callbackRegistry.executeSeedlingCallbacks(cl, cleanApkPath)
            }
            return
        }
        bridgeStore.invalidateSeedlingDexPath()

        Logger.i(TAG, "SeedlingSdk captured by $strategy: ${cl.javaClass.name}, apkPath=$cleanApkPath")
        Log.i(TAG, "SeedlingSdk captured by $strategy: CL=${cl.javaClass.name}, path=${cleanApkPath.takeLast(60)}")
        // 诊断: 输出 ClassLoader parent chain
        val parentChain = mutableListOf<String>()
        var p: ClassLoader? = cl.parent
        while (p != null && parentChain.size < 10) {
            parentChain.add(p.javaClass.name)
            p = p.parent
        }
        Logger.xposedLog(TAG, "SeedlingSdk CL parent chain: ${parentChain.joinToString(" → ")}")

        mainHandler.post {
            callbackRegistry.executeSeedlingCallbacks(cl, cleanApkPath)
        }
    }
}
