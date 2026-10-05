package com.astraflow.fluidcloud

import android.graphics.Color
import kotlin.math.roundToInt

/**
 * 卡片说明文件里写的颜色(现行规则「系统事件接入」):#RGB、#RRGGBB、#AARRGGBB(透明度在前,和安卓一样)、
 * rgb()/rgba()、颜色名;渐变取第一个颜色。读不出时为空。
 */
object OfficialCloudColor {
    private val FUNCTION = Regex("""rgba?\(([^)]*)\)""")
    private val HEX = Regex("""#[0-9a-fA-F]{3,8}(?![0-9a-fA-F])""")

    fun parse(raw: String?): Int? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (value.contains("gradient(")) {
            val first = listOfNotNull(HEX.find(value), FUNCTION.find(value)).minByOrNull { it.range.first } ?: return null
            return parse(first.value)
        }
        if (value.startsWith("#")) {
            val hex = value.substring(1)
            return when (hex.length) {
                3 -> runCatching { Color.parseColor("#" + hex.map { "$it$it" }.joinToString("")) }.getOrNull()
                6, 8 -> runCatching { Color.parseColor(value) }.getOrNull()
                else -> null
            }
        }
        FUNCTION.matchEntire(value)?.let { match ->
            val parts = match.groupValues[1].split(',').map(String::trim)
            if (parts.size < 3) return null
            fun channel(s: String): Int? = (if (s.endsWith("%")) s.removeSuffix("%").toFloatOrNull()?.times(2.55f) else s.toFloatOrNull())
                ?.takeIf { it.isFinite() }?.roundToInt()?.coerceIn(0, 255)
            val alpha = parts.getOrNull(3)?.let { a ->
                (if (a.endsWith("%")) a.removeSuffix("%").toFloatOrNull()?.div(100f) else a.toFloatOrNull())?.takeIf { it.isFinite() } ?: return null
            }?.let { (it.coerceIn(0f, 1f) * 255f).roundToInt() } ?: 255
            return Color.argb(alpha, channel(parts[0]) ?: return null, channel(parts[1]) ?: return null, channel(parts[2]) ?: return null)
        }
        return runCatching { Color.parseColor(value) }.getOrNull()
    }

    /** 渐变里最浓(最不透明)的那个颜色;不是渐变时照一个颜色读 */
    fun strongest(raw: String?, stops: List<Pair<Float, Int>>): Int? = stops.maxByOrNull { Color.alpha(it.second) }?.second ?: parse(raw)

    /**
     * 官方进度条的颜色(现行规则「展开卡片总表」):按先后给的几个颜色(进度条的颜色、当前节点的颜色、进度文字的颜色)取第一个
     * 看得见、不是白色或接近白色的;都不合适时为空(官方进度条默认是白色)。
     */
    fun barTint(candidates: List<Int?>): Int? = candidates.firstOrNull { c ->
        c != null && Color.alpha(c) >= MIN_TINT_ALPHA && minOf(Color.red(c), Color.green(c), Color.blue(c)) < NEAR_WHITE
    }

    /**
     * 写成渐变的颜色(「linear-gradient(90deg, #0FA7FF 2.58%, #3D8BFF 48.9%, #BE5DFF 97.93%)」):按先后给出各个颜色和它在哪
     * (0~1,没写位置的均匀分开);不是渐变、读不出两个以上颜色时为空。只认从左到右的渐变,角度不管。
     */
    fun gradient(raw: String?): List<Pair<Float, Int>> {
        val value = raw?.trim()?.takeIf { it.contains("gradient(") } ?: return emptyList()
        val body = value.substringAfter('(').substringBeforeLast(')')
        val stops = splitTopLevel(body).mapNotNull { part ->
            val color = listOfNotNull(HEX.find(part), FUNCTION.find(part)).minByOrNull { it.range.first } ?: return@mapNotNull null
            val at = STOP_AT.find(part.substring(color.range.last + 1))?.groupValues?.get(1)?.toFloatOrNull()?.div(100f)
            (parse(color.value) ?: return@mapNotNull null) to at
        }.take(MAX_GRADIENT_STOPS)
        if (stops.size < 2) return emptyList()
        return stops.mapIndexed { i, (color, at) -> (at ?: i.toFloat() / (stops.size - 1)).coerceIn(0f, 1f) to color }
    }

    /** 按顶层的逗号分开(括号里的逗号不算) */
    private fun splitTopLevel(text: String): List<String> {
        val parts = mutableListOf<String>()
        var depth = 0
        var start = 0
        text.forEachIndexed { i, c ->
            when (c) {
                '(' -> depth++
                ')' -> depth--
                ',' -> if (depth == 0) { parts += text.substring(start, i); start = i + 1 }
            }
        }
        parts += text.substring(start)
        return parts.map(String::trim)
    }

    private val STOP_AT = Regex("""(-?\d+(?:\.\d+)?)%""")
    private const val MAX_GRADIENT_STOPS = 8
    /** 透明度不到这么多(满 255)的颜色当看不见 */
    private const val MIN_TINT_ALPHA = 8
    /** 红、绿、蓝都不低于这么多(满 255)的颜色算接近白色(例如小布指令进度条的 rgba(231, 254, 251)) */
    private const val NEAR_WHITE = 224
}
