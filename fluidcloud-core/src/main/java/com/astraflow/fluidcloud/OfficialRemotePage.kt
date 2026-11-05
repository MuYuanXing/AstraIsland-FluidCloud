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
                val cardClicks: List<Pair<Int, String>>)

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
            clicks, register("clickList", "capsule"), register("panelClickList", "card"))
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
     * level 是控件编号,events 的 click 是动作名;胶囊的写在 compact 下,展开的写在 expanded 下。
     */
    fun page(views: Views): PageNode {
        fun clicks(list: List<Pair<Int, String>>) = list.map { (viewId, name) -> PageNode("click", viewId.toString(), events = mapOf("click" to name)) }
        return PageNode("card-template", attrs = mapOf("category" to "remote"), children = listOf(
            PageNode("compact", children = clicks(views.capsuleClicks)),
            PageNode("expanded", children = clicks(views.cardClicks)),
        ))
    }
}
