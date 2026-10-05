package com.astraflow.fluidcloud.hook

import java.io.File

object PluginDexPathResolver {

    private const val TAG = "PluginInterceptor"
    private const val pluginManagerFqn = "com.oplus.seedling.sdk.plugin.PluginManager"

    fun isPluginPath(path: String): Boolean {
        return path.contains("SystemUIPlugin")
            || path.contains("SeedlingSdk", ignoreCase = true)
            || path.contains("seedlingsdk", ignoreCase = true)
            || path.contains("seedling_plugin", ignoreCase = true)
    }

    /**
     * 从 dexPath 中提取 Plugin APK 的单一路径。
     *
     * BaseDexClassLoader 的 dexPath 可能以 `:` 分隔多个路径，
     * DexKit.create() 只能接受单一文件路径。
     */
    fun extractPluginApkPath(dexPath: String): String {
        if (!dexPath.contains(":")) return dexPath
        val segments = dexPath.split(":")
        return segments.firstOrNull { isPluginPath(it) } ?: dexPath
    }

    fun extractDexPath(cl: ClassLoader): String? {
        runCatching {
            val pathListField = Class.forName("dalvik.system.BaseDexClassLoader")
                .getDeclaredField("pathList").apply { isAccessible = true }
            val pathList = pathListField.get(cl) ?: return@runCatching null
            val str = pathList.toString()
            val apkMatch = Regex("""zip file "([^"]+\.apk)"""").find(str)
            if (apkMatch != null) return@runCatching apkMatch.groupValues[1]
            val dexMatch = Regex("""(?:dex file|zip file) "([^"]+\.(dex|jar))"""").find(str)
            dexMatch?.groupValues?.get(1)
        }.getOrNull()?.let { return it }

        runCatching {
            val baseDexCLClass = Class.forName("dalvik.system.BaseDexClassLoader")
            val pathListField = baseDexCLClass.getDeclaredField("pathList")
            pathListField.isAccessible = true
            val pathList = pathListField.get(cl) ?: return@runCatching null
            val dexElementsField = pathList.javaClass.getDeclaredField("dexElements")
            dexElementsField.isAccessible = true
            val dexElements = dexElementsField.get(pathList) as? Array<*> ?: return@runCatching null
            for (element in dexElements) {
                if (element == null) continue
                val pathField = runCatching {
                    element.javaClass.getDeclaredField("path").apply { isAccessible = true }
                }.getOrNull() ?: continue
                val file = pathField.get(element) as? File ?: continue
                if (file.exists()) return@runCatching file.absolutePath
            }
            null
        }.getOrNull()?.let { return it }

        runCatching {
            val match = Regex("""/[^\]\s,]+\.(apk|dex|jar)""").find(cl.toString())
            match?.value
        }.getOrNull()?.let { return it }

        return null
    }

    fun collectSeedlingDexPaths(cl: ClassLoader, apkPath: String): List<String> {
        val paths = mutableListOf<String>()

        if (apkPath.isNotEmpty() && File(apkPath).exists()) {
            paths.add(apkPath)
        }

        extractAllDexPaths(cl).forEach { path ->
            if (path !in paths && File(path).exists()) paths.add(path)
        }

        // Resolve actual user ID instead of hardcoding "0"
        val userId = runCatching {
            android.os.Process.myUid() / 100000
        }.getOrDefault(0)
        val knownPaths = listOf(
            "/data/user_de/$userId/com.android.systemui/files/seedlingsdk_plugin/SeedlingSdk.apk",
            "/data/user/$userId/com.android.systemui/files/seedlingsdk_plugin/SeedlingSdk.apk",
            // Also try user 0 as fallback for system user scenarios
            "/data/user_de/0/com.android.systemui/files/seedlingsdk_plugin/SeedlingSdk.apk",
            "/data/user/0/com.android.systemui/files/seedlingsdk_plugin/SeedlingSdk.apk",
        )
        for (path in knownPaths) {
            if (path !in paths && File(path).exists()) paths.add(path)
        }

        val parent = cl.parent
        if (parent != null && parent.javaClass.name != "java.lang.BootClassLoader") {
            extractAllDexPaths(parent).forEach { path ->
                if (path !in paths && File(path).exists() && isPluginPath(path)) {
                    paths.add(path)
                }
            }
        }

        runCatching {
            val pmClass = Class.forName(
                pluginManagerFqn, false, cl
            )
            val delegateField = pmClass.getDeclaredField("sInstance\$delegate")
            delegateField.isAccessible = true
            val delegate = delegateField.get(null) ?: return@runCatching
            val pmInstance = runCatching {
                val getValueMethod = delegate.javaClass.declaredMethods.firstOrNull { method ->
                    method.name == "getValue" && method.parameterTypes.isEmpty()
                }
                getValueMethod?.let {
                    it.isAccessible = true
                    it.invoke(delegate)
                }
            }.getOrNull() ?: return@runCatching

            val clField = pmClass.getDeclaredField("pluginDexClassLoader")
            clField.isAccessible = true
            val innerCL = clField.get(pmInstance) as? ClassLoader ?: return@runCatching
            val innerIdentity = System.identityHashCode(innerCL)
            val outerIdentity = System.identityHashCode(cl)
            if (innerIdentity != outerIdentity) {
                Logger.xposedLog(
                    TAG,
                    "PluginManager has different inner CL: ${innerCL.javaClass.name} (id=$innerIdentity vs outer=$outerIdentity)",
                )
                extractAllDexPaths(innerCL).forEach { path ->
                    if (path !in paths && File(path).exists()) paths.add(path)
                }
            }
        }.onFailure {
            Logger.d(TAG, "PluginManager inner CL extraction failed: ${it.message}")
        }

        return paths
    }

    private fun extractAllDexPaths(cl: ClassLoader): List<String> {
        val paths = mutableListOf<String>()
        runCatching {
            val baseDexCLClass = Class.forName("dalvik.system.BaseDexClassLoader")
            val pathListField = baseDexCLClass.getDeclaredField("pathList")
            pathListField.isAccessible = true
            val pathList = pathListField.get(cl) ?: return@runCatching

            val dexElementsField = pathList.javaClass.getDeclaredField("dexElements")
            dexElementsField.isAccessible = true
            val dexElements = dexElementsField.get(pathList) as? Array<*> ?: return@runCatching

            for (element in dexElements) {
                if (element == null) continue
                runCatching {
                    val pathField = element.javaClass.getDeclaredField("path")
                    pathField.isAccessible = true
                    val file = pathField.get(element) as? File
                    if (file != null) paths.add(file.absolutePath)
                }
                if (paths.isEmpty() || paths.last().isEmpty()) {
                    runCatching {
                        val str = element.toString()
                        val match = Regex("""/[^\]\s,]+\.(apk|dex|jar)""").find(str)
                        if (match != null && (paths.isEmpty() || paths.last() != match.value)) {
                            paths.add(match.value)
                        }
                    }
                }
            }
        }.onFailure {
            Logger.d(TAG, "extractAllDexPaths failed: ${it.message}")
        }

        if (paths.isEmpty()) {
            runCatching {
                val baseDexCLClass = Class.forName("dalvik.system.BaseDexClassLoader")
                val pathListField = baseDexCLClass.getDeclaredField("pathList")
                pathListField.isAccessible = true
                val pathList = pathListField.get(cl)
                val str = pathList?.toString() ?: return@runCatching
                Regex("""/[^\]\s,"]+\.(apk|dex|jar)""").findAll(str).forEach { match ->
                    paths.add(match.value)
                }
            }
        }

        return paths.distinct()
    }
}
