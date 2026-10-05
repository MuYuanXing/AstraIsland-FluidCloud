package com.astraflow.fluidcloud

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import com.astraflow.fluidcloud.hook.Log
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * 系统卡片里放在卡片所属应用自己共享位置的图(content:// 地址,例如外卖服务给的这一单的菜品图,现行规则「展开卡片总表」)。
 * - 只读卡片所属应用自己的共享位置:地址的提供方必须是这张卡片的来源应用或承载它的系统服务,别的一律不读。
 * - 在后台线程读、缩小到卡片用得上的大小后缓存;读好之前没有图,读好后请星流重新排一次卡片。系统界面主线程不读文件。
 * - 照片(例如菜品图)在卡片左边铺满,右下角带来源应用的小图标;说明文件写明按原样画的(例如转向箭头)按图标画。
 */
object OfficialCloudSharedImages {
    private const val TAG = "OfficialCloud"
    private const val MAX_BYTES = 4 * 1024 * 1024
    /** 卡片左边身份图最大 44,按 4 倍屏幕留够清晰度 */
    private const val MAX_EDGE = 256
    private val cache = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    /** 读不了的地址不再反复读(同一张卡每秒都会更新) */
    private val failed = LruCache<String, Boolean>(64)
    private val loading = HashSet<String>()
    private val main by lazy { Handler(Looper.getMainLooper()) }
    @Volatile private var context: Context? = null
    @Volatile private var onLoaded: () -> Unit = {}
    @Volatile private var worker: Executor = Executors.newSingleThreadExecutor { r -> Thread(r, "AstraFlow-SharedImages").apply { isDaemon = true } }

    fun bind(context: Context, onLoaded: () -> Unit) {
        this.context = context
        this.onLoaded = onLoaded
    }

    /**
     * 卡片数据里的图片地址是共享位置时,记成一张要在后台读的图;不是时返回 null。
     * 说明文件写明按原样画的(shapeStyle 为 origin,例如导航的转向箭头)按图标画,其余(例如裁成圆角的菜品图)按照片画。
     */
    fun reference(ref: String, owners: Set<String>, symbol: Boolean = false): CloudImage? {
        val value = ref.trim()
        if (!value.startsWith("content://", ignoreCase = true) || owners.isEmpty()) return null
        return CloudImage(key = value, symbol = symbol, shared = value, owners = owners)
    }

    /** 已经读好的图;还没读时在后台读,读好后请星流重新排一次卡片。读不了的返回 null。 */
    fun bitmap(image: CloudImage): Bitmap? {
        val uri = image.shared ?: return null
        synchronized(cache) { cache.get(uri) }?.let { return it }
        if (failed.get(uri) == true) return null
        val ctx = context ?: return null
        val launch = synchronized(loading) { loading.add(uri) }
        if (launch) worker.execute {
            val loaded = runCatching { load(ctx, uri, image.owners) }
                .onFailure { Log.w(TAG, "shared card picture unreadable: ${Uri.parse(uri).authority}", it) }.getOrNull()
            if (loaded != null) synchronized(cache) { cache.put(uri, loaded) } else failed.put(uri, true)
            synchronized(loading) { loading.remove(uri) }
            if (loaded != null) main.post { runCatching { onLoaded() } }
        }
        return null
    }

    private fun load(context: Context, value: String, owners: Set<String>): Bitmap? {
        val uri = Uri.parse(value)
        val authority = uri.authority ?: return null
        val provider = context.packageManager.resolveContentProvider(authority, 0)
        // 只读卡片所属应用自己的共享位置
        if (provider == null || provider.packageName !in owners) {
            Log.w(TAG, "shared card picture from $authority not read: provider ${provider?.packageName} is not the card's own app")
            return null
        }
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                if (out.size() > MAX_BYTES) return null
            }
            out.toByteArray()
        } ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth !in 1..16384 || bounds.outHeight !in 1..16384) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_EDGE) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val scale = MAX_EDGE.toFloat() / maxOf(decoded.width, decoded.height)
        return if (scale < 1f) Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt().coerceAtLeast(1),
            (decoded.height * scale).toInt().coerceAtLeast(1), true) else decoded
    }

    /** 检查用:换掉后台线程,并清空缓存 */
    fun useWorkerForTest(replacement: Executor?) {
        worker = replacement ?: Executors.newSingleThreadExecutor { r -> Thread(r, "AstraFlow-SharedImages").apply { isDaemon = true } }
    }

    fun clear() {
        synchronized(cache) { cache.evictAll() }
        failed.evictAll()
        synchronized(loading) { loading.clear() }
        context = null
        onLoaded = {}
    }
}
