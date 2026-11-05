package com.astraflow.fluidcloud

/** 一个监听器的一份全量列表只替换自己的记录，不能清除其它显示位置或用户的活动。 */
class OfficialCloudStore {
    private data class Owner(val revision: Long, val entries: Map<String, CloudSnapshot>)
    private val owners = LinkedHashMap<String, Owner>()
    private val ended = linkedSetOf<String>()

    fun apply(owner: String, revision: Long, change: CloudChange, records: List<CloudSnapshot>, removedKeys: Set<String> = emptySet()): Boolean {
        val previous = owners[owner]
        if (previous != null && revision <= previous.revision) return false
        val next = if (change == CloudChange.REPLACE) linkedMapOf() else LinkedHashMap(previous?.entries.orEmpty())
        if (change == CloudChange.REMOVE) {
            removedKeys.forEach(next::remove)
        } else {
            records.forEach {
                next[it.nativeKey] = it
                if (it.ended) ended.add(it.key)
            }
        }
        owners[owner] = Owner(revision, next)
        while (ended.size > 4096) ended.remove(ended.first())
        return true
    }

    /**
     * 每件事现在的一条记录:同一件事有几个显示位置送来记录时,用最新送来的那一条。
     * 显示位置暂时隐藏与服务结束是两回事,只有明确结束才封存整件事。
     */
    fun active(): List<CloudSnapshot> = owners.values.flatMap { it.entries.values }
        .groupBy { it.key }
        .mapNotNull { (_, variants) ->
            variants.filter { it.visible && it.key !in ended }
                .maxWithOrNull(compareBy<CloudSnapshot> { it.sourceUpdatedAtMs }.thenBy { it.revision })
        }

    fun clear() { owners.clear(); ended.clear() }
    /** 已经不在的监听者留下的记录一并撤掉(插件重载后换了新对象),不留下永远不会结束的卡片。 */
    fun removeOwners(dead: Set<String>): Boolean = owners.keys.removeAll(dead)

    fun aliveIdentities(): Set<String> = owners.values.flatMap { it.entries.values }.mapTo(linkedSetOf()) { it.key } - ended

    /** 这个系统记录编号现在的记录(除 [except] 以外任一来源送来的);没有为空 */
    fun record(nativeKey: String, except: String? = null): CloudSnapshot? =
        owners.entries.firstNotNullOfOrNull { (owner, entries) -> if (owner == except) null else entries.entries[nativeKey] }
}
