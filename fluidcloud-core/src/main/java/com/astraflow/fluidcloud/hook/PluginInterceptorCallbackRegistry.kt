package com.astraflow.fluidcloud.hook

/**
 * 插件/种子回调注册表。
 *
 * **回调列表持久化**:旧实现 execute*Callbacks 首次执行即
 * `toList().also { clear() }` 清空队列(af5da745 引入),且已捕获后注册的回调走
 * snapshot 立即执行、不入队——即首次捕获后队列恒为空。这导致插件 CL 换代重捕
 * (PluginInterceptorStateStore 换代语义)与 seedling 多代分发调
 * execute*Callbacks 时批次必为空:再投递对所有消费者都是空操作,hook 永远留在
 * 死 ClassLoader 上。
 *
 * 现语义:回调注册即永久入列(重复注册同一实例去重);已捕获后注册仍立即执行一次
 * (snapshot 路径),同时留在列里等待换代重捕时再执行。消费者按 CL 身份自行幂等
 * (同一 CL 重投跳过、新 CL 重装 hook)。
 *
 * 回调只拿到类加载器和文件路径,不再预先建代码搜索实例:要搜的一方自己在后台按需建、用完就关。
 * 卡片引擎插件要先在几份候选文件里挑出含卡片数据类的那一份,挑文件和调用它的回调都交给 [background],不占系统界面主线程。
 */
class PluginInterceptorCallbackRegistry(
    private val tag: String,
    private val background: (Runnable) -> Unit,
    private val bridgeStore: PluginDexKitBridgeStore,
) {

    private val callbackLock = Any()
    private val pluginCallbacks = mutableListOf<PluginClassLoaderInterceptor.OnPluginClassLoaderReady>()
    private val seedlingCallbacks = mutableListOf<PluginClassLoaderInterceptor.OnPluginClassLoaderReady>()

    fun registerPluginCallback(
        stateStore: PluginInterceptorStateStore,
        callback: PluginClassLoaderInterceptor.OnPluginClassLoaderReady,
    ) {
        // 先入列(换代重捕时再执行),再按捕获态决定要不要立即执行一次。
        synchronized(callbackLock) {
            if (!pluginCallbacks.contains(callback)) {
                pluginCallbacks.add(callback)
            }
        }
        val snapshot = stateStore.snapshotOrEnqueuePlugin { /* 已入列,无需再排队 */ }

        if (snapshot != null) {
            runCatching {
                callback.onReady(snapshot.classLoader, snapshot.apkPath)
            }.onFailure {
                Logger.e(tag, "Immediate callback execution failed", it)
            }
        } else {
            synchronized(callbackLock) {
                Logger.d(tag, "Callback registered, pending=${pluginCallbacks.size}")
            }
        }
    }

    fun registerSeedlingCallback(
        stateStore: PluginInterceptorStateStore,
        callback: PluginClassLoaderInterceptor.OnPluginClassLoaderReady,
    ) {
        synchronized(callbackLock) {
            if (!seedlingCallbacks.contains(callback)) {
                seedlingCallbacks.add(callback)
            }
        }
        val snapshot = stateStore.snapshotOrEnqueueSeedling { /* 已入列,无需再排队 */ }

        if (snapshot != null) {
            background(Runnable {
                runCatching {
                    val bestPath = bridgeStore.seedlingDexPath(snapshot.classLoader, snapshot.apkPath)
                    callback.onReady(snapshot.classLoader, bestPath)
                }.onFailure {
                    Logger.e(tag, "Immediate seedling callback failed", it)
                }
            })
        } else {
            synchronized(callbackLock) {
                Logger.d(tag, "Seedling callback registered, pending=${seedlingCallbacks.size}")
            }
        }
    }

    /** 首捕与换代重捕共用:对【全部】注册回调重放到给定 CL(消费者按 CL 身份幂等)。 */
    fun executePluginCallbacks(cl: ClassLoader, strategy: String, apkPath: String) {
        val batch = synchronized(callbackLock) { pluginCallbacks.toList() }
        if (batch.isEmpty()) return

        Logger.i(tag, "Executing ${batch.size} callbacks (strategy=$strategy, apkPath=${apkPath.takeLast(60)})")
        for (callback in batch) {
            runCatching {
                callback.onReady(cl, apkPath)
            }.onFailure {
                Logger.e(tag, "Plugin callback failed", it)
            }
        }
    }

    /** 首捕与多代分发共用:对【全部】注册回调重放。挑文件和调用回调都在后台线程做。 */
    fun executeSeedlingCallbacks(cl: ClassLoader, apkPath: String) {
        val callbacks = synchronized(callbackLock) { seedlingCallbacks.toList() }
        if (callbacks.isEmpty()) return

        Logger.i(tag, "Executing ${callbacks.size} seedling callback(s)")
        Logger.xposedLog(tag, "Executing ${callbacks.size} seedling callback(s), apkPath=${apkPath.takeLast(60)}")
        background(Runnable {
            val bestPath = runCatching { bridgeStore.seedlingDexPath(cl, apkPath) }
                .getOrElse {
                    Logger.e(tag, "Seedling DEX selection failed", it)
                    apkPath
                }
            callbacks.forEach { callback ->
                runCatching { callback.onReady(cl, bestPath) }
                    .onFailure { Logger.e(tag, "Seedling callback failed", it) }
            }
        })
    }
}
