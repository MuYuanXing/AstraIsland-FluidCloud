package com.astraisland.events

import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Bundle
import java.util.IdentityHashMap

/**
 * 标准事件的编码(contracts/island-system-events.v1.json)。接入件和星河岛在同一个进程里,但各自的类互不相认,
 * 所以只交换系统自带类型做成的包裹:Bundle、列表、位图、系统图标、字节。键名就是清单里的编号。
 * 包裹不跨进程传,位图与图标按对象放进去,不复制;同一张图在一批里只编一次,解出来还是同一个对象。
 * 认不出的种类按 unlisted 解,认不出的其它值当没填(清单:认不出时是 unlisted;没有的值不填)。
 */
object SystemEventCodec {
    /** 编码的版本;两边不一样时不交换(见对接约定) */
    const val VERSION = 1

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

    /** 解出一批事件;版本不一样或包裹不完整时返回空(不交换) */
    fun decode(bundle: Bundle?): List<SystemEvent>? {
        if (bundle == null || bundle.getInt(K_VERSION, -1) != VERSION) return null
        val images = bundle.list(K_IMAGES).map(::decodeImage)
        return bundle.list(K_EVENTS).mapNotNull { runCatching { decodeEvent(it, images) }.getOrNull() }
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
        putBoolean("noPicture", e.noPicture)
        putBoolean("openable", e.openable)
        putIntArray("relatedNotifications", e.relatedNotifications.toIntArray())
        e.notificationMark?.let { putStringArray("notificationMark", arrayOf(it.extra, it.value)) }
        putBoolean("complete", e.complete)
        putInt("fingerprint", e.fingerprint)
        e.callState?.let { putString("callState", it.id) }
        putBoolean("ringing", e.ringing)
        e.running?.let { putBoolean("running", it) }
        e.remainingMs?.let { putLong("remainingMs", it) }
        e.elapsedMs?.let { putLong("elapsedMs", it) }
        putString("timerName", e.timerName)
        putString("timerStatus", e.timerStatus)
        putString("stopwatchNote", e.stopwatchNote)
        putString("alarmLabel", e.alarmLabel)
        putString("alarmTime", e.alarmTime)
        putString("halfDay", e.halfDay)
        putString("gamePackage", e.gamePackage)
        putString("recordStateText", e.recordStateText)
        e.switch?.let { putString("switch", it.id) }
        putString("switchName", e.switchName)
        putString("switchState", e.switchState)
        putString("mediaPackage", e.mediaPackage)
        e.details?.let { putString("details", it.id) }
        putBundle(K_CARD, encodeCard(e.card, images))
    }

    private fun decodeEvent(b: Bundle, images: List<EventImage>) = SystemEvent(
        identity = b.getString("identity") ?: error("identity"),
        userId = b.getInt("userId"),
        revision = b.getLong("revision"),
        kind = EventKind.of(b.getString("kind")) ?: EventKind.UNLISTED,
        packageName = b.getString("packageName").orEmpty(),
        sourcePackage = b.getString("sourcePackage").orEmpty(),
        serviceName = b.getString("serviceName").orEmpty(),
        lockScreen = b.getBoolean("lockScreen"),
        alert = Alert.of(b.getString("alert")) ?: Alert.NONE,
        instant = Instant.of(b.getString("instant")) ?: Instant.NO,
        shake = b.getBoolean("shake"),
        noPicture = b.getBoolean("noPicture"),
        openable = b.getBoolean("openable"),
        relatedNotifications = b.getIntArray("relatedNotifications")?.toSet().orEmpty(),
        notificationMark = b.getStringArray("notificationMark")?.takeIf { it.size == 2 }?.let { NotificationMark(it[0], it[1]) },
        complete = b.getBoolean("complete", true),
        fingerprint = b.getInt("fingerprint"),
        callState = CallState.of(b.getString("callState")),
        ringing = b.getBoolean("ringing"),
        running = b.optBoolean("running"),
        remainingMs = b.optLong("remainingMs"),
        elapsedMs = b.optLong("elapsedMs"),
        timerName = b.getString("timerName"),
        timerStatus = b.getString("timerStatus"),
        stopwatchNote = b.getString("stopwatchNote"),
        alarmLabel = b.getString("alarmLabel"),
        alarmTime = b.getString("alarmTime"),
        halfDay = b.getString("halfDay"),
        gamePackage = b.getString("gamePackage"),
        recordStateText = b.getString("recordStateText"),
        switch = SwitchKind.of(b.getString("switch")),
        switchName = b.getString("switchName"),
        switchState = b.getString("switchState"),
        mediaPackage = b.getString("mediaPackage"),
        details = Details.of(b.getString("details")),
        card = b.getBundle(K_CARD)?.let { decodeCard(it, images) } ?: EventCard(),
    )

    // ── 卡片 ──

    private fun encodeCard(c: EventCard, images: Images) = Bundle().apply {
        putString("title", c.title)
        putString("body", c.body)
        putString("capsuleText", c.capsuleText)
        putString("capsuleLabel", c.capsuleLabel)
        putBoolean("capsuleWritesText", c.capsuleWritesText)
        images.put(this, "capsuleImage", c.capsuleImage)
        images.put(this, "capsuleRightImage", c.capsuleRightImage)
        c.capsuleRightProgress?.let { putBundle("capsuleRightProgress", encodeProgress(it)) }
        putBundle("capsule", Bundle().apply {
            putParcelableArrayList("leading", ArrayList(c.capsule.leading.map { encodeItem(it, images) }))
            putParcelableArrayList("trailing", ArrayList(c.capsule.trailing.map { encodeItem(it, images) }))
            putBoolean("absent", c.capsule.absent)
        })
        images.put(this, "picture", c.picture)
        c.progress?.let { putBundle("progress", encodeProgress(it)) }
        c.timer?.let { t ->
            putBundle("timer", Bundle().apply {
                putLong("baseMs", t.baseMs); putBoolean("countdown", t.countdown); putBoolean("elapsedClock", t.elapsedClock)
                putBoolean("running", t.running); t.frozenMs?.let { putLong("frozenMs", it) }
            })
        }
        putParcelableArrayList("buttons", ArrayList(c.buttons.map { button ->
            Bundle().apply {
                putString("id", button.id); putString("label", button.label); putString("use", button.use.id)
                images.put(this, "icon", button.icon)
                putBoolean("round", button.round); button.fill?.let { putInt("fill", it) }
                button.checked?.let { putBoolean("checked", it) }
                putBoolean("topRow", button.topRow); putBoolean("required", button.required); putBoolean("onRing", button.onRing)
            }
        }))
        putParcelableArrayList("texts", ArrayList(c.texts.map(::encodeText)))
        putBundle("firstTexts", Bundle().apply { c.firstTexts.forEach { (role, text) -> putString(role.id, text) } })
        putBundle("images", Bundle().apply { c.images.forEach { (role, image) -> images.put(this, role.id, image) } })
        c.percentInText?.let { putFloat("percentInText", it) }
        c.layout?.let { putString("layout", it.id) }
        c.ring?.let { r ->
            putBundle("ring", Bundle().apply { putFloat("fraction", r.fraction); r.color?.let { putInt("color", it) }; images.put(this, "inner", r.inner) })
        }
        putParcelableArrayList("tags", ArrayList(c.tags.map { tag ->
            Bundle().apply { images.put(this, "icon", tag.icon); putString("text", tag.text); putBoolean("ownLine", tag.ownLine) }
        }))
        images.put(this, "titleMark", c.titleMark)
        images.put(this, "spinner", c.spinner)
        images.put(this, "spinnerInner", c.spinnerInner)
        images.put(this, "headerImage", c.headerImage)
        images.put(this, "largeImage", c.largeImage)
        c.backgroundColor?.let { putInt("backgroundColor", it) }
        images.put(this, "backgroundImage", c.backgroundImage)
        c.lampColor?.let { putString("lampColor", it.id) }
    }

    private fun decodeCard(b: Bundle, images: List<EventImage>): EventCard {
        fun image(bundle: Bundle, key: String): EventImage? = bundle.optInt(key)?.let(images::getOrNull)
        val capsule = b.getBundle("capsule")
        return EventCard(
            title = b.getString("title").orEmpty(),
            body = b.getString("body").orEmpty(),
            capsuleText = b.getString("capsuleText").orEmpty(),
            capsuleLabel = b.getString("capsuleLabel"),
            capsuleWritesText = b.getBoolean("capsuleWritesText"),
            capsuleImage = image(b, "capsuleImage"),
            capsuleRightImage = image(b, "capsuleRightImage"),
            capsuleRightProgress = b.getBundle("capsuleRightProgress")?.let(::decodeProgress),
            capsule = EventCapsule(
                capsule?.list("leading").orEmpty().mapNotNull { decodeItem(it, images) },
                capsule?.list("trailing").orEmpty().mapNotNull { decodeItem(it, images) },
                capsule?.getBoolean("absent") == true),
            picture = image(b, "picture"),
            progress = b.getBundle("progress")?.let(::decodeProgress),
            timer = b.getBundle("timer")?.let { t ->
                EventTimer(t.getLong("baseMs"), t.getBoolean("countdown"), t.getBoolean("elapsedClock"), t.getBoolean("running", true), t.optLong("frozenMs"))
            },
            buttons = b.list("buttons").map { button ->
                EventButton(button.getString("id").orEmpty(), button.getString("label").orEmpty(),
                    ButtonUse.of(button.getString("use")) ?: ButtonUse.OTHER, image(button, "icon"), button.getBoolean("round"),
                    button.optInt("fill"), button.optBoolean("checked"), button.getBoolean("topRow"), button.getBoolean("required"),
                    button.getBoolean("onRing"))
            },
            texts = b.list("texts").mapNotNull(::decodeText),
            firstTexts = b.getBundle("firstTexts")?.let { first ->
                first.keySet().mapNotNull { id -> TextRole.of(id)?.let { role -> first.getString(id)?.let { role to it } } }.toMap()
            }.orEmpty(),
            images = b.getBundle("images")?.let { all ->
                all.keySet().mapNotNull { id -> ImageRole.of(id)?.let { role -> image(all, id)?.let { role to it } } }.toMap()
            }.orEmpty(),
            percentInText = b.optFloat("percentInText"),
            layout = CardLayout.of(b.getString("layout")),
            ring = b.getBundle("ring")?.let { r -> EventRing(r.getFloat("fraction"), r.optInt("color"), image(r, "inner")) },
            tags = b.list("tags").map { tag -> EventTag(image(tag, "icon"), tag.getString("text"), tag.getBoolean("ownLine")) },
            titleMark = image(b, "titleMark"),
            spinner = image(b, "spinner"),
            spinnerInner = image(b, "spinnerInner"),
            headerImage = image(b, "headerImage"),
            largeImage = image(b, "largeImage"),
            backgroundColor = b.optInt("backgroundColor"),
            backgroundImage = image(b, "backgroundImage"),
            lampColor = LampColor.of(b.getString("lampColor")),
        )
    }

    private fun encodeText(t: EventText) = Bundle().apply {
        putString("role", t.role.id); putString("text", t.text); putString("block", t.block.id); putBoolean("big", t.big)
        putStringArrayList("partTexts", ArrayList(t.parts.map { it.text }))
        putBooleanArray("partColored", t.parts.map { it.color != null }.toBooleanArray())
        putIntArray("partColors", t.parts.map { it.color ?: 0 }.toIntArray())
        t.color?.let { putInt("color", it) }
        putFloatArray("gradientAt", t.gradient.map { it.first }.toFloatArray())
        putIntArray("gradientColors", t.gradient.map { it.second }.toIntArray())
    }

    private fun decodeText(b: Bundle): EventText? {
        val texts = b.getStringArrayList("partTexts").orEmpty()
        val colored = b.getBooleanArray("partColored") ?: BooleanArray(0)
        val colors = b.getIntArray("partColors") ?: IntArray(0)
        val at = b.getFloatArray("gradientAt") ?: FloatArray(0)
        val stops = b.getIntArray("gradientColors") ?: IntArray(0)
        return EventText(
            role = TextRole.of(b.getString("role")) ?: TextRole.OTHER,
            text = b.getString("text") ?: return null,
            block = TextBlock.of(b.getString("block")) ?: return null,
            big = b.getBoolean("big"),
            parts = texts.mapIndexed { i, text -> EventTextPart(text, colors.getOrNull(i)?.takeIf { colored.getOrNull(i) == true }) },
            color = b.optInt("color"),
            gradient = at.indices.mapNotNull { i -> stops.getOrNull(i)?.let { at[i] to it } },
        )
    }

    private fun encodeProgress(p: EventProgress) = Bundle().apply {
        p.fraction?.let { putFloat("fraction", it) }
        putStringArrayList("labels", ArrayList(p.labels))
        p.steps?.let { putInt("steps", it) }
        p.tint?.let { putInt("tint", it) }
    }

    private fun decodeProgress(b: Bundle) =
        EventProgress(b.optFloat("fraction"), b.getStringArrayList("labels").orEmpty(), b.optInt("steps"), b.optInt("tint"))

    private fun encodeItem(item: CapsuleItem, images: Images) = Bundle().apply {
        when (item) {
            is CapsuleItem.Text -> { putString("type", "text"); putString("text", item.text); item.color?.let { putInt("color", it) }; putBoolean("timer", item.timer) }
            is CapsuleItem.Picture -> { putString("type", "picture"); images.put(this, "image", item.image); putBoolean("spin", item.spin) }
            is CapsuleItem.Ring -> {
                putString("type", "ring"); putBundle("progress", encodeProgress(item.progress)); item.color?.let { putInt("color", it) }
                images.put(this, "inner", item.inner)
            }
        }
    }

    private fun decodeItem(b: Bundle, images: List<EventImage>): CapsuleItem? = when (b.getString("type")) {
        "text" -> CapsuleItem.Text(b.getString("text").orEmpty(), b.optInt("color"), b.getBoolean("timer"))
        "picture" -> CapsuleItem.Picture(b.optInt("image")?.let(images::getOrNull), b.getBoolean("spin"))
        "ring" -> b.getBundle("progress")?.let { CapsuleItem.Ring(decodeProgress(it), b.optInt("color"), b.optInt("inner")?.let(images::getOrNull)) }
        else -> null
    }

    // ── 图 ──

    /** 一批里的图:同一个对象只编一次,事件里按序号引用 */
    private class Images {
        val encoded = ArrayList<Bundle>()
        private val index = IdentityHashMap<EventImage, Int>()

        fun put(into: Bundle, key: String, image: EventImage?) {
            if (image == null) return
            into.putInt(key, index.getOrPut(image) { encoded.add(encodeImage(image)); encoded.size - 1 })
        }
    }

    private fun encodeImage(image: EventImage) = Bundle().apply {
        putBoolean("symbol", image.symbol); putBoolean("appShared", image.appShared); putBoolean("substitute", image.substitute)
        when (image) {
            is EventImage.Picture -> { putString("type", "picture"); putParcelable("bitmap", image.bitmap); putString("identity", image.identity) }
            is EventImage.Platform -> { putString("type", "platform"); putParcelable("icon", image.icon) }
            is EventImage.Animation -> { putString("type", "animation"); putString("identity", image.identity); putBoolean("playing", image.playing); putByteArray("json", image.json) }
            is EventImage.Moving -> { putString("type", "moving"); putString("identity", image.identity); putByteArray("bytes", image.bytes) }
            is EventImage.Missing -> putString("type", "missing")
        }
    }

    private fun decodeImage(b: Bundle): EventImage {
        val symbol = b.getBoolean("symbol"); val shared = b.getBoolean("appShared"); val substitute = b.getBoolean("substitute")
        fun missing() = EventImage.Missing(symbol, shared, substitute)
        return when (b.getString("type")) {
            "picture" -> b.getParcelable("bitmap", Bitmap::class.java)?.let { EventImage.Picture(it, b.getString("identity"), symbol, shared, substitute) } ?: missing()
            "platform" -> b.getParcelable("icon", Icon::class.java)?.let { EventImage.Platform(it, symbol, shared, substitute) } ?: missing()
            "animation" -> b.getString("identity")?.let { id -> b.getByteArray("json")?.let { EventImage.Animation(id, b.getBoolean("playing"), it, symbol, shared, substitute) } } ?: missing()
            "moving" -> b.getString("identity")?.let { id -> b.getByteArray("bytes")?.let { EventImage.Moving(id, it, symbol, shared, substitute) } } ?: missing()
            else -> missing()
        }
    }

    // ── 读包裹的小工具 ──

    private fun Bundle.list(key: String): List<Bundle> = getParcelableArrayList(key, Bundle::class.java).orEmpty()
    private fun Bundle.optBoolean(key: String): Boolean? = if (containsKey(key)) getBoolean(key) else null
    private fun Bundle.optInt(key: String): Int? = if (containsKey(key)) getInt(key) else null
    private fun Bundle.optLong(key: String): Long? = if (containsKey(key)) getLong(key) else null
    private fun Bundle.optFloat(key: String): Float? = if (containsKey(key)) getFloat(key) else null

    private const val K_VERSION = "version"
    private const val K_EVENTS = "events"
    private const val K_IMAGES = "images"
    private const val K_CARD = "card"
}
