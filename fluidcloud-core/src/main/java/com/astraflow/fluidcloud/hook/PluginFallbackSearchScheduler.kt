package com.astraflow.fluidcloud.hook

import android.os.Handler

object PluginFallbackSearchScheduler {

    private val retryDelays = longArrayOf(5000, 10000, 20000)

    fun schedule(
        tag: String,
        mainHandler: Handler,
        stateStore: PluginInterceptorStateStore,
        onClassLoaderCreated: (ClassLoader, String, String) -> Unit,
        onTrySeedlingReflection: (ClassLoader) -> Unit,
    ) {
        for ((index, delay) in retryDelays.withIndex()) {
            mainHandler.postDelayed({
                if (stateStore.isPluginCaptured && stateStore.isSeedlingCaptured) {
                    Logger.d(tag, "S4: retry #${index + 1} skipped (both captured)")
                    return@postDelayed
                }
                Logger.d(tag, "S4: retry #${index + 1}/${retryDelays.size} at ${delay}ms")

                scanThreadContextClassLoaders(
                    tag = tag,
                    retryIndex = index + 1,
                    onClassLoaderCreated = onClassLoaderCreated,
                )

                if (stateStore.isPluginCaptured && !stateStore.isSeedlingCaptured) {
                    val pluginClassLoader = stateStore.currentPluginClassLoader
                    if (pluginClassLoader != null) {
                        Logger.d(
                            tag,
                            "S4+S6-b: retry #${index + 1} — Plugin captured but SeedlingSdk not, trying PluginManager reflection...",
                        )
                        onTrySeedlingReflection(pluginClassLoader)
                    }
                }
            }, delay)
        }
    }

    private fun scanThreadContextClassLoaders(
        tag: String,
        retryIndex: Int,
        onClassLoaderCreated: (ClassLoader, String, String) -> Unit,
    ) {
        runCatching {
            val checkedIds = HashSet<Int>()
            for (thread in Thread.getAllStackTraces().keys) {
                val classLoader = thread.contextClassLoader ?: continue
                val classLoaderId = System.identityHashCode(classLoader)
                if (!checkedIds.add(classLoaderId)) continue

                val dexPath = PluginDexPathResolver.extractDexPath(classLoader) ?: continue
                if (PluginDexPathResolver.isPluginPath(dexPath)) {
                    onClassLoaderCreated(classLoader, dexPath, "S4_ThreadScan_retry$retryIndex")
                }
            }
        }.onFailure {
            Logger.e(tag, "S4: Thread scan failed (retry #$retryIndex)", it)
        }
    }
}
