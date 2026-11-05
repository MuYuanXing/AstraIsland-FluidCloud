package com.astraisland.events

/**
 * 系统卡片的一页(标准清单 page 一节):官方模板按这张卡片此刻的数据代入以后的整棵树,从 card-template 开始。
 * 元素的类型、位置代号和全部属性照模板原样;属性值已代入数据、换成当前语言,显示条件不成立的元素不在树里。
 * 怎么摆由星河岛按区域(胶囊左右两边,展开卡片的顶部、中间、底部)、排法(category)和位置代号决定。
 */
data class PageNode(
    val type: String,
    /** 位置代号,照模板原样(例如 B1*、D1、E4);没写时为空 */
    val level: String = "",
    val attrs: Map<String, String> = emptyMap(),
    val children: List<PageNode> = emptyList(),
    /** 属性里写的图读好以后的样子:属性名(src、icon、iconOn……)→ 图 */
    val images: Map<String, EventImage> = emptyMap(),
    /** 元素登记的交互(模板 events):交互名(click 等)→ 模板 actions 里的动作名;星河岛上触发时把动作名交回接入件 */
    val events: Map<String, String> = emptyMap(),
) {
    /** 位置代号去掉表示必有的星号 */
    val code: String get() = level.removeSuffix("*")

    /** 第一个这种类型的子元素 */
    fun child(type: String): PageNode? = children.firstOrNull { it.type == type }

    /**
     * 点整张卡片执行的动作:模板卡片登记在根(card-template)上;通知变成的胶囊照系统流体云的解析,登记在卡片那一形态(expanded)上。
     * 都没有时为空(卡片不能点)。
     */
    val cardClick: String? get() = events["click"] ?: child("expanded")?.events?.get("click")
}
