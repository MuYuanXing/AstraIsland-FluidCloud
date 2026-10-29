package com.astraflow.fluidcloud

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

/**
 * 原版模板用的图(contracts/island-system-events.v1.json 的 templateImages):从系统卡片数据里挑出模板会引用的图,
 * 把数据值换成 host-images/名字 的记号,并按契约上限(最多 32 张、单张 1 MB、合计 8 MB)交给星河岛。
 *
 * 只认两种来源:系统送的字节(共享位置照片以外的图)、来源应用共享位置里的照片(复用 [OfficialCloudSharedImages] 读成字节)。
 * 读不出来、超上限的图不交:星河岛那边就是缺图,不占位、不崩(与外部接入的图片通道一致)。
 * 图片名就是数据字段名(模板里写 {{字段}} 的地方),不改写其它字段。
 */
object OfficialCloudTemplateImages {
    /** 数据里图片记号的固定前缀,与契约一致。 */
    const val PREFIX = "host-images/"
    const val MAX_IMAGES = 32
    const val MAX_IMAGE_BYTES = 1024 * 1024
    const val MAX_TOTAL_BYTES = 8 * 1024 * 1024
    /** 共享位置的照片缩放到的最大边长(压成 WebP 后远小于 1 MB)。 */
    private const val MAX_EDGE = 1024
    private const val WEBP_QUALITY = 85

    /** 挑图结果:改写后的模板数据、名字 → 图来源、共享位置照片属于哪个应用。 */
    class Picked(
        val data: Map<String, String>,
        val images: Map<String, TemplateImage>,
        val owner: String?,
    )

    /**
     * 从一份模板数据与系统送来的图片表里挑出模板用的图。
     * @param data 提供方送来的原始数据(一级字段 → 文字值;图字段的值是共享位置地址或占位)
     * @param images 系统送来的图片(数据字段名或地址 → 图)
     */
    fun pick(data: Map<String, String>, images: Map<String, CloudImage>): Picked {
        if (data.isEmpty() || images.isEmpty()) return Picked(data, emptyMap(), null)
        val out = LinkedHashMap<String, TemplateImage>()
        val rewritten = LinkedHashMap(data)
        var total = 0L
        var owner: String? = null
        for ((key, value) in data) {
            if (key.isEmpty() || key.length > 64 || key.startsWith(PREFIX)) continue
            val image = images[key] ?: images[value] ?: continue
            if (out.size >= MAX_IMAGES) break
            val source = when {
                image.shared != null -> TemplateImage.Shared(image)
                image.bytes?.takeIf { it.isNotEmpty() } != null -> TemplateImage.Bytes(image.bytes)
                else -> continue
            }
            val size = (source as? TemplateImage.Bytes)?.bytes?.size ?: 0
            if (size > MAX_IMAGE_BYTES || total + size > MAX_TOTAL_BYTES) continue
            total += size
            out[key] = source
            rewritten[key] = PREFIX + key
            if (source is TemplateImage.Shared && owner == null) owner = source.image.owners.firstOrNull()
        }
        return Picked(rewritten, out, owner)
    }

    /**
     * 翻译成标准事件里的图:字节原样交,共享位置的照片此刻读好了才交(读不好就是缺图);
     * 压缩后仍超过单张上限的不交。
     */
    fun event(images: Map<String, TemplateImage>): Map<String, ByteArray> {
        if (images.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, ByteArray>()
        for ((name, source) in images) {
            val bytes = when (source) {
                is TemplateImage.Bytes -> source.bytes
                is TemplateImage.Shared -> OfficialCloudSharedImages.bitmap(source.image)?.let(::encode) ?: continue
            }
            if (bytes.isEmpty() || bytes.size > MAX_IMAGE_BYTES) continue
            out[name] = bytes
        }
        return out
    }

    /** 位图压成 WebP(大于 [MAX_EDGE] 的先缩小);压不出来时为空。 */
    private fun encode(bitmap: Bitmap): ByteArray? = runCatching {
        val scale = (MAX_EDGE.toFloat() / maxOf(bitmap.width, bitmap.height)).coerceAtMost(1f)
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true) else bitmap
        val bytes = ByteArrayOutputStream().use { stream ->
            scaled.compress(Bitmap.CompressFormat.WEBP_LOSSY, WEBP_QUALITY, stream)
            stream.toByteArray()
        }
        if (scaled !== bitmap) scaled.recycle()
        bytes
    }.getOrNull()
}
