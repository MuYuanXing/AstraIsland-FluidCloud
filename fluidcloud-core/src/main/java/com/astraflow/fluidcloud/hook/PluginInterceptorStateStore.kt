package com.astraflow.fluidcloud.hook

data class PluginCaptureSnapshot(
    val classLoader: ClassLoader,
    val apkPath: String,
)

class PluginInterceptorStateStore {

    // All state transitions are guarded by this lock.
    // The lock is private — external code must use the atomic compound
    // operations provided below instead of acquiring the lock directly.
    private val lock = Any()

    private var pluginCaptured = false
    private var pluginClassLoader: ClassLoader? = null
    private var pluginApkPath: String? = null

    private var seedlingCaptured = false
    private var seedlingClassLoader: ClassLoader? = null
    private var seedlingApkPath: String? = null

    val isPluginCaptured: Boolean
        get() = synchronized(lock) { pluginCaptured }

    val isSeedlingCaptured: Boolean
        get() = synchronized(lock) { seedlingCaptured }

    val currentPluginClassLoader: ClassLoader?
        get() = synchronized(lock) { pluginClassLoader }

    // ======== Atomic compound operations ========

    /**
     * Atomically check capture state and return a snapshot if captured,
     * OR register [callback] as a pending callback and return null.
     *
     * This replaces the previous pattern where callers would acquire the
     * lock, call [pluginSnapshotIfCaptured], release the lock, then manually
     * add to a callback list outside the lock — which was a TOCTOU split.
     */
    fun snapshotOrEnqueuePlugin(
        enqueue: () -> Unit,
    ): PluginCaptureSnapshot? = synchronized(lock) {
        val classLoader = pluginClassLoader
        if (pluginCaptured && classLoader != null) {
            PluginCaptureSnapshot(
                classLoader = classLoader,
                apkPath = pluginApkPath.orEmpty(),
            )
        } else {
            enqueue()
            null
        }
    }

    fun snapshotOrEnqueueSeedling(
        enqueue: () -> Unit,
    ): PluginCaptureSnapshot? = synchronized(lock) {
        val classLoader = seedlingClassLoader
        if (seedlingCaptured && classLoader != null) {
            PluginCaptureSnapshot(
                classLoader = classLoader,
                apkPath = seedlingApkPath.orEmpty(),
            )
        } else {
            enqueue()
            null
        }
    }

    /**
     * 标记 plugin CL 已捕获。同一实例重复上报返回 false(去重);
     * 【不同的新实例】视为换代重捕,更新记录并返回 true ——
     * 运行期插件可能被系统卸载重建(长待机/内存回收后加载出新一代 ClassLoader,
     * seedling 侧已实证多代 CL 并存),新 CL 上的 Class 与旧 CL 上的
     * 是两批不同对象,不重捕则插件方法上的 hook 全部留在死 CL 上静默失效。
     * 旧 CL 引用在此被覆盖释放,不会因状态-store 强 pin 造成 ClassLoader 泄漏。
     */
    fun markPluginCaptured(cl: ClassLoader, apkPath: String): Boolean = synchronized(lock) {
        if (pluginCaptured && pluginClassLoader === cl) return@synchronized false
        pluginCaptured = true
        pluginClassLoader = cl
        pluginApkPath = apkPath
        true
    }

    /** 已捕获的种子 CL 实例 (身份比对用, 不参与状态流转)。 */
    val capturedSeedlingClassLoader: ClassLoader?
        get() = synchronized(lock) { seedlingClassLoader }

    fun markSeedlingCaptured(cl: ClassLoader, apkPath: String): Boolean = synchronized(lock) {
        if (seedlingCaptured) return@synchronized false
        seedlingCaptured = true
        seedlingClassLoader = cl
        seedlingApkPath = apkPath
        true
    }
}
