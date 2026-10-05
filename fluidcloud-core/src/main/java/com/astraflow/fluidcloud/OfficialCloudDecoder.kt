package com.astraflow.fluidcloud

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Chronometer
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import java.lang.ref.WeakReference
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 内容格式适配，不按商家名单放行。不搬运厂商布局，不在此绘制卡片(现行规则「系统事件接入」)。
 * 按钮按卡片模板登记的动作执行:读不到动作或参数的按钮不显示,按钮上的字也不写进正文;
 * 卡片里的图标放在左边身份图的位置,不当大图。只有通话卡片沿用系统原按钮(接听、挂断这类由电话服务先处理)。
 */
object OfficialCloudDecoder {
    private val containers = setOf("card-template", "compact", "expanded", "leading", "trailing", "center", "div", "stack", "remote", "span")
    /** [trailing]:在胶囊右边(说明文件的 trailing)里;胶囊里不在两边的按左边算。[parts]:分几段写的一句里看得见的各段 */
    private data class Located(val node: CloudNode, val compact: Boolean, val leading: Boolean, val trailing: Boolean = false,
        val parts: List<CloudText.Part> = emptyList(), val group: String? = null, val large: Boolean = false)
    /** 官方只画图标的圆形按钮 */
    private const val CIRCLE_BUTTON = "circle"
    /** 说明文件写成一直转的小部件(progressType) */
    private const val SPINNER_TYPE = "1"
    /** 可以点的进度环在按钮表里的编号 */
    const val RING_BUTTON = "tpl:ring"
    /** 官方的标签小块:G1、G2 跟着说明那一行(地铁线路、取单码引导的「体验版」、智慧文本的来源),G3、G4 跟着下一行(出行守护的时钟) */
    private val TAG_BLOCKS = setOf("G1", "G2", "G3", "G4")
    /** 小标签里的小图和字(读的时候先攒着) */
    private class TagParts(var icon: CloudImage? = null, var text: String? = null)
    /** 说明旁的小图的位置代号,和它归到的跟着说明的标签块 */
    private const val DESCRIPTION_ICON = "D2"
    private const val DESCRIPTION_TAG = "G1"
    /** 官方写在标题旁的小图的位置代号;放在卡片下面的大图的位置代号 */
    private const val TITLE_MARK = "D"
    private const val LARGE_PICTURE = "D21"
    /** 放在卡片底部的大图(现行规则「展开卡片总表」):引导页(guide1)的大图 D21、底部写成大图的一块(large-image,
     * 天通卫星的方向动画)、写明整张居中显示(CENTER_INSIDE)的预览图(互传收到文件的预览) */
    private const val GUIDE_LAYOUT = "guide1"
    private const val LARGE_BLOCK = "large-image"
    private const val WHOLE_PICTURE = "CENTER_INSIDE"
    /** 写 D 的底图(抢票加速的白底)的图名:不当标题后面的小图 */
    private const val BACKGROUND_NAME = "background"
    /** 标题后面的小图最大边长(像素):更大的是照片,不当小图 */
    private const val TITLE_MARK_MAX_PX = 200

    /** 按钮没画底色:没写、全透明,或是和卡片底一样的黑色 */
    private fun plainBackground(color: Int?): Boolean = color == null || color ushr 24 == 0 || color and 0xFFFFFF == 0

    /** 读好的图是不是小图(边长不超过 [TITLE_MARK_MAX_PX];只读图的大小,不解出整张图;读不出大小的矢量图、动画算小图) */
    private fun smallPicture(image: CloudImage): Boolean {
        image.bitmap?.let { return maxOf(it.width, it.height) <= TITLE_MARK_MAX_PX }
        val bytes = image.bytes ?: return true
        if (image.animated) return true
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        return bounds.outWidth <= 0 || maxOf(bounds.outWidth, bounds.outHeight) <= TITLE_MARK_MAX_PX
    }
    /** 说明文件写的字号档(fontSize7 → 7) */
    private val FONT_SIZE = Regex("""fontSize(\d+)""")
    /** 文字里的百分比(下载这类系统卡片只在文字里写进度)。 */
    private val PERCENT = Regex("""(?<![0-9.])(\d{1,3})(?:\.\d+)?\s*%""")

    fun decode(snapshot: CloudSnapshot, template: CloudTemplate? = null): CloudContent? {
        val flat = mutableListOf<Located>()
        var truncated = false
        // 官方给展开卡片中间那一块标的排法(取码类、对称这类,现行规则「展开卡片总表」)
        var centerLayout = ""
        // 官方铺在展开卡片上的底色、底图(说明文件 expanded 的 bgColor、bgImage)
        var expandedProps: Map<String, String>? = null
        // [group]:在展开卡片的标签小块(说明文件里位置代号 G1 的 div)里;这一块里的小图和字是一个小标签(现行规则「展开卡片总表」)。
        // 别的小块(例如导航红绿灯的 G7)照常读
        fun walk(node: CloudNode, compact: Boolean, leading: Boolean, depth: Int, trailing: Boolean = false, group: String? = null, large: Boolean = false) {
            if (depth > 20 || flat.size >= 512) { truncated = true; return }
            if (node.props["show"] == "false" || node.props["shown"] == "false") return
            // 兼容胶囊是给老版本流体云用的,和胶囊写的是同一件事;现在的系统只画胶囊,兼容胶囊的内容不算
            if (node.type == "compatible-capsule") return
            val small = when (node.type) { "compact" -> true; "expanded", "remote" -> false; else -> compact }
            val left = when (node.type) { "leading" -> true; "trailing" -> false; else -> leading }
            val right = when (node.type) { "leading" -> false; "trailing" -> true; else -> trailing }
            if (node.type == "center" && !small && centerLayout.isEmpty()) centerLayout = node.props["category"]?.trim().orEmpty().take(64)
            if (node.type == "expanded" && expandedProps == null) expandedProps = node.props
            val block = if (node.type == "div" && !small && node.level.trimEnd('*') in TAG_BLOCKS) node.level.trimEnd('*') else group
            val inLarge = large || (node.type == "trailing" && !small && node.props["category"]?.trim() == LARGE_BLOCK)
            // 一行文字分成几段写的(「25米」「右转」、时分秒)连成一句,不拆开,也不去掉相同的段(现行规则「系统事件接入」);
            // 官方写成全透明、看不见的段(快递取件说明前面的占位字「xxx」)不算
            val spans = node.children.filter { it.type == "span" }
            if (node.type == "text" && spans.isNotEmpty()) {
                val shownSpans = spans.filter { it.props["show"] != "false" && it.props["shown"] != "false" }.mapNotNull { span ->
                    val color = (template?.color(span.props["color"]) ?: OfficialCloudColor.parse(span.props["color"]))
                    if (color != null && color ushr 24 == 0) null else span to color
                }
                val parts = shownSpans.map { (span, color) -> CloudText.Part((span.props["text"] ?: span.props["value"]).orEmpty(), color) }
                    .filter { it.text.isNotEmpty() }
                val joined = (node.props["text"].orEmpty() + parts.joinToString("") { it.text }).trim()
                // 分段写的字(计时的时、分、秒)没给整句的颜色时,整句用第一段写的颜色
                val color = node.props["color"] ?: shownSpans.firstNotNullOfOrNull { (span, _) -> span.props["color"]?.takeIf(String::isNotBlank) }
                flat += Located(node.copy(props = node.props + ("text" to joined) + listOfNotNull(color?.let { "color" to it }), children = emptyList()),
                    small, left, right, parts, block, inLarge)
                return
            }
            flat += Located(node, small, left, right, group = block, large = inLarge)
            node.children.forEach { walk(it, small, left, depth + 1, right, block, inLarge) }
        }
        // 系统没把排好的卡片交给岛(手机上的系统卡片都是这样)时,照卡片说明文件和数据自己排(现行规则「系统事件接入」)
        val root = snapshot.root ?: template?.render(snapshot.data)
        root?.let { walk(it, false, false, 0) }
        val call = snapshot.serviceId == OfficialCloudServices.CALL
        val native = captureNative(snapshot, harvestButtons = call)
        val gaps = linkedSetOf<String>().apply { addAll(native.gaps) }
        if (truncated) gaps += "content-budget"
        val expanded = linkedSetOf<String>()
        val compact = linkedSetOf<String>()
        val leadingText = linkedSetOf<String>()
        var hero: CloudImage? = null
        var compactImage: CloudImage? = null
        var progress: CloudProgress? = null
        var timer: CloudTimer? = null
        val buttons = mutableListOf<CloudButton>()
        // 系统胶囊右边自己有什么:字(此刻为空也算)、进度小部件、图。胶囊右边照它显示,不拿卡片里的进度顶替
        var capsuleText = false
        var trailingProgress: CloudProgress? = null
        var trailingImage: CloudImage? = null
        // 展开卡片里的小进度环(游戏更新、互传发送中这类):画在卡片右边,不当进度条(「改好以后的样子」页)
        var ring: CloudRing? = null
        // 小标签(按所在的小块分开)和标题后面的小图
        val tags = linkedMapOf<String, TagParts>()
        val buttonIcons = linkedMapOf<String, CloudImage>()
        var titleMark: CloudImage? = null
        var spinner: CloudImage? = null
        var spinnerInner: CloudImage? = null
        // 系统胶囊左右两边各放了什么、什么颜色,照说明文件的先后(现行规则「系统事件接入」)
        val capsuleLeading = mutableListOf<CapsulePart>()
        val capsuleTrailing = mutableListOf<CapsulePart>()
        fun capsule(right: Boolean, part: CapsulePart) { (if (right) capsuleTrailing else capsuleLeading) += part }
        fun colorOf(raw: String?): Int? = template?.color(raw) ?: OfficialCloudColor.parse(raw)
        snapshot.notification?.notification?.let { notification ->
            val extras = notification.extras
            val max = extras.getInt(android.app.Notification.EXTRA_PROGRESS_MAX, 0)
            if (max > 0) progress = CloudProgress(if (extras.getBoolean(android.app.Notification.EXTRA_PROGRESS_INDETERMINATE)) null
                else (extras.getInt(android.app.Notification.EXTRA_PROGRESS).toFloat() / max).coerceIn(0f, 1f))
            if (extras.getBoolean(android.app.Notification.EXTRA_SHOW_CHRONOMETER) && notification.`when` > 0) {
                timer = CloudTimer(notification.`when`, extras.getBoolean(android.app.Notification.EXTRA_CHRONOMETER_COUNT_DOWN), false)
            }
            extras.getCharSequence("android.shortCriticalText")?.toString()?.takeIf { it.isNotBlank() }?.let(compact::add)
            notification.actions.orEmpty().filter { it.actionIntent != null && it.remoteInputs.isNullOrEmpty() && !it.title.isNullOrBlank() }
                .forEachIndexed { index, action -> buttons += CloudButton("notification-action-$index", action.title.toString(), pending = action.actionIntent) }
        }
        // 说明文件放的图标按系统符号画(写明裁成圆形的头像除外);读不到时用它写的替代图(alt),并标明是替代图。
        // 数据给的是卡片所属应用共享位置里的图(例如外卖的菜品图)时按照片记下,后台读好再显示(现行规则「展开卡片总表」)
        val owners = setOf(snapshot.packageName, snapshot.hostPackage).filter { it.isNotBlank() }.toSet()
        fun image(props: Map<String, String>, data: Map<String, String>, playing: Boolean = true): CloudImage? {
            val symbol = props["shapeStyle"] != "circle"
            val src = props["src"]?.trim()?.takeIf { it.isNotEmpty() }
            val alt = props["alt"]?.trim()?.takeIf { it.isNotEmpty() && it != src }
            return listOfNotNull(src, alt).firstNotNullOfOrNull { ref ->
                val found = OfficialCloudSharedImages.reference(ref, owners, symbol = props["shapeStyle"] == "origin")
                    ?: (snapshot.images[ref] ?: snapshot.images.values.firstOrNull { it.key == ref })?.copy(symbol = symbol)
                    ?: template?.picture(ref, data, symbol = symbol, playing = playing)
                if (found != null && ref == alt) found.copy(substitute = true) else found
            }
        }
        // 说明文件自带的会动图标:按 autoplay 决定播不播(现行规则「系统事件接入」)
        fun picture(node: CloudNode): CloudImage? = if (node.type == "lottie") {
            image(node.props, snapshot.data, node.props["autoplay"]?.let(OfficialCloudExpr::truthy) ?: true)
                ?.takeIf { it.animated || it.bytes != null || it.bitmap != null }
        } else image(node.props, snapshot.data)
        val ringInner = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<CloudNode, Boolean>())
        // 说明文件给每一项标的位置代号(去掉星号)下的文字和图,各取第一个
        val levels = linkedMapOf<String, String>()
        val levelImages = linkedMapOf<String, CloudImage>()
        // 胶囊右边的图记作胶囊右边的图;胶囊里其余的图放左边身份位;卡片里的第一张图放卡片身份位。
        // 展开卡片顶部那一行的图(位置代号 D8、D9)另记:第一张画在来源条上,不当左边的图(「改好以后的样子」页)
        val headerImages = mutableListOf<CloudImage>()
        var largeImage: CloudImage? = null
        fun place(picked: CloudImage, small: Boolean, left: Boolean, right: Boolean) {
            when {
                !small && left -> headerImages += picked
                !small -> if (hero == null) hero = picked
                right -> if (trailingImage == null) trailingImage = picked
                else -> if (compactImage == null) compactImage = picked
            }
        }
        // 展开卡片里带位置代号的字,卡片的主次照它排(现行规则「系统事件接入」)
        val officialTexts = mutableListOf<CloudText>()
        flat.forEach { (node, small, left, right, parts, group, large) ->
            val props = node.props
            val text = (props["text"] ?: props["value"]).orEmpty().trim().take(8192)
            val level = node.level.trimEnd('*')
            // 展开卡片里小块中的字和小图是一个小标签(例如取单码引导的「体验版」、地铁线路的图标),不当标题、说明、左边的图
            val tag = if (!small && group != null) tags.getOrPut(group) { TagParts() } else null
            when (node.type) {
                "text", "span" -> if (tag != null) { if (tag.text == null && text.isNotBlank()) tag.text = text } else {
                    val parsedTimer = parseTimer(props)
                    // 写了到点时刻(alarmTime,例如秒抢闹钟)的字只是占位(「占位」),系统画的是倒计时:字不当内容
                    val placeholder = parsedTimer != null && props.containsKey("alarmTime")
                    if (text.isNotBlank() && !placeholder) (if (small) { if (left) leadingText else compact } else expanded).add(text)
                    if (text.isNotBlank() && level.isNotEmpty() && !placeholder) levels.putIfAbsent(level, text)
                    if (!small && text.isNotBlank() && level.isNotEmpty() && !placeholder) officialTexts += CloudText(level, text,
                        when { left -> CloudText.Block.TOP; right -> CloudText.Block.BOTTOM; else -> CloudText.Block.CENTER },
                        size = props["fontSize"]?.let { FONT_SIZE.find(it) }?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..9 },
                        parts = parts.takeIf { it.size >= 2 }.orEmpty(), color = colorOf(props["color"]),
                        gradient = template?.gradient(props["gradientColors"]) ?: OfficialCloudColor.gradient(props["gradientColors"]))
                    if (small && right) capsuleText = true
                    if (small) capsule(right, CapsulePart.Text(if (placeholder) "" else text, colorOf(props["color"]), timer = parsedTimer != null))
                    parsedTimer?.let { timer = it }
                    if (props.containsKey("count-down-time") && props["count-down-time"].orEmpty().isNotBlank() && timer == null) gaps += "timer-format"
                    if (props.containsKey("countDownTime") && props["countDownTime"].orEmpty().isNotBlank() && timer == null) gaps += "timer-format"
                }
                // 胶囊里读不到的图也记下那里放了图(左边照官方换成服务图标);进度环里的图随进度环记
                "image", "lottie" -> {
                    val picked = picture(node)
                    if (picked == null) gaps += "image-unavailable"
                    else {
                        when {
                            tag != null -> if (tag.icon == null) tag.icon = picked
                            // 卡片上进度环(和转圈小图标)正中间的图只画在环里,不再当左边的图画第二遍
                            !small && node in ringInner -> Unit
                            // 说明旁的小图(D2)官方多半放在跟着说明的标签块(G1)里;单独放着的(快递取件的定位图标、
                            // 小布陪伴通话时长旁的图标)同样当说明后面的小标签,不当左边的图
                            !small && level == DESCRIPTION_ICON -> tags.getOrPut(DESCRIPTION_TAG) { TagParts() }.let { if (it.icon == null) it.icon = picked }
                            // 官方写在标题旁的状态小图(位置代号只写 D:完成的对勾、失败、已完成的动画、游戏的状态图):放在标题后面,
                            // 不当左边的图。同样写 D 的预览图(写明整张居中显示)、底图(抢票加速的白底)、写明尺寸的图(车辆充电的车)不算
                            !small && level == TITLE_MARK && node !in ringInner && props["scaleType"]?.trim() != WHOLE_PICTURE && props["width"] == null &&
                                !picked.key.substringAfterLast('/').substringAfterLast('\\').contains(BACKGROUND_NAME, ignoreCase = true) &&
                                smallPicture(picked) -> if (titleMark == null) titleMark = picked
                            // 放在卡片底部的大图(引导页的大图、天通卫星的方向动画、互传的预览)另记,不当左边的图
                            !small && (large || (level == LARGE_PICTURE && centerLayout == GUIDE_LAYOUT) ||
                                (level == TITLE_MARK && props["scaleType"]?.trim() == WHOLE_PICTURE)) -> if (largeImage == null) largeImage = picked
                            // 官方放在卡片下面的别的大图(D21)不当左边的图
                            !small && level == LARGE_PICTURE -> Unit
                            else -> place(picked, small, left, right)
                        }
                        if (level.isNotEmpty()) levelImages.putIfAbsent(level, picked)
                    }
                    if (small && node !in ringInner) capsule(right, CapsulePart.Picture(picked))
                }
                // 分阶段的进度:说明文件写明分几段(step)和现在是第几段(currentStep,从 1 数),percent 是这一段走了多少,
                // 换算成整条的进度(和系统卡片自己写的整条进度一致,现行规则「展开卡片总表」)
                "progress" -> {
                    val number = props["percent"]?.toFloatOrNull()?.takeIf { it.isFinite() && it in 0f..100f }
                    val stages = labels(props["node-labels"] ?: props["nodeLabels"])
                    val steps = props["step"]?.toFloatOrNull()?.toInt()?.takeIf { it in 1..16 }
                    val current = props["currentStep"]?.toFloatOrNull()?.toInt()
                    val tint = OfficialCloudColor.barTint(listOf(props["color"], props["currentNodeColor"], props["textColor"]).map(::colorOf))
                    val read = if (number != null) CloudProgress(wholeBar(number / 100f, steps, current), stages, steps, tint)
                        else CloudProgress(null, stages, steps, tint).also { gaps += "progress-format" }
                    progress = read
                    if (small && right) trailingProgress = read
                    if (small) capsule(right, CapsulePart.Ring(read, colorOf(props["color"])))
                }
                // 写成一直转的小部件(progressType 1,小布执行中):不是进度,读成转圈的小图标;卡片上放在正文前面,胶囊上放在右边
                "widget" -> if (props["progressType"]?.trim() == SPINNER_TYPE) {
                    val icon = props["progressIcon"]?.takeIf { it.isNotBlank() }?.let { image(mapOf("src" to it), snapshot.data) }
                    if (icon == null) gaps += "image-unavailable"
                    else if (small) capsule(right, CapsulePart.Picture(icon, spin = true))
                    else if (spinner == null) {
                        spinner = icon
                        // 转圈图标正中间来源给的小图(不转)
                        spinnerInner = node.children.firstOrNull { it.type == "image" || it.type == "lottie" }?.also(ringInner::add)?.let(::picture)
                    }
                } else {
                // 带上下限的小部件是进度环(例如录音保存进度):数值不当正文
                    val value = text.toFloatOrNull()
                    val min = props["min"]?.toFloatOrNull() ?: 0f
                    val max = props["max"]?.toFloatOrNull()
                    if (value != null && max != null && max > min && value.isFinite()) {
                        val read = CloudProgress(((value - min) / (max - min)).coerceIn(0f, 1f))
                        if (small) progress = read
                        if (small && right) trailingProgress = read
                        // 进度环中间的图(车辆充电的闪电、生成完成的对勾)是进度环的一部分,不另算一张图
                        val inner = node.children.firstOrNull { it.type == "image" || it.type == "lottie" }
                        inner?.let(ringInner::add)
                        if (small) capsule(right, CapsulePart.Ring(read, colorOf(props["color"]), inner?.let(::picture)))
                        else if (ring == null) {
                            ring = CloudRing(read.fraction ?: 0f, colorOf(props["color"]), inner?.let(::picture))
                            // 官方把这个环做成可以点的(互传、远程文件传输点环取消),点它和点卡片不一样时,星河岛上的环也能点
                            template?.node(node.type, node.level, snapshot.data)?.let { tapped ->
                                val click = tapped.events["click"]?.takeIf { it != template.cardClick }
                                val action = click?.let { template.action(it, snapshot.data) }
                                // 名字(读屏用)照同一个动作的按钮上的字,环上的数字不当名字
                                if (action != null) buttons += CloudButton(RING_BUTTON, template.buttonLabel(click, snapshot.data).orEmpty(), action = action, onRing = true)
                            }
                        }
                    } else gaps += "node:widget"
                }
                "button" -> if (!call) {
                    // 按模板登记的动作执行;读不到动作或参数时不显示,也不把按钮上的字当正文
                    val templateNode = template?.button(node.level, snapshot.data)
                    val action = templateNode?.let { template.action(it.events["click"], snapshot.data) }
                    // 只画图标、星河岛知道是什么的按钮(录屏的系统声音、麦克风)写短名字;给读屏的说明(「录制系统声音按钮 已选中」)
                    // 不当按钮上的字
                    val label = text.ifBlank { OfficialCloudServices.iconButtonLabel(action, snapshot.data)
                        ?: templateNode?.let { template.label(it, snapshot.data) }.orEmpty() }
                    // 官方只画图标的圆形按钮(type 是 circle)记下它的图标,卡片上画成圆钮;文字按钮带的小图标和底色也记下
                    // (现行规则「展开卡片总表」)
                    val round = props["type"] == CIRCLE_BUTTON
                    // 写成开关的按钮:开着用开着的图标,关着用关着的(录屏的系统声音、麦克风)
                    val checked = props["checked"]?.trim()?.lowercase()?.let(OfficialCloudExpr::truthy)
                    val iconSource = (if (checked == false) props["iconOff"]?.takeIf { it.isNotBlank() } else null) ?: props["icon"] ?: props["iconOn"]
                    val icon = iconSource?.takeIf { it.isNotBlank() }?.let { image(mapOf("src" to it), snapshot.data) }
                    // 只画图标的按钮的图标另外按位置记下:系统没给动作、由星河岛补动作的按钮(秒表的计次)也用官方的图标
                    if (round && icon != null && level.isNotEmpty()) buttonIcons.putIfAbsent(level, icon)
                    val inert = round && icon != null && templateNode != null && templateNode.events["click"] == null
                    // 画成圆钮样子、系统没登记点击、也没有底色的是提示用的小图(投屏失败、领券失败、已发送到车这类):
                    // 放在标题后面,和完成的对勾一样(系统卡片有的图一样不少)
                    if (inert && action == null && plainBackground(colorOf(props["bgColor"])) && titleMark == null) titleMark = icon
                    when {
                        // 只画图标、系统没登记点击的按钮只读它的图标,不当按钮
                        action == null -> if (!inert) gaps += "button-action"
                        // 只有图标、没有字的按钮(和小布聊天时底部的四个)照样画,底部一排并排
                        label.isBlank() && icon == null -> gaps += "button-label"
                        else -> buttons += CloudButton("tpl:${node.level}", label, action = action, icon = icon, round = round && icon != null,
                            fill = colorOf(props["bgColor"]), checked = checked, top = left && !small)
                    }
                } else {
                    val id = node.level.ifBlank { props["level"].orEmpty() }
                    val target = native.buttons.firstOrNull { it.id == id || (text.isNotBlank() && it.label == text) }
                        ?: native.targets[id]?.let { CloudButton(id, text, it) }
                    // 通话:系统原按钮读不到时,按模板登记的动作(交给来电界面自己处理),名字用模板写的无障碍描述(例如「免提」)
                    val templateNode = template?.button(node.level, snapshot.data)
                    val action = templateNode?.let { template.action(it.events["click"], snapshot.data) }
                    val label = text.ifBlank { templateNode?.let { template.label(it, snapshot.data) }.orEmpty() }
                    when {
                        text.isNotBlank() && target != null -> buttons += target
                        action != null && label.isNotBlank() -> buttons += CloudButton(OfficialCloudCallButtons.stableId(label, "tpl:${node.level}"), label, action = action)
                        else -> gaps += "button-target"
                    }
                }
                "spectrum" -> Unit // 装饰用的声波条不迁入岛；来源图标负责身份显示。
                else -> if (node.type !in containers) {
                    // 未认识的复合控件可能携带机器数据，不把原始对象文本放进用户卡片。
                    if (text.isNotBlank() && !text.startsWith('{') && !text.startsWith('[')) expanded += text
                    gaps += "node:${node.type.take(40)}"
                }
            }
        }
        if (expanded.isEmpty()) expanded.addAll(native.texts)
        if (compact.isEmpty()) compact.addAll(native.compactTexts)
        // 通话:系统原按钮与模板按钮是同一个时合成一个(留着原按钮,也带上模板登记的动作)
        if (call) native.buttons.forEach { candidate ->
            val index = buttons.indexOfFirst { it.id == candidate.id || (it.pending != null && it.label == candidate.label) }
            if (index < 0) buttons += candidate
            else if (buttons[index].target == null && buttons[index].pending == null) buttons[index] = candidate.copy(action = buttons[index].action ?: candidate.action)
        }
        progress = progress ?: native.progress
        timer = timer ?: native.timer
        // 卡片别处没有图时,顶部那一行的第二张图(降雨的阵雨图标、快捷指令正在用的应用图标)放左边
        hero = hero ?: headerImages.getOrNull(1) ?: native.hero
        val title = snapshot.title.ifBlank { expanded.firstOrNull().orEmpty() }.trim().take(512)
        val description = snapshot.description.trim()
        if (description.isNotBlank()) expanded.add(description)
        expanded.remove(title)
        // 按钮上的字不写进正文(现行规则「系统事件接入」)
        buttons.forEach { expanded.remove(it.label) }
        // 文字里写的百分比只记下来;只有应用下载、游戏更新、互传用它画进度条,由展开卡片总表决定(现行规则「展开卡片总表」)
        val textPercent = (listOf(title) + compact).firstNotNullOfOrNull { percentOf(it) }
        val body = expanded.joinToString("\n").take(16384)
        val compactText = compact.joinToString(" ").ifBlank { description.ifBlank { title } }.take(2048)
        if (title.isBlank() && body.isBlank() && compactText.isBlank() && progress == null && timer == null) return null
        // 卡片上放不放得下全部按钮由星河岛按自己的卡片判断(放不下时同一件事的通知卡在岛上就留通知卡)
        val hasExpanded = (flat.any { it.node.type == "expanded" } && flat.any { !it.compact && it.node.type in setOf("text", "image", "progress", "button") }) ||
            (native.expandedAvailable && (native.texts.isNotEmpty() || native.hero != null || native.progress != null || native.timer != null))
        if (!hasExpanded) gaps += "expanded-unavailable"
        if (root == null && !native.expandedAvailable) gaps += "summary-only"
        val cardAction = template?.let { it.action(it.cardClick, snapshot.data) }
        // 服务标志(A1):数据给的是卡片所属应用共享位置里的图时照样读,不然按说明文件自带的图找
        val logo = template?.logoSource(snapshot.data)?.let { OfficialCloudSharedImages.reference(it, owners) } ?: template?.logo(snapshot.data)
        return CloudContent(title.ifBlank { compactText }, body, compactText,
            leadingText.joinToString(" ").takeIf { it.isNotBlank() }, compactImage ?: logo ?: snapshot.icon,
            hero, progress, timer, buttons.distinctBy { it.id }, hasExpanded && gaps.isEmpty(), gaps, cardAction,
            capsuleText = capsuleText || compact.isNotEmpty(), trailingProgress = trailingProgress, trailingImage = trailingImage,
            levels = levels, levelImages = levelImages, textPercent = textPercent, capsule = CloudCapsule(capsuleLeading, capsuleTrailing,
                // 照说明文件自己排的这一页没写胶囊;系统给的排好的内容里没有胶囊不算(那可能只是它此刻显示的那一部分)
                absent = snapshot.root == null && root != null && flat.none { it.node.type == "compact" }),
            texts = officialTexts, centerLayout = centerLayout, ring = ring,
            tags = tags.filterValues { it.icon != null || it.text != null }.map { (block, it) -> CloudTag(it.icon, it.text, block) }, titleMark = titleMark, spinner = spinner, spinnerInner = spinnerInner,
            buttonIcons = buttonIcons, headerImage = headerImages.firstOrNull(), largeImage = largeImage,
            backgroundColor = expandedProps?.get("bgColor")?.let { raw -> OfficialCloudColor.strongest(raw, template?.gradient(raw) ?: OfficialCloudColor.gradient(raw))
                ?: template?.color(raw) },
            backgroundImage = expandedProps?.get("bgImage")?.trim()?.takeIf { it.isNotEmpty() }?.let { image(mapOf("src" to it), snapshot.data) })
    }

    private fun percentOf(text: String): Float? = PERCENT.find(text)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 0..100 }?.let { it / 100f }

    /** 分 [steps] 段、现在在第 [current] 段(从 1 数)、这一段走了 [part] 时整条的进度;没写分段时就是 [part]。 */
    fun wholeBar(part: Float, steps: Int?, current: Int?): Float {
        if (steps == null || current == null) return part.coerceIn(0f, 1f)
        return (((current - 1).coerceIn(0, steps - 1)) + part.coerceIn(0f, 1f)) / steps
    }

    /**
     * 卡片模板里的计时。chronometer 写作「[起点, start 或 stop, 当前秒数]」,起点按开机时钟:
     * 起点晚于现在是倒计时;stop 表示停住,显示当前秒数(与系统卡片引擎一致)。
     * count-down-time 是倒计时的结束时刻(墙上时钟,毫秒或时间文字)。
     */
    fun parseTimer(props: Map<String, String>, nowElapsedMs: Long = SystemClock.elapsedRealtime()): CloudTimer? {
        props["chronometer"]?.let { value ->
            val parts = value.removeSurrounding("[", "]").split(',').map(String::trim)
            val base = parts.firstOrNull()?.toLongOrNull()?.takeIf { it >= 0 } ?: return@let
            val countdown = base > nowElapsedMs
            val running = parts.getOrNull(1) == "start"
            if (running) return CloudTimer(base, countdown, true)
            val seconds = parts.getOrNull(2)?.toLongOrNull() ?: Math.round(kotlin.math.abs(base - nowElapsedMs) / 1000.0)
            return CloudTimer(base, countdown, true, running = false, frozenMs = seconds * 1000L)
        }
        val raw = (props["count-down-time"] ?: props["countDownTime"] ?: props["alarmTime"])?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val value = raw.removeSurrounding("[", "]").substringBefore(',').trim().trim('"', '\'')
        val epoch = value.toLongOrNull()?.takeIf { it >= 100_000_000_000L } ?: runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(value, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()
        return epoch?.let { CloudTimer(it, true, false) }
    }

    private fun labels(raw: String?): List<String> = raw.orEmpty().removeSurrounding("[", "]").split(',')
        .map { it.trim().trim('"', '\'') }.filter { it.isNotBlank() }.take(16)

    private data class NativeContent(
        val texts: MutableSet<String> = linkedSetOf(), val compactTexts: MutableSet<String> = linkedSetOf(),
        val buttons: MutableList<CloudButton> = mutableListOf(), var hero: CloudImage? = null,
        var timer: CloudTimer? = null, var progress: CloudProgress? = null, var expandedAvailable: Boolean = false,
        val targets: MutableMap<String, WeakReference<View>> = linkedMapOf(), val gaps: MutableSet<String> = linkedSetOf(),
        var heroArea: Long = 0,
    )

    /**
     * 老格式从系统已经创建的控件读文字、计时和图标;不截图、不挂载、不创建官方卡片。
     * 只有通话卡片收系统原按钮([harvestButtons]):其它卡片的按钮按模板登记的动作执行,不去点系统界面上的控件。
     */
    private fun captureNative(snapshot: CloudSnapshot, harvestButtons: Boolean): NativeContent {
        val result = NativeContent()
        val harvested = ArrayList<HarvestedButton>()
        val card = snapshot.nativeCard ?: snapshot.nativeSeedling
        if (card == null && snapshot.nativeViews.isEmpty()) return result
        for (size in listOf(9, 8, 7)) {
            val roots = ((OfficialCloudReflection.call(card, "getViewListBySize", size) as? Collection<*>)?.filterIsInstance<View>().orEmpty() +
                listOfNotNull(OfficialCloudReflection.call(card, "getViewBySize", size) as? View) + snapshot.nativeViews[size].orEmpty()).distinct()
            if (size == 9 && roots.isNotEmpty()) result.expandedAvailable = true
            var visited = 0
            fun visit(view: View, depth: Int) {
                if (depth > 24 || ++visited > 512) return
                // 可见性只约束内容读取;通话按钮照样收——系统胶囊被岛顶替后它的视图被标 GONE,
                // 但按钮还在树里,电话服务不接受时点系统原按钮仍是真实指令。
                val visible = view.visibility == View.VISIBLE
                val text = if (visible) (view as? TextView)?.text?.toString()?.trim().orEmpty().take(8192) else ""
                val clickable = view.isClickable && view.isEnabled
                if (text.isNotBlank()) (if (size == 7) result.compactTexts else result.texts).add(text)
                if (view is Chronometer) {
                    val running = runCatching { Chronometer::class.java.getDeclaredField("mStarted").apply { isAccessible = true }.getBoolean(view) }.getOrDefault(false)
                    if (running) result.timer = CloudTimer(view.base, view.isCountDown, true)
                }
                if (view is android.widget.SeekBar || view is android.widget.CompoundButton) result.gaps += "interactive-control"
                if (view is ProgressBar && view !is android.widget.SeekBar) {
                    result.progress = CloudProgress(if (view.isIndeterminate || view.max <= 0) null else view.progress.toFloat() / view.max)
                }
                if (size == 9 && view is ImageView && view.drawable != null &&
                    (view.drawable.intrinsicWidth >= 96 || view.drawable.intrinsicHeight >= 96)) {
                    val area = view.drawable.intrinsicWidth.toLong() * view.drawable.intrinsicHeight
                    if (area > result.heroArea) {
                        result.hero = OfficialCloudImages.fromDrawable(view.drawable, "native:${snapshot.nativeKey}:${view.id}")
                        result.heroArea = area
                    }
                }
                if (harvestButtons && size != 7 && clickable) {
                    // 图标按钮(接听/挂断常是纯图标)没有文字,用无障碍描述当名字;没名字的不要
                    val label = text.ifBlank { view.contentDescription?.toString()?.trim().orEmpty() }
                    if (label.isNotBlank()) {
                        val id = view.tag?.toString()?.takeIf { it.isNotBlank() } ?: "view:${view.id}:${System.identityHashCode(view)}"
                        harvested += HarvestedButton(id, label, view, visible)
                    }
                }
                if (harvestButtons && size != 7 && view.isEnabled && view.hasOnClickListeners()) view.tag?.toString()?.let { result.targets[it] = WeakReference(view) }
                if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i), depth + 1)
            }
            roots.forEach { visit(it, 0) }
        }
        result.buttons += collapseHarvested(harvested)
        return result
    }

    private data class HarvestedButton(val id: String, val label: String, val view: View, val visible: Boolean)

    /**
     * 同一张卡会在不同尺寸各画一遍,扬声器之类会收进来两次;同名只留一个,看得见的优先。
     * 被岛顶替后标成隐藏的接听/挂断仍保留(点下去还是真指令),由通话认领决定响铃后要不要丢掉过期的接听。
     */
    private fun collapseHarvested(raw: List<HarvestedButton>): List<CloudButton> {
        val seenId = HashSet<String>()
        val seenLabel = HashSet<String>()
        val out = ArrayList<CloudButton>()
        for (item in raw.sortedByDescending { it.visible }) {
            if (!seenId.add(item.id)) continue
            val key = item.label.trim()
            if (key.isEmpty() || !seenLabel.add(key)) continue
            out += CloudButton(OfficialCloudCallButtons.stableId(item.label, item.id), item.label, WeakReference(item.view))
        }
        return out
    }
}
