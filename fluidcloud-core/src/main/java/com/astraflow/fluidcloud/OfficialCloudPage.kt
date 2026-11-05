package com.astraflow.fluidcloud

import com.astraisland.events.PageNode

/**
 * 系统卡片这一页的官方内容树(标准清单 page 一节):模板按这张卡片的数据代入,图片按官方的读法读好。
 * 一张图先看卡片所属应用共享位置里的(content:// 地址,后台读好再送一版),再看提供方随数据送来的,最后找模板目录里的文件。
 * 写明裁成圆形(shapeStyle 为 circle)的是照片,其余按系统符号画;共享位置里的图只有写明 origin 时按系统符号画。
 * 动画按说明文件播放:lottie 照 autoplay 决定播不播(没写时播);写明 loop 为真才循环,手动触发(manuallyPlay)、
 * loop 为假或 "1" 的播完停在最后一帧;循环时从 loopStartFrame 接回。
 */
object OfficialCloudPage {
    fun build(snapshot: CloudSnapshot, template: CloudTemplate): PageNode? {
        val owners = setOf(snapshot.packageName, snapshot.hostPackage).filter { it.isNotBlank() }.toSet()
        return template.page(snapshot.data) { type, attrs, value ->
            val symbol = attrs["shapeStyle"] != "circle"
            val playing = if (type == "lottie") attrs["autoplay"]?.let(OfficialCloudExpr::truthy) ?: true else true
            val loop = if (attrs["manuallyPlay"] == "true") false else attrs["loop"]?.let { OfficialCloudExpr.truthy(it) && it != "1" } ?: false
            val found = OfficialCloudSharedImages.reference(value, owners, symbol = attrs["shapeStyle"] == "origin")
                ?: (snapshot.images[value] ?: snapshot.images.values.firstOrNull { it.key == value })?.copy(symbol = symbol)
                ?: template.picture(value, snapshot.data, symbol = symbol, playing = playing,
                    loopStartFrame = attrs["loopStartFrame"]?.trim()?.toIntOrNull()?.coerceAtLeast(0), loop = loop)
            OfficialCloudImages.event(found)
        }
    }
}
