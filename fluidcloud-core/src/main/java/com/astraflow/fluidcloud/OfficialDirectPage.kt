package com.astraflow.fluidcloud

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.Icon
import android.os.Build
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import androidx.annotation.RequiresApi
import androidx.core.graphics.ColorUtils
import com.astraisland.events.EventImage
import com.astraisland.events.PageNode
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * 直接通知(系统流体云插件 SOURCE_COMMON 记录里的那条通知)照系统流体云的解析读成官方内容树。
 *
 * 官方出处(ColorOS 17 系统流体云插件 SystemUIPlugin.apk 的 com/oplus/systemui/plugins/shared/data/parse/):
 * - 入口 r.java:83 b(sbn):通知附加信息里 oplus.livealert.card 是非空文字时走自定义界面解析 n.java(本文件的
 *   [customUi]);否则按 android.template 选样式解析(d.java:12)。
 * - 形态字典 v.java:14 d(sbn)(反编译漏掉的几处写入以原始指令为准):
 *   capsule ← b(),card ← c(),miniCard ← f(),immersiveCard ← e();自定义界面解析的 e() 恒为空(n.java:8408)。
 * - 资源写法「类型/名字」的解析见 shared/data/protocol/a.java:46 与 com/heytap/log/core/bean/b.java:233 F()。
 *
 * 排版:官方显示端按自己的区段样式画(胶囊 LACapsuleSectionStyle,卡片 LACardSectionStyle),不是模板卡片的
 * card_modular_* 区域排法。区域的 category 写官方区段样式名,元素的 level 写官方区段里的槽位名(pic、title、
 * titleLeft、buttonList……)。
 */
internal object OfficialDirectPage {
    private const val CARD_EXTRA = "oplus.livealert.card"
    private const val CAPSULE_EXTRA = "oplus.livealert.capsule"

    /** 系统流体云插件的颜色(res/values/colors.xml):card_content_title_color、card_content_text_color、mini_card_text_color */
    private val CARD_TITLE = 0xE6FFFFFF.toInt()
    private val CARD_TEXT = 0x8AFFFFFF.toInt()
    private val MINI_TEXT = 0xFFFFFFFF.toInt()

    /** default_btn_background_color、capsule_default_text_color */
    private val BUTTON_FILL = 0x1EFFFFFF
    private val CAPSULE_TEXT = 0xE6FFFFFF.toInt()

    /** 每条通知(按通知 key)此刻登记的动作:动作名 → Intent 或 PendingIntent;按钮另记它当时的状态 */
    private val registered = ConcurrentHashMap<String, Map<String, Target>>()

    /** 一个元素登记的点按目标;[buttonState] 是按钮当时的状态(官方按钮发送时带 la_state_eum),不是按钮时为空 */
    private class Target(val intent: Intent?, val pending: PendingIntent?, val buttonState: String?)

    /**
     * 系统界面进程自己的上下文(官方解析与点按用的 hostContext,即系统界面的应用上下文):
     * 读应用资源里的图与颜色、打开页面都用它。
     */
    private val host: Context by lazy {
        Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as Context
    }

    /**
     * 这条通知的官方内容树,照官方先后选两条路(r.java:83 b()):附加信息 oplus.livealert.card 是非空文字时先走自定义界面解析
     * (根 attrs protocol=customUi);没有这项或自定义界面解析不成立时走样式解析(r.java:105-111,根 attrs protocol=style)。
     * 读到的动作按通知 key 记下,供 [perform] 执行。
     */
    fun build(sbn: StatusBarNotification): PageNode? {
        val card = sbn.notification.extras.getString(CARD_EXTRA)
        val custom = if (card.isNullOrEmpty()) null else Reader(sbn).let { reader -> reader.customUi(JSONObject(card))?.let { reader to it } }
        val (reader, page) = custom ?: Reader(sbn).let { reader -> reader to (reader.style() ?: return null) }
        registered[sbn.key] = reader.targets
        return page
    }

    /**
     * 执行内容树上登记的动作 [action],照官方点下去的做法:
     * - 整张胶囊、卡片、元素的点按(template/page/entity/f.java:100-200):Intent 加 FLAG_ACTIVITY_NEW_TASK 后
     *   startActivity;PendingIntent 直接 send()。两样都有时两样都做。
     * - 按钮(template/component/module/g.java:530-600):Intent 同上;PendingIntent 用
     *   send(context, 0, Intent().putExtra("la_state_eum", 状态为 ON 时 1,否则 0))。
     */
    fun perform(sbn: StatusBarNotification, action: String): Boolean {
        val target = registered[sbn.key]?.get(action) ?: return false
        target.intent?.let { intent ->
            intent.flags = intent.flags or Intent.FLAG_ACTIVITY_NEW_TASK
            host.startActivity(intent)
        }
        target.pending?.let { pending ->
            if (target.buttonState == null) pending.send()
            else pending.send(host, 0, Intent().putExtra("la_state_eum", if (target.buttonState == "ON") 1 else 0))
        }
        return target.intent != null || target.pending != null
    }

    /** 读一条通知:资源按官方协议在这条通知的附加信息与来源应用里找;读到的动作记在 [targets] */
    private class Reader(val sbn: StatusBarNotification) {
        val targets = LinkedHashMap<String, Target>()
        private val extras = sbn.notification.extras

        /**
         * 自定义界面解析(n.java):胶囊 b()(n.java:54-158)、卡片 c()(n.java:339 起)、小卡 f()(n.java:8416-8490);
         * 沉浸卡 e() 官方恒为空(n.java:8408)。胶囊、卡片任一官方解析不成立时官方整条改走样式解析(r.java:99-111),
         * 这里整条不出内容树。
         */
        fun customUi(card: JSONObject): PageNode? {
            val capsule = capsule(card) ?: return null
            val expanded = card(card)
            val mini = miniCard(card) ?: return null
            return PageNode("card-template", attrs = mapOf("protocol" to "customUi"), children = listOf(capsule, expanded, mini))
        }

        // ---------------- 胶囊(n.java:54-158) ----------------

        /**
         * 胶囊:附加信息 oplus.livealert.capsule 有内容时读它的 capsuleData(type 为 t_sym 或 t_sym_action)与 attr;
         * 没有时用卡片 primaryData(只认 t_standard:icon 放左边图,title 放右边文字,n.java:69-96)。
         * 左边区段是 IMAGE_TEXT_STYLE(i.java:13 k()),右边 t_sym 是 TEXT_IMAGE_STYLE、t_sym_action 是 ICONS_STYLE。
         */
        private fun capsule(card: JSONObject): PageNode? {
            val raw = extras.getString(CAPSULE_EXTRA)
            if (raw.isNullOrEmpty()) {
                val primary = card.optJSONObject("primaryData") ?: return null
                if (primary.optString("type") != "t_standard") return null
                val leading = region("leading", "IMAGE_TEXT_STYLE", null,
                    listOfNotNull(primary.optJSONObject("icon")?.let { picture(it, "pic", "capsule.pic") }))
                val trailing = region("trailing", "TEXT_IMAGE_STYLE", null,
                    listOfNotNull(primary.optJSONObject("title")?.let { text(it, "title", "capsule.title") }))
                return compact(null, leading, trailing)
            }
            val json = JSONObject(raw)
            val data = json.optJSONObject("capsuleData") ?: return null
            val attr = json.optJSONObject("attr")
            val leading = region("leading", "IMAGE_TEXT_STYLE", null, firstSection(data, "capsule.first"))
            val trailing = when (data.optString("type")) {
                "t_sym" -> region("trailing", "TEXT_IMAGE_STYLE", null, listOfNotNull(
                    data.optJSONObject("lastIcon")?.let { picture(it, "pic", "capsule.last.pic") },
                    data.optJSONObject("lastTitle")?.let { text(it, "title", "capsule.last.title") },
                    data.optJSONObject("lastContent")?.let { text(it, "content", "capsule.last.content") },
                ))
                // 按钮列表 lastBtns 有内容时放按钮,否则放 lastIcons 的第一张图(n.java:150-155)
                "t_sym_action" -> region("trailing", "ICONS_STYLE", null, listOfNotNull(
                    buttons(data, "lastBtns", "buttonList", "capsule.last.button")
                        ?: data.optJSONArray("lastIcons")?.takeIf { it.length() > 0 }?.optJSONObject(0)
                            ?.let { picture(it, "picModule", "capsule.last.picModule") },
                ))
                else -> return null
            }
            return compact(attr, leading, trailing)
        }

        /** 胶囊左边区段(i.java:13 k()):firstIcon → pic,firstTitle → title,firstContent → content */
        private fun firstSection(data: JSONObject, prefix: String): List<PageNode> = listOfNotNull(
            data.optJSONObject("firstIcon")?.let { picture(it, "pic", "$prefix.pic") },
            data.optJSONObject("firstTitle")?.let { text(it, "title", "$prefix.title") },
            data.optJSONObject("firstContent")?.let { text(it, "content", "$prefix.content") },
        )

        /**
         * 胶囊整体(n.java:8517 i()):背景 attr.background,主色 attr.primaryColor(没写时官方用自己的默认主色
         * card_default_primary_color),亮边 attr.bgLight,整个胶囊的点按 attr.intent。
         */
        private fun compact(attr: JSONObject?, leading: PageNode, trailing: PageNode): PageNode =
            whole("compact", attr, "capsule", listOf(leading, trailing))

        // ---------------- 卡片(n.java:339 起) ----------------

        /**
         * 卡片:headerData(顶部,官方区域 HEAD)、primaryData(中间,INFO)、subData(底部,ASSIST),外加整张卡片的 attr。
         * 区段样式照官方:顶部 t_sym → SYMMETRY_STYLE(j.java n())、t_sym_action → IMAGE_TEXT_STYLE(j.java m())、
         * t_custom → CUSTOM_STYLE(j.java l());底部 t_actions → BUTTON_LIST_STYLE(j.java k())、t_custom → CUSTOM_STYLE、
         * t_component → SINGLE_MODULE_STYLE(n.java:553-597);中间 t_textinfos → INFOS_STYLE(n.java:641-709)。
         */
        private fun card(card: JSONObject): PageNode {
            val header = card.optJSONObject("headerData")?.let { data ->
                when (data.optString("type")) {
                    "t_sym" -> symmetry(data, "leading", "card.header")
                    "t_sym_action" -> imageText(data, "leading", "card.header")
                    "t_custom" -> custom(data, "leading")
                    else -> null
                }
            }
            val primary = card.optJSONObject("primaryData")?.let { data ->
                when (data.optString("type")) {
                    "t_textinfos" -> infos(data, "center", "card.primary")
                    else -> null
                }
            }
            val sub = card.optJSONObject("subData")?.let { data ->
                when (data.optString("type")) {
                    "t_actions" -> region("trailing", "BUTTON_LIST_STYLE", data.optJSONObject("attr"),
                        listOfNotNull(buttons(data, "btns", "buttonList", "card.sub.button")))
                    "t_custom" -> custom(data, "trailing")
                    "t_component" -> component(data, "trailing", "card.sub")
                    else -> null
                }
            }
            return whole("expanded", card.optJSONObject("attr"), "card", listOfNotNull(header, primary, sub))
        }

        /**
         * 对称区段 SYMMETRY_STYLE(j.java n()):左 firstIcon/firstTitle/firstLabel/firstContent,
         * 右 lastIcon/lastTitle/lastLabel/lastContent,中间 spaceIcon/spaceSubText/spaceText,另有 firstExLabel、
         * lastExLabel、firstLabelSub、lastLabelSub;位置 contentPos、labelPos、exLabelPos、spacePos 原样写进区域属性。
         */
        private fun symmetry(data: JSONObject, region: String, prefix: String): PageNode {
            val children = listOfNotNull(
                data.optJSONObject("firstIcon")?.let { picture(it, "picLeft", "$prefix.picLeft") },
                data.optJSONObject("firstTitle")?.let { text(it, "titleLeft", "$prefix.titleLeft") },
                data.optJSONObject("firstLabel")?.let { text(it, "subTitleLeft", "$prefix.subTitleLeft") },
                data.optJSONObject("firstContent")?.let { text(it, "contentLeft", "$prefix.contentLeft") },
                data.optJSONObject("lastIcon")?.let { picture(it, "picRight", "$prefix.picRight") },
                data.optJSONObject("lastTitle")?.let { text(it, "titleRight", "$prefix.titleRight") },
                data.optJSONObject("lastLabel")?.let { text(it, "subTitleRight", "$prefix.subTitleRight") },
                data.optJSONObject("lastContent")?.let { text(it, "contentRight", "$prefix.contentRight") },
                data.optJSONObject("spaceIcon")?.let { picture(it, "pic", "$prefix.pic") },
                data.optJSONObject("spaceSubText")?.let { text(it, "subContentCenter", "$prefix.subContentCenter") },
                data.optJSONObject("spaceText")?.let { text(it, "contentCenter", "$prefix.contentCenter") },
                data.optJSONObject("firstExLabel")?.let { text(it, "exSubTitleLeft", "$prefix.exSubTitleLeft") },
                data.optJSONObject("lastExLabel")?.let { text(it, "exSubTitleRight", "$prefix.exSubTitleRight") },
                data.optJSONObject("firstLabelSub")?.let { text(it, "subTitleLeftSub", "$prefix.subTitleLeftSub") },
                data.optJSONObject("lastLabelSub")?.let { text(it, "subTitleRightSub", "$prefix.subTitleRightSub") },
            )
            val node = region(region, "SYMMETRY_STYLE", data.optJSONObject("attr"), children)
            val positions = listOf("contentPos", "labelPos", "exLabelPos", "spacePos")
                .mapNotNull { key -> data.optString(key).takeIf { it.isNotEmpty() }?.let { key to it } }
            return node.copy(attrs = node.attrs + positions)
        }

        /**
         * 图文区段 IMAGE_TEXT_STYLE(j.java m()):firstTitle → content,firstContent → subContent,firstIcon → image;
         * 右边 lastBtns 有内容时放按钮列表,否则放 lastIcons 的第一张图(picModule)。
         */
        private fun imageText(data: JSONObject, region: String, prefix: String): PageNode = region(region, "IMAGE_TEXT_STYLE",
            data.optJSONObject("attr"), listOfNotNull(
                data.optJSONObject("firstTitle")?.let { text(it, "content", "$prefix.content") },
                data.optJSONObject("firstContent")?.let { text(it, "subContent", "$prefix.subContent") },
                data.optJSONObject("firstIcon")?.let { picture(it, "image", "$prefix.image") },
                buttons(data, "lastBtns", "buttonList", "$prefix.button")
                    ?: data.optJSONArray("lastIcons")?.takeIf { it.length() > 0 }?.optJSONObject(0)
                        ?.let { picture(it, "picModule", "$prefix.picModule") },
            ))

        /**
         * 应用自绘区段 CUSTOM_STYLE(j.java l()):rvId 指向附加信息里的 RemoteViews(资源写法 layout/名字),
         * 官方把它原样铺进区段(customModule);rvId 读不到时官方判解析失败。这里写明 rvId,内容由显示端按 RemoteViews 处理。
         */
        private fun custom(data: JSONObject, region: String): PageNode =
            region(region, "CUSTOM_STYLE", data.optJSONObject("attr"),
                listOf(PageNode("remote-views", "customModule", mapOf("rvId" to data.optString("rvId")))))

        /**
         * 单模块区段 SINGLE_MODULE_STYLE(n.java:553-597,底部 t_component):按顺序认 Icon(放图)、ProgressWidget(放进度图)、
         * Chronometer(放计时文字)、LabelText(放标签),键名首字母大小写都认(h.java:95 h()、101 i())。
         */
        private fun component(data: JSONObject, region: String, prefix: String): PageNode {
            val child = when {
                has(data, "Icon") -> child(data, "Icon")?.let { picture(JSONObject().put("icon", it), "picModule", "$prefix.picModule") }
                has(data, "ProgressWidget") -> picture(data, "picModule", "$prefix.picModule")
                has(data, "Chronometer") -> child(data, "Chronometer")?.let { label(it, "labelModule", "$prefix.labelModule") }
                else -> child(data, "LabelText")?.let { label(it, "labelModule", "$prefix.labelModule") }
            }
            return region(region, "SINGLE_MODULE_STYLE", data.optJSONObject("attr"), listOfNotNull(child)).let {
                it.copy(attrs = it.attrs + ("gravityDefault" to "CENTER_VERTICAL"))
            }
        }

        /**
         * 信息区段 INFOS_STYLE(n.java:641-709,中间 t_textinfos):subs 前三项 → titleLeft/titleCenter/titleRight,
         * infos 前三项 → contentLeft/contentCenter/contentRight。
         */
        private fun infos(data: JSONObject, region: String, prefix: String): PageNode {
            val slots = listOf("Left", "Center", "Right")
            val subs = data.optJSONArray("subs")
            val infos = data.optJSONArray("infos")
            val children = buildList {
                slots.forEachIndexed { index, side ->
                    subs?.optJSONObject(index)?.let { add(text(it, "title$side", "$prefix.title$side")) }
                }
                slots.forEachIndexed { index, side ->
                    infos?.optJSONObject(index)?.let { add(text(it, "content$side", "$prefix.content$side")) }
                }
            }
            return region(region, "INFOS_STYLE", data.optJSONObject("attr"), children)
        }

        // ---------------- 小卡(n.java:8416-8490) ----------------

        /**
         * 小卡:卡片 primaryData 必须有(没有时官方判解析失败);criticalData 有时只认 t_sym_action,按图文区段读
         * (j.java m());没有时读 primaryData 的 title → content、subTitle → subContent、icon → image(IMAGE_TEXT_STYLE)。
         * 主色 attr.primaryColor,点按 attr.intent。
         */
        private fun miniCard(card: JSONObject): PageNode? {
            val primary = card.optJSONObject("primaryData") ?: return null
            val critical = card.optJSONObject("criticalData")
            if (critical != null && critical.optString("type") != "t_sym_action") return null
            val section = if (critical != null) imageText(critical, "center", "mini.critical") else region("center", "IMAGE_TEXT_STYLE",
                primary.optJSONObject("attr"), listOfNotNull(
                    primary.optJSONObject("title")?.let { text(it, "content", "mini.content") },
                    primary.optJSONObject("subTitle")?.let { text(it, "subContent", "mini.subContent") },
                    primary.optJSONObject("icon")?.let { picture(it, "image", "mini.image") },
                ))
            val attr = card.optJSONObject("attr")
            val whole = whole("miniCard", attr, "miniCard", listOf(section))
            return whole.copy(attrs = whole.attrs.filterKeys { it == "primaryColor" })
        }

        // ---------------- 样式解析(r.java:105-111) ----------------

        /**
         * 样式解析:d.java:12 a() 按附加信息 android.template 选样式——没写且通知没有自定义视图 contentView 是标准样式(u.java),
         * 没写而有 contentView 是自定义样式(g.java),安卓 16 起的 ProgressStyle 是进度按钮样式(t.java),BigTextStyle 是长文本
         * 样式(a.java),安卓 17 起的 MetricStyle 是指标样式(q.java,[metricCard]);CallStyle、MediaStyle 官方交给各自的专用
         * 解析器(c.java、media/parse/f.java),本文件不读,不出内容树;其余写法(NONE)官方没有对应解析器,按标准样式读(r.java:110)。
         * 每种样式照 v.java:14 d() 读胶囊 b()、卡片 c()、小卡 f();这四种样式的胶囊都是 s.java:616 的通用胶囊,不会为空,
         * 沉浸卡 e() 恒为空。
         */
        fun style(): PageNode? {
            val notification = sbn.notification
            val template = extras.getString("android.template")
            val (card, mini) = when {
                template.isNullOrEmpty() && notification.contentView == null -> standardCard(null, null) to standardMini(null, null)
                template.isNullOrEmpty() -> customCard() to standardMini(null, null)
                Build.VERSION.SDK_INT >= 36 && template == Notification.ProgressStyle::class.java.name -> progressCard() to standardMini(null, null)
                template == Notification.CallStyle::class.java.name -> return null
                template == Notification.BigTextStyle::class.java.name -> {
                    // 长文本(a.java:103 q()):标题用 android.title.big(空时 android.title),正文用 android.bigText(空时 android.text)
                    val title = extras.getCharSequence("android.title.big")?.takeIf { it.isNotEmpty() } ?: extras.getCharSequence("android.title")
                    val text = extras.getCharSequence("android.bigText")?.takeIf { it.isNotEmpty() } ?: extras.getCharSequence("android.text")
                    standardCard(title, text) to standardMini(title, text)
                }
                template == Notification.MediaStyle::class.java.name -> return null
                Build.VERSION.SDK_INT >= 37 && template == Notification.MetricStyle::class.java.name ->
                    metricCard(metricStyle() ?: return null) to standardMini(null, null)
                else -> standardCard(null, null) to standardMini(null, null)
            }
            return PageNode("card-template", attrs = mapOf("protocol" to "style"), children = listOf(styleCapsule(), card, mini))
        }

        /**
         * 通用胶囊的图(s.java:616 g()):通知小图标(附加信息 oplus_small_icon,没有时用通知的 smallIcon)读得出时用它;
         * 读不出时用附加信息 oplus.substAppIcon,再读不出用来源应用的图标。卡片右侧的应用图标也用这张(dataRepo oplus.smallIcon)。
         */
        private val capsuleIcon: Icon by lazy {
            val small = (extras.getParcelable("oplus_small_icon", Icon::class.java) ?: sbn.notification.smallIcon)
                ?.takeIf { it.loadDrawable(host) != null }
            small ?: extras.getParcelable("oplus.substAppIcon", Icon::class.java)?.takeIf { it.loadDrawable(host) != null }
                ?: Icon.createWithResource(sbn.packageName, host.packageManager.getApplicationInfo(sbn.packageName, 0).icon)
        }

        /**
         * 通用胶囊(s.java:616 g()):左边图文区段 IMAGE_TEXT_STYLE 放 pic([capsuleIcon],20×20 圆形;附加信息 lottieRes
         * 不是空白时作为这张图的动画),右边文图区段 TEXT_IMAGE_STYLE 放 title([capsuleTitle])。
         */
        private fun styleCapsule(): PageNode {
            val attrs = linkedMapOf("width" to "20", "height" to "20", "shape" to "CIRCLE")
            extras.getString("lottieRes")?.takeIf { it.isNotBlank() }?.let { attrs["lottieRes"] = it }
            val pic = PageNode("image", "pic", attrs, images = mapOf("src" to EventImage.Platform(capsuleIcon)))
            return PageNode("compact", children = listOf(
                region("leading", "IMAGE_TEXT_STYLE", null, listOf(pic)),
                region("trailing", "TEXT_IMAGE_STYLE", null, listOf(capsuleTitle())),
            ))
        }

        /**
         * 胶囊右边的字(s.java:719 i()),颜色是附加信息 oplus.shortCriticalTextColor(没有时 capsule_default_text_color)
         * 提亮到亮度至少 0.6(b.java:373 M()),单行。先后:安卓 16 起的 shortCriticalText 不空时写它;指标样式(q.java:346 i())
         * 接着写重点指标(criticalMetric 在指标里的那一项,没有时第一项,见 [metricInto]),一项指标都没有时写 android.title;
         * 其余样式 when 不为 0 且
         * android.showChronometer 时是计时(android.chronometerCountDown 定正倒);when 不为 0、android.showWhen 且离 when
         * 超过 1 分钟时是到 when 的倒计时(胶囊倒计时);否则写 android.title。
         */
        private fun capsuleTitle(): PageNode {
            val notification = sbn.notification
            val whenMs = notification.`when`
            val critical = if (Build.VERSION.SDK_INT >= 36) notification.shortCriticalText.orEmpty() else ""
            val ink = brighten(extras.getInt("oplus.shortCriticalTextColor", CAPSULE_TEXT))
            val attrs = linkedMapOf("textColor" to color(ink), "maxLine" to "1")
            val metricStyle = if (Build.VERSION.SDK_INT >= 37 && extras.getString("android.template") == Notification.MetricStyle::class.java.name)
                metricStyle() else null
            when {
                critical.isNotEmpty() -> attrs["value"] = critical
                Build.VERSION.SDK_INT >= 37 && metricStyle != null -> {
                    val metrics = metricStyle.metrics
                    val index = metricStyle.criticalMetric?.let(metrics::indexOf)?.takeIf { it >= 0 } ?: 0
                    val picked = metrics.getOrNull(index)
                    if (picked != null) metricInto(attrs, picked, ink)
                    else attrs["value"] = extras.getCharSequence("android.title")?.toString().orEmpty()
                }
                whenMs != 0L && extras.getBoolean("android.showChronometer") ->
                    timer(attrs, whenMs, extras.getBoolean("android.chronometerCountDown", false), chips = false)
                whenMs != 0L && extras.getBoolean("android.showWhen") && whenMs - System.currentTimeMillis() > 60_000L ->
                    timer(attrs, whenMs, countDown = true, chips = true)
                else -> attrs["value"] = extras.getCharSequence("android.title")?.toString().orEmpty()
            }
            return PageNode("text", "title", attrs)
        }

        /**
         * 计时(TimerModel,C0861q):倒计时的 when 按开机时钟换算(f.java:594 n()),正计时照墙上时钟;这些样式的计时都在走
         * (started),不显示毫秒;[chips] 是胶囊倒计时(chipsCountdown)。
         */
        private fun timer(attrs: MutableMap<String, String>, whenMs: Long, countDown: Boolean, chips: Boolean) {
            attrs["when"] = (if (countDown) SystemClock.elapsedRealtime() + (whenMs - System.currentTimeMillis()) else whenMs).toString()
            attrs["clock"] = if (countDown) "elapsed" else "wall"
            attrs["countDown"] = countDown.toString()
            attrs["started"] = "true"
            attrs["showMs"] = "false"
            if (chips) attrs["chipsCountdown"] = "true"
        }

        /**
         * 信息区段 NORMAL_STYLE(s.java:103 k()),[title]、[text] 没给时读 android.title、android.text:
         * title 标题(不空才有);content 正文——when 不为 0 且 android.showChronometer 时是计时,否则是正文(不空才有);
         * subContent 是 android.subText(不空才有);pic 是通知大图 largeIcon(读得出才有)。
         * 卡片:标题、正文 card_content_title_color,副文 card_content_text_color,最多 2 行;大图 42×42、圆角 8。
         * 小卡 [mini]:字一律 mini_card_text_color、字号 12、正文与副文字重 REGULAR,单行并滚动一遍(repeat 1);标题与正文都空时
         * 标题写应用名;没有大图时用通知 smallIcon;图的尺寸照 f.java:391 j() 随大图比例,圆角 6。
         */
        private fun info(title: CharSequence?, text: CharSequence?, mini: Boolean): PageNode {
            val notification = sbn.notification
            val textValue = (text ?: extras.getCharSequence("android.text"))?.toString().orEmpty()
            val titleRaw = (title ?: extras.getCharSequence("android.title"))?.toString().orEmpty()
            val titleValue = if (mini && titleRaw.isEmpty() && textValue.isEmpty()) {
                host.packageManager.getApplicationLabel(host.packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
            } else titleRaw
            val subText = extras.getCharSequence("android.subText")?.toString().orEmpty()
            val main = if (mini) MINI_TEXT else CARD_TITLE
            val weight = if (mini) "normal" else null
            val children = mutableListOf<PageNode>()
            if (titleValue.isNotEmpty()) children += styleText("title", titleValue, main, mini, null)
            if (notification.`when` != 0L && extras.getBoolean("android.showChronometer", false)) {
                val attrs = styleAttrs(main, mini, weight)
                timer(attrs, notification.`when`, extras.getBoolean("android.chronometerCountDown", false), chips = false)
                children += PageNode("text", "content", attrs)
            } else if (textValue.isNotEmpty()) {
                children += styleText("content", textValue, main, mini, weight)
            }
            if (subText.isNotEmpty()) children += styleText("subContent", subText, if (mini) MINI_TEXT else CARD_TEXT, mini, weight)
            val large = notification.getLargeIcon()?.takeIf { it.loadDrawable(host) != null }
            val pic = large ?: if (mini) notification.smallIcon?.takeIf { it.loadDrawable(host) != null } else null
            if (pic != null) {
                val (width, height) = if (mini) miniPictureSize(large) else 42 to 42
                children += PageNode("image", "pic", mapOf("width" to width.toString(), "height" to height.toString(),
                    "radius" to if (mini) "6" else "8"), images = mapOf("src" to EventImage.Platform(pic)))
            }
            return region("center", "NORMAL_STYLE", null, children)
        }

        /** 信息区段一段字的样式(TextModel,C0860p):颜色、小卡字号 12 与滚动一遍、字重;卡片最多 2 行,小卡单行 */
        private fun styleAttrs(ink: Int, mini: Boolean, weight: String?): MutableMap<String, String> {
            val attrs = linkedMapOf("textColor" to color(ink), "maxLine" to if (mini) "1" else "2")
            if (mini) {
                attrs["textSize"] = "12"
                attrs["repeat"] = "1"
            }
            weight?.let { attrs["fontWeight"] = it }
            return attrs
        }

        private fun styleText(level: String, value: String, ink: Int, mini: Boolean, weight: String?): PageNode =
            PageNode("text", level, styleAttrs(ink, mini, weight).also { it["value"] = value })

        /**
         * 小卡图的尺寸(f.java:391 j(),宽 × 高):大图读不出或宽高为 0 时 28×28;横图宽 28、高按比例;竖图高 32、宽按比例;
         * 方图 28×28。
         */
        private fun miniPictureSize(large: Icon?): Pair<Int, Int> {
            val drawable = large?.loadDrawable(host)
            val width = drawable?.intrinsicWidth ?: 0
            val height = drawable?.intrinsicHeight ?: 0
            if (width == 0 || height == 0) return 28 to 28
            val ratio = width.toFloat() / height
            return when {
                width > height -> 28 to (28 / ratio).toInt()
                width != height -> (ratio * 32).toInt() to 32
                else -> 28 to 28
            }
        }

        /**
         * 挑通知按钮(s.java:548 n()):按顺序最多 [limit] 个,跳过上下文按钮(isContextual)、按钮附加信息标了
         * key_action_priority 的;有文字、有 actionIntent 又带回复输入(RemoteInput)的,只要有一个输入允许自由输入或给了候选回复就跳过。
         */
        private fun pickActions(limit: Int?): List<Notification.Action> {
            val picked = ArrayList<Notification.Action>()
            for (action in sbn.notification.actions.orEmpty()) {
                if (limit != null && picked.size >= limit) continue
                if (action.isContextual || action.extras.getBoolean("key_action_priority", false)) continue
                val inputs = action.remoteInputs
                val reply = !action.title.isNullOrEmpty() && action.actionIntent != null && inputs != null &&
                    inputs.any { it.allowFreeFormInput || !it.choices.isNullOrEmpty() }
                if (!reply) picked += action
            }
            return picked
        }

        /**
         * 一个通知按钮(s.java:590 o(),ButtonModel 的标签 ICON_AND_TEXT):字 card_content_title_color 单行([hideText] 时不写字),
         * 图标 20×20 圆形(按钮自己的图标;[hideIcon] 时不放),底色 default_btn_background_color,外形 ROUND_RECT、高 26,
         * 说明是按钮文字;点下照按钮的做法发送 actionIntent(按钮没有状态,la_state_eum 为 0)。
         */
        private fun actionButton(action: Notification.Action, hideText: Boolean, hideIcon: Boolean, name: String): PageNode {
            val title = action.title?.toString().orEmpty()
            val attrs = linkedMapOf("value" to if (hideText) "" else title, "textColor" to color(CARD_TITLE), "maxLine" to "1",
                "bgColor" to color(BUTTON_FILL), "labelType" to "ICON_AND_TEXT", "shape" to "ROUND_RECT", "height" to "26",
                "iconSize" to "20", "desc" to title)
            val icon = action.getIcon()
            val images = if (hideIcon || icon == null) emptyMap() else mapOf("icon" to EventImage.Platform(icon))
            val pending = action.actionIntent
            val events = if (pending != null) {
                targets[name] = Target(null, pending, "")
                mapOf("click" to name)
            } else emptyMap()
            return PageNode("button", "button", attrs, images = images, events = events)
        }

        /**
         * 通知按钮列表(s.java:689 h()):[pickActions] 最多 3 个;有的没字而全都有图标时只画图标,全都有字而有的没图标时只画字。
         */
        private fun actionButtons(prefix: String): List<PageNode> {
            val actions = pickActions(3)
            val someUntitled = actions.any { it.title.isNullOrEmpty() }
            val someIconless = actions.any { it.getIcon() == null }
            return actions.mapIndexed { index, action ->
                actionButton(action, hideText = someUntitled && !someIconless, hideIcon = !someUntitled && someIconless, "$prefix.$index")
            }
        }

        /** 按钮列表区段 BUTTON_LIST_STYLE:buttonList 按 [arr] 排列(eqd 等分、def 默认),区段居中(LayoutAlign CENTER) */
        private fun buttonRegion(type: String, buttons: List<PageNode>, arr: String): PageNode =
            region(type, "BUTTON_LIST_STYLE", null, listOf(PageNode("button-list", "buttonList", mapOf("arr" to arr), buttons)))
                .let { it.copy(attrs = it.attrs + ("gravity" to "CENTER")) }

        /**
         * 卡片右侧的辅助区段 SINGLE_MODULE_STYLE(u.java:57-66):labelModule 是 ICON_LABEL 标签,图是 [capsuleIcon](24×24、圆角 6),
         * 区段贴顶(LayoutAlign TOP)。
         */
        private fun appIconAssist(): PageNode {
            val label = PageNode("label", "labelModule", mapOf("labelType" to "ICON_LABEL", "width" to "24", "height" to "24", "radius" to "6"),
                images = mapOf("icon" to EventImage.Platform(capsuleIcon)))
            return region("assist", "SINGLE_MODULE_STYLE", null, listOf(label)).let { it.copy(attrs = it.attrs + ("gravity" to "TOP")) }
        }

        /**
         * 样式卡片整体(media/parse/c.java):页面边距 [padding];通知写明 android.colorized 时卡片底色是通知颜色 color,否则透明。
         */
        private fun styleWhole(padding: Int, children: List<PageNode>): PageNode {
            val attrs = linkedMapOf("padding" to padding.toString())
            if (extras.getBoolean("android.colorized")) attrs["background"] = color(sbn.notification.color)
            return PageNode("expanded", attrs = attrs, children = children)
        }

        /**
         * 标准样式、长文本样式的卡片(u.java:41 c()、a.java:41 c()):中间信息区段([info]),右侧应用图标([appIconAssist]),
         * 底部按钮列表区段(有按钮才有,等分排列);页面边距 8(notification_page_normal_padding)。
         */
        private fun standardCard(title: CharSequence?, text: CharSequence?): PageNode {
            val buttons = actionButtons("card.extension.button")
            return styleWhole(8, listOfNotNull(info(title, text, mini = false), appIconAssist(),
                buttons.takeIf { it.isNotEmpty() }?.let { buttonRegion("trailing", it, "eqd") }))
        }

        /**
         * 自定义样式的卡片(g.java:13 c()、media/parse/c.java:52):中间是应用自绘区段 CUSTOM_STYLE,customModule 是通知自己的
         * contentView(RemoteViews,来源应用 package);底部按钮列表区段总在(按钮可以为空,等分排列);没有页面边距。
         */
        private fun customCard(): PageNode = styleWhole(0, listOf(
            region("center", "CUSTOM_STYLE", null, listOf(PageNode("remote-views", "customModule",
                mapOf("view" to "contentView", "package" to sbn.packageName)))),
            buttonRegion("trailing", actionButtons("card.extension.button"), "eqd"),
        ))

        /**
         * 进度按钮样式的卡片(t.java:24 c()、media/parse/c.java:187):中间信息区段、右侧应用图标,底部 PROGRESS_BUTTON_STYLE 区段——
         * progress 是通知 ProgressStyle 的进度(进度值、分段长度与颜色、节点位置与颜色、起点/终点/滑块图标、按进度着色、不定进度),
         * progressButtonList 是通知按钮(有才有,等分排列);没有页面边距。
         */
        @RequiresApi(36)
        private fun progressCard(): PageNode {
            val style = Notification.Builder.recoverBuilder(host, sbn.notification).style as? Notification.ProgressStyle
            val progress = style?.let { progressStyle ->
                val images = LinkedHashMap<String, EventImage>()
                progressStyle.progressStartIcon?.let { images["start"] = EventImage.Platform(it) }
                progressStyle.progressEndIcon?.let { images["end"] = EventImage.Platform(it) }
                progressStyle.progressTrackerIcon?.let { images["tracker"] = EventImage.Platform(it) }
                PageNode("progress", "progress", linkedMapOf(
                    "progress" to progressStyle.progress.toString(),
                    "segments" to progressStyle.progressSegments.joinToString(",") { "${it.length}:${color(it.color)}" },
                    "points" to progressStyle.progressPoints.joinToString(",") { "${it.position}:${color(it.color)}" },
                    "styledByProgress" to progressStyle.isStyledByProgress.toString(),
                    "indeterminate" to progressStyle.isProgressIndeterminate.toString(),
                ), images = images)
            }
            val buttons = actionButtons("card.extension.button").takeIf { it.isNotEmpty() }
                ?.let { PageNode("button-list", "progressButtonList", mapOf("arr" to "eqd"), it) }
            return styleWhole(0, listOf(info(null, null, mini = false), appIconAssist(),
                region("trailing", "PROGRESS_BUTTON_STYLE", null, listOfNotNull(progress, buttons))))
        }

        /** 这条通知的指标样式(q.java:427 q():按通知重建 Builder 取样式);不是指标样式时为空 */
        @RequiresApi(37)
        private fun metricStyle(): Notification.MetricStyle? =
            Notification.Builder.recoverBuilder(host, sbn.notification).style as? Notification.MetricStyle

        /**
         * 一项指标的值写进 [attrs](q.java:435 r()、E.java:19 a()):颜色是指标的语义色(semanticStyle 不为 0 时
         * Notification.semanticStyleToColorRes 对应的颜色),否则 [ink];[size] 不为空时写字号。
         * - 时间差 TimeDifference 给了 zeroTime、zeroElapsedRealtime、pausedDuration 之一时是计时(TimerModel):isTimer 为真是
         *   倒计时,when 按开机时钟换算(f.java:594 n())。zeroTime 是这一刻;zeroElapsedRealtime 换成墙上时钟;pausedDuration
         *   停住,倒计时 when = 现在 + 剩余、正计时 when = 现在 − 已过,停在读到这一刻。
         * - 其余是文字:toValueString 的第一种写法(textVariants),单行。
         */
        @RequiresApi(37)
        private fun metricInto(attrs: MutableMap<String, String>, metric: Notification.Metric, ink: Int, size: String? = null) {
            val semantic = metric.semanticStyle
            val shown = if (semantic == 0) ink else (Notification::class.java.getMethod("semanticStyleToColorRes", Int::class.javaPrimitiveType)
                .invoke(null, semantic) as Int?)?.let(host::getColor) ?: ink
            attrs["textColor"] = color(shown)
            size?.let { attrs["textSize"] = it }
            val value = metric.value
            val now = System.currentTimeMillis()
            val time = value as? Notification.Metric.TimeDifference
            val zero = time?.let { difference ->
                difference.zeroTime?.let { it.toEpochMilli() to true }
                    ?: difference.zeroElapsedRealtime?.let { now - SystemClock.elapsedRealtime() + it to true }
                    ?: difference.pausedDuration?.let { (if (difference.isTimer) now + it.toMillis() else now - it.toMillis()) to false }
            }
            if (time != null && zero != null) {
                val countDown = time.isTimer
                val (wall, started) = zero
                attrs["when"] = (if (countDown) SystemClock.elapsedRealtime() + (wall - now) else wall).toString()
                attrs["clock"] = if (countDown) "elapsed" else "wall"
                attrs["countDown"] = countDown.toString()
                attrs["started"] = started.toString()
                if (!started) attrs["pausedAt"] = now.toString()
                attrs["showMs"] = "false"
            } else {
                attrs["value"] = valueString(value).first.firstOrNull().orEmpty()
                attrs["maxLine"] = "1"
            }
        }

        /** 指标值的文字(Notification.Metric.MetricValue.toValueString,系统隐藏接口):各种写法 textVariants 与小字 subtext */
        @RequiresApi(37)
        private fun valueString(value: Notification.Metric.MetricValue): Pair<List<String>, String> {
            val text = value.javaClass.getMethod("toValueString", Context::class.java).invoke(value, host)
            val variants = (text.javaClass.getMethod("textVariants").invoke(text) as List<*>).map { it.toString() }
            return variants to (text.javaClass.getMethod("subtext").invoke(text) as CharSequence?)?.toString().orEmpty()
        }

        /** 指标的名字(E.java:36):值带小字(单位等)时写成「名字 (小字)」(metric_label_unit「%1$s (%2$s)」),否则就是名字 */
        @RequiresApi(37)
        private fun metricLabel(metric: Notification.Metric): String {
            val sub = valueString(metric.value).second
            val label = metric.label?.toString().orEmpty()
            return if (sub.isNotEmpty()) "$label ($sub)" else label
        }

        /** 一段单行字(TextModel 的 maxLines 1);[ink] 为空时用区段样式的颜色 */
        private fun lineText(level: String, value: String, ink: Int?): PageNode =
            PageNode("text", level, LinkedHashMap<String, String>().also { attrs ->
                attrs["value"] = value
                ink?.let { attrs["textColor"] = color(it) }
                attrs["maxLine"] = "1"
            })

        /**
         * 指标样式的卡片(q.java:317 c(),反编译漏掉的部分以原始指令为准);页面边距 8(media/parse/c.java:171
         * notification_page_normal_padding)。应用图标是附加信息 oplus.substAppIcon(读得出时),否则来源应用的图标,圆角 4
         * (card_app_icon_radius)。
         * - 只有一项指标:中间信息区段 NORMAL_STYLE(居中)放 pic(应用图标 42×42,call_card_template_large_icon_size)、
         *   title(android.title,有就写,单行)、content(指标名字 [metricLabel],单行);右边辅助区段 SINGLE_MODULE_STYLE(居中)的
         *   labelModule 是指标的值,字号 20(text_large_size),颜色 card_content_title_color(有语义色时用语义色)。
         * - 两项以上:顶部区段 NORMAL_STYLE 放 pic(应用图标 14×14,call_card_template_app_icon_size)和 title(android.title,
         *   不空才有,card_content_title_color,单行);中间信息区段 INFOS_STYLE(居中)——两项时左、右两栏,三项时左、中、右三栏,
         *   每栏 title* 是指标名字(card_content_title_color,单行),content* 是指标的值(card_content_text_color,有语义色时用
         *   语义色);四项以上官方这一区段是空的。
         * - 底部按钮列表区段(居中;通知按钮最多 3 个、等分,s.java:689 h()),有按钮才有。
         */
        @RequiresApi(37)
        private fun metricCard(style: Notification.MetricStyle): PageNode {
            val metrics = style.metrics
            val many = metrics.size > 1
            val icon = extras.getParcelable("oplus.substAppIcon", Icon::class.java)?.takeIf { it.loadDrawable(host) != null }
                ?: Icon.createWithResource(sbn.packageName, host.packageManager.getApplicationInfo(sbn.packageName, 0).icon)
            val iconSize = if (many) "14" else "42"
            val pic = PageNode("image", "pic", mapOf("width" to iconSize, "height" to iconSize, "radius" to "4"),
                images = mapOf("src" to EventImage.Platform(icon)))
            val title = extras.getCharSequence("android.title")?.toString()
            val buttons = actionButtons("card.extension.button").takeIf { it.isNotEmpty() }?.let { buttonRegion("trailing", it, "eqd") }
            if (!many) {
                val metric = metrics.first()
                val value = PageNode("label", "labelModule", LinkedHashMap<String, String>().also { attrs ->
                    attrs["labelType"] = "TEXT"
                    metricInto(attrs, metric, CARD_TITLE, size = "20")
                })
                val info = region("center", "NORMAL_STYLE", null,
                    listOfNotNull(pic, title?.let { lineText("title", it, null) }, lineText("content", metricLabel(metric), null)))
                val assist = region("assist", "SINGLE_MODULE_STYLE", null, listOf(value))
                return styleWhole(8, listOfNotNull(centered(info), centered(assist), buttons))
            }
            val header = region("leading", "NORMAL_STYLE", null,
                listOfNotNull(pic, title?.takeIf { it.isNotEmpty() }?.let { lineText("title", it, CARD_TITLE) }))
            val sides = when (metrics.size) {
                2 -> listOf("Left", "Right")
                3 -> listOf("Left", "Center", "Right")
                else -> emptyList()
            }
            val info = region("center", "INFOS_STYLE", null, sides.flatMapIndexed { index, side ->
                listOf(lineText("title$side", metricLabel(metrics[index]), CARD_TITLE),
                    PageNode("text", "content$side", LinkedHashMap<String, String>().also { metricInto(it, metrics[index], CARD_TEXT) }))
            })
            return styleWhole(8, listOfNotNull(header, centered(info), buttons))
        }

        /** 区段居中(LayoutAlign CENTER) */
        private fun centered(region: PageNode): PageNode = region.copy(attrs = region.attrs + ("gravity" to "CENTER"))

        /**
         * 小卡(u.java:92 f() 等):中间是按小卡读的信息区段([info] mini);辅助区段是锁屏小卡按钮(s.java:754 j())——
         * 第一个按钮附加信息 oplus_key_action_type 为 1、oplus_key_action_config 里 oplus_lock_mini_state 为 1 且带图标的通知按钮,
         * 只画图标,有才有。
         */
        private fun standardMini(title: CharSequence?, text: CharSequence?): PageNode {
            val lock = pickActions(null).firstOrNull { action ->
                val state = action.extras.getString("oplus_key_action_config")?.let { JSONObject(it).optInt("oplus_lock_mini_state") }
                action.extras.getInt("oplus_key_action_type", -1) == 1 && state == 1 && action.getIcon() != null
            }
            val assist = lock?.let { buttonRegion("assist", listOf(actionButton(it, hideText = true, hideIcon = false, "mini.assist.button")), "def") }
            return PageNode("miniCard", children = listOfNotNull(info(title, text, mini = true), assist))
        }

        // ---------------- 公共部分 ----------------

        /**
         * 一张形态的整体(胶囊 n.java:8517 i()、卡片、小卡):背景 attr.background(f.java:233 g())、主色 attr.primaryColor、
         * 亮边 attr.bgLight(资源写法读成文字)、整体点按 attr.intent(n.java:8496 h())。
         */
        private fun whole(type: String, attr: JSONObject?, action: String, children: List<PageNode>): PageNode {
            val attrs = LinkedHashMap<String, String>()
            val images = LinkedHashMap<String, EventImage>()
            if (attr != null) {
                background(attr, attrs, images)
                (resource(attr.optString("primaryColor")) as? Int)?.let { attrs["primaryColor"] = color(it) }
                (resource(attr.optString("bgLight")) as? String)?.let { attrs["bgLight"] = it }
            }
            val events = if (attr != null && register(action, attr.optString("intent"), null)) mapOf("click" to action) else emptyMap()
            return PageNode(type, attrs = attrs, children = children, images = images, events = events)
        }

        /** 一个区段:category 写官方区段样式名;对齐 attr.gravity 原样写(官方 f.java:329 i() 换成 LayoutAlign);背景同整体 */
        private fun region(type: String, category: String, attr: JSONObject?, children: List<PageNode>): PageNode {
            val attrs = linkedMapOf("category" to category)
            val images = LinkedHashMap<String, EventImage>()
            if (attr != null) {
                attr.optString("gravity").takeIf { it.isNotEmpty() }?.let { attrs["gravity"] = it }
                background(attr, attrs, images)
            }
            return PageNode(type, attrs = attrs, children = children, images = images)
        }

        /**
         * 背景(f.java:233 g()):attr.background 是资源写法的文字,或 {animSrc, repeat};读成颜色时写成 #AARRGGBB,
         * 读成图时放进图片表。
         */
        private fun background(attr: JSONObject, attrs: MutableMap<String, String>, images: MutableMap<String, EventImage>) {
            val value = attr.opt("background") ?: return
            val uri = when (value) {
                is JSONObject -> value.optString("animSrc").takeIf { it.isNotEmpty() }
                is String -> value
                else -> null
            } ?: return
            when (val found = resource(uri)) {
                is Int -> attrs["background"] = color(found)
                is Icon -> images["background"] = EventImage.Platform(found)
                is CloudImage -> OfficialCloudImages.event(found)?.let { images["background"] = it }
            }
        }

        /**
         * 文字(h.java:466 b()):有 when(不为 -1)时是计时,读 when、countDown、started、showMs、format——
         * 倒计时的 when 官方按开机时钟换算(f.java:594 n()),没开始的停在读到这一刻(h.java:689-701);
         * 否则读 text。字体属性 textAttr(fontWeight、textStyle、maxLine、ellipsize、animDir、animEffect)、字号 textSize、
         * 颜色 textColor(资源写法)、type 为 TEXT 时的底色 bgColor、点按 intent。
         */
        fun text(json: JSONObject, level: String, action: String): PageNode {
            val attrs = LinkedHashMap<String, String>()
            val timer = json.optLong("when", -1L) != -1L
            if (timer) {
                val countDown = json.optBoolean("countDown", false)
                val started = json.optBoolean("started", false)
                val raw = json.optLong("when", 0L)
                attrs["when"] = (if (countDown) android.os.SystemClock.elapsedRealtime() + (raw - System.currentTimeMillis()) else raw).toString()
                attrs["clock"] = if (countDown) "elapsed" else "wall"
                attrs["countDown"] = countDown.toString()
                attrs["started"] = started.toString()
                if (!started) attrs["pausedAt"] = System.currentTimeMillis().toString()
                attrs["showMs"] = json.optBoolean("showMs", false).toString()
                json.optString("format").takeIf { it.isNotEmpty() }?.let { attrs["format"] = it }
            } else {
                attrs["value"] = json.optString("text", "")
            }
            json.optJSONObject("textAttr")?.let { textAttr ->
                for (key in listOf("fontWeight", "textStyle", "maxLine", "ellipsize", "animDir", "animEffect")) {
                    if (textAttr.has(key)) attrs[key] = textAttr.optString(key)
                }
            }
            json.optString("textSize").takeIf { it.isNotEmpty() }?.let { attrs["textSize"] = it }
            (resource(json.optString("textColor")) as? Int)?.let { attrs["textColor"] = color(it) }
            if (json.optString("type").uppercase(Locale.ROOT) == "TEXT") {
                (resource(json.optString("bgColor")) as? Int)?.let { attrs["bgColor"] = color(it) }
            }
            val events = if (register(action, json.optString("intent"), null)) mapOf("click" to action) else emptyMap()
            return PageNode("text", level, attrs, events = events)
        }

        /**
         * 图(h.java:1778 e()):Icon/icon 是普通图,ProgressWidget/progressWidget 是带进度环的图(两样都没有时不放);
         * 图本身是里面 icon 对象的 icon(资源写法)。尺寸 size、说明 desc、点按 intent 读外层那个对象;
         * 进度图另读 progress、showProgress、ring、bgColor、color。
         */
        fun picture(json: JSONObject, level: String, action: String): PageNode? {
            val iconBox = json.optJSONObject("Icon") ?: json.optJSONObject("icon")
            val progressBox = json.optJSONObject("ProgressWidget") ?: json.optJSONObject("progressWidget")
            if (iconBox == null && progressBox == null) return null
            val outer = iconBox ?: progressBox!!
            val icon = if (iconBox != null) iconBox else progressBox!!.optJSONObject("icon")
            val attrs = LinkedHashMap<String, String>()
            outer.optString("size").takeIf { it.isNotEmpty() }?.let { attrs["size"] = it }
            outer.optString("desc").takeIf { it.isNotEmpty() }?.let { attrs["desc"] = it }
            if (progressBox != null) {
                attrs["progress"] = progressBox.optString("progress")
                attrs["showProgress"] = progressBox.optInt("showProgress").toString()
                progressBox.optJSONObject("ring")?.let { attrs["ring"] = it.toString() }
                (resource(outer.optString("bgColor")) as? Int)?.let { attrs["bgColor"] = color(it) }
                outer.optJSONObject("color")?.let { attrs["color"] = it.toString() }
            }
            val images = LinkedHashMap<String, EventImage>()
            icon?.optString("icon")?.let { uri -> image(uri)?.let { images["src"] = it } }
            val events = if (register(action, outer.optString("intent"), null)) mapOf("click" to action) else emptyMap()
            return PageNode(if (progressBox != null) "progress-image" else "image", level, attrs, images = images, events = events)
        }

        /**
         * 标签(h.java:1463 c()):文字 text、图 icon(对象,里面的 icon 是资源写法)、图的位置 iconPosition、样式 style、
         * 外形 shape、底色 bgColor、紧凑 denseMode、说明 desc、点按 intent。
         */
        fun label(json: JSONObject, level: String, action: String): PageNode {
            val attrs = LinkedHashMap<String, String>()
            json.optString("text").takeIf { it.isNotEmpty() }?.let { attrs["value"] = it }
            for (key in listOf("iconPosition", "style", "shape", "desc")) {
                json.optString(key).takeIf { it.isNotEmpty() }?.let { attrs[key] = it }
            }
            if (json.has("denseMode")) attrs["denseMode"] = json.optInt("denseMode").toString()
            (resource(json.optString("bgColor")) as? Int)?.let { attrs["bgColor"] = color(it) }
            val images = LinkedHashMap<String, EventImage>()
            json.optJSONObject("icon")?.optString("icon")?.let { uri -> image(uri)?.let { images["icon"] = it } }
            val events = if (register(action, json.optString("intent"), null)) mapOf("click" to action) else emptyMap()
            return PageNode("label", level, attrs, images = images, events = events)
        }

        /**
         * 按钮列表(h.java:120 a()):[key] 下的数组,排列方式 arr(def、eqd、syd,没写时 def)。每个按钮读状态 state、
         * 样式 style、勾选样式 checkedStyle、类型 type、尺寸 size、标记 tag 与标签的各项(文字、图、底色、说明、点按 intent);
         * 点按时带上按钮当时的状态(官方 g.java:557 la_state_eum)。数组不存在时没有按钮列表。
         */
        fun buttons(data: JSONObject, key: String, level: String, prefix: String): PageNode? {
            val list = data.optJSONArray(key) ?: return null
            val children = (0 until list.length()).mapNotNull { index -> list.optJSONObject(index)?.let { button(it, "$prefix.$index") } }
            return PageNode("button-list", level, mapOf("arr" to data.optString("arr", "def")), children)
        }

        private fun button(json: JSONObject, action: String): PageNode {
            val state = json.optString("state").uppercase(Locale.ROOT)
            val node = label(json, "button", action)
            val attrs = LinkedHashMap(node.attrs)
            for (key in listOf("checkedStyle", "type", "size", "tag")) {
                json.optString(key).takeIf { it.isNotEmpty() }?.let { attrs[key] = it }
            }
            if (state.isNotEmpty()) attrs["state"] = state
            // 按钮的点按另按按钮的做法登记(带状态)
            val events = if (register(action, json.optString("intent"), state)) mapOf("click" to action) else emptyMap()
            return node.copy(type = "button", attrs = attrs, events = events)
        }

        /** 登记点按目标:资源写法读成 Intent 或 PendingIntent(f.java:279 h());都不是时不登记 */
        private fun register(action: String, uri: String, buttonState: String?): Boolean {
            val target = resource(uri)
            val intent = target as? Intent
            val pending = target as? PendingIntent
            if (intent == null && pending == null) return false
            targets[action] = Target(intent, pending, buttonState)
            return true
        }

        /** 资源读成图:附加信息里的 Icon、来源应用的图片资源、来源应用共享位置里的图(content://) */
        private fun image(uri: String): EventImage? = when (val found = resource(uri)) {
            is Icon -> EventImage.Platform(found)
            is CloudImage -> OfficialCloudImages.event(found)
            else -> null
        }

        /**
         * 资源写法(protocol/a.java:46 a() 与 bean/b.java:233 F()):去掉首尾空白;content:// 开头的是来源应用共享位置里的图;
         * 否则按第一个「/」分成类型与名字(类型不分大小写):icon → 附加信息里的 Icon,pi → 附加信息里的 PendingIntent,
         * intent → Intent.parseUri(名字, 0),colorInt → 十六进制颜色(protocol 里的 transition/k.java:181 f()),
         * configRes → 附加信息里的文字,layout → 附加信息里的 RemoteViews,drawable 与 animDrawable → 来源应用的图片资源,
         * color → 来源应用的颜色资源。写法不对或类型不认识时为空。
         */
        fun resource(uri: String): Any? {
            val text = uri.trim()
            if (text.startsWith("content://", ignoreCase = true)) {
                return CloudImage("direct:${sbn.key}:$text", shared = text, owners = setOf(sbn.packageName))
            }
            val slash = text.indexOf('/')
            if (slash <= 0 || slash >= text.length - 1) return null
            val name = text.substring(slash + 1)
            return when (text.substring(0, slash).lowercase(Locale.ROOT)) {
                "icon" -> extras.getParcelable(name, Icon::class.java)
                "pi" -> extras.getParcelable(name, PendingIntent::class.java)
                "intent" -> Intent.parseUri(name, 0)
                "colorint" -> colorInt(name)
                "configres" -> extras.getString(name)
                "layout" -> extras.getParcelable(name, android.widget.RemoteViews::class.java)
                "drawable", "animdrawable" -> {
                    val resources = host.packageManager.getResourcesForApplication(sbn.packageName)
                    Icon.createWithResource(sbn.packageName, resources.getIdentifier(name, "drawable", sbn.packageName))
                }
                "color" -> {
                    val resources = host.packageManager.getResourcesForApplication(sbn.packageName)
                    resources.getColor(resources.getIdentifier(name, "color", sbn.packageName), null)
                }
                else -> null
            }
        }

        private fun has(json: JSONObject, pascal: String): Boolean = json.has(pascal) || json.has(lowerFirst(pascal))

        private fun child(json: JSONObject, pascal: String): JSONObject? = json.optJSONObject(pascal) ?: json.optJSONObject(lowerFirst(pascal))

        private fun lowerFirst(text: String): String = if (text.isEmpty()) text else text[0].lowercaseChar() + text.substring(1)
    }

    /** 十六进制颜色(transition/k.java:181 f()):去掉开头的 #;3 位每位重复一次,6 位 RRGGBB,8 位 AARRGGBB;其它位数不认 */
    private fun colorInt(raw: String): Int? {
        val hex = raw.trimStart('#')
        return when (hex.length) {
            3 -> Color.parseColor("#" + hex.map { "$it$it" }.joinToString(""))
            6, 8 -> Color.parseColor("#$hex")
            else -> null
        }
    }

    private fun color(value: Int): String = "#%08X".format(value)

    /** 胶囊字的颜色提亮(b.java:373 M()):换成色相、饱和度、亮度,亮度低于 0.6 时提到 0.6,再换回不透明的颜色 */
    private fun brighten(value: Int): Int {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(value, hsl)
        if (hsl[2] < 0.6f) hsl[2] = 0.6f
        return ColorUtils.HSLToColor(hsl)
    }
}
