package com.astraflow.fluidcloud

import android.content.Context
import com.astraisland.events.EventImage
import com.astraisland.events.PageNode
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * 系统卡片的模板(现行规则「系统事件接入」)。系统界面把每个服务的卡片模板解压在自己的存储里
 * (files/upk/<服务号的 SHA-256>/<版本号>/),模板写明卡片的内容树、每个元素按下时交给谁、带什么参数,以及元素什么时候显示。
 * 插件在系统界面进程里读同一份模板,按这张卡片的数据代入得到官方内容树([page]),点按时发出与系统卡片完全相同的请求([action])。
 * 只读文件,不改任何东西。
 */
class CloudTemplate(
    /** 动作名 → 登记的做法;写成列表的按顺序试,前一种打不开再用下一种(例如先开应用里的页面,没装应用时去应用商店) */
    private val actions: Map<String, List<JSONObject>>,
    private val defaults: Map<String, String>,
    private val strings: Map<String, String>,
    private val dir: File?,
    val cardClick: String?,
    private val tree: List<Tree> = emptyList(),
    /** 说明文件自带的资源表:类型(images、anim)→ 名字 → 文件路径;按屏幕密度与深浅色选好(现行规则「系统事件接入」) */
    private val resources: Map<String, Map<String, String>> = emptyMap(),
    /** 模板版本号:动作里的 package 只有 2.001.000 起的模板才读(官方卡片引擎 d6/r3.java:665) */
    private val versionCode: Long = 0L,
    /** 页面级特效:coeRes、cardLight、customBgConfig,代入数据后放在内容树根上 */
    private val coeRes: JSONObject? = null,
    private val cardLight: JSONArray? = null,
    private val customBgConfig: JSONObject? = null,
) {
    class Node(val type: String, val level: String, val attrs: Map<String, String>, val events: Map<String, String>,
               val shown: String?, val classes: List<String> = emptyList(), val styleColor: String? = null)

    /** 说明文件里的层级 */
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

    /** 动作 [name] 在这张卡片此刻的数据下是不是挂在写明锁屏显示(lockscreenCapsuleShow)的元素上:锁屏时只执行这些(与系统锁屏一致) */
    fun lockScreenAction(name: String, data: Map<String, String>): Boolean {
        val scope = scope(data)
        fun visit(item: Tree): Boolean = (item.node.events.containsValue(name) &&
            item.node.attrs["lockscreenCapsuleShow"]?.let { OfficialCloudExpr.truthy(OfficialCloudExpr.value(it, scope, strings)) } == true) ||
            item.children.any(::visit)
        return tree.any(::visit)
    }

    /**
     * 这张卡片此刻的官方内容树:流体云那一张 card-template 整棵,照模板的类型、位置代号与先后。显示条件(shown、show)不成立的元素去掉;
     * 属性全部代入数据,图片以外的换成当前语言;用到的数据读不到的属性不写(与模板引擎一致)。图片属性按 [image] 读好放进
     * [PageNode.images];交互只留 actions 里登记了的动作。页面级 coeRes、cardLight、customBgConfig 代入数据后写在根上。
     */
    fun page(data: Map<String, String>, image: (type: String, attrs: Map<String, String>, value: String) -> EventImage?): PageNode? {
        val scope = scope(data)
        return firstCard(tree)?.let { pageNode(it, scope, image) }?.let { root ->
            val effects = LinkedHashMap<String, String>()
            coeRes?.let { effects["coeRes"] = resolveJson(it, scope)?.toString().orEmpty() }
            cardLight?.let { effects["cardLight"] = resolveCardLight(it, scope)?.toString().orEmpty() }
            customBgConfig?.let { effects["customBgConfig"] = resolveJson(it, scope)?.toString().orEmpty() }
            if (effects.isEmpty()) root else root.copy(attrs = root.attrs + OfficialCloudEngine.attrs(effects))
        }
    }

    /** 流体云那一张:entry 写着 notification 的 card-template(同一页里还可能有给桌面的 desktop 卡片) */
    private fun firstCard(list: List<Tree>): Tree? {
        for (item in list) {
            if (item.node.type == CARD && item.node.attrs["entry"].orEmpty().contains("notification")) return item
            firstCard(item.children)?.let { return it }
        }
        return null
    }

    private fun pageNode(item: Tree, scope: Map<String, String>, image: (String, Map<String, String>, String) -> EventImage?): PageNode? {
        val node = item.node
        if (node.shown != null && !OfficialCloudExpr.truthy(OfficialCloudExpr.value(node.shown, scope, strings))) return null
        // 写在属性里的显示条件 show 与 shown 同义(官方卡片引擎 o1.E 把两者都当成显示与否)
        for (key in SHOW_ATTRS) node.attrs[key]?.let { if (!OfficialCloudExpr.truthy(OfficialCloudExpr.value(it, scope, strings))) return null }
        val attrs = LinkedHashMap<String, String>()
        for ((name, raw) in node.attrs) {
            val value = OfficialCloudExpr.text(raw, scope, strings) ?: continue
            if (name in PAGE_IMAGES) { attrs[name] = value; continue }
            attrs[name] = resolveValue(value) ?: continue
        }
        // 元素的 style.color 在没写 shown 时转成文字颜色(官方卡片引擎 v6/z3.java:1729-1737)
        if (node.shown.isNullOrEmpty()) node.styleColor?.let { raw ->
            OfficialCloudExpr.text(raw, scope, strings)?.let { resolveValue(it) }?.let { attrs["color"] = it; attrs["textColor"] = it }
        }
        // 文字元素的 classList:官方 TextLoader 只取第一个样式类当字号,写明 fontSize 的以 fontSize 为准;
        // 段(span)与文字元素用的字号表不同(见 OfficialCloudEngine.classFontSize)
        if ((node.type == "text" || node.type == "span") && attrs["fontSize"].isNullOrEmpty() && node.classes.isNotEmpty()) {
            val first = OfficialCloudExpr.text(node.classes.first(), scope, strings).orEmpty()
            OfficialCloudEngine.classFontSize(first, node.type)?.let { attrs["fontSize"] = it }
        }
        val kept = OfficialCloudEngine.attrs(attrs)
        val images = LinkedHashMap<String, EventImage>()
        for (name in PAGE_IMAGES) {
            val value = kept[name]?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            image(node.type, kept, value)?.let { images[name] = it }
        }
        // 进度条的节点图标(nodeIcons)写成图片地址的数组(官方 p2 按 JSON 数组逐项读):第 i 项放在 nodeIcons[i]
        attrs["nodeIcons"]?.trim()?.takeIf { it.startsWith("[") }?.let { raw ->
            val list = JSONArray(raw)
            for (i in 0 until list.length()) {
                val value = translate(list.getString(i)).let { item ->
                    RESOURCE.matchEntire(unwrap(item))?.groupValues?.get(1)?.let { ref -> "\$r('$ref')" } ?: item
                }
                image(node.type, kept, value)?.let { images["nodeIcons[$i]"] = it }
            }
        }
        return PageNode(node.type, node.level, kept, item.children.mapNotNull { pageNode(it, scope, image) }, images,
            node.events.filterValues { it in actions })
    }

    /** 一个已代入数据的属性值:资源代号按选好的资源表换成实际值;表里没有的不写 */
    private fun resolveValue(raw: String): String? {
        val text = translate(raw)
        val ref = RESOURCE.matchEntire(unwrap(text))?.groupValues?.get(1) ?: return text
        return resources[ref.substringBefore('.')]?.get(ref.substringAfter('.'))
    }

    /** 页面级 JSON(coeRes、customBgConfig)逐项代入数据;读不到的那一项不写 */
    private fun resolveJson(json: JSONObject, scope: Map<String, String>): JSONObject? {
        val out = JSONObject()
        for (key in json.keys()) {
            when (val value = json.opt(key)) {
                is String -> {
                    val text = OfficialCloudExpr.text(value, scope, strings)
                    val resolved = text?.let(::resolveValue)
                    if (resolved != null) out.put(key, resolved)
                }
                is JSONObject -> resolveJson(value, scope)?.let { out.put(key, it) }
                is JSONArray -> resolveArray(value, scope)?.let { out.put(key, it) }
                null -> Unit
                else -> out.put(key, value)
            }
        }
        return out
    }

    private fun resolveArray(array: JSONArray, scope: Map<String, String>): JSONArray? {
        val out = JSONArray()
        for (i in 0 until array.length()) {
            when (val value = array.opt(i)) {
                is String -> {
                    val text = OfficialCloudExpr.text(value, scope, strings)
                    val resolved = text?.let(::resolveValue)
                    if (resolved != null) out.put(resolved)
                }
                is JSONObject -> resolveJson(value, scope)?.let(out::put)
                is JSONArray -> resolveArray(value, scope)?.let(out::put)
                null -> Unit
                else -> out.put(value)
            }
        }
        return out
    }

    /** 卡片光效 cardLight(官方 v6/z3.java:809-835、1474-1494):只认 1 边缘、2 阴影、3 扩散三种 */
    private fun resolveCardLight(array: JSONArray, scope: Map<String, String>): JSONArray? {
        val out = JSONArray()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            fun value(key: String): String? = item.optString(key).takeIf { it.isNotEmpty() }
                ?.let { OfficialCloudExpr.text(it, scope, strings) }?.let(::resolveValue)
            val type = value("type")?.toIntOrNull()?.takeIf { it in 1..3 } ?: continue
            val color = value("color") ?: continue
            val light = JSONObject().put("type", type.toString()).put("typeName", when (type) {
                1 -> "edge"
                2 -> "shadow"
                else -> "diffusion"
            }).put("color", color)
            (value("subColor") ?: color).let { light.put("subColor", it) }
            value("position")?.let { light.put("position", it) }
            if (type == 3) {
                light.put("stop", value("stop") ?: "false")
                light.put("repeat", value("repeat") ?: "-1")
                light.put("offsetX", value("offsetX") ?: "0")
                light.put("offsetY", value("offsetY") ?: "0")
                value("circleAlpha")?.let { light.put("circleAlpha", it) }
            }
            out.put(light)
        }
        return out
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
        // 2.001.000 以前的模板不读 package(官方 d6/r3.java:665);写了 package 的去掉动作名后的空值用动作名
        val packageName = if (versionCode >= 2_001_000L) field("package") ?: name else null
        return CloudAction(type, uri, method.ifBlank { name }, params, field("data"), packageName, field("flag"))
    }

    private val pictures = HashMap<String, CloudImage>()
    /** 图片写法(代入数据后)→ 找到的文件;找不到的也记下 */
    private val files = HashMap<String, java.util.Optional<File>>()

    /**
     * 模板里的图片内容;同一个文件只读一次、不再查文件时间(秒表这类每秒刷新的卡片不反复碰文件;
     * 同一版模板的文件不会变,换了文件的模板按新的一份重读)。动画文件(.json)是会动的图标,
     * [playing] 为假时停在第一帧;[symbol] 为真时按系统符号画(现行规则「系统事件接入」)。
     */
    fun picture(
        src: String?,
        data: Map<String, String>,
        symbol: Boolean = true,
        playing: Boolean = true,
        loopStartFrame: Int? = null,
        loop: Boolean = true,
    ): CloudImage? {
        val file = image(src, data) ?: return null
        val key = file.absolutePath
        val cached = synchronized(pictures) { pictures[key] } ?: run {
            val bytes = runCatching { file.readBytes() }.getOrNull()?.takeIf { it.size <= OfficialCloudImages.MAX_BYTES } ?: return null
            CloudImage("template:$key", bytes = bytes, symbol = true, animated = file.name.endsWith(".json"))
                .also { picture -> synchronized(pictures) { if (pictures.size >= 16) pictures.clear(); pictures[key] = picture } }
        }
        return if (cached.symbol == symbol && cached.playing == playing && cached.loopStartFrame == loopStartFrame && cached.loop == loop) cached
            else cached.copy(symbol = symbol, playing = playing, loopStartFrame = loopStartFrame, loop = loop)
    }

    /** 数据里写成「{{$r('color.xxx')}}」的颜色去掉外面的花括号(代入数据后没有再算一遍) */
    private fun unwrap(raw: String): String = raw.trim().let { if (it.startsWith("{{") && it.endsWith("}}")) it.removeSurrounding("{{", "}}").trim() else it }

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
        /** 写在属性里的显示条件 */
        private val SHOW_ATTRS = listOf("show", "shown")
        private val RESOURCE = Regex("""\${'$'}r\('([^']+)'\)""")
        /** 还没翻译的代号:$t('strings.xxx') 或 $t("strings.xxx") */
        private val KEY = Regex("""\${'$'}t\(\s*(['"])(.+?)\1\s*\)""")
        /** 写的是图片的属性(模板里的图片地址、资源代号或应用共享位置的地址) */
        val PAGE_IMAGES = setOf("src", "alt", "aodStaticImage", "icon", "iconOn", "iconOff", "iconDisable", "transitionOn", "transitionOff",
            "lottieIcon", "bgImage", "background", "progressIcon")
        /** 官方 md.R:层级里有冒号的取到冒号为止(-1:template、-2:swiper 原样),没有的去掉末尾的 *;没写层级为空 */
        private fun levelKey(level: String): String? = when {
            level.isEmpty() -> null
            !level.contains(':') || level == "-1:template" || level == "-2:swiper" -> if (level.endsWith("*") && level.length > 1) level.dropLast(1) else level
            else -> level.substring(0, level.indexOf(':') + 1)
        }
        /** 从模板文件内容建模板;[dir] 是模板所在目录(找图片用),[strings] 是当前语言的文字表,[versionCode] 是模板版本。 */
        fun parse(json: String, strings: Map<String, String> = emptyMap(), dir: File? = null,
                  resources: Map<String, Map<String, String>> = emptyMap(), versionCode: Long = 0L): CloudTemplate? = runCatching {
            val root = JSONObject(json)
            var count = 0
            var cardClick: String? = null
            val actions = root.optJSONObject("actions")?.let { a -> a.keys().asSequence().mapNotNull { k ->
                val ways = when (val value = a.opt(k)) {
                    is JSONObject -> listOf(value)
                    // 登记成数组时官方只执行 deeplink 写法(官方 v6/z3.java:1443-1451)
                    is JSONArray -> (0 until value.length()).mapNotNull { value.optJSONObject(it)?.takeIf { it.optString("type") == TYPE_DEEPLINK } }
                    else -> emptyList()
                }
                ways.takeIf { it.isNotEmpty() }?.let { k to it }
            }.toMap() }.orEmpty()
            // 官方卡片引擎(seedlingsdk v6/z3.l1 与 z3.b.C)把元素的事件按层级(md.R 化简后)登记,同一层级同一事件后登记的为准,
            // 显示条件不成立的元素也登记;按下时按元素的层级取动作(z3.b.B)。秒表计时中的暂停、计次按钮自己不写事件,
            // 用的是同层级开始、取消按钮登记的动作
            val levelEvents = HashMap<String, LinkedHashMap<String, String>>()
            fun build(value: Any?, depth: Int): List<Tree> {
                if (depth > 40 || count > 2048) return emptyList()
                return when (value) {
                    is JSONArray -> (0 until value.length()).flatMap { build(value.opt(it), depth + 1) }
                    is JSONObject -> {
                        val type = value.optString("type")
                        val attrs = LinkedHashMap<String, String>()
                        value.optJSONObject("attr")?.let { attr -> attr.keys().forEach { attrs[it] = attr.opt(it)?.toString().orEmpty() } }
                        // 循环节点的位置代号官方默认 -2:swiper,repeat 原样交给排版(官方 v6/z3.java:1723-1742)
                        if (type == "swiper" && attrs["level"].isNullOrEmpty()) attrs["level"] = "-2:swiper"
                        value.optString("repeat").takeIf { it.isNotEmpty() }?.let { attrs["repeat"] = it }
                        val events = value.optJSONObject("events")?.let { ev -> ev.keys().asSequence().associateWith { ev.optString(it) } }.orEmpty()
                        if (type == CARD && cardClick == null && attrs["entry"].orEmpty().contains("notification")) cardClick = events["click"]
                        // classList 只对文字生效:官方取第一个样式类定字号,样式类里的数据照样代入
                        val classes = value.optJSONArray("classList")?.let { list ->
                            (0 until list.length()).mapNotNull { list.optString(it).takeIf(String::isNotEmpty) }
                        }.orEmpty()
                        val styleColor = value.optJSONObject("style")?.optString("color")?.takeIf { it.isNotEmpty() }
                        val node = Node(type, attrs["level"].orEmpty(), attrs, events,
                            value.optString("shown", "").takeIf { it.isNotEmpty() }, classes, styleColor)
                        count++
                        val children = build(value.opt("children"), depth + 1)
                        levelKey(node.level)?.let { key -> events.filterValues { it in actions }.forEach { (event, action) -> levelEvents.getOrPut(key, ::LinkedHashMap)[event] = action } }
                        listOf(Tree(node, children))
                    }
                    else -> emptyList()
                }
            }
            fun resolve(item: Tree): Tree = item.node.let { node ->
                val events = levelKey(node.level)?.let { levelEvents[it].orEmpty() } ?: node.events
                Tree(Node(node.type, node.level, node.attrs, events, node.shown, node.classes, node.styleColor), item.children.map(::resolve))
            }
            val tree = build(root.opt("template"), 0).map(::resolve)
            val defaults = root.optJSONObject("data")?.let(::flatten).orEmpty()
            CloudTemplate(actions, defaults, strings, dir, cardClick, tree, resources, versionCode,
                root.optJSONObject("coeRes"), root.optJSONArray("cardLight"), root.optJSONObject("customBgConfig"))
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
    /** 模板目录 → 说明文件写的第一页(连同 config.json 的修改时间;没有时为空) */
    private val firstPages = HashMap<String, Pair<Long, String>>()
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

    /**
     * 卡片数据没写页名时用说明文件 runtime.interactive.pages 的第一页(官方三代 JsCardParser:q4.java:149-153、
     * g4.java:149、b4.java:145-150)。同一个模板目录只读一次,文件变了再读。
     */
    private fun firstPage(context: Context, serviceId: String, version: Long): String = runCatching {
        val file = File(directory(root(context), serviceId, version) ?: return "", "config.json")
        val modified = file.lastModified()
        synchronized(firstPages) { firstPages[file.path]?.takeIf { it.first == modified }?.let { return it.second } }
        val page = file.takeIf { it.isFile && it.length() <= MAX_CONFIG_BYTES }?.readText()
            ?.let { JSONObject(it).optJSONObject("runtime")?.optJSONObject("interactive")?.optJSONArray("pages")?.optString(0) }
            ?.trim().orEmpty().take(200)
        synchronized(firstPages) { if (firstPages.size >= 128) firstPages.clear(); firstPages[file.path] = modified to page }
        page
    }.getOrDefault("")

    /** 读模板文件(页面文件没变、语言、屏幕密度、深浅色没变时用解析过的那一份)。会碰文件,排卡片时用 [current]。 */
    fun load(context: Context, serviceId: String, version: Long, pageId: String, locale: Locale = Locale.getDefault()): CloudTemplate? = runCatching {
        val dir = directory(root(context), serviceId, version) ?: return null
        // 页名写法有「index」「pages/index」「pages/index.json」几种(卡片引擎报的是最后一种),统一成文件名
        fun pageName(raw: String): String = raw.trim().removePrefix("/").removePrefix("pages/").removeSuffix(".json")
        val requested = pageId.takeIf { it.isNotBlank() }?.let(::pageName)
        val name = requested ?: pageName(firstPage(context, serviceId, version))
        // 数据没写页名时用说明文件的第一页;数据写明的页找不到时按官方留空,不再退到 index
        val page = name.takeIf { it.isNotBlank() && it.all { c -> c.isLetterOrDigit() || c == '_' || c == '-' } }
            ?.let { File(dir, "pages/$it.json") }?.takeIf { it.isFile } ?: return null
        // 文字表按语言读;资源表按屏幕密度选(官方 u5.java:175-306),深浅色一律取深色那份:
        // 星河岛和状态栏流体云的底色都固定是黑色,不跟手机的浅色模式走
        val density = density(context)
        val key = "${page.absolutePath}|${locale.toLanguageTag()}|${density ?: "-"}"
        val modified = page.lastModified()
        synchronized(cache) {
            cache[key]?.takeIf { it.modified == modified }?.let { return it.template }
        }
        val parsed = CloudTemplate.parse(page.readText(), strings(dir, locale), dir, resources(dir, density),
            dir.name.toLongOrNull() ?: version)
        synchronized(cache) { cache[key] = Cached(modified, parsed) }
        parsed
    }.getOrNull()

    /** 屏幕密度档(官方 u5.q():按显示密度分 ldpi/mdpi/hdpi/xhdpi/xxhdpi/xxxhdpi) */
    private fun density(context: Context): String? {
        val value = context.resources.displayMetrics.density
        return when {
            value <= 0f -> null
            value < 0.875f -> "ldpi"
            value < 1.25f -> "mdpi"
            value < 1.75f -> "hdpi"
            value < 2.5f -> "xhdpi"
            value < 3.5f -> "xxhdpi"
            else -> "xxxhdpi"
        }
    }

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

    /**
     * 说明文件自带的资源表(官方 u5.t() 深色时的顺序:本屏幕密度的 dark、phone-dark,再 dark,最后 default)。
     * 每个键以先找到的那张表为准,不按文件整体覆盖。类型 → 名字(可带下一层,用点连起来) → 值。
     */
    private fun resources(dir: File, density: String?): Map<String, Map<String, String>> {
        val names = buildList {
            density?.let { add("dark-$it.json") }
            density?.let { add("phone-dark-$it.json") }
            add("dark.json")
            add("default.json")
        }
        val tables = HashMap<String, HashMap<String, String>>()
        for (name in names) {
            val file = File(dir, "assets/res/$name").takeIf { it.isFile } ?: continue
            runCatching {
                val json = JSONObject(file.readText())
                for (type in json.keys()) {
                    val table = json.optJSONObject(type) ?: continue
                    val into = tables.getOrPut(type) { HashMap() }
                    flattenResource(table, "", into)
                }
            }
        }
        return tables
    }

    private fun flattenResource(json: JSONObject, prefix: String, into: MutableMap<String, String>) {
        for (key in json.keys()) {
            val value = json.opt(key)
            when (value) {
                is JSONObject -> flattenResource(value, prefix + key + ".", into)
                is JSONArray -> Unit
                null -> Unit
                else -> value.toString().takeIf { it.isNotBlank() }?.let { into.putIfAbsent(prefix + key, it) }
            }
        }
    }

    /**
     * 这种语言的文字表(官方 u5.z():先精确语言-地区,再语言,再同语言的其他地区,最后 defaults、en)。
     * 每个代号以先找到的那份翻译为准;都找不到时为空。
     */
    private fun strings(dir: File, locale: Locale): Map<String, String> {
        val folder = File(dir, "i18n")
        val available = folder.listFiles()?.filter { it.isFile && it.name.endsWith(".json") }
            ?.map { it.name.removeSuffix(".json") }?.sortedBy { it.lowercase(Locale.ROOT) }.orEmpty()
        if (available.isEmpty()) return emptyMap()
        val language = locale.language
        val country = locale.country
        val names = buildList {
            if (language.isNotEmpty()) {
                if (country.isNotEmpty()) add("$language-$country")
                add(language)
                addAll(available.filter { it.startsWith("$language-") && country.isNotEmpty() && it.contains(country) })
                addAll(available.filter { it.startsWith("$language-") })
            }
            if ("defaults" in available) add("defaults")
            addAll(available.filter { it.startsWith("en") })
        }.distinct()
        val result = HashMap<String, String>()
        for (name in names) {
            val file = File(folder, "$name.json").takeIf { it.isFile } ?: continue
            runCatching {
                val json = JSONObject(file.readText())
                val table = json.optJSONObject("strings") ?: json
                flattenStrings(table, "", result)
            }
        }
        return result
    }

    private fun flattenStrings(json: JSONObject, prefix: String, into: MutableMap<String, String>) {
        for (key in json.keys()) {
            val value = json.opt(key)
            when (value) {
                is JSONObject -> flattenStrings(value, prefix + key + ".", into)
                is JSONArray -> Unit
                null -> Unit
                else -> value.toString().takeIf { it.isNotBlank() }?.let { into.putIfAbsent(prefix + key, it) }
            }
        }
    }

    fun sha256(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        .joinToString("") { "%02x".format(it) }

    fun clear() {
        synchronized(cache) { cache.clear() }
        synchronized(versions) { versions.clear() }
        synchronized(known) { known.clear() }
        synchronized(intents) { intents.clear() }
        synchronized(firstPages) { firstPages.clear() }
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

    fun evaluate(expression: String, scope: Map<String, String>, strings: Map<String, String>): String? {
        // 花括号里直接写图片路径(识别音乐 {{assets/images/icon_logo_small.svg}}):官方原样当作值(官方 v6/z3.java:1932-1933)
        val path = expression.trim()
        if (path.startsWith("assets/") || path.startsWith("/assets/")) return path
        return runCatching { Parser(expression, scope, strings).parse() }.getOrNull()
    }

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
