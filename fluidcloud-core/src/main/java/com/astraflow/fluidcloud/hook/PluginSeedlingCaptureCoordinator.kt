package com.astraflow.fluidcloud.hook

import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method

class PluginSeedlingCaptureCoordinator(
    private val tag: String,
    private val isSeedlingCaptured: () -> Boolean,
    private val capturedSeedlingClassLoader: () -> ClassLoader? = { null },
    private val onClassLoaderCreated: (ClassLoader, String, String) -> Unit,
    private val onSeedlingCaptured: (ClassLoader, String, String) -> Unit,
    /** 检查用:换掉「在插件的类加载器上找 PluginManager 类」 */
    private val loadPluginManager: ((ClassLoader) -> Class<*>)? = null,
    /** 检查用:换掉「在方法后面装挂钩」 */
    private val hookAfter: (Method, (XposedInterface.Chain, Any?) -> Any?) -> XposedInterface.HookHandle? = HookHelper::hookMethodAfter,
) {

    // 系统流体云插件的类加载器与插件管理类:插件就是经它们装进系统界面的
    private val seedlingClassLoaderFqn = "com.oplus.seedling.sdk.plugin.classloader.SeedlingClassLoader"
    private val pantaBaseDexClassLoaderFqn = "com.oplus.pantanal.classloader.PantaBaseDexClassLoader"
    private val pluginManagerFqn = "com.oplus.seedling.sdk.plugin.PluginManager"

    /** 已武装 PluginManager 的 plugin CL(弱引用,不 pin 死掉的旧 CL);换代重捕按身份放行重装。 */
    private var hookedPluginManagerCl = java.lang.ref.WeakReference<ClassLoader>(null)

    /** 装在当前这一代插件 PluginManager 上的挂钩;换了新一代插件时撤掉 */
    private var pluginManagerHook: XposedInterface.HookHandle? = null

    fun hookDexClassLoader() {
        val seedlingHooked = runCatching {
            val seedlingClassLoaderClass = Class.forName(seedlingClassLoaderFqn)
            var hookedCount = 0
            for (ctor in seedlingClassLoaderClass.declaredConstructors) {
                HookHelper.hookConstructor(ctor, afterCallback = { chain, _ ->
                    val classLoader = chain.thisObject as? ClassLoader ?: return@hookConstructor null
                    val dexPath = PluginDexPathResolver.extractDexPath(classLoader) ?: return@hookConstructor null
                    onClassLoaderCreated(classLoader, dexPath, "S5_SeedlingCL")
                    null
                })
                hookedCount++
            }
            Logger.xposedLog(tag, "S5a: Hooked SeedlingClassLoader ($hookedCount constructors)")
            true
        }.getOrElse {
            Logger.xposedLog(tag, "S5a: SeedlingClassLoader not found: ${it.message}")
            false
        }

        val pantaHooked = if (!seedlingHooked) {
            runCatching {
                val pantaClass = Class.forName(pantaBaseDexClassLoaderFqn)
                var hookedCount = 0
                for (ctor in pantaClass.declaredConstructors) {
                    HookHelper.hookConstructor(ctor, afterCallback = { chain, _ ->
                        val classLoader = chain.thisObject as? ClassLoader ?: return@hookConstructor null
                        val dexPath = PluginDexPathResolver.extractDexPath(classLoader) ?: return@hookConstructor null
                        onClassLoaderCreated(classLoader, dexPath, "S5_PantaBDCL")
                        null
                    })
                    hookedCount++
                }
                Logger.xposedLog(tag, "S5b: Hooked PantaBaseDexClassLoader ($hookedCount constructors)")
                true
            }.getOrElse {
                Logger.xposedLog(tag, "S5b: PantaBaseDexClassLoader not found")
                false
            }
        } else {
            false
        }

        if (!seedlingHooked && !pantaHooked) {
            runCatching {
                val dexClassLoaderClass = Class.forName("dalvik.system.DexClassLoader")
                val ctor = dexClassLoaderClass.getDeclaredConstructor(
                    String::class.java,
                    String::class.java,
                    String::class.java,
                    ClassLoader::class.java,
                )
                HookHelper.hookConstructor(ctor, afterCallback = { chain, _ ->
                    val dexPath = chain.getArg(0) as? String ?: return@hookConstructor null
                    val classLoader = chain.thisObject as? ClassLoader ?: return@hookConstructor null
                    onClassLoaderCreated(classLoader, dexPath, "S5_DexClassLoader")
                    null
                })
                Logger.xposedLog(tag, "S5c: Hooked DexClassLoader (fallback)")
            }
        }
    }

    fun hookPluginManagerGainClassLoader(pluginCL: ClassLoader) {
        // 同一 plugin CL 只武装一次;换代重捕的新 CL 必须重新武装——
        // Class.forName(..., pluginCL) 在新 CL 上得到的是新 Class 对象,旧 hook 不在其上。
        // 早先此处用 isSeedlingCaptured() 当总闸:种子已捕获后,换代的新
        // PluginManager 永远不会被 hook,种子若随后也换代则丢失捕获通路。
        if (hookedPluginManagerCl.get() === pluginCL) return
        // 换了新一代插件:先撤掉装在上一代 PluginManager 上的挂钩——旧一代已经没人用,挂钩留着会让它整份一直占着内存
        pluginManagerHook?.let { previous ->
            pluginManagerHook = null
            runCatching { previous.unhook() }
                .onFailure { Logger.xposedLog(tag, "S6-a: unhook previous generation failed: ${it.message}") }
        }

        runCatching {
            val pluginManagerClass = loadPluginManager?.invoke(pluginCL) ?: Class.forName(
                pluginManagerFqn,
                false,
                pluginCL,
            )
            Logger.xposedLog(tag, "S6-a: PluginManager class loaded: ${pluginManagerClass.name}")

            val targetMethod = runCatching {
                pluginManagerClass.getDeclaredMethod("gainPluginClassLoader").also { it.isAccessible = true }
            }.getOrElse {
                Logger.xposedLog(tag, "S6-a: gainPluginClassLoader not found by name, searching by signature...")
                val dexClassLoaderClass = Class.forName("dalvik.system.DexClassLoader")
                pluginManagerClass.declaredMethods.firstOrNull { method ->
                    method.parameterTypes.isEmpty() &&
                        method.returnType == dexClassLoaderClass &&
                        !java.lang.reflect.Modifier.isStatic(method.modifiers)
                }?.also { it.isAccessible = true }
            } ?: runCatching {
                pluginManagerClass.getDeclaredMethod("getSeedlingPluginClassLoader\$pantanal_client_release")
                    .also {
                        it.isAccessible = true
                        Logger.xposedLog(
                            tag,
                            "S6-a: Using fallback method getSeedlingPluginClassLoader\$pantanal_client_release()",
                        )
                    }
            }.getOrNull()

            if (targetMethod == null) {
                Logger.xposedLog(tag, "S6-a: No hookable PluginManager method found, relying on S6-b")
                return
            }

            pluginManagerHook = hookAfter(targetMethod) { _, result ->
                // 已捕获也继续 — 首个捕获可能是 SDK 壳 CL,
                // 后到的不同实例 (含 ULE 业务类) 仍要上报, 消费者按锚点自筛
                val classLoader = result as? ClassLoader ?: return@hookAfter result
                if (isSeedlingCaptured() && capturedSeedlingClassLoader() === classLoader) return@hookAfter result
                val dexPath = PluginDexPathResolver.extractDexPath(classLoader) ?: return@hookAfter result
                if (dexPath.contains("SeedlingSdk", ignoreCase = true)) {
                    Logger.xposedLog(tag, "S6-a: SeedlingSdk CL captured via PluginManager: ${classLoader.javaClass.name}")
                    onSeedlingCaptured(
                        classLoader,
                        PluginDexPathResolver.extractPluginApkPath(dexPath),
                        "S6a_PluginManager",
                    )
                }
                result
            }
            Logger.xposedLog(tag, "S6-a: Hooked ${targetMethod.name}() on PluginManager")
            hookedPluginManagerCl = java.lang.ref.WeakReference(pluginCL)
        }.onFailure {
            Logger.xposedLog(tag, "S6-a: Hook failed: ${it.message}, relying on S6-b fallback")
        }
    }

    fun trySeedlingFromPluginManagerReflection(pluginCL: ClassLoader) {
        if (isSeedlingCaptured()) return

        runCatching {
            val pluginManagerClass = Class.forName(
                pluginManagerFqn,
                false,
                pluginCL,
            )

            val delegateField = pluginManagerClass.getDeclaredField("sInstance\$delegate")
            delegateField.isAccessible = true
            val delegate = delegateField.get(null)
            if (delegate == null) {
                Logger.xposedLog(tag, "S6-b: sInstance\$delegate is null (PluginManager not initialized)")
                return
            }

            val pluginManagerInstance = runCatching {
                val getValueMethod = delegate.javaClass.declaredMethods.firstOrNull { method ->
                    method.name == "getValue" && method.parameterTypes.isEmpty()
                }
                getValueMethod?.let {
                    it.isAccessible = true
                    it.invoke(delegate)
                }
            }.getOrNull() ?: runCatching {
                val valueField = delegate.javaClass.declaredFields.firstOrNull { field ->
                    field.name == "_value" || field.name == "value"
                }
                valueField?.let {
                    it.isAccessible = true
                    val value = it.get(delegate)
                    if (value != null && value.javaClass.name != "kotlin.UNINITIALIZED_VALUE") value else null
                }
            }.getOrNull()

            if (pluginManagerInstance == null) {
                Logger.xposedLog(tag, "S6-b: PluginManager sInstance not yet initialized (lazy not triggered)")
                return
            }

            val classLoaderField = pluginManagerClass.getDeclaredField("pluginDexClassLoader")
            classLoaderField.isAccessible = true
            val seedlingClassLoader = classLoaderField.get(pluginManagerInstance) as? ClassLoader
            if (seedlingClassLoader == null) {
                Logger.xposedLog(tag, "S6-b: pluginDexClassLoader is null (SeedlingSdk not loaded yet)")
                return
            }

            val dexPath = PluginDexPathResolver.extractDexPath(seedlingClassLoader)
            if (dexPath != null && dexPath.contains("SeedlingSdk", ignoreCase = true)) {
                Logger.xposedLog(
                    tag,
                    "S6-b: SeedlingSdk CL captured via PluginManager reflection: ${seedlingClassLoader.javaClass.name}",
                )
                onSeedlingCaptured(
                    seedlingClassLoader,
                    PluginDexPathResolver.extractPluginApkPath(dexPath),
                    "S6b_PluginManager_reflection",
                )
            } else {
                Logger.xposedLog(
                    tag,
                    "S6-b: pluginDexClassLoader exists but path doesn't contain SeedlingSdk: ${dexPath?.takeLast(60)}",
                )
            }
        }.onFailure {
            Logger.xposedLog(tag, "S6-b: PluginManager reflection failed: ${it.message}")
        }
    }
}
