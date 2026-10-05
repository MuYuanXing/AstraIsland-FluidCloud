package com.astraflow.fluidcloud

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min

/**
 * 认图片身份用的两个小工具,算法与星河岛的一样(同一张图在两边算出同一个身份):
 * 位图按全部像素算指纹;图片文件看是不是会动的 WebP。
 */
object OfficialCloudPictures {
    /** 算指纹时每批读这么多像素(按整行取),不把整张图复制一遍。 */
    private const val FINGERPRINT_BATCH_PIXELS = 64 * 1024
    /** 64 位 FNV-1a 散列的起始值与乘数。 */
    private const val FNV_OFFSET = -0x340d631b7bdddcdbL
    private const val FNV_PRIME = 0x100000001b3L
    private const val UNSIGNED_INT_MASK = 0xffffffffL

    /**
     * 位图内容指纹:尺寸加全部像素的 64 位散列。同一张图重新发送(新对象、像素相同)得到同一个值,有一个像素不同就不同。
     * 硬件位图先复制成可读的再算;读不了返回 null,按对象身份处理。
     */
    fun fingerprint(bitmap: Bitmap?): String? {
        if (bitmap == null || bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return null
        val readable = if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return null else bitmap
        return try {
            runCatching { hashPixels(readable) }.getOrNull()
        } finally {
            if (readable !== bitmap) readable.recycle()
        }
    }

    private fun hashPixels(bitmap: Bitmap): String {
        val w = bitmap.width
        val h = bitmap.height
        val rows = min(h, max(1, FINGERPRINT_BATCH_PIXELS / w))
        val batch = IntArray(w * rows)
        var hash = mix(mix(FNV_OFFSET, w), h)
        var y = 0
        while (y < h) {
            val n = min(rows, h - y)
            bitmap.getPixels(batch, 0, w, 0, y, w, n)
            for (i in 0 until w * n) hash = mix(hash, batch[i])
            y += n
        }
        return "${w}x$h:${java.lang.Long.toHexString(hash)}"
    }

    private fun mix(hash: Long, value: Int): Long = (hash xor (value.toLong() and UNSIGNED_INT_MASK)) * FNV_PRIME

    /** 会动的 WebP:文件头写着 RIFF、WEBP、VP8X,并标了动画 */
    fun isAnimatedWebp(bytes: ByteArray): Boolean = bytes.size > VP8X_FLAGS_AT && ascii(bytes, 0, "RIFF") && ascii(bytes, 8, "WEBP") &&
        ascii(bytes, 12, "VP8X") && (bytes[VP8X_FLAGS_AT].toInt() and ANIMATION_FLAG) != 0

    private fun ascii(bytes: ByteArray, at: Int, text: String): Boolean = text.indices.all { bytes[at + it] == text[it].code.toByte() }

    private const val VP8X_FLAGS_AT = 20
    private const val ANIMATION_FLAG = 0x02
}
