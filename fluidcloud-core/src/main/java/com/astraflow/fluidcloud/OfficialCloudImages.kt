package com.astraflow.fluidcloud

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import com.astraisland.events.EventImage
import java.security.MessageDigest

object OfficialCloudImages {
    const val MAX_BYTES = 2 * 1024 * 1024
    private const val MAX_EDGE = 1024
    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    /** 同一份图片对象只换算一次:每次同步都会对每条记录取图,不能每次都重新算指纹、重新解码。 */
    private val resolved = java.util.WeakHashMap<CloudImage, EventImage>()

    /** 记录上各个位置的图最近一次拷下的那一份(按记录与位置记,总大小有上限;每一份另算一点开销,很小的图也不会无限多) */
    private val copies = object : LruCache<String, CloudImage>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: CloudImage) = (value.bytes?.size ?: 0) + 1024
    }

    /** 同一份字节(沿用的拷贝)的指纹只算一次:按这份字节本身记,字节没人用了就一起忘掉 */
    private val digests = java.util.WeakHashMap<ByteArray, String>()

    /**
     * 系统每次送来的图片数据都是新的一份(导航、秒表每秒一次):内容和这个位置上次的一样时沿用上次拷好的那一份,
     * 不再拷贝、不再算指纹;变了才拷一份新的。[slot] 是哪条记录的哪个位置。
     */
    fun copyOf(slot: String, key: String, bytes: ByteArray): CloudImage {
        synchronized(copies) { copies.get(slot) }?.takeIf { it.key == key && it.bytes?.contentEquals(bytes) == true }?.let { return it }
        return CloudImage(key, bytes = bytes.copyOf()).also { image -> synchronized(copies) { copies.put(slot, image) } }
    }

    /**
     * 换成标准事件里的图(读好、认好身份);此刻没有可画的(共享位置的照片还在后台读、读不出)时是 [EventImage.Missing],
     * 标着它是什么样的图。
     */
    fun event(image: CloudImage?): EventImage? {
        if (image == null) return null
        // 共享位置里的照片(例如外卖的菜品图):后台读好才有;同一个地址当成同一张图,不因为重新送来就交叉淡换
        // (现行规则「展开卡片总表」)
        image.shared?.let { uri ->
            return OfficialCloudSharedImages.bitmap(image)?.let { EventImage.Picture(it, "shared:$uri", image.symbol, appShared = true, substitute = image.substitute) }
                ?: EventImage.Missing(image.symbol, appShared = true, substitute = image.substitute)
        }
        synchronized(resolved) { resolved[image]?.let { return it } }
        return decode(image)?.also { ref -> synchronized(resolved) { resolved[image] = ref } }
            ?: EventImage.Missing(image.symbol, substitute = image.substitute)
    }

    /**
     * 读成标准事件里的图(现行规则「系统事件接入」):动画文件是会动的图标;位图与图片文件按像素认身份——
     * 系统每次送来的都是新的图片对象,同一张图不当成换了图(不交叉淡换、不重新解码);说明文件放的图标按系统符号画。
     */
    private fun decode(image: CloudImage): EventImage? = runCatching {
        val symbol = image.symbol
        val substitute = image.substitute
        image.platform?.let { return EventImage.Platform(it, symbol, substitute = substitute) }
        if (image.animated) {
            val bytes = image.bytes?.takeIf { it.isNotEmpty() } ?: return null
            return EventImage.Animation(image.key, image.playing, bytes, symbol, substitute = substitute)
        }
        image.bitmap?.takeUnless { it.isRecycled }?.let { original ->
            // 系统可能在卡片结束时回收自己的位图；岛的收起动画只能引用扩展持有的副本。
            val pixels = OfficialCloudPictures.fingerprint(original)
            val key = pixels?.let { "bitmap:$it" } ?: "bitmap:${System.identityHashCode(original)}:${original.generationId}:${original.width}:${original.height}"
            synchronized(cache) { cache.get(key) }?.let { return EventImage.Picture(it, pixels, symbol, substitute = substitute) }
            val scale = (MAX_EDGE.toFloat() / maxOf(original.width, original.height)).coerceAtMost(1f)
            val owned = if (scale < 1f) Bitmap.createScaledBitmap(original,
                (original.width * scale).toInt().coerceAtLeast(1), (original.height * scale).toInt().coerceAtLeast(1), true)
                else original.copy(Bitmap.Config.ARGB_8888, false)
            if (owned == null || owned.isRecycled) return null
            synchronized(cache) { cache.put(key, owned) }
            return EventImage.Picture(owned, pixels, symbol, substitute = substitute)
        }
        val bytes = image.bytes?.takeIf { it.isNotEmpty() && it.size <= MAX_BYTES } ?: return null
        val hash = synchronized(digests) { digests[bytes] } ?: MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            .also { synchronized(digests) { digests[bytes] = it } }
        synchronized(cache) { cache.get(hash) }?.let { return EventImage.Picture(it, hash, symbol, substitute = substitute) }
        // 说明文件里的动图 WebP(取单码引导的大图、加载中的转圈)照样播放;只认图片的地方画第一帧(现行规则「系统事件接入」)
        if (OfficialCloudPictures.isAnimatedWebp(bytes)) return EventImage.Moving("webp:$hash", bytes, symbol, substitute = substitute)
        // 卡片说明文件里的矢量图标(系统卡片多用)自己画成位图
        if (OfficialCloudSvg.looksLikeSvg(bytes)) {
            val drawn = OfficialCloudSvg.render(bytes) ?: return null
            synchronized(cache) { cache.put(hash, drawn) }
            return EventImage.Picture(drawn, hash, symbol, substitute = substitute)
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth !in 1..16384 || bounds.outHeight !in 1..16384) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_EDGE) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        synchronized(cache) { cache.put(hash, bitmap) }
        EventImage.Picture(bitmap, hash, symbol, substitute = substitute)
    }.getOrNull()

    fun fromDrawable(drawable: Drawable?, key: String): CloudImage? = runCatching {
        if (drawable == null) return null
        if (drawable is BitmapDrawable) return CloudImage(key, bitmap = drawable.bitmap)
        val width = drawable.intrinsicWidth.coerceIn(1, MAX_EDGE)
        val height = drawable.intrinsicHeight.coerceIn(1, MAX_EDGE)
        val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val oldBounds = android.graphics.Rect(drawable.bounds)
        try { drawable.setBounds(0, 0, width, height); drawable.draw(Canvas(image)) }
        finally { drawable.bounds = oldBounds }
        CloudImage(key, bitmap = image)
    }.getOrNull()

    fun clear() {
        synchronized(cache) { cache.evictAll() }
        synchronized(resolved) { resolved.clear() }
        synchronized(copies) { copies.evictAll() }
        synchronized(digests) { digests.clear() }
    }
}
