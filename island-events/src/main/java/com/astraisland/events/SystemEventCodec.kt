package com.astraisland.events

import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Bundle
import java.util.IdentityHashMap

/**
 * 标准事件的编码(contracts/island-system-events.v1.json)。接入件和星河岛在同一个进程里,但各自的类互不相认,
 * 所以只交换系统自带类型做成的包裹:Bundle、列表、位图、系统图标、字节。键名就是清单里的编号。
 * 包裹不跨进程传,位图与图标按对象放进去,不复制;同一张图在一批里只编一次,解出来还是同一个对象。
 */
object SystemEventCodec {
    /** 编码的版本;两边不一样时不交换(见对接约定) */
    const val VERSION = 2

    fun encode(events: List<SystemEvent>): Bundle {
        val images = Images()
        val list = ArrayList<Bundle>(events.size)
        for (event in events) list += encodeEvent(event, images)
        return Bundle().apply {
            putInt(K_VERSION, VERSION)
            putParcelableArrayList(K_EVENTS, list)
            putParcelableArrayList(K_IMAGES, images.encoded)
        }
    }

    /** 解出一批事件;版本不一样时返回空(不交换) */
    fun decode(bundle: Bundle?): List<SystemEvent>? {
        if (bundle == null || bundle.getInt(K_VERSION, -1) != VERSION) return null
        val images = bundle.list(K_IMAGES).map(::decodeImage)
        return bundle.list(K_EVENTS).map { decodeEvent(it, images) }
    }

    // ── 事件 ──

    private fun encodeEvent(e: SystemEvent, images: Images) = Bundle().apply {
        putString("identity", e.identity)
        putInt("userId", e.userId)
        putLong("revision", e.revision)
        putString("kind", e.kind.id)
        putString("packageName", e.packageName)
        putString("sourcePackage", e.sourcePackage)
        putString("serviceName", e.serviceName)
        putBoolean("lockScreen", e.lockScreen)
        putString("alert", e.alert.id)
        putString("instant", e.instant.id)
        putBoolean("shake", e.shake)
        putBoolean("openable", e.openable)
        putIntArray("relatedNotifications", e.relatedNotifications.toIntArray())
        e.notificationMark?.let { putStringArray("notificationMark", arrayOf(it.extra, it.value)) }
        putInt("fingerprint", e.fingerprint)
        e.callState?.let { putString("callState", it.id) }
        putBoolean("ringing", e.ringing)
        putString("mediaPackage", e.mediaPackage)
        putBundle(K_PAGE, encodePage(e.page, images))
        putParcelable("capsuleViews", e.capsuleViews)
        putParcelable("cardViews", e.cardViews)
    }

    private fun decodeEvent(b: Bundle, images: List<EventImage>) = SystemEvent(
        identity = b.getString("identity")!!,
        userId = b.getInt("userId"),
        revision = b.getLong("revision"),
        kind = EventKind.of(b.getString("kind"))!!,
        packageName = b.getString("packageName")!!,
        sourcePackage = b.getString("sourcePackage")!!,
        serviceName = b.getString("serviceName")!!,
        lockScreen = b.getBoolean("lockScreen"),
        alert = Alert.of(b.getString("alert"))!!,
        instant = Instant.of(b.getString("instant"))!!,
        shake = b.getBoolean("shake"),
        openable = b.getBoolean("openable"),
        relatedNotifications = b.getIntArray("relatedNotifications")!!.toSet(),
        notificationMark = b.getStringArray("notificationMark")?.let { NotificationMark(it[0], it[1]) },
        fingerprint = b.getInt("fingerprint"),
        callState = b.getString("callState")?.let { CallState.of(it)!! },
        ringing = b.getBoolean("ringing"),
        mediaPackage = b.getString("mediaPackage"),
        page = decodePage(b.getBundle(K_PAGE)!!, images),
        capsuleViews = b.getParcelable("capsuleViews", android.widget.RemoteViews::class.java),
        cardViews = b.getParcelable("cardViews", android.widget.RemoteViews::class.java),
    )

    // ── 官方内容树 ──

    private fun encodePage(node: PageNode, images: Images): Bundle = Bundle().apply {
        putString("type", node.type)
        putString("level", node.level)
        putBundle("attrs", Bundle().apply { node.attrs.forEach { (name, value) -> putString(name, value) } })
        putBundle("images", Bundle().apply { node.images.forEach { (name, image) -> images.put(this, name, image) } })
        putBundle("events", Bundle().apply { node.events.forEach { (name, action) -> putString(name, action) } })
        putParcelableArrayList("children", ArrayList(node.children.map { encodePage(it, images) }))
    }

    private fun decodePage(b: Bundle, images: List<EventImage>): PageNode {
        val attrs = b.getBundle("attrs")!!
        val pictures = b.getBundle("images")!!
        val events = b.getBundle("events")!!
        return PageNode(
            type = b.getString("type")!!,
            level = b.getString("level")!!,
            attrs = attrs.keySet().associateWith { attrs.getString(it)!! },
            children = b.list("children").map { decodePage(it, images) },
            images = pictures.keySet().associateWith { images[pictures.getInt(it)] },
            events = events.keySet().associateWith { events.getString(it)!! },
        )
    }

    // ── 图 ──

    /** 一批里的图:同一个对象只编一次,事件里按序号引用 */
    private class Images {
        val encoded = ArrayList<Bundle>()
        private val index = IdentityHashMap<EventImage, Int>()

        fun put(into: Bundle, key: String, image: EventImage) {
            into.putInt(key, index.getOrPut(image) { encoded.add(encodeImage(image)); encoded.size - 1 })
        }
    }

    private fun encodeImage(image: EventImage) = Bundle().apply {
        putBoolean("symbol", image.symbol); putBoolean("appShared", image.appShared); putBoolean("substitute", image.substitute)
        when (image) {
            is EventImage.Picture -> { putString("type", "picture"); putParcelable("bitmap", image.bitmap); putString("identity", image.identity) }
            is EventImage.Platform -> { putString("type", "platform"); putParcelable("icon", image.icon) }
            is EventImage.Animation -> {
                putString("type", "animation"); putString("identity", image.identity); putBoolean("playing", image.playing); putByteArray("json", image.json)
                image.loopStartFrame?.let { putInt("loopStartFrame", it) }
                putBoolean("loop", image.loop)
            }
            is EventImage.Moving -> { putString("type", "moving"); putString("identity", image.identity); putByteArray("bytes", image.bytes) }
            is EventImage.Missing -> putString("type", "missing")
        }
    }

    private fun decodeImage(b: Bundle): EventImage {
        val symbol = b.getBoolean("symbol"); val shared = b.getBoolean("appShared"); val substitute = b.getBoolean("substitute")
        return when (b.getString("type")!!) {
            "picture" -> EventImage.Picture(b.getParcelable("bitmap", Bitmap::class.java)!!, b.getString("identity"), symbol, shared, substitute)
            "platform" -> EventImage.Platform(b.getParcelable("icon", Icon::class.java)!!, symbol, shared, substitute)
            "animation" -> EventImage.Animation(b.getString("identity")!!, b.getBoolean("playing"), b.getByteArray("json")!!, symbol, shared, substitute,
                if (b.containsKey("loopStartFrame")) b.getInt("loopStartFrame") else null, b.getBoolean("loop"))
            "moving" -> EventImage.Moving(b.getString("identity")!!, b.getByteArray("bytes")!!, symbol, shared, substitute)
            "missing" -> EventImage.Missing(symbol, shared, substitute)
            else -> error("unknown image type ${b.getString("type")}")
        }
    }

    // ── 读包裹的小工具 ──

    private fun Bundle.list(key: String): List<Bundle> = getParcelableArrayList(key, Bundle::class.java)!!

    private const val K_VERSION = "version"
    private const val K_EVENTS = "events"
    private const val K_IMAGES = "images"
    private const val K_PAGE = "page"
}
