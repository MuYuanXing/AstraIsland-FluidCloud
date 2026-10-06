package com.astraflow.fluidcloud

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * 系统卡片的模板(现行规则「系统事件接入」)。系统界面把每个服务的卡片模板解压在自己的存储里
 * (files/upk/<服务号的 SHA-256>/<版本号>/),模板里写明每个按钮按下时交给谁、带什么参数,以及按钮什么时候显示。
 * 岛在系统界面进程里读同一份模板,按这张卡片的数据代入,按钮就能发出与系统卡片完全相同的请求。
 * 只读文件,不改任何东西;读不到模板或代入不了时,这个按钮就不显示。
 */
class CloudTemplate(
    private val nodes: List<Node>,
    /** 动作名 → 登记的做法;写成列表的按顺序试,前一种打不开再用下一种(例如先开应用里的页面,没装应用时去应用商店) */
    private val actions: Map<String, List<JSONObject>>,
    private val defaults: Map<String, String>,
    private val strings: Map<String, String>,
    private val dir: File?,
    val cardClick: String?,
    private val tree: List<Tree> = emptyList(),
    /** 说明文件自带的资源表:类型(images、anim)→ 名字 → 文件路径;深色优先(现行规则「系统事件接入」) */
    private val resources: Map<String, Map<String, String>> = emptyMap(),
) {
    class Node(val type: String, val level: String, val attrs: Map<String, String>, val events: Map<String, String>, val shown: String?)

    /** 说明文件里的层级,系统没交排好的卡片时按它排出胶囊与展开两部分。 */
    class Tree(val node: Node, val children: List<Tree>)

    /**
     * 这张卡片的数据:提供方送来的覆盖模板自带的默认值;数据里还没翻译的代号换成当前语言
     * (现行规则「系统事件接入」)。
     */
    fun scope(data: Map<String, String>): Map<String, String> = (defaults + data).mapValues { (_, value) -> translate(value) }

    /** 写成 $t('strings.xxx') 的代号按文字表换成当前语言;文字表里没有时为空,不把代号显示出来。 */
    fun translate(value: String): String {
        val key = KEY.matchEntire(value.trim())?.groupValues?.get(2) ?: return value
        return strings[key.substringAfter("strings.")] ?: strings[key] ?: ""
    }

    /**
     * 系统没把排好的卡片交给岛时,照说明文件和数据排出胶囊与展开两部分(只取第一张带这两部分的卡片模板):
     * 按显示条件去掉此刻不显示的节点,文字、图片地址、进度与计时按数据代入;没有点击动作的按钮不当按钮
     * (现行规则「系统事件接入」)。
     */
    fun render(data: Map<String, String>): CloudNode? {
        val scope = scope(data)
        val card = findCard(tree) ?: return null
        val parts = card.children.filter { it.node.type in PARTS }.mapNotNull { renderNode(it, scope, 0) }
        return parts.takeIf { it.isNotEmpty() }?.let { CloudNode(CARD, children = it) }
    }

    private fun findCard(list: List<Tree>): Tree? {
        for (item in list) {
            if (item.node.type == CARD && item.children.any { it.node.type in PARTS }) return item
            findCard(item.children)?.let { return it }
        }
        return null
    }

    private fun renderNode(item: Tree, scope: Map<String, String>, depth: Int): CloudNode? {
        val node = item.node
        if (depth > 24) return null
        if (node.shown != null && !OfficialCloudExpr.truthy(OfficialCloudExpr.value(node.shown, scope, strings))) return null
        // 写在属性里的显示条件(show)与节点上的(shown)一样处理(现行规则「系统事件接入」)
        node.attrs["show"]?.let { if (!OfficialCloudExpr.truthy(OfficialCloudExpr.value(it, scope, strings))) return null }
        // 没登记点击的按钮不画;只画图标的圆形按钮留着,读它的图标(秒表的计次由星河岛补动作,图标用官方的)
        if (node.type == "button" && node.events["click"] == null && node.attrs["type"] != "circle") return null
        val props = LinkedHashMap<String, String>()
        for ((key, raw) in node.attrs) {
            val name = if (key == "value") "text" else key
            if (name !in RENDER_ATTRS) continue
            val value = OfficialCloudExpr.text(raw, scope, strings) ?: continue
            props[name] = if (name == "src") value else translate(value)
        }
        return CloudNode(node.type, node.level, props, item.children.mapNotNull { renderNode(it, scope, depth + 1) })
    }

    /** 画面上某个按钮(按层级编号找)在当前数据下对应的模板节点:同编号有几个时按「显示条件」挑出正在显示的那一个。 */
    fun button(level: String, data: Map<String, String>): Node? = node("button", level, data)

    /** 同 [button],找别的种类的节点(例如可以点的进度环 widget) */
    fun node(type: String, level: String, data: Map<String, String>): Node? {
        val scope = scope(data)
        val candidates = nodes.filter { it.level == level && it.type == type }
        if (candidates.size <= 1) return candidates.singleOrNull()
        val shown = candidates.filter { node -> node.shown == null || OfficialCloudExpr.truthy(OfficialCloudExpr.value(node.shown, scope, strings)) }
        return shown.singleOrNull()
    }

    /** 和点击动作 [event] 一样的按钮上的字(例如互传的进度环和「取消」按钮点了都是取消);没有这样的按钮时为空 */
    fun buttonLabel(event: String, data: Map<String, String>): String? =
        nodes.filter { it.type == "button" && it.events["click"] == event }.firstNotNullOfOrNull { label(it, data) }

    /** 按钮上的字:有文字用文字,纯图标按钮用无障碍描述(模板里的 voiceLabel)。 */
    fun label(node: Node, data: Map<String, String>): String? {
        val scope = scope(data)
        // 互传大图页的接收按钮误将朗读文字绑定到 reject；按接收请求读取系统提供的 accept 文字。
        val voiceLabel = if (node.attrs["voiceLabel"] == "{{reject}}" &&
            action(node.events["click"], data)?.let { it.uri == "com.oplus.oshare.cardwidget" && it.params["event"] == "accept" } == true)
            "{{accept}}" else node.attrs["voiceLabel"]
        return listOfNotNull(node.attrs["value"], voiceLabel).firstNotNullOfOrNull { raw ->
            OfficialCloudExpr.text(raw, scope, strings)?.let(::translate)?.trim()?.takeIf { it.isNotEmpty() }
        }
    }

    /**
     * 代入数据后的动作;动作不存在、类型认不出或用到的数据读不到时返回 null。
     * 登记成几种做法的列表时(打车卡片的「路线」、导航卡片的点击都是),第一种能代入的是这个动作,其余按顺序放在 [CloudAction.alternatives]。
     */
    fun action(key: String?, data: Map<String, String>): CloudAction? {
        val name = key ?: return null
        val scope = scope(data)
        val built = actions[name].orEmpty().mapNotNull { build(name, it, scope) }
        return built.firstOrNull()?.let { if (built.size > 1) it.copy(alternatives = built.drop(1)) else it }
    }

    private fun build(name: String, raw: JSONObject, scope: Map<String, String>): CloudAction? {
        fun field(name: String): String? = raw.optString(name, "").takeIf { it.isNotEmpty() }?.let { OfficialCloudExpr.text(it, scope, strings) }
        val type = field("type")?.takeIf { it == TYPE_MESSAGE || it == TYPE_DEEPLINK } ?: return null
        val uri = field("uri").orEmpty()
        // 参数里用到、但数据和说明文件自带的默认内容都没有的名字(打车提醒写的 subTitle、领券写的 status、识别音乐写的 $status)
        // 照官方当空,按钮照样显示、照样执行;动作的类型和地址仍须读到
        val params = LinkedHashMap<String, String>()
        raw.optJSONObject("params")?.let { json ->
            for (name in json.keys()) {
                val value = json.opt(name)
                params[name] = if (value is String) OfficialCloudExpr.text(value, scope, strings, blankMissing = true).orEmpty() else value?.toString() ?: ""
            }
        }
        if (type == TYPE_MESSAGE && uri.isBlank()) return null
        // 模板没写方法名时,方法名就是动作名(与系统的卡片引擎一致)
        val method = if (raw.has("method")) field("method").orEmpty() else name
        return CloudAction(type, uri, method.ifBlank { name }, params, field("data"), field("package"), field("flag"))
    }

    private val pictures = HashMap<String, CloudImage>()
    /** 图片写法(代入数据后)→ 找到的文件;找不到的也记下 */
    private val files = HashMap<String, java.util.Optional<File>>()

    /**
     * 模板里的图片内容;同一个文件只读一次、不再查文件时间(秒表这类每秒刷新的卡片不反复碰文件;
     * 同一版模板的文件不会变,换了文件的模板按新的一份重读)。动画文件(.json)是会动的图标,
     * [playing] 为假时停在第一帧;[symbol] 为真时按系统符号画(现行规则「系统事件接入」)。
     */
    fun picture(src: String?, data: Map<String, String>, symbol: Boolean = true, playing: Boolean = true): CloudImage? {
        val file = image(src, data) ?: return null
        val key = file.absolutePath
        val cached = synchronized(pictures) { pictures[key] } ?: run {
            val bytes = runCatching { file.readBytes() }.getOrNull()?.takeIf { it.size <= OfficialCloudImages.MAX_BYTES } ?: return null
            CloudImage("template:$key", bytes = bytes, symbol = true, animated = file.name.endsWith(".json"))
                .also { picture -> synchronized(pictures) { if (pictures.size >= 16) pictures.clear(); pictures[key] = picture } }
        }
        return if (cached.symbol == symbol && cached.playing == playing) cached else cached.copy(symbol = symbol, playing = playing)
    }

    /**
     * 说明文件写的颜色(已代入数据):写成 $r('color.xxx') 的按说明文件自带的资源表找(深色优先),数据里写成
     * 「{{$r('color.xxx')}}」的一样找;其余照写法读(见 [OfficialCloudColor]);读不出时为空。
     */
    fun color(raw: String?): Int? {
        val value = raw?.let(::unwrap)?.takeIf { it.isNotEmpty() } ?: return null
        val resource = RESOURCE.matchEntire(value)?.groupValues?.get(1) ?: return OfficialCloudColor.parse(value)
        return OfficialCloudColor.parse(resources[resource.substringBefore('.')]?.get(resource.substringAfter('.')))
    }

    /** 说明文件写的渐变色(已代入数据):写成 $r('color.xxx') 的按自带的资源表找;不是渐变时为空(见 [OfficialCloudColor.gradient])。 */
    fun gradient(raw: String?): List<Pair<Float, Int>> {
        val value = raw?.let(::unwrap)?.takeIf { it.isNotEmpty() } ?: return emptyList()
        val resource = RESOURCE.matchEntire(value)?.groupValues?.get(1) ?: return OfficialCloudColor.gradient(value)
        return OfficialCloudColor.gradient(resources[resource.substringBefore('.')]?.get(resource.substringAfter('.')))
    }

    /** 数据里写成「{{$r('color.xxx')}}」的颜色去掉外面的花括号(代入数据后没有再算一遍) */
    private fun unwrap(raw: String): String = raw.trim().let { if (it.startsWith("{{") && it.endsWith("}}")) it.removeSurrounding("{{", "}}").trim() else it }

    /** 卡片的服务标志:说明文件里卡片下面直接放的 A1 图片(胶囊的图标位什么都读不到时用它)。 */
    fun logo(data: Map<String, String>): CloudImage? = picture(logoNode()?.attrs?.get("src"), data)

    /** 服务标志写的图片地址(已代入数据);数据给的是应用共享位置里的图时由调用方按共享图读。没写时为空。 */
    fun logoSource(data: Map<String, String>): String? =
        logoNode()?.attrs?.get("src")?.let { OfficialCloudExpr.text(it, scope(data), strings) }?.trim()?.takeIf { it.isNotEmpty() }

    private fun logoNode() = findCard(tree)?.children?.firstOrNull { it.node.type == "image" && it.node.level.startsWith("A1") }?.node

    /** 模板里的图片(写成 $r('images.xxx') 或 assets 下的路径)在本机的文件;读不到时返回 null。同一个写法只找一次文件。 */
    fun image(src: String?, data: Map<String, String>): File? {
        val dir = dir ?: return null
        val raw = src?.let { OfficialCloudExpr.text(it, scope(data), strings) }?.trim().orEmpty()
        if (raw.isEmpty()) return null
        synchronized(files) { files[raw] }?.let { return it.orElse(null) }
        return find(dir, raw).also { found -> synchronized(files) { if (files.size >= 64) files.clear(); files[raw] = java.util.Optional.ofNullable(found) } }
    }

    private fun find(dir: File, raw: String): File? {
        val resource = RESOURCE.matchEntire(raw)?.groupValues?.get(1)
        // 写成 $r('images.xxx') / $r('anim.xxx') 的按说明文件自带的资源表找真实文件(计时器的 icon_logo 是 timer_logo.svg),
        // 表里没有时按 assets/images/名字 找;矢量图标(svg)与动画文件(json)也认;写成「/assets/…」的按模板目录里的路径;
        // 只写了文件名的(手势提示的 precise_notification_icon.png)也到 assets/images 里找
        val candidates = if (resource != null) {
            val type = resource.substringBefore('.')
            val name = resource.substringAfter('.')
            val mapped = resources[type]?.get(name)?.takeIf { !it.contains("..") }?.let { listOf(File(dir, it.trimStart('/'))) }.orEmpty()
            mapped + listOf("png", "webp", "jpg", "svg", "json").map { File(dir, "assets/images/$name.$it") }
        } else if (!raw.contains("://") && !raw.contains("..")) {
            listOf(File(dir, raw.trimStart('/'))) + listOfNotNull(File(dir, "assets/images/$raw").takeIf { '/' !in raw })
        } else emptyList()
        return candidates.firstOrNull { it.isFile && it.canonicalPath.startsWith(dir.canonicalPath) }
    }

    companion object {
        const val TYPE_MESSAGE = "message"
        const val TYPE_DEEPLINK = "deeplink"
        private const val CARD = "card-template"
        private val RESOURCE = Regex("""\${'$'}r\('([^']+)'\)""")
        /** 还没翻译的代号:$t('strings.xxx') 或 $t("strings.xxx") */
        private val KEY = Regex("""\${'$'}t\(\s*(['"])(.+?)\1\s*\)""")
        /** 卡片的胶囊与展开两部分 */
        private val PARTS = setOf("compact", "expanded", "compatible-capsule")
        /** 排卡片时代入的属性:文字(value)、图片、进度(分几段、第几段)、计时、进度两端的字、朗读文字、颜色 */
        private val RENDER_ATTRS = setOf("text", "src", "percent", "step", "currentStep", "chronometer", "count-down-time", "countDownTime", "node-labels",
            "nodeLabels", "voiceLabel", "min", "max", "alt", "autoplay", "shapeStyle", "color", "alarmTime", "category", "fontSize", "gradientColors",
            "type", "icon", "iconOn", "iconOff", "bgColor", "bgImage", "checked", "currentNodeColor", "textColor", "scaleType", "width", "progressType", "progressIcon")

        /** 模板读不到时,数据里还没翻译的代号一律当空,不把代号显示出来。 */
        fun stripKeys(data: Map<String, String>): Map<String, String> = data.mapValues { (_, value) -> if (KEY.matches(value.trim())) "" else value }

        /** 从模板文件内容建模板;[dir] 是模板所在目录(找图片用),[strings] 是当前语言的文字表。 */
        fun parse(json: String, strings: Map<String, String> = emptyMap(), dir: File? = null,
                  resources: Map<String, Map<String, String>> = emptyMap()): CloudTemplate? = runCatching {
            val root = JSONObject(json)
            val nodes = ArrayList<Node>()
            var cardClick: String? = null
            fun build(value: Any?, depth: Int): List<Tree> {
                if (depth > 40 || nodes.size > 2048) return emptyList()
                return when (value) {
                    is JSONArray -> (0 until value.length()).flatMap { build(value.opt(it), depth + 1) }
                    is JSONObject -> {
                        val type = value.optString("type")
                        val attrs = value.optJSONObject("attr")?.let { attr -> attr.keys().asSequence().associateWith { attr.opt(it)?.toString().orEmpty() } }.orEmpty()
                        val events = value.optJSONObject("events")?.let { ev -> ev.keys().asSequence().associateWith { ev.optString(it) } }.orEmpty()
                        if (type == CARD && cardClick == null) cardClick = events["click"]
                        val node = Node(type, attrs["level"].orEmpty(), attrs, events, value.optString("shown", "").takeIf { it.isNotEmpty() })
                        nodes += node
                        listOf(Tree(node, build(value.opt("children"), depth + 1)))
                    }
                    else -> emptyList()
                }
            }
            val tree = build(root.opt("template"), 0)
            val actions = root.optJSONObject("actions")?.let { a -> a.keys().asSequence().mapNotNull { k ->
                val ways = when (val value = a.opt(k)) {
                    is JSONObject -> listOf(value)
                    is JSONArray -> (0 until value.length()).mapNotNull { value.optJSONObject(it) }
                    else -> emptyList()
                }
                ways.takeIf { it.isNotEmpty() }?.let { k to it }
            }.toMap() }.orEmpty()
            val defaults = root.optJSONObject("data")?.let(::flatten).orEmpty()
            CloudTemplate(nodes, actions, defaults, strings, dir, cardClick, tree, resources)
        }.getOrNull()

        /** 卡片数据只取第一层:文字、数字、真假原样转成文字,里面再套的对象或数组保留原文(与系统发送参数时的写法一致)。 */
        fun flatten(json: JSONObject): Map<String, String> = json.keys().asSequence().associateWith { key ->
            when (val value = json.opt(key)) { null, JSONObject.NULL -> ""; else -> value.toString() }
        }
    }
}

/**
 * 找模板文件、按目录与文件的修改时间缓存解析结果。只读系统界面自己的存储。
 * 排卡片时(系统界面主线程)用 [current]:读过的直接用、不碰文件,核对文件交给后台线程。
 */
object OfficialCloudTemplates {
    private data class Cached(val modified: Long, val template: CloudTemplate?)
    private val cache = object : LinkedHashMap<String, Cached>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>?) = size > 48
    }
    /** 一个服务的模板目录上次列出的各版本,以及列的时候这个目录的修改时间 */
    private data class Versions(val modified: Long, val dirs: List<File>)
    private val versions = HashMap<String, Versions>()
    /** 服务号 → 模板目录名(服务号的 SHA-256) */
    private val hashes = java.util.concurrent.ConcurrentHashMap<String, String>()
    @Volatile var rootOverride: File? = null
    /** 系统界面自己的存储位置只取一次(每次新建一个存储上下文很费事) */
    @Volatile private var storage: File? = null

    fun root(context: Context): File = rootOverride ?: storage
        ?: File(context.createDeviceProtectedStorageContext().filesDir, "upk").also { storage = it }

    /** 服务号对应的模板目录:版本号对得上用它,否则用最新的一版。这个服务的模板目录没变(修改时间一样)就不再列一遍。 */
    fun directory(root: File, serviceId: String, version: Long): File? {
        val base = File(root, hashes.getOrPut(serviceId) { sha256(serviceId) })
        val modified = base.lastModified()
        val listed = synchronized(versions) { versions[base.path]?.takeIf { it.modified == modified } }
            ?: Versions(modified, base.listFiles()?.filter { it.isDirectory && it.name.all(Char::isDigit) }.orEmpty())
                .also { synchronized(versions) { if (versions.size >= 64) versions.clear(); versions[base.path] = it } }
        return listed.dirs.firstOrNull { it.name == version.toString() } ?: listed.dirs.maxByOrNull { it.name.toLongOrNull() ?: -1L }
    }

    /** 模板目录 → 说明文件写的服务意图(连同读的时候 config.json 的修改时间;读不到的也记下) */
    private val intents = HashMap<String, Pair<Long, String>>()
    /** config.json 的大小上限(手机上的都在几 KB 以内) */
    private const val MAX_CONFIG_BYTES = 256 * 1024

    /**
     * 这个服务的卡片说明文件写的服务意图(config.json 里 intent 的第一个 action);同一个模板目录只读一次,文件变了再读。
     * 读不到时为空。
     */
    fun intentAction(context: Context, serviceId: String, version: Long): String = runCatching {
        val file = File(directory(root(context), serviceId, version) ?: return "", "config.json")
        val modified = file.lastModified()
        synchronized(intents) { intents[file.path]?.takeIf { it.first == modified }?.let { return it.second } }
        val action = file.takeIf { it.isFile && it.length() <= MAX_CONFIG_BYTES }?.readText()
            ?.let { JSONObject(it).optJSONObject("intent")?.optJSONArray("action")?.optString(0) }?.trim().orEmpty().take(200)
        synchronized(intents) { if (intents.size >= 128) intents.clear(); intents[file.path] = modified to action }
        action
    }.getOrDefault("")

    /** 读模板文件(页面文件没变、语言没变时用解析过的那一份)。会碰文件,排卡片时用 [current]。 */
    fun load(context: Context, serviceId: String, version: Long, pageId: String, locale: Locale = Locale.getDefault()): CloudTemplate? = runCatching {
        val dir = directory(root(context), serviceId, version) ?: return null
        // 页名写法有「index」「pages/index」「pages/index.json」几种(卡片引擎报的是最后一种),统一成文件名
        val name = pageId.trim().removePrefix("/").removePrefix("pages/").removeSuffix(".json")
        val page = listOf(name.takeIf { it.isNotBlank() && it.all { c -> c.isLetterOrDigit() || c == '_' || c == '-' } }, "index")
            .firstNotNullOfOrNull { candidate -> candidate?.let { File(dir, "pages/$it.json") }?.takeIf { it.isFile } } ?: return null
        // 文字表按语言读:换了语言按新语言重读(现行规则「系统事件接入」)
        val key = "${page.absolutePath}|${locale.toLanguageTag()}"
        val modified = page.lastModified()
        synchronized(cache) {
            cache[key]?.takeIf { it.modified == modified }?.let { return it.template }
        }
        val parsed = CloudTemplate.parse(page.readText(), strings(dir, locale), dir, resources(dir))
        synchronized(cache) { cache[key] = Cached(modified, parsed) }
        parsed
    }.getOrNull()

    /** 排卡片用过的请求(服务号、版本、页、语言)→ 最近一次读到的模板(没有模板也记下) */
    private class Known(val template: CloudTemplate?)
    private val known = object : LinkedHashMap<String, Known>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Known>?) = size > 48
    }
    private val checking = HashSet<String>()
    private val background: Executor = Executors.newSingleThreadExecutor { Thread(it, "AstraFlow-CloudTemplates").apply { isDaemon = true } }
    @Volatile private var worker: Executor = background

    /**
     * 排卡片用的模板(系统界面主线程,导航、秒表每秒一次):读过的直接用,不碰文件;同时交给后台线程按目录与文件的修改时间核对一次,
     * 变了就在后台重读,下一次更新用新的一份。这一版、这一页、这种语言第一次用到时在这里读一次。
     */
    fun current(context: Context, serviceId: String, version: Long, pageId: String): CloudTemplate? {
        val locale = Locale.getDefault()
        val key = "$serviceId|$version|$pageId|${locale.toLanguageTag()}"
        val seen = synchronized(known) { known[key] }
            ?: return load(context, serviceId, version, pageId, locale).also { synchronized(known) { known[key] = Known(it) } }
        if (synchronized(checking) { checking.add(key) }) runCatching {
            worker.execute {
                try { load(context, serviceId, version, pageId, locale).let { fresh -> synchronized(known) { known[key] = Known(fresh) } } }
                finally { synchronized(checking) { checking.remove(key) } }
            }
        }.onFailure { synchronized(checking) { checking.remove(key) } }
        return seen.template
    }

    /** 检查用:换掉后台线程(传 null 换回原来的) */
    fun useWorkerForTest(replacement: Executor?) { worker = replacement ?: background }

    /** 说明文件自带的资源表(assets/res/default.json,深色 dark.json 覆盖同名项):类型 → 名字 → 文件路径。 */
    private fun resources(dir: File): Map<String, Map<String, String>> {
        val tables = HashMap<String, HashMap<String, String>>()
        for (name in listOf("default", "dark")) {
            val file = File(dir, "assets/res/$name.json").takeIf { it.isFile } ?: continue
            runCatching {
                val json = JSONObject(file.readText())
                for (type in json.keys()) {
                    val table = json.optJSONObject(type) ?: continue
                    val into = tables.getOrPut(type) { HashMap() }
                    for (key in table.keys()) table.optString(key).takeIf { it.isNotBlank() }?.let { into[key] = it }
                }
            }
        }
        return tables
    }

    /** 这种语言的文字表(找不到时依次退到同语言、简体中文、英文)。 */
    private fun strings(dir: File, locale: Locale): Map<String, String> {
        val names = listOf("${locale.language}-${locale.country}", locale.language, "zh-CN", "en-US")
        val file = names.map { File(dir, "i18n/$it.json") }.firstOrNull { it.isFile } ?: return emptyMap()
        return runCatching {
            val json = JSONObject(file.readText())
            val table = json.optJSONObject("strings") ?: json
            table.keys().asSequence().associateWith { table.optString(it) }
        }.getOrDefault(emptyMap())
    }

    fun sha256(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        .joinToString("") { "%02x".format(it) }

    fun clear() {
        synchronized(cache) { cache.clear() }
        synchronized(versions) { versions.clear() }
        synchronized(known) { known.clear() }
        storage = null
    }
}

/**
 * 模板里 {{ }} 的写法:数据字段、取反、并且、或者、等于与不等于、括号、文字与数字、$t('strings.xxx') 文字表。
 * 数据里的值都是文字:空、false、0、null 当作假。认不出的写法整体当作读不到。
 */
object OfficialCloudExpr {
    // 花括号两边都要转义:手机上的正则不认没转义的 }(电脑认),写错了这个对象整个用不了
    private val HOLE = Regex("""\{\{(.*?)\}\}""")

    /**
     * 把一段带 {{ }} 的文字代入数据;用到的数据读不到时返回 null。[blankMissing] 为真时读不到的那一处当空,
     * 照样返回代入后的文字(按钮动作的参数:见 [CloudTemplate.action])。
     */
    fun text(raw: String, scope: Map<String, String>, strings: Map<String, String>, blankMissing: Boolean = false): String? {
        if (!raw.contains("{{")) return raw
        var missing = false
        val out = HOLE.replace(raw) { match ->
            val value = evaluate(match.groupValues[1], scope, strings)
            if (value == null) { missing = true; "" } else value
        }
        return if (missing && !blankMissing) null else out
    }

    /** 整段就是一个 {{ }} 时取它的值(用于显示条件)。 */
    fun value(raw: String, scope: Map<String, String>, strings: Map<String, String>): String? {
        val inner = HOLE.matchEntire(raw.trim())?.groupValues?.get(1) ?: return raw
        return evaluate(inner, scope, strings)
    }

    fun truthy(value: String?): Boolean = !(value == null || value.isEmpty() || value == "false" || value == "0" || value == "null")

    fun evaluate(expression: String, scope: Map<String, String>, strings: Map<String, String>): String? =
        runCatching { Parser(expression, scope, strings).parse() }.getOrNull()

    private class Parser(private val s: String, private val scope: Map<String, String>, private val strings: Map<String, String>) {
        private var i = 0
        fun parse(): String? { val v = or(); skip(); check(i == s.length) { "trailing input" }; return v }
        private fun skip() { while (i < s.length && s[i].isWhitespace()) i++ }
        private fun eat(token: String): Boolean { skip(); return if (s.startsWith(token, i)) { i += token.length; true } else false }
        private fun or(): String? {
            var left = and()
            while (eat("||")) { val right = and(); left = if (truthy(left)) left else right }
            return left
        }
        private fun and(): String? {
            var left = eq()
            while (eat("&&")) { val right = eq(); left = if (!truthy(left)) left else right }
            return left
        }
        private fun eq(): String? {
            val left = unary()
            return when {
                eat("===") || eat("==") -> (left == unary()).toString()
                eat("!==") || eat("!=") -> (left != unary()).toString()
                else -> left
            }
        }
        private fun unary(): String? = if (peekNot()) { i++; (!truthy(unary())).toString() } else primary()
        private fun peekNot(): Boolean { skip(); return i < s.length && s[i] == '!' && !s.startsWith("!=", i) }
        private fun primary(): String? {
            skip()
            check(i < s.length) { "unexpected end" }
            val c = s[i]
            return when {
                c == '(' -> { i++; val v = or(); check(eat(")")) { "missing )" }; v }
                c == '\'' || c == '"' -> literal(c)
                c.isDigit() || c == '-' -> { val start = i; i++; while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++; s.substring(start, i) }
                s.startsWith("\$t(", i) -> { i += 3; skip(); val key = literal(s[i]); check(eat(")")) { "missing )" }; strings[key.substringAfter("strings.")] ?: strings[key] }
                s.startsWith("\$r(", i) -> { val start = i; i += 3; skip(); literal(s[i]); check(eat(")")) { "missing )" }; s.substring(start, i) }
                c.isLetter() || c == '_' || c == '$' -> identifier()
                else -> error("unexpected $c")
            }
        }
        private fun literal(quote: Char): String {
            check(s[i] == quote) { "expected quote" }
            val end = s.indexOf(quote, i + 1).also { check(it > i) { "open quote" } }
            val text = s.substring(i + 1, end)
            i = end + 1
            return text
        }
        private fun identifier(): String? {
            val start = i
            while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_' || s[i] == '$' || s[i] == '.')) i++
            return when (val name = s.substring(start, i)) {
                "true", "false" -> name
                "null", "undefined" -> null
                else -> scope[name]
            }
        }
    }
}
