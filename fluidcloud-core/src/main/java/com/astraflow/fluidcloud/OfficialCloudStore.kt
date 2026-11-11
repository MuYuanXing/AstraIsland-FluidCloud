package com.astraflow.fluidcloud

/** 显示位置各自保存成员关系，同一事件的内容只保留已收到的最新版本。 */
class OfficialCloudStore {
    private data class Owner(val revision: Long, val entries: Map<String, CloudSnapshot>)
    private val owners = LinkedHashMap<String, Owner>()
    private val latest = LinkedHashMap<String, CloudSnapshot>()
    private val ended = linkedSetOf<String>()
    private val versionOrder = compareBy<CloudSnapshot> { it.sourceUpdatedAtMs }.thenBy { it.revision }

    fun apply(owner: String, revision: Long, change: CloudChange, records: List<CloudSnapshot>, removedKeys: Set<String> = emptySet()): Boolean {
        val previous = owners[owner]
        if (previous != null && revision <= previous.revision) return false
        val before = identities()
        val next = if (change == CloudChange.REPLACE) linkedMapOf() else LinkedHashMap(previous?.entries.orEmpty())
        if (change == CloudChange.REMOVE) {
            removedKeys.forEach(next::remove)
        } else {
            for (record in records) {
                val old = previous?.entries?.get(record.nativeKey)
                // 整份名单重发不代表其中每一件事都更新；保留未变化记录自己的内容版本。
                val current = old?.takeIf {
                    it == record.copy(revision = it.revision, sourceUpdatedAtMs = it.sourceUpdatedAtMs)
                } ?: record.copy(revision = revision, sourceUpdatedAtMs = revision)
                next[current.nativeKey] = current
                if (current.ended) ended.add(current.key)
                else if (current.key !in before) ended.remove(current.key)
                val known = latest[current.key]
                if (known == null || versionOrder.compare(current, known) > 0) latest[current.key] = current
            }
        }
        owners[owner] = Owner(revision, next)
        val present = identities()
        // 只有系统名单明确撤掉最后一个成员才结束；重新加载撤销监听走 removeOwners。
        previous?.entries?.values?.filter { it.key !in present }?.forEach { ended.add(it.key) }
        latest.keys.retainAll(present)
        while (ended.size > 4096) ended.remove(ended.first())
        return true
    }

    /** 位置可见性与内容版本分开：旧位置仍可见时采用最新内容，不恢复它留存的旧版本。 */
    fun active(): List<CloudSnapshot> {
        val visible = owners.values.flatMap { it.entries.values }.filter { it.visible }.mapTo(HashSet()) { it.key }
        return latest.values.filter { it.key in visible && it.key !in ended }
    }

    fun clear() { owners.clear(); latest.clear(); ended.clear() }
    /** 系统名单移除或内容明确结束的事件身份；重新加载与暂时缺席不算结束。 */
    fun endedIdentities(): Set<String> = ended.toSet()

    /** 旧监听者撤下只移除内容，不把同一事件的重新认领当成新的一次。 */
    fun removeOwners(dead: Set<String>): Boolean {
        val changed = owners.keys.removeAll(dead)
        if (changed) latest.keys.retainAll(identities())
        return changed
    }

    private fun identities(): Set<String> = owners.values.flatMap { it.entries.values }.mapTo(linkedSetOf()) { it.key }
    fun aliveIdentities(): Set<String> = identities() - ended

    /** 按当前成员确认来源，再返回这件事共用的最新内容。 */
    fun record(nativeKey: String, except: String? = null): CloudSnapshot? {
        val member = owners.entries.firstNotNullOfOrNull { (owner, entries) -> if (owner == except) null else entries.entries[nativeKey] }
        return member?.let { latest[it.key] }
    }
}
