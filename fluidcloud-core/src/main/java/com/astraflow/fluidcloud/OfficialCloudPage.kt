package com.astraflow.fluidcloud

import com.astraisland.events.PageNode

/**
 * 系统卡片这一页的官方内容树(标准清单 page 一节):模板按这张卡片的数据代入,图片按官方的读法读好。
 * 一张图先看卡片所属应用共享位置里的(content:// 地址,后台读好再送一版),再看写成 @drawable/名字 的承载应用自己的图片资源
 * (与官方卡片引擎一致,后台读好再送一版),再看提供方随数据送来的,最后找模板目录里的文件。
 * 写明裁成圆形(shapeStyle 为 circle)的是照片,其余按系统符号画;共享位置里的图只有写明 origin 时按系统符号画。
 * 动画按说明文件播放:lottie 默认自动播放(官方 e2 的 autoplay 默认就是 "true"),模板写明 "false" 才不播;
 * 写明 loop 为真才循环,没写或写 "1" 的播一遍停在最后一帧;循环时从 loopStartFrame 接回;
 * 手动触发(manuallyPlay)不播时停在最后一帧,其余停在第一帧(官方 b8/w.java:589)。
 */
object OfficialCloudPage {
    fun build(snapshot: CloudSnapshot, template: CloudTemplate): PageNode? {
        val owners = setOf(snapshot.packageName, snapshot.hostPackage).filter { it.isNotBlank() }.toSet()
        return template.page(snapshot.data) { type, attrs, value ->
            val symbol = attrs["shapeStyle"] != "circle"
            // 官方 lottie 节点的 autoplay 默认就是 "true"(官方 e2 的构造里写死),模板写明 "false" 才不播;
            // 不播时手动触发(manuallyPlay)的停在最后一帧、其余停在第一帧(官方 f8/f4.java:497、b8/w.java:589)
            val manually = attrs["manuallyPlay"] == "true"
            val playing = if (type == "lottie") attrs["autoplay"]?.trim() != "false" else true
            val loop = when {
                manually -> false
                !playing -> true
                else -> attrs["loop"]?.let { OfficialCloudExpr.truthy(it) && it != "1" } ?: false
            }
            val found = OfficialCloudSharedImages.reference(value, owners, symbol = attrs["shapeStyle"] == "origin")
                ?: OfficialCloudSharedImages.hostDrawable(value, snapshot.hostPackage, symbol = symbol)
                ?: (snapshot.images[value] ?: snapshot.images.values.firstOrNull { it.key == value })?.copy(symbol = symbol)
                ?: template.picture(value, snapshot.data, symbol = symbol, playing = playing,
                    loopStartFrame = attrs["loopStartFrame"]?.trim()?.toIntOrNull()?.coerceAtLeast(0), loop = loop)
            OfficialCloudImages.event(found)
        }
    }
}
