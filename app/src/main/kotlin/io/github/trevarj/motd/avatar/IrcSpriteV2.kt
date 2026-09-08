package io.github.trevarj.motd.avatar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.LruCache
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.roundToInt

private const val ASSET_ROOT = "irc-sprites-v2/"
private const val CATALOG_FILE = "${ASSET_ROOT}catalog.json"

/** A deterministic selection from the V2 raster catalog, independent of Android or a theme. */
internal data class IrcSpriteV2Traits(
    val body: Int,
    val head: Int,
    val face: Int,
    val accessory: Int,
)

/**
 * Derive every part independently so expanding one catalog axis only changes that axis. The
 * default counts document the packaged V2 catalog; tests may pass other counts to exercise bounds.
 */
internal fun ircSpriteV2Traits(
    name: String,
    bodyCount: Int = 3,
    headCount: Int = 8,
    faceCount: Int = 7,
    accessoryCount: Int = 10,
): IrcSpriteV2Traits {
    require(bodyCount > 0 && headCount > 0 && faceCount > 0 && accessoryCount > 0)
    val nick = canonicalAvatarNick(name)
    return IrcSpriteV2Traits(
        body = v2SaltedIndex(nick, "body", bodyCount),
        head = v2SaltedIndex(nick, "head", headCount),
        face = v2SaltedIndex(nick, "face", faceCount),
        accessory = v2SaltedIndex(nick, "accessory", accessoryCount),
    )
}

private fun v2SaltedIndex(
    nick: String,
    salt: String,
    bound: Int,
): Int {
    var hash = -3750763034362895579L
    for (byte in "$salt:$nick".encodeToByteArray()) {
        hash = (hash xor (byte.toLong() and 0xffL)) * 1099511628211L
    }
    hash = (hash xor (hash ushr 30)) * -4658895280553007687L
    hash = (hash xor (hash ushr 27)) * -7723592293110705685L
    hash = hash xor (hash ushr 31)
    return ((hash ushr 1) % bound.toLong()).toInt()
}

internal data class IrcSpriteV2Rect(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)

internal data class IrcSpriteV2Component(
    val id: String,
    val file: String,
    val rect: IrcSpriteV2Rect,
    val behindHead: Boolean = false,
    val faceRect: IrcSpriteV2Rect? = null,
    val accessoryRects: Map<String, IrcSpriteV2Rect> = emptyMap(),
)

internal data class IrcSpriteV2Catalog(
    val canvasSize: Int,
    val framing: IrcSpriteV2Framing? = null,
    val bodies: List<IrcSpriteV2Component>,
    val heads: List<IrcSpriteV2Component>,
    val faces: List<IrcSpriteV2Component>,
    val accessories: List<IrcSpriteV2Component>,
    val accents: List<IrcSpriteV2Component>,
)

/** Optional catalog framing applied around the selected head after the neutral disc is painted. */
internal data class IrcSpriteV2Framing(
    val zoom: Float = 1f,
)

/** Maps every sprite-layer rect through the selected head's catalog-defined framing. */
internal data class IrcSpriteV2LayerTransform(
    private val headCenterX: Float,
    private val headCenterY: Float,
    private val zoom: Float,
) {
    fun rect(source: IrcSpriteV2Rect): IrcSpriteV2Rect =
        IrcSpriteV2Rect(
            x = coordinate(source.x, headCenterX),
            y = coordinate(source.y, headCenterY),
            width = source.width * zoom,
            height = source.height * zoom,
        )

    private fun coordinate(
        value: Float,
        headCenter: Float,
    ): Float = 0.5f + (value - headCenter) * zoom

    companion object {
        fun forHead(
            head: IrcSpriteV2Rect,
            zoom: Float,
        ): IrcSpriteV2LayerTransform =
            IrcSpriteV2LayerTransform(
                headCenterX = head.x + head.width / 2f,
                headCenterY = head.y + head.height / 2f,
                zoom = zoom,
            )
    }
}

/**
 * Android bitmap compositor shared by Compose avatars and notifications. It keeps source, tinted,
 * and complete scene caches bounded, so a scrolling list only pays decoding and pixel tinting once
 * per recent component/color pair rather than once per draw.
 */
internal object IrcSpriteV2Renderer {
    private val sourceCache = bitmapCache(6 * 1024)
    private val tintedCache = bitmapCache(8 * 1024)
    private val compositeCache = bitmapCache(8 * 1024)

    @Volatile private var catalog: IrcSpriteV2Catalog? = null

    @Volatile private var catalogAttempted = false

    fun available(context: Context): Boolean = catalog(context) != null

    fun catalogForTest(context: Context): IrcSpriteV2Catalog? = catalog(context)

    fun render(
        context: Context,
        name: String,
        accent: Int,
        sizePx: Int,
        baseColor: Int,
        ringColor: Int,
        includeAccessory: Boolean = true,
    ): Bitmap? {
        val parsed = catalog(context) ?: return null
        val traits = ircSpriteV2Traits(name, parsed.bodies.size, parsed.heads.size, parsed.faces.size, parsed.accessories.size)
        val key = "$name:${accent and 0x00ffffff}:$sizePx:$baseColor:$ringColor:$includeAccessory:${traits.body}:${traits.head}:${traits.face}:${traits.accessory}"
        synchronized(compositeCache) { compositeCache.get(key)?.let { return it } }
        val result = compose(context, parsed, traits, accent, sizePx.coerceAtLeast(1), baseColor, ringColor, includeAccessory)
        synchronized(compositeCache) { compositeCache.put(key, result) }
        return result
    }

    private fun compose(
        context: Context,
        catalog: IrcSpriteV2Catalog,
        traits: IrcSpriteV2Traits,
        accent: Int,
        sizePx: Int,
        baseColor: Int,
        ringColor: Int,
        includeAccessory: Boolean,
    ): Bitmap {
        val output = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = false }
        val clip = Path().apply { addCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, Path.Direction.CW) }
        canvas.save()
        canvas.clipPath(clip)
        canvas.drawColor(baseColor)

        val body = catalog.bodies[traits.body]
        val head = catalog.heads[traits.head]
        val face = catalog.faces[traits.face]
        val accessory = catalog.accessories[traits.accessory]
        val accessoryRect = head.accessoryRects[accessory.id] ?: accessory.rect
        val transform = catalog.framing?.let { IrcSpriteV2LayerTransform.forHead(head.rect, it.zoom) }

        fun framed(rect: IrcSpriteV2Rect): IrcSpriteV2Rect = transform?.rect(rect) ?: rect
        drawIfPresent(canvas, paint, context, body, framed(body.rect), accent, sizePx)
        if (includeAccessory && accessory.behindHead) {
            drawIfPresent(canvas, paint, context, accessory, framed(accessoryRect), accent, sizePx)
        }
        drawIfPresent(canvas, paint, context, head, framed(head.rect), accent, sizePx)
        drawIfPresent(canvas, paint, context, face, framed(head.faceRect ?: face.rect), accent, sizePx)
        if (includeAccessory && !accessory.behindHead) {
            drawIfPresent(canvas, paint, context, accessory, framed(accessoryRect), accent, sizePx)
        }
        // A catalog can grow with several accents, but V2 currently intentionally has one badge.
        catalog.accents.firstOrNull()?.let { drawIfPresent(canvas, paint, context, it, framed(it.rect), accent, sizePx) }
        canvas.restore()

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = (sizePx / 32f).coerceAtLeast(1f)
        paint.color = ringColor
        canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f - paint.strokeWidth / 2f, paint)
        return output
    }

    private fun drawIfPresent(
        canvas: Canvas,
        paint: Paint,
        context: Context,
        component: IrcSpriteV2Component,
        rect: IrcSpriteV2Rect,
        accent: Int,
        sizePx: Int,
    ) {
        val bitmap = tinted(context, component, accent) ?: return
        val destination =
            RectF(
                rect.x * sizePx,
                rect.y * sizePx,
                (rect.x + rect.width) * sizePx,
                (rect.y + rect.height) * sizePx,
            )
        canvas.drawBitmap(bitmap, null, destination, paint)
    }

    private fun tinted(
        context: Context,
        component: IrcSpriteV2Component,
        accent: Int,
    ): Bitmap? {
        val key = "${component.file}:${accent and 0x00ffffff}"
        synchronized(tintedCache) { tintedCache.get(key)?.let { return it } }
        val source = source(context, component.file) ?: return null
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(source.width * source.height)
        source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        for (index in pixels.indices) {
            pixels[index] = tintIrcSpriteV2Pixel(pixels[index], accent)
        }
        output.setPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        synchronized(tintedCache) { tintedCache.put(key, output) }
        return output
    }

    private fun source(
        context: Context,
        file: String,
    ): Bitmap? {
        synchronized(sourceCache) { sourceCache.get(file)?.let { return it } }
        val decoded =
            runCatching {
                context.assets.open("$ASSET_ROOT$file").use { BitmapFactory.decodeStream(it) }
            }.getOrNull() ?: return null
        synchronized(sourceCache) { sourceCache.put(file, decoded) }
        return decoded
    }

    private fun catalog(context: Context): IrcSpriteV2Catalog? {
        catalog?.let { return it }
        // An in-flight notification load must finish before Compose treats the catalog as absent.
        synchronized(this) {
            catalog?.let { return it }
            if (catalogAttempted) return null
            catalogAttempted = true
            return runCatching {
                val text =
                    context.assets
                        .open(CATALOG_FILE)
                        .bufferedReader()
                        .use { it.readText() }
                parseIrcSpriteV2Catalog(text)
            }.getOrNull()?.also { catalog = it }
        }
    }

    private fun bitmapCache(maxSizeKb: Int): LruCache<String, Bitmap> =
        object : LruCache<String, Bitmap>(maxSizeKb) {
            override fun sizeOf(
                key: String,
                value: Bitmap,
            ): Int = value.allocationByteCount / 1024
        }
}

/** Tint a neutral source pixel while retaining its original alpha for transparent PNG layers. */
internal fun tintIrcSpriteV2Pixel(
    pixel: Int,
    accent: Int,
    accentStrength: Float = 0.22f,
): Int {
    val alpha = Color.alpha(pixel)
    if (alpha == 0) return pixel
    val luminance = (0.2126f * Color.red(pixel) + 0.7152f * Color.green(pixel) + 0.0722f * Color.blue(pixel)).roundToInt()

    fun channel(componentAccent: Int): Int = (luminance * ((1f - accentStrength) + accentStrength * componentAccent / 255f)).roundToInt().coerceIn(0, 255)
    return Color.argb(alpha, channel(Color.red(accent)), channel(Color.green(accent)), channel(Color.blue(accent)))
}

internal fun parseIrcSpriteV2Catalog(raw: String): IrcSpriteV2Catalog {
    val root = Json.parseToJsonElement(raw).jsonObject
    require(root["version"]?.jsonPrimitive?.intOrNull == 2) { "Unsupported IRC sprite catalog" }
    val canvasSize = root["canvasSize"]?.jsonPrimitive?.intOrNull ?: error("Missing canvasSize")
    val components = root["components"]?.jsonObject ?: error("Missing components")
    return IrcSpriteV2Catalog(
        canvasSize = canvasSize,
        framing =
            root["framing"]?.jsonObject?.let { framing ->
                IrcSpriteV2Framing(
                    zoom = framing["zoom"]?.jsonPrimitive?.floatOrNull ?: 1f,
                ).also {
                    require(it.zoom.isFinite() && it.zoom > 0f) { "Framing zoom must be finite and positive" }
                }
            },
        bodies = components.requiredComponents("body"),
        heads = components.requiredComponents("head"),
        faces = components.requiredComponents("face"),
        accessories = components.requiredComponents("accessory"),
        accents = components.requiredComponents("accent"),
    )
}

private fun JsonObject.requiredComponents(name: String): List<IrcSpriteV2Component> =
    (this[name]?.jsonArray ?: error("Missing $name components")).map { element ->
        val objectValue = element.jsonObject
        val id = objectValue["id"]?.jsonPrimitive?.contentOrNull ?: error("Missing $name id")
        val file = objectValue["file"]?.jsonPrimitive?.contentOrNull ?: error("Missing $name file")
        IrcSpriteV2Component(
            id = id,
            file = file,
            rect = objectValue.requiredRect("rect"),
            behindHead = objectValue["behindHead"]?.jsonPrimitive?.booleanOrNull ?: false,
            faceRect = objectValue["faceRect"]?.jsonArray?.toRect(),
            accessoryRects =
                objectValue["accessoryRects"]?.jsonObject?.mapValues { (_, value) -> value.jsonArray.toRect() }
                    ?: emptyMap(),
        )
    }

private fun JsonObject.requiredRect(name: String): IrcSpriteV2Rect = (this[name]?.jsonArray ?: error("Missing $name")).toRect()

private fun JsonArray.toRect(): IrcSpriteV2Rect {
    require(size == 4) { "A sprite rect must have four values" }
    val values = map { it.jsonPrimitive.floatOrNull ?: error("A sprite rect must be numeric") }
    require(values.all { it >= 0f && it <= 1f } && values[0] + values[2] <= 1f && values[1] + values[3] <= 1f) {
        "Sprite rect must stay within the normalized canvas"
    }
    return IrcSpriteV2Rect(values[0], values[1], values[2], values[3])
}
