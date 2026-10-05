package com.astraflow.fluidcloud

/**
 * 系统流体云通话卡的按钮认领。只根据按钮上的字（含图标按钮的无障碍描述）判断角色，
 * 不猜位置。接通后才会出现扬声器：有扬声器就不再当来电，避免把已经接通后仍留在树上的「接听」再画出来。
 */
object OfficialCloudCallButtons {
    enum class Role { ANSWER, DECLINE, HANGUP, SPEAKER, OTHER }

    fun roleOf(label: String): Role {
        val text = label.trim()
        if (text.isEmpty()) return Role.OTHER
        return when {
            text.contains("接听") || text.contains("接通") || matches(text, "answer", "accept") -> Role.ANSWER
            text.contains("拒绝") || text.contains("拒接") || matches(text, "decline", "reject") -> Role.DECLINE
            text.contains("挂断") || text.contains("结束通话") || matches(text, "hang up", "hangup", "end call") -> Role.HANGUP
            text.contains("扬声器") || text.contains("免提") || matches(text, "speaker", "hands-free", "handsfree") -> Role.SPEAKER
            else -> Role.OTHER
        }
    }

    /** 接听、拒接、挂断、扬声器按用途取固定编号(与系统原按钮一致),其它按钮用 [fallback]。 */
    fun stableId(label: String, fallback: String): String = when (val role = roleOf(label)) {
        Role.OTHER -> fallback
        else -> "call:${role.name.lowercase()}"
    }

    fun incoming(buttons: List<CloudButton>, ringingExtra: Boolean): Boolean {
        val roles = buttons.map { roleOf(it.label) }
        if (Role.SPEAKER in roles) return false
        return ringingExtra || Role.ANSWER in roles
    }

    fun isCallControl(buttonId: String?, buttons: List<CloudButton>): Boolean {
        if (buttonId == null) return false
        if (buttonId in setOf("answer", "decline", "hangup")) return true
        val button = buttons.firstOrNull { it.id == buttonId } ?: return false
        return roleOf(button.label) in setOf(Role.ANSWER, Role.DECLINE, Role.HANGUP, Role.SPEAKER)
    }

    private fun matches(text: String, vararg words: String): Boolean {
        val lower = text.lowercase()
        return words.any { word ->
            if (' ' in word) lower.contains(word) else Regex("\\b${Regex.escape(word)}\\b").containsMatchIn(lower)
        }
    }
}
