package com.astraflow.fluidcloud

import android.os.Parcel
import android.widget.RemoteViews
import com.astraisland.events.PageNode
import org.json.JSONArray
import org.json.JSONObject

/**
 * 航班、火车这类卡片(系统卡片引擎类型 REMOTE_VIEW):出行服务直接送来画好的整套界面(RemoteViews),系统卡片引擎原样铺出来
 * (C17 SeedlingSdk remoteviewsengine/e.java T()、V())。卡片数据的 ui_data 照 RemoteViewSetting 的字段写:
 * 状态栏胶囊与中卡用 remoteViewByteArray、点按登记 clickList;展开的大卡用 panelRemoteViewByteArray、点按登记 panelClickList。
 * 界面是 Parcel 序列化后的字节(写成数字数组),照官方 l() 还原:unmarshall、回到开头、RemoteViews.CREATOR。
 * 点按登记的每一项(ClickItem:viewId、type、uri、method、params、data)照官方 j()/k() 绑到界面里编号为 viewId 的控件上:
 * deeplink 打开页面,message 交给提供方。界面里自带的点击(PendingIntent)由界面自己处理。
 */
object OfficialRemotePage {
    /** 卡片引擎用 REMOTE_VIEW 的服务(官方服务号名单,seedlingsdk q6/b.java:14、17):航班、火车、12306 */
    val SERVICES = setOf(
        "536872751", "536872752", "536872753", "536872754", "536877081", "536877082", "536877083", "536877084",
        "536877085", "536877086", "536877087", "536877088", "536877089", "536877090", "536878563",
        "536878557", "536878558", "536873194", "536873195", "536873196", "536873197", "536873198", "536873199",
        "536877071", "536877072", "536877073", "536877074", "536877075", "536877076", "536877077", "536877078",
        "536877079", "536877080",
    )

    /** 读到的界面与点按:[capsule] 胶囊,[card] 展开大卡;[clicks] 动作名 → 登记的做法 */
    class Views(val capsule: RemoteViews?, val card: RemoteViews?, val clicks: Map<String, CloudAction>, val capsuleClicks: List<Pair<Int, String>>,
                val cardClicks: List<Pair<Int, String>>,
                /** 这一份只是变化的部分:照官方叠在已有界面上,不整份重铺 */
                val reapply: Boolean = false,
                /** 铺好界面后逐个控件调用的方法(官方 widgetActionItems) */
                val widgetActions: List<WidgetAction> = emptyList())

    /** 官方 WidgetActionItem:对编号 [viewId] 的控件调用 [widget] 类上的 [methods] */
    class WidgetAction(val viewId: Int, val widget: String, val methods: List<WidgetMethod>)

    /** 官方 Method:方法名与按 JSON 读到的参数(数字为 Double,布尔为 Boolean,文字为 String) */
    class WidgetMethod(val method: String, val params: List<Any?>)

    /** 卡片数据(提供方送来的那一份)里的界面与点按 */
    fun read(raw: ByteArray): Views {
        val envelope = JSONObject(String(raw, Charsets.UTF_8))
        val setting = when (val ui = envelope.get("ui_data")) {
            is JSONObject -> ui
            else -> JSONObject(ui.toString())
        }
        val clicks = LinkedHashMap<String, CloudAction>()
        fun register(list: String, prefix: String): List<Pair<Int, String>> {
            val items = setting.optJSONArray(list) ?: return emptyList()
            return (0 until items.length()).map { index ->
                val item = items.getJSONObject(index)
                val name = "$prefix:$index"
                clicks[name] = action(item)
                item.optInt("viewId", -1) to name
            }
        }
        return Views(views(setting.optJSONArray("remoteViewByteArray")), views(setting.optJSONArray("panelRemoteViewByteArray")),
            clicks, register("clickList", "capsule"), register("panelClickList", "card"),
            reapply = setting.optBoolean("isReapply"), widgetActions = widgetActions(setting.optJSONArray("widgetActionItems")))
    }

    /** 官方的控件动作表(RemoteViewSetting.widgetActionItems):编号、控件类、方法名与参数 */
    private fun widgetActions(items: JSONArray?): List<WidgetAction> {
        if (items == null) return emptyList()
        return (0 until items.length()).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            val methods = item.optJSONArray("methods")?.let { list ->
                (0 until list.length()).mapNotNull { at ->
                    val method = list.optJSONObject(at) ?: return@mapNotNull null
                    val params = method.optJSONArray("params")?.let { values ->
                        (0 until values.length()).map { values.get(it) }
                    }.orEmpty()
                    WidgetMethod(method.optString("method"), params)
                }
            }.orEmpty()
            WidgetAction(item.optInt("viewId", -1), item.optString("widgetFullClassName"), methods)
        }
    }

    /** 官方 l():字节还原成界面 */
    private fun views(bytes: JSONArray?): RemoteViews? {
        if (bytes == null || bytes.length() == 0) return null
        val data = ByteArray(bytes.length()) { bytes.getInt(it).toByte() }
        val parcel = Parcel.obtain()
        parcel.unmarshall(data, 0, data.size)
        parcel.setDataPosition(0)
        val views = RemoteViews.CREATOR.createFromParcel(parcel)
        parcel.recycle()
        return views
    }

    /** 点按登记的一项(ClickItem)换成插件执行动作的写法:params 是一段对象文字,里面的值一律按文字 */
    private fun action(item: JSONObject): CloudAction {
        val params = item.optString("params").takeIf { it.isNotEmpty() }?.let(::JSONObject)
            ?.let { json -> json.keys().asSequence().associateWith { json.get(it).toString() } }.orEmpty()
        return CloudAction(item.getString("type"), item.optString("uri"), item.optString("method"), params,
            item.optString("data").takeIf { it.isNotEmpty() })
    }

    /**
     * 交给星河岛的内容树:根是 card-template,category 写 remote;界面本身随事件另交。点按登记写成 click 元素,
     * level 是控件编号,events 的 click 是动作名;控件动作表写成 widget 元素。整张卡片的点击照官方执行点按清单的第一项。
     * 这一份只是变化的部分时([Views.reapply])根上写 reapply,星河岛照官方叠在已有界面上。
     */
    fun page(views: Views): PageNode {
        fun clicks(list: List<Pair<Int, String>>) = list.map { (viewId, name) -> PageNode("click", viewId.toString(), events = mapOf("click" to name)) }
        fun widgets(list: List<WidgetAction>) = list.flatMap { action ->
            action.methods.filter { it.method.isNotEmpty() }.map { method ->
                PageNode("widget", action.viewId.toString(), attrs = mapOf(
                    "widget" to action.widget,
                    "method" to method.method,
                    "params" to org.json.JSONArray(method.params).toString(),
                ))
            }
        }
        val press = views.cardClicks.firstOrNull()?.second ?: views.capsuleClicks.firstOrNull()?.second
        val attrs = linkedMapOf("category" to "remote")
        if (views.reapply) attrs["reapply"] = "true"
        if (views.card != null) attrs["panel"] = "true"
        return PageNode("card-template",
            attrs = attrs,
            events = if (press != null) mapOf("click" to press) else emptyMap(),
            children = listOf(
                PageNode("compact", children = clicks(views.capsuleClicks) + widgets(views.widgetActions)),
                PageNode("expanded", children = clicks(views.cardClicks) + widgets(views.widgetActions)),
            ))
    }
}
