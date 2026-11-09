package com.astraisland.events

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Icon
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.IdentityHashMap

/** 反馈包中的图片、动画与完整字段。只在诊断后台线程写文件，不改动显示用的资源。 */
class DiagnosticResources(private val context: Context, private val directory: File) {
    private val files = IdentityHashMap<Any, String>()
    private val errors = ArrayList<String>()
    private var next = 0

    fun bytes(value: ByteArray, extension: String): String = files[value] ?: save(value, extension) {
        it.writeBytes(value)
    }

    fun encodedImage(value: String): JSONObject = try {
        val body = if (value.startsWith("data:")) value.substringAfter("base64,") else value
        JSONObject().put("file", bytes(android.util.Base64.decode(body, android.util.Base64.DEFAULT), "bin"))
    } catch (failure: Exception) {
        val message = "背景图片收集失败：${failure.message}"
        errors += message
        JSONObject().put("error", message)
    }

    private fun save(identity: Any, extension: String, write: (File) -> Unit): String {
        val name = "assets/${++next}.$extension"
        val file = File(directory, name)
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        write(file)
        files[identity] = name
        return name
    }

    private fun bitmap(value: Bitmap): String = files[value] ?: save(value, "png") { file ->
        check(!value.isRecycled) { "图片已被系统回收" }
        file.outputStream().use { check(value.compress(Bitmap.CompressFormat.PNG, 100, it)) { "图片写入失败" } }
    }

    fun image(value: EventImage): JSONObject {
        val result = JSONObject().put("symbol", value.symbol).put("appShared", value.appShared).put("substitute", value.substitute)
        try {
            when (value) {
                is EventImage.Picture -> result.put("type", "picture").put("identity", value.identity)
                    .put("file", bitmap(value.bitmap))
                is EventImage.Animation -> result.put("type", "animation").put("identity", value.identity)
                    .put("playing", value.playing).put("loop", value.loop).put("loopStartFrame", value.loopStartFrame)
                    .put("file", bytes(value.json, "json"))
                is EventImage.Moving -> result.put("type", "moving").put("identity", value.identity).put("file", bytes(value.bytes, "webp"))
                is EventImage.Platform -> {
                    result.put("type", "platform").put("description", value.icon.toString())
                    val name = files[value.icon] ?: save(value.icon, "png") { file ->
                        val drawable = checkNotNull(value.icon.loadDrawable(context)) { "系统图标无法读取" }
                        val picture = Bitmap.createBitmap(drawable.intrinsicWidth.coerceAtLeast(1), drawable.intrinsicHeight.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                        val bounds = android.graphics.Rect(drawable.bounds)
                        try {
                            drawable.setBounds(0, 0, picture.width, picture.height)
                            drawable.draw(Canvas(picture))
                            file.outputStream().use { check(picture.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                        } finally { drawable.bounds = bounds; picture.recycle() }
                    }
                    result.put("file", name)
                }
                is EventImage.Missing -> result.put("type", "missing")
            }
        } catch (failure: Exception) {
            val detail = "资源收集失败：${failure.javaClass.simpleName} ${failure.message}"
            errors += detail
            result.put("error", detail)
        }
        return result
    }

    fun page(node: PageNode): JSONObject = JSONObject().put("type", node.type).put("level", node.level)
        .put("attrs", JSONObject(node.attrs)).put("events", JSONObject(node.events))
        .put("images", JSONObject().apply { node.images.forEach { (key, image) -> put(key, image(image)) } })
        .put("children", JSONArray().apply { node.children.forEach { put(page(it)) } })

    @Suppress("DEPRECATION")
    fun value(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is String, is Boolean, is Number, is JSONObject, is JSONArray -> value
        is CharSequence -> value.toString()
        is Bundle -> JSONObject().apply { value.keySet().sorted().forEach { put(it, value(value.get(it))) } }
        is Bitmap -> image(EventImage.Picture(value, null))
        is Icon -> image(EventImage.Platform(value))
        is ByteArray -> JSONObject().put("type", "bytes").put("file", bytes(value, "bin")).put("size", value.size)
        is Map<*, *> -> JSONObject().apply { value.forEach { (key, item) -> put(key.toString(), value(item)) } }
        is Iterable<*> -> JSONArray().apply { value.forEach { put(value(it)) } }
        else -> if (value.javaClass.isArray) JSONArray().apply {
            for (i in 0 until java.lang.reflect.Array.getLength(value)) put(value(java.lang.reflect.Array.get(value, i)))
        } else JSONObject().put("class", value.javaClass.name).put("description", value.toString())
    }

    /** 出错项随材料交付，采集方据此标成不完整。 */
    fun finish() {
        if (errors.isNotEmpty()) File(directory, "errors.txt").writeText(errors.joinToString("\n"))
    }
}
