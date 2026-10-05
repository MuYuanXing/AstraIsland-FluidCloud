package com.astraflow.fluidcloud

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import androidx.core.graphics.PathParser
import org.xmlpull.v1.XmlPullParser
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 卡片说明文件里的矢量图标(系统卡片的图标多是 SVG,手机自带的图片解码认不出)画成位图
 * (现行规则「系统事件接入」:照卡片自带的说明文件排出图标)。
 * 只认图标常用的写法:svg、g、path、rect、circle、ellipse、line、polygon、polyline,填色、描边、透明度、奇偶填充、
 * 变换与裁切,线性渐变与径向渐变填色(红绿灯这类图标用,现行规则「展开卡片总表」),遮罩(按透明度或按亮度,例如电池图标),
 * 图案填充与图里直接嵌的图片(设计工具导出的圆形头像、服务标志多是这样),以及引用本图里的一部分;
 * 滤镜(阴影、模糊)略去只画形状。引用外部文件和其它认不出的写法整张不画,返回 null,不画出走样的图。
 */
object OfficialCloudSvg {
    private const val EDGE = 96
    private const val MAX_NODES = 2000
    /** 嵌在图里的图片:字节数、边长上限 */
    private const val MAX_IMAGE_BYTES = 1024 * 1024
    private const val MAX_IMAGE_EDGE = 1024
    /** 图案铺满一个形状最多铺几块 */
    private const val MAX_TILES = 64
    /** 按亮度的遮罩:颜色的亮度当透明度(白色全留、黑色不留) */
    private val LUMINANCE_TO_ALPHA = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0.2125f, 0.7154f, 0.0721f, 0f, 0f)
    private val SKIPPED = setOf("defs", "clipPath", "mask", "filter", "linearGradient", "radialGradient", "pattern", "symbol",
        "title", "desc", "metadata", "style", "marker")
    private val SHAPES = setOf("path", "rect", "circle", "ellipse", "line", "polygon", "polyline")
    private val SEPARATOR = Regex("""[\s,]+""")
    private val TRANSFORM = Regex("""(matrix|translate|scale|rotate)\s*\(([^)]*)\)""")
    private val REFERENCE = Regex("""url\(\s*['"]?#([^)'"\s]+)['"]?\s*\)""")
    private val GRADIENTS = setOf("linearGradient", "radialGradient")

    private class Node(val name: String, val attrs: Map<String, String>, val children: MutableList<Node> = mutableListOf())
    private class Unsupported : RuntimeException()
    /**
     * 一张图里按编号找得到的节点(裁切、渐变、遮罩、图案、引用),以及视口大小(按用户坐标写的渐变,百分比按它算);
     * [using] 是正在画的引用,引用绕回自己时整张不画。
     */
    private class Doc(val ids: Map<String, Node>, val width: Float, val height: Float, val using: MutableSet<Node> = HashSet())
    /** 一笔的颜色:纯色,或按这个形状算好的渐变 */
    private class Ink(val color: Int, val shader: Shader? = null) {
        fun paint(kind: Paint.Style, opacity: Float) = Paint(Paint.ANTI_ALIAS_FLAG).also { p ->
            p.style = kind
            p.color = color
            p.shader = shader
            p.alpha = (Color.alpha(color) * opacity).roundToInt().coerceIn(0, 255)
        }
    }
    /** 会往下传的样式(opacity 不往下传,按层单独处理) */
    private data class Style(
        val fill: String = "#000000", val fillOpacity: Float = 1f, val evenOdd: Boolean = false,
        val stroke: String = "none", val strokeWidth: Float = 1f, val strokeOpacity: Float = 1f,
        val cap: Paint.Cap = Paint.Cap.BUTT, val join: Paint.Join = Paint.Join.MITER,
    )

    fun looksLikeSvg(bytes: ByteArray): Boolean {
        val head = String(bytes, 0, minOf(bytes.size, 1024), Charsets.UTF_8).trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        return head.startsWith("<svg") || ((head.startsWith("<?xml") || head.startsWith("<!--")) && head.contains("<svg"))
    }

    fun render(bytes: ByteArray, edge: Int = EDGE): Bitmap? = try {
        val root = parse(bytes)?.takeIf { it.name == "svg" }
        root?.let { draw(it, edge) }
    } catch (_: Unsupported) {
        null
    } catch (_: Exception) {
        null
    }

    private fun draw(root: Node, edge: Int): Bitmap? {
        val ids = HashMap<String, Node>()
        index(root, ids)
        val box = root.attrs["viewBox"]?.trim()?.split(SEPARATOR)?.mapNotNull { it.toFloatOrNull() }?.takeIf { it.size == 4 }
        val width = box?.get(2) ?: length(root.attrs["width"]) ?: return null
        val height = box?.get(3) ?: length(root.attrs["height"]) ?: return null
        if (width <= 0f || height <= 0f) return null
        val scale = edge / maxOf(width, height)
        val bitmap = Bitmap.createBitmap((width * scale).roundToInt().coerceAtLeast(1), (height * scale).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(scale, scale)
        canvas.translate(-(box?.get(0) ?: 0f), -(box?.get(1) ?: 0f))
        drawChildren(root, canvas, style(root.attrs, Style()), Doc(ids, width, height))
        return bitmap
    }

    private fun drawChildren(node: Node, canvas: Canvas, inherited: Style, doc: Doc) {
        node.children.forEach { child -> drawNode(child, canvas, inherited, doc) }
    }

    private fun drawNode(node: Node, canvas: Canvas, inherited: Style, doc: Doc) {
        if (node.name in SKIPPED) return
        val attrs = node.attrs
        if (attrs["display"] == "none" || attrs["visibility"] == "hidden") return
        val style = style(attrs, inherited)
        val opacity = attrs["opacity"]?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 1f
        val save = canvas.save()
        try {
            attrs["transform"]?.let { canvas.concat(transform(it)) }
            attrs["clip-path"]?.let { canvas.clipPath(clip(it, doc.ids)) }
            // 遮罩:先把这一项画在单独一层上,再按遮罩留下该留的部分
            val mask = attrs["mask"]?.let { value -> doc.ids[reference(value) ?: throw Unsupported()]?.takeIf { it.name == "mask" } ?: throw Unsupported() }
            if (mask != null) canvas.saveLayer(null, null)
            when (node.name) {
                "g", "svg", "a" -> {
                    val layer = if (opacity < 1f) canvas.saveLayerAlpha(null, (opacity * 255).roundToInt()) else -1
                    drawChildren(node, canvas, style, doc)
                    if (layer >= 0) canvas.restoreToCount(layer)
                }
                in SHAPES -> shape(node)?.let { path -> paint(canvas, path, style, opacity, doc) }
                "image" -> image(canvas, attrs, opacity)
                "use" -> use(canvas, node, style, opacity, doc)
                else -> throw Unsupported()
            }
            mask?.let { masked(canvas, it, doc) }
        } finally {
            canvas.restoreToCount(save)
        }
    }

    /**
     * 按遮罩留下这一层该留的部分:按透明度的遮罩留下遮罩画到的地方;按亮度的(默认)再乘上遮罩的亮度(白色全留、黑色不留)。
     * 遮罩按用户坐标写了范围时范围外不留;按外框写的范围(默认比外框大一圈)不另裁。
     */
    private fun masked(canvas: Canvas, mask: Node, doc: Doc) {
        val a = declared(mask.attrs)
        if (a["maskContentUnits"] == "objectBoundingBox") throw Unsupported()
        val region = if (a["maskUnits"] == "userSpaceOnUse") {
            val x = length(a["x"]) ?: 0f; val y = length(a["y"]) ?: 0f
            RectF(x, y, x + (length(a["width"]) ?: doc.width), y + (length(a["height"]) ?: doc.height))
        } else null
        val keep = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
        val brightness = Paint(keep).apply { colorFilter = ColorMatrixColorFilter(LUMINANCE_TO_ALPHA) }
        val passes = if (a["mask-type"] == "alpha") listOf(keep) else listOf(brightness, keep)
        for (paint in passes) {
            val layer = canvas.saveLayer(null, paint)
            region?.let(canvas::clipRect)
            drawChildren(mask, canvas, style(mask.attrs, Style()), doc)
            canvas.restoreToCount(layer)
        }
    }

    /** 嵌在图里的图片(只认写在图里的 data: 图片,不读外部文件):按 preserveAspectRatio 放进 x、y、width、height 那一块 */
    private fun image(canvas: Canvas, attrs: Map<String, String>, opacity: Float) {
        val w = length(attrs["width"]) ?: return
        val h = length(attrs["height"]) ?: return
        if (w <= 0f || h <= 0f) return
        val bitmap = embedded(attrs["href"] ?: throw Unsupported())
        val x = length(attrs["x"]) ?: 0f; val y = length(attrs["y"]) ?: 0f
        val ratio = attrs["preserveAspectRatio"]?.trim().orEmpty().split(SEPARATOR).filter(String::isNotEmpty)
        val align = ratio.firstOrNull() ?: "xMidYMid"
        val dst = RectF(x, y, x + w, y + h)
        val save = canvas.save()
        try {
            if (align != "none") {
                val slice = ratio.getOrNull(1) == "slice"
                val scale = if (slice) maxOf(w / bitmap.width, h / bitmap.height) else minOf(w / bitmap.width, h / bitmap.height)
                val dw = bitmap.width * scale; val dh = bitmap.height * scale
                val left = x + when { "xMin" in align -> 0f; "xMax" in align -> w - dw; else -> (w - dw) / 2f }
                val top = y + when { "YMin" in align -> 0f; "YMax" in align -> h - dh; else -> (h - dh) / 2f }
                if (slice) canvas.clipRect(dst)
                dst.set(left, top, left + dw, top + dh)
            }
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { alpha = (opacity * 255).roundToInt().coerceIn(0, 255) }
            canvas.drawBitmap(bitmap, null, dst, paint)
        } finally {
            canvas.restoreToCount(save)
        }
    }

    /** data:image/…;base64,… 里的图片 */
    private fun embedded(href: String): Bitmap {
        val value = href.trim()
        if (!value.startsWith("data:image/") || !value.contains(";base64,")) throw Unsupported()
        val bytes = java.util.Base64.getMimeDecoder().decode(value.substringAfter(";base64,"))
        if (bytes.size > MAX_IMAGE_BYTES) throw Unsupported()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth !in 1..MAX_IMAGE_EDGE || bounds.outHeight !in 1..MAX_IMAGE_EDGE) throw Unsupported()
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw Unsupported()
    }

    /** 引用本图里的一部分(常见的是图案里引用一张嵌入图片):按 x、y 挪过去再画那一部分 */
    private fun use(canvas: Canvas, node: Node, style: Style, opacity: Float, doc: Doc) {
        val target = node.attrs["href"]?.let(::fragment)?.let(doc.ids::get) ?: throw Unsupported()
        if (!doc.using.add(target)) throw Unsupported()
        val layer = if (opacity < 1f) canvas.saveLayerAlpha(null, (opacity * 255).roundToInt()) else -1
        try {
            canvas.translate(length(node.attrs["x"]) ?: 0f, length(node.attrs["y"]) ?: 0f)
            // 引用的是遮罩、渐变、符号这类不直接画的部分时整张不画(放在 defs 里的图片、形状照样画)
            if (target.name in SKIPPED) throw Unsupported()
            drawNode(target, canvas, style, doc)
        } finally {
            if (layer >= 0) canvas.restoreToCount(layer)
            doc.using.remove(target)
        }
    }

    /**
     * 图案填色:按形状外框(默认)或用户坐标定一块,里面的内容按外框(patternContentUnits)或用户坐标画,
     * 一块一块铺满形状,形状以外不画。
     */
    private fun pattern(canvas: Canvas, path: Path, pattern: Node, alpha: Float, doc: Doc) {
        val a = declared(pattern.attrs)
        if (a["viewBox"] != null || a["patternTransform"] != null || a["href"] != null) throw Unsupported()
        val bounds = RectF().also { path.computeBounds(it, true) }
        if (bounds.width() <= 0f || bounds.height() <= 0f) return
        val box = a["patternUnits"] != "userSpaceOnUse"
        fun along(name: String, origin: Float, size: Float) = if (box) origin + (fraction(a[name]) ?: 0f) * size else length(a[name]) ?: 0f
        fun extent(name: String, size: Float) = if (box) (fraction(a[name]) ?: 0f) * size else length(a[name]) ?: 0f
        val tx = along("x", bounds.left, bounds.width()); val ty = along("y", bounds.top, bounds.height())
        val tw = extent("width", bounds.width()); val th = extent("height", bounds.height())
        if (tw <= 0f || th <= 0f) return
        val i0 = kotlin.math.floor((bounds.left - tx) / tw).toInt(); val i1 = kotlin.math.ceil((bounds.right - tx) / tw).toInt()
        val j0 = kotlin.math.floor((bounds.top - ty) / th).toInt(); val j1 = kotlin.math.ceil((bounds.bottom - ty) / th).toInt()
        if ((i1 - i0).toLong() * (j1 - j0) > MAX_TILES) throw Unsupported()
        val save = canvas.save()
        try {
            canvas.clipPath(path)
            if (alpha < 1f) canvas.saveLayerAlpha(null, (alpha * 255).roundToInt())
            val contentBox = a["patternContentUnits"] == "objectBoundingBox"
            for (i in i0 until i1) for (j in j0 until j1) {
                val ox = tx + i * tw; val oy = ty + j * th
                val tile = canvas.save()
                canvas.clipRect(ox, oy, ox + tw, oy + th)
                canvas.translate(ox, oy)
                if (contentBox) canvas.scale(bounds.width(), bounds.height())
                drawChildren(pattern, canvas, style(pattern.attrs, Style()), doc)
                canvas.restoreToCount(tile)
            }
        } finally {
            canvas.restoreToCount(save)
        }
    }

    private fun paint(canvas: Canvas, path: Path, style: Style, opacity: Float, doc: Doc) {
        if (style.evenOdd) path.fillType = Path.FillType.EVEN_ODD
        val fillPattern = style.fill.trim().takeIf { it.startsWith("url(") }?.let(::reference)?.let(doc.ids::get)?.takeIf { it.name == "pattern" }
        if (fillPattern != null) pattern(canvas, path, fillPattern, style.fillOpacity * opacity, doc)
        else ink(style.fill, path, doc)?.let { ink -> canvas.drawPath(path, ink.paint(Paint.Style.FILL, style.fillOpacity * opacity)) }
        if (style.strokeWidth > 0f) ink(style.stroke, path, doc)?.let { ink ->
            canvas.drawPath(path, ink.paint(Paint.Style.STROKE, style.strokeOpacity * opacity).apply {
                strokeWidth = style.strokeWidth
                strokeCap = style.cap
                strokeJoin = style.join
            })
        }
    }

    /** 一笔的颜色:纯色,或 url(#编号) 指向本图里的渐变(后面可以跟一个编号找不到时用的颜色)。 */
    private fun ink(value: String, path: Path, doc: Doc): Ink? {
        val v = value.trim()
        if (!v.startsWith("url(")) return color(v)?.let { Ink(it) }
        val target = doc.ids[reference(v) ?: throw Unsupported()]
        if (target == null) return v.substringAfter(')').trim().takeIf { it.isNotEmpty() }?.let { Ink(color(it) ?: return null) } ?: throw Unsupported()
        if (target.name !in GRADIENTS) throw Unsupported()
        return gradient(target, path, doc)
    }

    /**
     * 线性、径向渐变:色标(位置、颜色、透明度),坐标按形状的外框(默认)或按用户坐标,渐变自己的变换,两头以外的铺法,
     * 以及 href 继承另一个渐变的色标与属性。没有色标时不画这一笔;只有一个色标、两端重合或半径为零时按最后一个色标的纯色画。
     */
    private fun gradient(node: Node, path: Path, doc: Doc): Ink? {
        val chain = generateSequence(node) { g -> g.attrs["href"]?.let(::fragment)?.let(doc.ids::get)?.takeIf { it.name in GRADIENTS } }
            .take(8).toList()
        fun attr(name: String, sameKind: Boolean = false) = chain.firstOrNull { (!sameKind || it.name == node.name) && it.attrs[name] != null }?.attrs?.get(name)
        val stops = chain.firstOrNull { g -> g.children.any { it.name == "stop" } }?.children?.filter { it.name == "stop" }.orEmpty()
        if (stops.isEmpty()) return null
        val offsets = FloatArray(stops.size)
        val colors = IntArray(stops.size)
        var last = 0f
        stops.forEachIndexed { i, stop ->
            val all = declared(stop.attrs)
            // 色标位置不往回走:比前一个小时按前一个算
            last = maxOf(last, (fraction(all["offset"]) ?: 0f).coerceIn(0f, 1f))
            offsets[i] = last
            val base = color(all["stop-color"] ?: "#000000") ?: Color.TRANSPARENT
            val alpha = all["stop-opacity"]?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 1f
            colors[i] = Color.argb((Color.alpha(base) * alpha).roundToInt(), Color.red(base), Color.green(base), Color.blue(base))
        }
        if (stops.size == 1) return Ink(colors[0])
        val box = attr("gradientUnits") != "userSpaceOnUse"
        val bounds = RectF().also { path.computeBounds(it, true) }
        if (box && (bounds.width() <= 0f || bounds.height() <= 0f)) return null
        fun x(name: String, default: String) = coordinate(attr(name, true) ?: default, box, doc.width)
        fun y(name: String, default: String) = coordinate(attr(name, true) ?: default, box, doc.height)
        fun r(name: String, default: String) = coordinate(attr(name, true) ?: default, box, sqrt((doc.width * doc.width + doc.height * doc.height) / 2f))
        val tile = when (attr("spreadMethod")) { "reflect" -> Shader.TileMode.MIRROR; "repeat" -> Shader.TileMode.REPEAT; else -> Shader.TileMode.CLAMP }
        val shader = if (node.name == "linearGradient") {
            val x1 = x("x1", "0%"); val y1 = y("y1", "0%"); val x2 = x("x2", "100%"); val y2 = y("y2", "0%")
            if (x1 == x2 && y1 == y2) return Ink(colors.last())
            LinearGradient(x1, y1, x2, y2, colors, offsets, tile)
        } else {
            val cx = x("cx", "50%"); val cy = y("cy", "50%"); val radius = r("r", "50%")
            if (radius <= 0f) return Ink(colors.last())
            val fx = attr("fx", true)?.let { coordinate(it, box, doc.width) } ?: cx
            val fy = attr("fy", true)?.let { coordinate(it, box, doc.height) } ?: cy
            val fr = r("fr", "0%")
            if (fx == cx && fy == cy && fr == 0f) RadialGradient(cx, cy, radius, colors, offsets, tile)
            else RadialGradient(fx, fy, fr, cx, cy, radius, LongArray(colors.size) { Color.pack(colors[it]) }, offsets, tile)
        }
        // 渐变坐标 → 形状所在的坐标:先按渐变自己的变换,按外框写的再铺到外框上
        val local = Matrix()
        if (box) { local.setTranslate(bounds.left, bounds.top); local.preScale(bounds.width(), bounds.height()) }
        attr("gradientTransform")?.let { local.preConcat(transform(it)) }
        shader.setLocalMatrix(local)
        return Ink(Color.BLACK, shader)
    }

    /** 渐变的坐标:按外框写的,数值与百分比都是外框的几分之几;按用户坐标写的,百分比按视口换算。 */
    private fun coordinate(value: String, box: Boolean, viewport: Float): Float {
        val v = value.trim()
        val number = v.removeSuffix("%").removeSuffix("px").toFloatOrNull() ?: throw Unsupported()
        return when {
            !v.endsWith("%") -> number
            box -> number / 100f
            else -> number / 100f * viewport
        }
    }

    /** 色标位置:数值或百分比 */
    private fun fraction(value: String?): Float? {
        val v = value?.trim() ?: return null
        return if (v.endsWith("%")) v.removeSuffix("%").toFloatOrNull()?.div(100f) else v.toFloatOrNull()
    }

    private fun shape(node: Node): Path? {
        val a = node.attrs
        fun f(name: String) = length(a[name]) ?: 0f
        return when (node.name) {
            "path" -> a["d"]?.takeIf { it.isNotBlank() }?.let { PathParser.createPathFromPathData(it) }
            "rect" -> {
                val w = f("width"); val h = f("height")
                if (w <= 0f || h <= 0f) return null
                val rx = length(a["rx"]) ?: length(a["ry"]) ?: 0f
                val ry = length(a["ry"]) ?: rx
                Path().apply {
                    val rect = RectF(f("x"), f("y"), f("x") + w, f("y") + h)
                    if (rx > 0f || ry > 0f) addRoundRect(rect, minOf(rx, w / 2), minOf(ry, h / 2), Path.Direction.CW) else addRect(rect, Path.Direction.CW)
                }
            }
            "circle" -> f("r").takeIf { it > 0f }?.let { r -> Path().apply { addCircle(f("cx"), f("cy"), r, Path.Direction.CW) } }
            "ellipse" -> if (f("rx") > 0f && f("ry") > 0f) Path().apply {
                addOval(RectF(f("cx") - f("rx"), f("cy") - f("ry"), f("cx") + f("rx"), f("cy") + f("ry")), Path.Direction.CW)
            } else null
            "line" -> Path().apply { moveTo(f("x1"), f("y1")); lineTo(f("x2"), f("y2")) }
            "polygon", "polyline" -> {
                val points = a["points"].orEmpty().trim().split(SEPARATOR).mapNotNull { it.toFloatOrNull() }
                if (points.size < 4) return null
                Path().apply {
                    moveTo(points[0], points[1])
                    for (i in 2 until points.size - 1 step 2) lineTo(points[i], points[i + 1])
                    if (node.name == "polygon") close()
                }
            }
            else -> null
        }
    }

    /** clip-path="url(#id)":按那一组形状(带各自的变换)的并集裁切。 */
    private fun clip(value: String, ids: Map<String, Node>): Path {
        val target = ids[reference(value) ?: throw Unsupported()]?.takeIf { it.name == "clipPath" } ?: throw Unsupported()
        if (target.attrs["clipPathUnits"] == "objectBoundingBox") throw Unsupported()
        val result = Path()
        target.children.forEach { child ->
            if (child.name !in SHAPES) throw Unsupported()
            val path = shape(child) ?: return@forEach
            if (style(child.attrs, Style()).evenOdd || child.attrs["clip-rule"] == "evenodd") path.fillType = Path.FillType.EVEN_ODD
            child.attrs["transform"]?.let { path.transform(transform(it)) }
            result.op(path, Path.Op.UNION)
        }
        target.attrs["transform"]?.let { result.transform(transform(it)) }
        return result
    }

    /** 属性与 style 里写的声明合在一起(style 里写的算数) */
    private fun declared(attrs: Map<String, String>): Map<String, String> = attrs + attrs["style"].orEmpty().split(';').mapNotNull { part ->
        part.split(':', limit = 2).takeIf { it.size == 2 }?.let { (k, v) -> k.trim() to v.trim() }
    }

    private fun style(attrs: Map<String, String>, parent: Style): Style {
        val all = declared(attrs)
        fun opacity(name: String, fallback: Float) = all[name]?.toFloatOrNull()?.coerceIn(0f, 1f) ?: fallback
        return Style(
            fill = all["fill"]?.also(::checkPaint) ?: parent.fill,
            fillOpacity = opacity("fill-opacity", parent.fillOpacity),
            evenOdd = all["fill-rule"]?.let { it == "evenodd" } ?: parent.evenOdd,
            stroke = all["stroke"]?.also(::checkPaint) ?: parent.stroke,
            strokeWidth = length(all["stroke-width"]) ?: parent.strokeWidth,
            strokeOpacity = opacity("stroke-opacity", parent.strokeOpacity),
            cap = when (all["stroke-linecap"]) { "round" -> Paint.Cap.ROUND; "square" -> Paint.Cap.SQUARE; "butt" -> Paint.Cap.BUTT; else -> parent.cap },
            join = when (all["stroke-linejoin"]) { "round" -> Paint.Join.ROUND; "bevel" -> Paint.Join.BEVEL; "miter" -> Paint.Join.MITER; else -> parent.join },
        )
    }

    /** 引用填色只认本图里的编号(渐变,画的时候再核对);引用外部文件的整张不画 */
    private fun checkPaint(value: String) { val v = value.trim(); if (v.startsWith("url(") && reference(v) == null) throw Unsupported() }

    private fun color(value: String): Int? {
        val v = value.trim()
        if (v.isEmpty() || v == "none" || v == "transparent") return null
        if (v == "currentColor") return Color.BLACK
        if (v.startsWith("#")) {
            val hex = v.substring(1)
            return when (hex.length) {
                3 -> Color.parseColor("#" + hex.map { "$it$it" }.joinToString(""))
                6 -> Color.parseColor("#$hex")
                8 -> Color.parseColor("#" + hex.substring(6) + hex.substring(0, 6))
                else -> throw Unsupported()
            }
        }
        Regex("""rgba?\(([^)]*)\)""").matchEntire(v)?.let { match ->
            val parts = match.groupValues[1].split(',').map(String::trim)
            fun channel(s: String) = if (s.endsWith("%")) (s.removeSuffix("%").toFloat() * 2.55f).roundToInt() else s.toFloat().roundToInt()
            val alpha = parts.getOrNull(3)?.toFloatOrNull()?.let { (it.coerceIn(0f, 1f) * 255).roundToInt() } ?: 255
            return Color.argb(alpha, channel(parts[0]).coerceIn(0, 255), channel(parts[1]).coerceIn(0, 255), channel(parts[2]).coerceIn(0, 255))
        }
        return runCatching { Color.parseColor(v) }.getOrElse { throw Unsupported() }
    }

    private fun transform(value: String): Matrix {
        val matrix = Matrix()
        TRANSFORM.findAll(value).forEach { match ->
            val n = match.groupValues[2].trim().split(SEPARATOR).mapNotNull { it.toFloatOrNull() }
            val step = Matrix()
            when (match.groupValues[1]) {
                "matrix" -> if (n.size == 6) step.setValues(floatArrayOf(n[0], n[2], n[4], n[1], n[3], n[5], 0f, 0f, 1f)) else throw Unsupported()
                "translate" -> step.setTranslate(n.getOrElse(0) { 0f }, n.getOrElse(1) { 0f })
                "scale" -> step.setScale(n.getOrElse(0) { 1f }, n.getOrElse(1) { n.getOrElse(0) { 1f } })
                "rotate" -> if (n.size >= 3) step.setRotate(n[0], n[1], n[2]) else step.setRotate(n.getOrElse(0) { 0f })
            }
            matrix.preConcat(step)
        }
        return matrix
    }

    private fun reference(value: String): String? = REFERENCE.find(value)?.groupValues?.get(1)

    /** href="#编号" 里的编号 */
    private fun fragment(value: String): String? = value.trim().takeIf { it.startsWith("#") && it.length > 1 }?.substring(1)

    private fun length(value: String?): Float? = value?.trim()?.removeSuffix("px")?.toFloatOrNull()

    private fun index(node: Node, ids: MutableMap<String, Node>) {
        node.attrs["id"]?.let { ids[it] = node }
        node.children.forEach { index(it, ids) }
    }

    private fun parse(bytes: ByteArray): Node? {
        val parser = android.util.Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(bytes.inputStream(), "UTF-8")
        val stack = ArrayDeque<Node>()
        var root: Node? = null
        var count = 0
        while (true) {
            when (parser.next()) {
                XmlPullParser.END_DOCUMENT -> return root
                XmlPullParser.START_TAG -> {
                    if (++count > MAX_NODES) return null
                    val attrs = (0 until parser.attributeCount).associate { parser.getAttributeName(it).substringAfter(':') to parser.getAttributeValue(it) }
                    val node = Node(parser.name.substringAfter(':'), attrs)
                    stack.lastOrNull()?.children?.add(node) ?: run { root = node }
                    stack.addLast(node)
                }
                XmlPullParser.END_TAG -> stack.removeLastOrNull()
            }
        }
    }
}
