package com.astraflow.fluidcloud.hook

import org.luckypray.dexkit.DexKitBridge

/**
 * 卡片引擎插件(SeedlingSdk)交给回调的文件:在候选 DEX 文件里挑出含卡片数据类的那一份。
 *
 * 挑的时候逐个临时建代码搜索实例查一遍,查完马上关掉,不一直开着;挑中的路径记下,同一次捕获里后到的类加载器直接用。
 * 建实例、按文字查类都慢,调用方在后台线程调用,不占系统界面主线程。
 */
class PluginDexKitBridgeStore(
    private val tag: String,
) {

    /** 卡片数据类的特征文字:含这段文字的那份文件就是要找的 */
    private val tokStatusBarCapsuleData = "StatusBarCapsuleData(index="

    private val lock = Any()

    @Volatile
    private var seedlingDexPath: String? = null

    /** 卡片引擎插件用哪份文件;哪份都没有卡片数据类时用插件本身的路径(不记下,后到的类加载器再挑)。 */
    fun seedlingDexPath(cl: ClassLoader, apkPath: String): String {
        synchronized(lock) { seedlingDexPath?.let { return it } }
        val found = findSeedlingDexPath(cl, apkPath)
        synchronized(lock) {
            seedlingDexPath?.let { return it }
            if (found != null) {
                seedlingDexPath = found
                return found
            }
        }
        Logger.xposedLog(tag, "WARN: No DEX contains target classes, falling back to original: $apkPath")
        return apkPath
    }

    fun invalidateSeedlingDexPath() {
        synchronized(lock) {
            seedlingDexPath = null
        }
    }

    private fun findSeedlingDexPath(cl: ClassLoader, apkPath: String): String? {
        val candidatePaths = PluginDexPathResolver.collectSeedlingDexPaths(cl, apkPath)
        Logger.xposedLog(tag, "Seedling candidate DEX paths: ${candidatePaths.size}")
        candidatePaths.forEachIndexed { index, path ->
            Logger.xposedLog(tag, "  [$index] $path")
        }

        for (path in candidatePaths) {
            // 以前在找代码的途中把系统界面带崩过的文件不再找;找的时候先记一笔、找完擦掉(见 CrashGuard)
            val file = java.io.File(path)
            val guardKey = "$path|${file.length()}|${file.lastModified()}"
            if (CrashGuard.isSkipped(guardKey)) continue
            val found = CrashGuard.risky(guardKey) {
                val testBridge = runCatching { DexKitBridge.create(path) }.getOrNull() ?: return@risky false
                // 查完就关:挑中的也不留着,要用的一方自己在后台按需建、用完就关
                testBridge.use { bridge -> hasSeedlingClasses(bridge, cl, path) }
            }
            if (found) return path
        }
        return null
    }

    private fun hasSeedlingClasses(testBridge: DexKitBridge, cl: ClassLoader, path: String): Boolean {
        val hasTargetClasses = runCatching {
            val result = testBridge.findClass {
                matcher { usingStrings(tokStatusBarCapsuleData) }
            }
            result.isNotEmpty()
        }.getOrElse { false }

        val hasSizeClass = if (!hasTargetClasses) {
            runCatching {
                val result = testBridge.findClass {
                    matcher { addMethod { name = "<init>"; paramCount = 20 } }
                }
                result.any { resultClass ->
                    val cls = runCatching { resultClass.getInstance(cl) }.getOrNull()
                    cls?.declaringClass?.declaredClasses?.size?.let { it >= 5 } == true
                }
            }.getOrElse { false }
        } else {
            false
        }

        if (hasTargetClasses || hasSizeClass) {
            Logger.xposedLog(
                tag,
                "Found correct SeedlingSdk DEX: $path (hasTarget=$hasTargetClasses, hasSize=$hasSizeClass)",
            )
            return true
        }
        val classCount = runCatching {
            testBridge.findClass { matcher { addMethod { name = "<init>" } } }.size
        }.getOrElse { -1 }
        Logger.xposedLog(tag, "DEX $path has ~$classCount classes but no target — skipping")
        return false
    }
}
