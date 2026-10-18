package com.astraflow.fluidcloud

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** 将系统复合节点中的机器数据读取为标准文字明细，不搬运厂商视图。 */
internal object OfficialCloudCompound {
    const val MAX_FACTS = 64
    private const val MAX_LABEL = 160
    private const val MAX_VALUE = 1024
    private const val MAX_DEPTH = 6

    fun read(raw: String): List<CloudFact>? {
        val input = raw.trim()
        if (input.isEmpty()) return emptyList()
        val value = runCatching { JSONTokener(input).nextValue() }.getOrNull()
        return when (value) {
            is JSONObject -> when {
                value.has("stationList") -> stations(value)
                value.has("vehicleInfoList") -> vehicles(value)
                value.has("paramsArray") -> generic(value.opt("paramsArray"))
                else -> null
            }
            is JSONArray -> null
            is String -> value.trim().takeIf(String::isNotEmpty)?.let { listOf(CloudFact("内容 1", it.take(MAX_VALUE))) }
            is Number, is Boolean -> listOf(CloudFact("内容 1", input.take(MAX_VALUE)))
            null -> if (input.first() != '{' && input.first() != '[' && input.length <= MAX_VALUE)
                listOf(CloudFact("内容 1", input)) else null
            else -> null
        }
    }

    private fun stations(root: JSONObject): List<CloudFact> {
        val list = root.optJSONArray("stationList") ?: return emptyList()
        val current = root.optInt("curIndex", -1)
        val output = mutableListOf<CloudFact>()
        for (index in 0 until list.length().coerceAtMost(MAX_FACTS)) {
            val station = list.optJSONObject(index) ?: continue
            val name = station.optString("name").trim().takeIf(String::isNotEmpty) ?: continue
            val label = when {
                index == current -> "当前站"
                index == current + 1 -> "下一站"
                index == current - 1 -> "上一站"
                else -> "途经站 " + (index + 1)
            }
            val lines = station.optJSONArray("lines").stringsFromObjects("name", "line")
            val notes = buildList {
                if (lines.isNotEmpty()) add("换乘 " + lines.distinct().joinToString("、"))
                if (station.optBoolean("isTerminal")) add("终点站")
            }
            output += CloudFact(label, (listOf(name) + notes).joinToString(" · ").take(MAX_VALUE))
        }
        return output
    }

    private fun vehicles(root: JSONObject): List<CloudFact> {
        val list = root.optJSONArray("vehicleInfoList") ?: return emptyList()
        val output = mutableListOf<CloudFact>()
        for (index in 0 until list.length().coerceAtMost(MAX_FACTS / 4)) {
            val item = list.optJSONObject(index) ?: continue
            val route = item.optString("name").trim()
            val suffix = if (route.isNotEmpty()) " · $route" else ""
            for ((key, label) in VEHICLE_TEXT_FIELDS) {
                val value = item.optString(key).trim().takeIf(String::isNotEmpty) ?: continue
                output += CloudFact(label + suffix, value.take(MAX_VALUE))
            }
        }
        return output
    }

    private fun generic(value: Any?): List<CloudFact> {
        val output = mutableListOf<CloudFact>()
        fun visit(item: Any?, inherited: String?, depth: Int) {
            if (depth > MAX_DEPTH || output.size >= MAX_FACTS) return
            when (item) {
                is JSONArray -> for (index in 0 until item.length()) {
                    val child = item.opt(index) ?: continue
                    visit(child, inherited?.takeIf(String::isNotBlank) ?: "项目 " + (index + 1), depth + 1)
                    if (output.size >= MAX_FACTS) break
                }
                is JSONObject -> {
                    val label = first(item, LABEL_KEYS)?.take(MAX_LABEL)
                    val unit = first(item, setOf("unit"))
                    val value = first(item, VALUE_KEYS)?.let { raw ->
                        (if (unit.isNullOrBlank()) raw else "$raw $unit").take(MAX_VALUE)
                    }
                    if (!label.isNullOrBlank() && !value.isNullOrBlank()) output += CloudFact(label, value)
                    for (key in item.keys()) {
                        if (key in METADATA_KEYS || key in LABEL_KEYS || key in VALUE_KEYS) continue
                        visit(item.opt(key), label ?: inherited ?: key, depth + 1)
                        if (output.size >= MAX_FACTS) break
                    }
                }
                JSONObject.NULL, null -> Unit
                else -> {
                    val text = item.toString().trim().take(MAX_VALUE)
                    if (text.isNotEmpty()) output += CloudFact(inherited?.take(MAX_LABEL) ?: "内容 " + (output.size + 1), text)
                }
            }
        }
        visit(value, null, 0)
        return output
    }

    private fun first(item: JSONObject, keys: Set<String>): String? =
        keys.firstNotNullOfOrNull { key ->
            item.opt(key)?.takeUnless { it === JSONObject.NULL }?.toString()?.trim()?.takeIf(String::isNotEmpty)
        }

    private fun JSONArray?.stringsFromObjects(vararg keys: String): List<String> {
        val list = this ?: return emptyList()
        val result = mutableListOf<String>()
        for (i in 0 until list.length().coerceAtMost(MAX_FACTS)) {
            val item = list.optJSONObject(i) ?: continue
            keys.firstNotNullOfOrNull { key -> item.optString(key).trim().takeIf(String::isNotEmpty) }?.let(result::add)
        }
        return result
    }

    private val LABEL_KEYS = linkedSetOf("name", "label", "title", "key", "paramName", "parameterName", "itemName")
    private val VALUE_KEYS = linkedSetOf("value", "text", "content", "description", "detail", "result", "paramValue")
    private val METADATA_KEYS = setOf("id", "color", "bgColor", "bgcolor", "icon", "image", "src", "d2", "d3", "d4", "unit")
    private val VEHICLE_TEXT_FIELDS = linkedMapOf(
        "c5text" to "到站情况",
        "c4text" to "列车提示",
        "c6text" to "补充信息",
        "c10text" to "出行提示",
    )
}
