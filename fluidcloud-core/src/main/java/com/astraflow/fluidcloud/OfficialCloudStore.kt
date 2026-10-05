package com.astraflow.fluidcloud

/** 一个监听器的一份全量列表只替换自己的记录，不能清除其它显示位置或用户的活动。 */
class OfficialCloudStore {
    private data class Owner(val revision: Long, val entries: Map<String, CloudRecord>)
    private val owners = LinkedHashMap<String, Owner>()
    private val ended = linkedSetOf<String>()

    fun apply(owner: String, revision: Long, change: CloudChange, records: List<CloudRecord>, removedKeys: Set<String> = emptySet()): Boolean {
        val previous = owners[owner]
        if (previous != null && revision <= previous.revision) return false
        val next = if (change == CloudChange.REPLACE) linkedMapOf() else LinkedHashMap(previous?.entries.orEmpty())
        if (change == CloudChange.REMOVE) {
            removedKeys.forEach(next::remove)
        } else {
            records.forEach {
                val old = next[it.snapshot.nativeKey]
                val merged = if (old != null && old.snapshot.key == it.snapshot.key) it.copy(content = it.content.copy(buttons = keepButtons(old.content.buttons, it.content.buttons, it.snapshot.serviceId == OfficialCloudServices.CALL))) else it
                next[it.snapshot.nativeKey] = merged
                if (merged.snapshot.ended) ended.add(merged.snapshot.key)
            }
        }
        owners[owner] = Owner(revision, next)
        while (ended.size > 4096) ended.remove(ended.first())
        return true
    }

    fun active(): List<CloudRecord> = owners.values.flatMap { it.entries.values }
        .groupBy { it.snapshot.key }
        .mapNotNull { (_, variants) ->
            // 显示位置暂时隐藏与服务结束是两回事。只有明确结束才封存整件事。
            val visible = variants.filter { it.snapshot.visible && it.snapshot.key !in ended }
            val newestTime = visible.maxOfOrNull { it.snapshot.sourceUpdatedAtMs } ?: return@mapNotNull null
            visible.filter { it.snapshot.sourceUpdatedAtMs == newestTime }
                .maxWithOrNull(compareBy<CloudRecord> { it.content.complete }
                    .thenBy { it.content.body.length + it.content.buttons.size * 30 }.thenBy { it.snapshot.revision })
        }

    fun clear() { owners.clear(); ended.clear() }
    fun enrich(owner: String, expectedRevision: Long, records: List<CloudRecord>): Boolean {
        val current = owners[owner]?.takeIf { it.revision == expectedRevision } ?: return false
        val next = LinkedHashMap(current.entries)
        records.forEach { record ->
            val old = next[record.snapshot.nativeKey]
            if (old != null && old.snapshot.key == record.snapshot.key) {
                val merged = record.copy(content = record.content.copy(buttons = keepButtons(old.content.buttons, record.content.buttons, record.snapshot.serviceId == OfficialCloudServices.CALL)))
                next[record.snapshot.nativeKey] = merged
                if (merged.snapshot.ended) ended.add(merged.snapshot.key)
            }
        }
        owners[owner] = Owner(current.revision, next)
        return true
    }
    fun revision(owner: String): Long? = owners[owner]?.revision
    fun removeOwner(owner: String) = owners.remove(owner) != null
    /** 已经不在的监听者留下的记录一并撤掉(插件重载后换了新对象),不留下永远不会结束的卡片。 */
    fun removeOwners(dead: Set<String>): Boolean = owners.keys.removeAll(dead)

    /**
     * 通话卡片刷新时常常暂时没有按钮,沿用还能按的那几个;其它卡片按这一次送来的为准,
     * 按钮消失就不再显示(现行规则「系统事件接入」)。
     */
    private fun keepButtons(previous: List<CloudButton>, incoming: List<CloudButton>, call: Boolean): List<CloudButton> {
        if (!call) return incoming
        if (incoming.isEmpty()) return previous
        return incoming.map { button ->
            val role = OfficialCloudCallButtons.roleOf(button.label)
            val old = if (role == OfficialCloudCallButtons.Role.OTHER) previous.firstOrNull { it.id == button.id }
                else previous.firstOrNull { OfficialCloudCallButtons.roleOf(it.label) == role }
            if (old == null || old.id == button.id) button else button.copy(id = old.id)
        }
    }

    fun keys(): Set<String> = active().mapTo(linkedSetOf()) { it.snapshot.key }
    /** 所有显示位置送来的、还在的系统记录编号(不论系统此刻显不显示、结没结束) */
    fun allNativeKeys(): Set<String> = owners.values.flatMapTo(linkedSetOf()) { it.entries.keys }
    fun nativeKeys(key: String): Set<String> = owners.values.flatMap { it.entries.values }.filter { it.snapshot.key == key }
        .mapTo(linkedSetOf()) { it.snapshot.nativeKey }
}
