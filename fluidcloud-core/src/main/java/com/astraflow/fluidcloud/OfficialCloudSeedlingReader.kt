package com.astraflow.fluidcloud

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 系统服务卡片(系统流体云里不是通知变成的那一种)的读法,只用 OPPO 流体云接口里写明不改名的部分(现行规则「系统事件接入」):
 * - 服务名单:DecisionObserver.onListChanged 送来一个显示位置此刻有哪些服务(ServiceInfo);名单里没有了就是结束了;
 * - 卡片内容:UIDataInterceptor.onReceiveUIData 送来一张卡片的内容(PantanalUIData 里的 SeedlingUIData);
 * - 撤掉名单监听:DecisionManager.unregisterMultiInstanceListCallback,这个显示位置的记录管理不用了,它的卡片一并结束。
 * 每个显示位置(状态栏、通知栏)的记录管理各记一份,和系统流体云插件一样照名单增删、照内容更新:被「关掉的应用」不收,
 * 通知栏只收实时状态一类,强制重建的服务丢掉旧内容。每次变了把这一份整份交给 OfficialCloudSource。
 * 不读系统流体云插件内部的记录和日志文字;插件内部的对象只按它身上 OPPO 接口里的类型认(显示位置、服务信息、卡片)。
 */
object OfficialCloudSeedlingReader {
    private const val TAG = "OfficialCloud"
    const val ENTRANCE = "pantanal.app.bean.Entrance"
    const val CARD = "pantanal.app.Card"
    const val PANTANAL_DATA = "pantanal.app.bean.PantanalUIData"
    const val SEEDLING_UI_DATA = "com.oplus.seedling.sdk.seedling.SeedlingUIData"
    const val MANAGER = "pantanal.decision.DecisionManager"
    /** 系统流体云插件照这个设置不收某些应用的卡片(ALL:申请了安卓 16 实时通知权限的应用;否则是用分号隔开的包名) */
    private const val DISABLE_SETTING = "livealert_disable_seedling"
    private const val DISABLE_ALL = "ALL"
    private const val PROMOTED_PERMISSION = "android.permission.POST_PROMOTED_NOTIFICATIONS"
    /** 实时状态一类的服务(通知栏只收这一类) */
    private const val SEEDLING_TYPE_LIVE = 1
    private const val MAX_SERVICES = 128

    /**
     * 一张系统服务卡片在一个显示位置上此刻的样子:事件编号(和系统流体云给这件事的编号一样:服务号|服务实例|用户|服务时间戳)、
     * 用户、显示位置,以及读到的 OPPO 接口对象——服务信息、最近一份卡片内容(还没送到时为空)、系统卡片和卡片引擎(点按钮用)。
     */
    class Entry(val key: String, val user: Int, val entrance: String, val service: Any, val ui: Any?, val card: Any?, val seedling: Any?)

    /** 一个显示位置的记录管理:编号、显示位置、收到过名单没有、此刻的卡片(照名单的先后) */
    private class Owner(val id: String, val entrance: String, manager: Any) {
        val manager = WeakReference(manager)
        var listed = false
        val entries = LinkedHashMap<String, Entry>()
    }

    /** 记录管理 → 它那一份。记录管理随插件释放,这里不留住它。 */
    private val owners = WeakHashMap<Any, Owner>()
    /** 编号 → 那一份(记录管理已经不在了还没撤的,下次交数据时撤掉) */
    private val byId = LinkedHashMap<String, Owner>()
    private val ownerSequence = AtomicLong()
    /** 交出去的每一份都带一个递增的版本号,和通知变成的胶囊共用一个(OfficialCloudHook.nextRevision) */
    @Volatile var revisions: () -> Long = OfficialCloudHook::nextRevision
    /** 整份交出去、撤掉整份的去处(检查里换成记下来) */
    @Volatile var deliver: (owner: String, revision: Long, entries: List<Entry>) -> Unit =
        { owner, revision, entries -> OfficialCloudSource.receive(owner, revision, CloudChange.REPLACE, entries, false) }
    @Volatile var drop: (Set<String>) -> Unit = { OfficialCloudSource.dropOwners(it) }

    /** 服务名单送到(系统把名单交给这个显示位置的记录管理之前) */
    fun onServiceList(observer: Any?, list: Any?) {
        val manager = managerOf(observer ?: return) ?: return
        val entrance = entranceOf(manager) ?: return
        val services = (list as? List<*>)?.filterNotNull()?.take(MAX_SERVICES) ?: return
        val context = contextOf(manager)
        val user = currentUser()
        val kept = services.filter { service ->
            !disabled(context, OfficialCloudReflection.text(OfficialCloudReflection.call(OfficialCloudReflection.call(service, "getNewSeedlingCardOptions"), "getDataSourcePkgName"))) &&
                (entrance != NOTIFICATION || (OfficialCloudReflection.call(service, "getSeedlingType") as? Number)?.toInt() == SEEDLING_TYPE_LIVE)
        }
        val adoptions = mutableListOf<Entry>()
        synchronized(this) {
            val owner = ownerOf(manager, entrance)
            val next = LinkedHashMap<String, Entry>()
            // 记录管理手上已有的记录(挂钩装好之前就在的卡片从这里认领),只在有卡片还没有内容时才找一次
            val existing by lazy { modelsOf(manager) }
            for (service in kept) {
                val key = keyOf(service, user) ?: continue
                val old = owner.entries[key]
                // 强制重建的服务:系统换一张新卡片,旧内容不要了,等新内容送到
                val rebuild = OfficialCloudReflection.call(service, "getForceRebuild") == true
                next[key] = when {
                    old != null && !rebuild && old.ui != null -> Entry(key, user, entrance, service, old.ui, old.card, old.seedling)
                    rebuild -> Entry(key, user, entrance, service, null, null, null)
                    else -> adopt(existing, key, user, entrance, service)?.also(adoptions::add) ?: Entry(key, user, entrance, service, null, null, null)
                }
            }
            val added = next.keys - owner.entries.keys
            val removed = owner.entries.keys - next.keys
            owner.entries.clear()
            owner.entries.putAll(next)
            owner.listed = true
            if (added.isNotEmpty() || removed.isNotEmpty() || adoptions.isNotEmpty()) com.astraflow.fluidcloud.hook.Log.i(TAG,
                "service list: ${owner.id}($entrance) size=${next.size} added=${added.map(::service)} removed=${removed.map(::service)} " +
                    "adopted=${adoptions.map { service(it.key) }} filtered=${services.size - kept.size}")
            emit(owner)
        }
        // 认领到的状态栏卡片:让位也照样认领,星河岛定下要让位时把最近一份内容再交一次,不等它下一次更新
        adoptions.filter { it.entrance == STATUS_BAR }.forEach { entry ->
            runCatching { entry.card?.let { OfficialCloudYield.adopt(entry.key, it, entry.service, entry.user) } }
        }
        OfficialCloudHook.onReaderData(manager.javaClass.classLoader)
    }

    /**
     * 挂钩装好之前就在的卡片(系统界面刚启动、插件重新加载时,挂钩在后台找挂点这一会儿里来的):记录管理手上已有这件事的记录时,
     * 从记录身上取最近一份内容和系统卡片(都是 OPPO 接口里的对象),不用等它下一次更新。
     */
    private fun adopt(models: List<Any>, key: String, user: Int, entrance: String, service: Any): Entry? {
        val model = models.firstOrNull { model ->
            OfficialCloudReflection.fieldOfType(model, OfficialCloudReflection.SERVICE_INFO)?.let { keyOf(it, user) } == key
        } ?: return null
        val card = OfficialCloudReflection.fieldOfType(model, CARD)
        val ui = OfficialCloudReflection.call(card?.let { OfficialCloudReflection.fieldOfType(it, PANTANAL_DATA) }, "getSeedlingUIData")
            ?: OfficialCloudReflection.fieldOfType(model, SEEDLING_UI_DATA) ?: return null
        val seedling = OfficialCloudReflection.fieldOfType(model, OfficialCloudReflection.SEEDLING) ?: OfficialCloudReflection.call(card, "getInnerCard")
        return Entry(key, user, entrance, service, ui, card, seedling)
    }

    /** 记录管理手上的记录(带服务信息的对象):在它身上的集合里,或者再往下一层的集合里 */
    private fun modelsOf(manager: Any): List<Any> {
        val found = java.util.IdentityHashMap<Any, Unit>()
        fun collect(items: Iterable<*>) = items.take(MAX_SERVICES).forEach { item ->
            if (item != null && OfficialCloudReflection.fields(item.javaClass).any { it.type.name == OfficialCloudReflection.SERVICE_INFO }) found[item] = Unit
        }
        fun visit(value: Any?, depth: Int) {
            when (value) {
                null -> Unit
                is Collection<*> -> collect(value)
                is Map<*, *> -> collect(value.values)
                else -> if (depth < 2 && !value.javaClass.isPrimitive && value !is String && value !is Context) {
                    OfficialCloudReflection.fields(value.javaClass).forEach { field ->
                        visit(runCatching { field.isAccessible = true; field.get(value) }.getOrNull(), depth + 1)
                    }
                }
            }
        }
        OfficialCloudReflection.fields(manager.javaClass).forEach { field ->
            visit(runCatching { field.isAccessible = true; field.get(manager) }.getOrNull(), 1)
        }
        return found.keys.toList()
    }

    /** 卡片内容送到(系统把内容交给卡片之前) */
    fun onUIData(interceptor: Any?, data: Any?) {
        val located = locate(interceptor ?: return) ?: return
        val ui = OfficialCloudReflection.call(data, "getSeedlingUIData") ?: return
        val pkg = OfficialCloudReflection.text(OfficialCloudReflection.call(ui, "getDataSourcePkgName"))
        val disabled = disabled(contextOf(located.manager), pkg)
        synchronized(this) {
            val owner = ownerOf(located.manager, located.entrance)
            val known = owner.entries[located.key]
            // 名单里没有这件事:系统流体云插件不收这份内容(事情已经结束,或者还没轮到),这里也不收
            if (known == null && owner.listed) {
                noteOnce("ignored:${owner.id}:${located.key}", "content ignored (not in list): ${owner.id} ${service(located.key)}")
                return
            }
            if (disabled) {
                if (owner.entries.remove(located.key) != null) emit(owner)
                return
            }
            val seedling = OfficialCloudReflection.fieldOfType(located.model, OfficialCloudReflection.SEEDLING)
                ?: OfficialCloudReflection.call(located.card, "getInnerCard") ?: known?.seedling
            val entry = Entry(located.key, located.user, located.entrance, located.service, ui, located.card ?: known?.card, seedling)
            owner.entries[located.key] = entry
            // 到货诊断(正式包也写进岛的日志):每个显示位置按「服务号、卡片引擎在不在、内容树有没有」签名写一行,签名变了才写
            val line = "${owner.id}(${owner.entrance}) ${OfficialCloudReflection.describe(entry)}"
            noteOnce("content:${line.substringBefore(" data=")}", "content: $line known=${known != null}")
            emit(owner)
        }
        OfficialCloudHook.onReaderData(located.manager.javaClass.classLoader)
    }

    /** 系统撤掉一个显示位置的名单监听(记录管理不用了):它的卡片一并结束 */
    fun onObserverGone(observer: Any?) {
        val manager = managerOf(observer ?: return) ?: return
        val owner = synchronized(this) { owners.remove(manager)?.also { byId.remove(it.id) } } ?: return
        com.astraflow.fluidcloud.hook.Log.i(TAG, "observer unregistered: ${owner.id}(${owner.entrance}) dropped=${owner.entries.size}")
        drop(setOf(owner.id))
    }

    /** 记录管理还在的那几份是哪些类加载器定义的(插件重新加载后,旧一代的记录管理都不在了才撤旧一代的挂钩) */
    fun liveLoaders(): Set<ClassLoader> = synchronized(this) {
        byId.values.mapNotNullTo(mutableSetOf()) { it.manager.get()?.javaClass?.classLoader }
    }

    /** 卡片内容送到的那一处属于哪件事:接收对象身上的记录管理(带显示位置)和记录(带服务信息、系统卡片)。让位也按这个认。 */
    class Located(val manager: Any, val entrance: String, val model: Any, val service: Any, val card: Any?, val key: String, val user: Int)

    fun locate(interceptor: Any): Located? {
        val manager = managerOf(interceptor) ?: return null
        val entrance = entranceOf(manager) ?: return null
        val model = modelOf(interceptor) ?: return null
        val service = OfficialCloudReflection.fieldOfType(model, OfficialCloudReflection.SERVICE_INFO) ?: return null
        val user = currentUser()
        val key = keyOf(service, user) ?: return null
        return Located(manager, entrance, model, service, OfficialCloudReflection.fieldOfType(model, CARD), key, user)
    }

    /** 事件编号,和系统流体云给这件事的编号一样:服务号|服务实例|用户|服务时间戳 */
    fun keyOf(service: Any, user: Int): String? {
        val serviceId = OfficialCloudReflection.text(OfficialCloudReflection.call(service, "getServiceId")).takeIf { it.isNotBlank() } ?: return null
        val time = (OfficialCloudReflection.call(service, "getTimeStamp") as? Number)?.toLong() ?: return null
        return "$serviceId|${OfficialCloudReflection.text(OfficialCloudReflection.call(service, "getServiceInstanceId"))}|$user|$time"
    }

    /** 这个显示位置此刻的卡片整份交给 OfficialCloudSource;顺手撤掉记录管理已经不在的那几份 */
    private fun emit(owner: Owner) {
        deliver(owner.id, revisions(), owner.entries.values.toList())
        val gone = byId.values.filter { it.manager.get() == null }.map { it.id }
        if (gone.isNotEmpty()) {
            gone.forEach(byId::remove)
            drop(gone.toSet())
        }
    }

    private fun ownerOf(manager: Any, entrance: String): Owner = owners.getOrPut(manager) {
        Owner("seedling-${ownerSequence.incrementAndGet()}", entrance, manager).also { byId[it.id] = it }
    }

    /** 带着显示位置(OPPO 接口里的 Entrance)的那个对象:名单监听者、内容接收对象身上都有它 */
    private fun managerOf(holder: Any): Any? = OfficialCloudReflection.fields(holder.javaClass).firstNotNullOfOrNull { field ->
        runCatching { field.isAccessible = true; field.get(holder) }.getOrNull()
            ?.takeIf { value -> OfficialCloudReflection.fields(value.javaClass).any { it.type.name == ENTRANCE } }
    }

    /** 内容接收对象身上那条记录:带服务信息(OPPO 接口里的 ServiceInfo)的那个对象 */
    private fun modelOf(interceptor: Any): Any? = OfficialCloudReflection.fields(interceptor.javaClass).firstNotNullOfOrNull { field ->
        runCatching { field.isAccessible = true; field.get(interceptor) }.getOrNull()
            ?.takeIf { value -> OfficialCloudReflection.fields(value.javaClass).any { it.type.name == OfficialCloudReflection.SERVICE_INFO } }
    }

    /** 显示位置:状态栏、通知栏(OPPO 接口里写的编号 8、16);别的位置不读 */
    private fun entranceOf(manager: Any): String? =
        when ((OfficialCloudReflection.call(OfficialCloudReflection.fieldOfType(manager, ENTRANCE), "getEntranceType") as? Number)?.toInt()) {
            OfficialCloudYield.STATUS_BAR -> STATUS_BAR
            NOTIFICATION_TYPE -> NOTIFICATION
            else -> null
        }

    const val STATUS_BAR = "ENTRY_STATUS_BAR"
    const val NOTIFICATION = "ENTRY_NOTIFICATION"
    private const val NOTIFICATION_TYPE = 16

    private fun contextOf(manager: Any): Context? = OfficialCloudReflection.fields(manager.javaClass)
        .firstOrNull { Context::class.java.isAssignableFrom(it.type) }
        ?.let { field -> runCatching { field.isAccessible = true; field.get(manager) as? Context }.getOrNull() }

    /** 这个应用的卡片系统流体云插件不收(照「关掉的应用」设置,读法和插件一样) */
    private fun disabled(context: Context?, pkg: String): Boolean {
        if (context == null || pkg.isBlank()) return false
        val setting = runCatching { Settings.Global.getString(context.contentResolver, DISABLE_SETTING) }.getOrNull()?.trim()
        if (setting.isNullOrEmpty()) return false
        if (setting != DISABLE_ALL) return pkg in setting.split(';')
        return runCatching {
            context.packageManager.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS).requestedPermissions?.contains(PROMOTED_PERMISSION) == true
        }.getOrDefault(false)
    }

    /** 此刻的用户(系统流体云按它给事件编号) */
    private fun currentUser(): Int = runCatching {
        android.app.ActivityManager::class.java.getMethod("getCurrentUser").invoke(null) as Int
    }.getOrElse {
        noteOnce("user", "current user unreadable; events are keyed to user 0")
        0
    }

    private fun service(key: String) = key.substringBefore('|')

    private val noted = linkedSetOf<String>()

    private fun noteOnce(key: String, message: String) {
        val fresh = synchronized(noted) { if (noted.size > 256) noted.clear(); noted.add(key) }
        if (fresh) com.astraflow.fluidcloud.hook.Log.i(TAG, message)
    }

    fun resetForTest() {
        synchronized(this) { owners.clear(); byId.clear() }
        synchronized(noted) { noted.clear() }
        revisions = OfficialCloudHook::nextRevision
        deliver = { owner, revision, entries -> OfficialCloudSource.receive(owner, revision, CloudChange.REPLACE, entries, false) }
        drop = { OfficialCloudSource.dropOwners(it) }
    }
}
