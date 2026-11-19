package io.github.trevarj.motd.data.prefs

import android.content.ContentResolver
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Xml
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.vector.PathParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.IOException
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

internal enum class WallpaperSvgError {
    UNREADABLE,
    TOO_LARGE,
    UNSUPPORTED,
    NOT_OUTLINE,
}

internal class WallpaperSvgException(
    val error: WallpaperSvgError,
    cause: Throwable? = null,
) : IllegalArgumentException(error.name, cause)

internal object WallpaperSvg {
    fun decode(bytes: ByteArray): WallpaperGlyph = WallpaperGlyph.parse(decodeUtf8(bytes))

    fun validate(icon: ImportedWallpaperIcon): WallpaperGlyph {
        if (icon.svg.length > MAX_BYTES) fail(WallpaperSvgError.TOO_LARGE)
        val encoded =
            try {
                Charsets.UTF_8.newEncoder().encode(CharBuffer.wrap(icon.svg))
            } catch (error: CharacterCodingException) {
                throw WallpaperSvgException(WallpaperSvgError.UNSUPPORTED, error)
            }
        if (encoded.remaining() > MAX_BYTES) fail(WallpaperSvgError.TOO_LARGE)
        val bytes = ByteArray(encoded.remaining()).also { encoded.get(it) }
        svgRequire(icon.id == assetId(bytes))
        return WallpaperGlyph.parse(icon.svg)
    }

    suspend fun import(
        context: Context,
        uri: Uri,
    ): ImportedWallpaperIcon =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val bytes = readImport(resolver, uri)
            val svg = decodeUtf8(bytes)
            WallpaperGlyph.parse(svg)
            ImportedWallpaperIcon(assetId(bytes), importName(resolver, uri), svg)
        }

    private suspend fun readImport(
        resolver: ContentResolver,
        uri: Uri,
    ): ByteArray =
        try {
            val input = resolver.openInputStream(uri) ?: fail(WallpaperSvgError.UNREADABLE)
            input.use {
                val buffer = ByteArray(MAX_BYTES + 1)
                var size = 0
                while (size <= MAX_BYTES) {
                    coroutineContext.ensureActive()
                    val read = it.read(buffer, size, buffer.size - size)
                    if (read < 0) break
                    if (read == 0) {
                        val byte = it.read()
                        if (byte < 0) break
                        buffer[size++] = byte.toByte()
                    } else {
                        size += read
                    }
                }
                if (size > MAX_BYTES) fail(WallpaperSvgError.TOO_LARGE)
                buffer.copyOf(size)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: WallpaperSvgException) {
            throw error
        } catch (error: Exception) {
            throw WallpaperSvgException(WallpaperSvgError.UNREADABLE, error)
        }

    private fun importName(
        resolver: ContentResolver,
        uri: Uri,
    ): String {
        val supplied =
            try {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    val column = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (column >= 0 && it.moveToFirst()) it.getString(column) else null
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // A provider may allow opening the file but not querying metadata.
                null
            }
        val basename = supplied?.takeIf(String::isNotBlank) ?: uri.lastPathSegment.orEmpty().substringAfterLast(':')
        val stem =
            basename.substringAfterLast('/').substringAfterLast('\\').let { name ->
                val extension = name.lastIndexOf('.')
                if (extension > 0) name.substring(0, extension) else name
            }
        return buildString {
            var index = 0
            while (index < stem.length && length < 64) {
                val point = stem.codePointAt(index)
                val count = Character.charCount(point)
                index += count
                if (!Character.isISOControl(point) && xmlChar(point) && Character.getType(point) != Character.FORMAT.toInt()) {
                    if (length + count > 64) break
                    appendCodePoint(point)
                }
            }
        }.trim().ifEmpty { "SVG" }
    }

    private fun decodeUtf8(bytes: ByteArray): String {
        if (bytes.size > MAX_BYTES) fail(WallpaperSvgError.TOO_LARGE)
        return try {
            Charsets.UTF_8
                .newDecoder()
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (error: CharacterCodingException) {
            throw WallpaperSvgException(WallpaperSvgError.UNSUPPORTED, error)
        }
    }

    private fun assetId(bytes: ByteArray): String =
        buildString(68) {
            append("svg:")
            for (byte in MessageDigest.getInstance("SHA-256").digest(bytes)) {
                append(HEX[(byte.toInt() ushr 4) and 15])
                append(HEX[byte.toInt() and 15])
            }
        }
}

/** Validated stroke outlines in original viewBox units, with the origin normalized to (0, 0). */
internal class WallpaperGlyph private constructor(
    val width: Float,
    val height: Float,
    /** Conservative radius of clipped stroke ink about the viewport center, in viewBox units. */
    val inkRadius: Double,
    private val content: SvgDrawing,
) {
    // Android Path can memoize native bounds; a cached glyph may be drawn by both tile workers.
    @Synchronized
    fun draw(
        canvas: Canvas,
        ink: Int,
        paint: Paint,
    ) {
        val save = canvas.save()
        try {
            if (!canvas.clipRect(0f, 0f, width, height)) return
            paint.style = Paint.Style.FILL
            paint.isAntiAlias = true
            content.draw(canvas, ink, paint)
        } finally {
            canvas.restoreToCount(save)
        }
    }

    companion object {
        internal fun parse(svg: String): WallpaperGlyph {
            val xml = svg.removePrefix("\uFEFF")
            checkXmlMarkup(xml)
            try {
                val parser =
                    Xml.newPullParser().apply {
                        setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
                        if (getFeature(XmlPullParser.FEATURE_VALIDATION)) setFeature(XmlPullParser.FEATURE_VALIDATION, false)
                        setInput(StringReader(xml))
                    }
                val decoder = SvgDecoder(parser)
                val content = decoder.read()
                return WallpaperGlyph(decoder.width, decoder.height, decoder.inkRadius, content)
            } catch (error: XmlPullParserException) {
                throw WallpaperSvgException(WallpaperSvgError.UNSUPPORTED, error)
            } catch (error: IOException) {
                throw WallpaperSvgException(WallpaperSvgError.UNSUPPORTED, error)
            } catch (error: IllegalArgumentException) {
                if (error is WallpaperSvgException) throw error
                throw WallpaperSvgException(WallpaperSvgError.UNSUPPORTED, error)
            }
        }
    }
}

private sealed interface SvgDrawing {
    fun draw(
        canvas: Canvas,
        ink: Int,
        paint: Paint,
    )

    class Stroke(
        private val outline: Path,
        private val alpha: Int,
    ) : SvgDrawing {
        override fun draw(
            canvas: Canvas,
            ink: Int,
            paint: Paint,
        ) {
            paint.color = (ink and 0x00FFFFFF) or ((((ink ushr 24) * alpha + 127) / 255) shl 24)
            canvas.drawPath(outline, paint)
        }
    }

    class Group(
        private val nodes: Array<SvgDrawing>,
        private val alpha: Int,
    ) : SvgDrawing {
        override fun draw(
            canvas: Canvas,
            ink: Int,
            paint: Paint,
        ) {
            // SVG opacity composites a group once, including where its child strokes overlap.
            val layer = if (alpha < 255) canvas.saveLayerAlpha(null, alpha) else -1
            for (node in nodes) node.draw(canvas, ink, paint)
            if (layer >= 0) canvas.restoreToCount(layer)
        }
    }
}

private class SvgDecoder(
    private val parser: XmlPullParser,
) {
    var width = 0f
        private set
    var height = 0f
        private set
    var inkRadius = 0.0
        private set
    private var namespace = ""
    private var shapeCount = 0
    private var segmentCount = 0
    private var hasVisibleStroke = false
    private val bounds = RectF()
    private val matrixValues = FloatArray(9)
    private val viewport = Path()
    private val scratch = Path()
    private val diagonal = Matrix().apply { setRotate(45f) }
    private val diagonalBounds = RectF()
    private val strokePaint = Paint().apply { style = Paint.Style.STROKE }

    fun read(): SvgDrawing {
        while (parser.nextToken() != XmlPullParser.START_TAG) {
            svgRequire(parser.eventType != XmlPullParser.END_DOCUMENT)
            checkText(allowText = false)
        }
        svgRequire(parser.name == "svg")
        namespace = parser.namespace.orEmpty()
        svgRequire(namespace.isEmpty() || namespace == SVG_NS)
        val attributes = readAttributes()
        val declaredWidth = attributes.remove("width")?.let(::length)
        val declaredHeight = attributes.remove("height")?.let(::length)
        svgRequire(declaredWidth == null || declaredWidth > 0)
        svgRequire(declaredHeight == null || declaredHeight > 0)
        val viewBox = attributes.remove("viewBox")?.let { NumberList(it).all(4).also { values -> svgRequire(values.size == 4) } }
        val x = viewBox?.get(0) ?: 0f
        val y = viewBox?.get(1) ?: 0f
        width = viewBox?.get(2) ?: declaredWidth ?: fail()
        height = viewBox?.get(3) ?: declaredHeight ?: fail()
        svgRequire(width >= 1 && height >= 1)
        viewport.addRect(0f, 0f, width, height, Path.Direction.CW)
        val normalize = Matrix().apply { setTranslate(-x, -y) }
        val root = readElement(attributes, SvgStyle(), normalize, 1f, true)
        while (parser.nextToken() != XmlPullParser.END_DOCUMENT) checkText(allowText = false)
        if (root == null || !hasVisibleStroke || inkRadius <= 0) fail(WallpaperSvgError.NOT_OUTLINE)
        return root
    }

    private fun readElement(
        attributes: MutableMap<String, String>,
        inherited: SvgStyle,
        parentMatrix: Matrix,
        parentOpacity: Float,
        parentFillVisible: Boolean,
    ): SvgDrawing? {
        val name = parser.name
        if (name == "title" || name == "desc") {
            svgRequire(attributes.isEmpty())
            while (parser.nextToken() != XmlPullParser.END_TAG) checkText(allowText = true)
            return null
        }
        val transform = attributes.remove("transform")?.let(::parseTransform)
        val matrix =
            if (transform == null) {
                parentMatrix
            } else {
                Matrix(parentMatrix).apply {
                    preConcat(transform)
                    getValues(matrixValues)
                    svgRequire(matrixValues.all(Float::isFinite))
                }
            }
        val opacity = attributes.remove("opacity")?.let(::opacity) ?: 1f
        val layerAlpha = (opacity * 255).roundToInt()
        val combinedOpacity = parentOpacity * (layerAlpha / 255f)
        val fillVisible = parentFillVisible && opacity > 0
        val style = inherited.applyAttributes(attributes)
        val isContainer = name == "svg" || name == "g"
        val drawing =
            if (isContainer) {
                svgRequire(attributes.isEmpty())
                null
            } else {
                svgRequire(++shapeCount <= MAX_SHAPES)
                val path = shape(name, attributes)
                svgRequire(attributes.isEmpty())
                makeStroke(path, matrix, style, name == "line", fillVisible, parentOpacity, opacity)
            }
        val children = if (isContainer) ArrayList<SvgDrawing>() else null
        while (parser.nextToken() != XmlPullParser.END_TAG) {
            if (parser.eventType != XmlPullParser.START_TAG) {
                checkText(allowText = false)
                continue
            }
            val child = parser.name
            svgRequire(child != "svg" && (isContainer || child == "title" || child == "desc"))
            readElement(readAttributes(), style, matrix, combinedOpacity, fillVisible)?.let { children?.add(it) }
        }
        if (children == null) return drawing
        if (layerAlpha == 0) return null
        return when {
            children.isEmpty() -> null
            children.size == 1 && layerAlpha == 255 -> children[0]
            else -> SvgDrawing.Group(children.toTypedArray(), layerAlpha)
        }
    }

    private fun readAttributes(): MutableMap<String, String> {
        val depth = parser.depth
        svgRequire(depth <= MAX_DEPTH && parser.namespace.orEmpty() == namespace)
        val firstNamespace = parser.getNamespaceCount(depth - 1)
        val lastNamespace = parser.getNamespaceCount(depth)
        if (firstNamespace < lastNamespace) {
            val prefixes = HashSet<String?>()
            for (index in firstNamespace until lastNamespace) {
                val uri = parser.getNamespaceUri(index)
                val prefix = parser.getNamespacePrefix(index)
                svgRequire(prefix != "xml" && prefix != "xmlns" && prefixes.add(prefix))
                svgRequire(uri == SVG_NS || (uri.isNullOrEmpty() && prefix.isNullOrEmpty()))
            }
        }
        val result = HashMap<String, String>(parser.attributeCount)
        for (index in 0 until parser.attributeCount) {
            svgRequire(parser.getAttributeNamespace(index).isNullOrEmpty() && parser.getAttributePrefix(index).isNullOrEmpty())
            val value = parser.getAttributeValue(index)
            checkXmlCharacters(value)
            svgRequire(result.put(parser.getAttributeName(index), value) == null)
        }
        result.remove("id")
        return result
    }

    private fun checkText(allowText: Boolean) {
        when (parser.eventType) {
            XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.IGNORABLE_WHITESPACE, XmlPullParser.ENTITY_REF -> {
                val text = parser.text ?: fail()
                if (allowText) checkXmlCharacters(text) else svgRequire(text.all(Char::svgSpace))
            }

            XmlPullParser.COMMENT -> {
                Unit
            }

            else -> {
                fail()
            }
        }
    }

    private fun shape(
        name: String,
        attributes: MutableMap<String, String>,
    ): Path {
        fun number(
            key: String,
            default: Float = 0f,
        ): Float = attributes.remove(key)?.let(::length) ?: default

        fun size(
            key: String,
            default: Float = 0f,
        ): Float = number(key, default).also { svgRequire(it >= 0) }

        fun ellipse(
            rx: Float,
            ry: Float,
        ): Path {
            val x = number("cx")
            val y = number("cy")
            val oval = RectF(x - rx, y - ry, x + rx, y + ry)
            checkCoordinates(oval)
            return Path().apply {
                if (rx > 0 && ry > 0) {
                    addSegments(4)
                    addOval(oval, Path.Direction.CW)
                }
            }
        }
        return when (name) {
            "path" -> {
                parsePath(attributes.remove("d").orEmpty(), ::addSegments)
            }

            "line" -> {
                Path().apply {
                    addSegments(1)
                    moveTo(number("x1"), number("y1"))
                    lineTo(number("x2"), number("y2"))
                }
            }

            "rect" -> {
                val x = number("x")
                val y = number("y")
                val w = size("width")
                val h = size("height")
                val rx = attributes.remove("rx")?.let(::length)
                val ry = attributes.remove("ry")?.let(::length)
                svgRequire((rx == null || rx >= 0) && (ry == null || ry >= 0))
                val roundX = minOf(rx ?: ry ?: 0f, w / 2)
                val roundY = minOf(ry ?: rx ?: 0f, h / 2)
                val rect = RectF(x, y, x + w, y + h)
                checkCoordinates(rect)
                Path().apply {
                    if (w > 0 && h > 0) {
                        if (roundX > 0 && roundY > 0) {
                            addSegments(8)
                            addRoundRect(rect, roundX, roundY, Path.Direction.CW)
                        } else {
                            addSegments(4)
                            addRect(rect, Path.Direction.CW)
                        }
                    }
                }
            }

            "circle" -> {
                size("r").let { ellipse(it, it) }
            }

            "ellipse" -> {
                ellipse(size("rx"), size("ry"))
            }

            "polyline", "polygon" -> {
                val points = NumberList(attributes.remove("points").orEmpty()).all(2 * (MAX_SEGMENTS + 1), coordinatePairs = true)
                svgRequire(points.size % 2 == 0)
                Path().apply {
                    if (points.size >= 4) {
                        moveTo(points[0], points[1])
                        for (index in 2 until points.size step 2) lineTo(points[index], points[index + 1])
                        addSegments(points.size / 2 - 1)
                        if (name == "polygon") {
                            addSegments(1)
                            close()
                        }
                    }
                }
            }

            else -> {
                fail()
            }
        }
    }

    private fun addSegments(count: Int) {
        segmentCount += count
        svgRequire(segmentCount <= MAX_SEGMENTS)
    }

    private fun makeStroke(
        path: Path,
        matrix: Matrix,
        style: SvgStyle,
        isLine: Boolean,
        fillVisible: Boolean,
        parentOpacity: Float,
        opacity: Float,
    ): SvgDrawing? {
        if (path.isEmpty) return null
        checkTransformedBounds(path, matrix, scratch, checkLocal = true)
        if (!isLine && fillVisible && style.fillAlpha > 0 && style.fillOpacity > 0) fail(WallpaperSvgError.NOT_OUTLINE)
        val alpha = (style.strokeAlpha * style.strokeOpacity * opacity * 255).roundToInt()
        if (style.strokeWidth <= 0 || alpha == 0 || parentOpacity == 0f) return null
        strokePaint.strokeWidth = style.strokeWidth
        strokePaint.strokeCap = style.lineCap
        strokePaint.strokeJoin = style.lineJoin
        strokePaint.strokeMiter = style.miterLimit
        val outline = Path()
        if (!strokePaint.getFillPath(path, outline) || outline.isEmpty) return null
        checkTransformedBounds(outline, matrix, outline)
        includeInInkRadius(outline)
        if (!hasVisibleStroke) {
            svgRequire(scratch.op(outline, viewport, Path.Op.INTERSECT))
            hasVisibleStroke = !scratch.isEmpty
        }
        return SvgDrawing.Stroke(outline, alpha)
    }

    @Suppress("DEPRECATION") // computeBounds(RectF, Boolean) is available at minSdk.
    private fun includeInInkRadius(outline: Path) {
        val left = maxOf(0f, bounds.left)
        val top = maxOf(0f, bounds.top)
        val right = minOf(width, bounds.right)
        val bottom = minOf(height, bounds.bottom)
        if (left >= right || top >= bottom) return
        val cx = width / 2.0
        val cy = height / 2.0
        val x = maxOf(cx - left, right - cx)
        val y = maxOf(cy - top, bottom - cy)
        val axisBound = hypot(x, y)
        if (axisBound <= inkRadius) return

        // A filled stroke lies within its Bézier control bounds. In addition to the
        // clipped rectangle, bound projections on the two diagonals. One of these
        // four unsigned axes is within 22.5° of any point's radial direction.
        outline.transform(diagonal, scratch)
        scratch.computeBounds(diagonalBounds, true)
        val diagonalX = (cx - cy) * SQRT_HALF
        val diagonalY = (cx + cy) * SQRT_HALF
        val projection =
            maxOf(
                maxOf(x, y),
                maxOf(diagonalX - diagonalBounds.left, diagonalBounds.right - diagonalX),
                maxOf(diagonalY - diagonalBounds.top, diagonalBounds.bottom - diagonalY),
            )
        // Pad the float transform (stroke controls are confined to ±16 viewports).
        val diagonalBound = (projection + maxOf(width, height) * 0.0001) / COS_SIXTEENTH_TURN
        val radius = if (diagonalBound.isFinite()) minOf(axisBound, diagonalBound) else axisBound
        inkRadius = maxOf(inkRadius, radius)
    }

    @Suppress("DEPRECATION") // computeBounds(RectF, Boolean) is available at minSdk.
    private fun checkTransformedBounds(path: Path, matrix: Matrix, destination: Path, checkLocal: Boolean = false) {
        // Check mapped floats before feeding them back to Skia, which can drop non-finite paths.
        path.computeBounds(bounds, true)
        if (checkLocal) checkCoordinates(bounds)
        matrix.mapRect(bounds)
        svgRequire(bounds.left.isFinite() && bounds.top.isFinite() && bounds.right.isFinite() && bounds.bottom.isFinite())
        path.transform(matrix, destination)
        destination.computeBounds(bounds, true)
        svgRequire(
            bounds.left >= -16 * width && bounds.right <= 16 * width &&
                bounds.top >= -16 * height && bounds.bottom <= 16 * height,
        )
    }
}

private data class SvgStyle(
    val fillAlpha: Float = 1f,
    val fillOpacity: Float = 1f,
    val strokeAlpha: Float = 0f,
    val strokeOpacity: Float = 1f,
    val strokeWidth: Float = 1f,
    val lineCap: Paint.Cap = Paint.Cap.BUTT,
    val lineJoin: Paint.Join = Paint.Join.MITER,
    val miterLimit: Float = 4f,
) {
    fun applyAttributes(attrs: MutableMap<String, String>): SvgStyle {
        fun property(name: String): String? = attrs.remove(name)?.trim(Char::svgSpace)?.takeUnless { it == "inherit" }
        return SvgStyle(
            fillAlpha = property("fill")?.let(::pigmentAlpha) ?: fillAlpha,
            fillOpacity = property("fill-opacity")?.let(::opacity) ?: fillOpacity,
            strokeAlpha = property("stroke")?.let(::pigmentAlpha) ?: strokeAlpha,
            strokeOpacity = property("stroke-opacity")?.let(::opacity) ?: strokeOpacity,
            strokeWidth = property("stroke-width")?.let { length(it).also { width -> svgRequire(width >= 0) } } ?: strokeWidth,
            lineCap =
                when (property("stroke-linecap")) {
                    null -> lineCap
                    "butt" -> Paint.Cap.BUTT
                    "round" -> Paint.Cap.ROUND
                    "square" -> Paint.Cap.SQUARE
                    else -> fail()
                },
            lineJoin =
                when (property("stroke-linejoin")) {
                    null -> lineJoin
                    "miter" -> Paint.Join.MITER
                    "round" -> Paint.Join.ROUND
                    "bevel" -> Paint.Join.BEVEL
                    else -> fail()
                },
            miterLimit = property("stroke-miterlimit")?.let { coordinate(it).also { miter -> svgRequire(miter >= 1) } } ?: miterLimit,
        )
    }
}

private fun parsePath(
    source: String,
    addSegments: (Int) -> Unit,
): Path {
    val numbers = NumberList(source)
    val canonical = StringBuilder(source.length)
    val values = FloatArray(7)
    var command = '\u0000'
    var x = 0f
    var y = 0f
    var startX = 0f
    var startY = 0f
    var first = true
    while (numbers.hasMore()) {
        var explicit = false
        if (numbers.peek() in PATH_COMMANDS) {
            command = numbers.take()
            explicit = true
        }
        val upper = command.uppercaseChar()
        if (first) svgRequire(upper == 'M')
        first = false
        val count =
            when (upper) {
                'Z' -> {
                    svgRequire(explicit)
                    x = startX
                    y = startY
                    canonical.append('Z')
                    addSegments(1)
                    continue
                }

                'H', 'V' -> {
                    1
                }

                'M', 'L', 'T' -> {
                    2
                }

                'S', 'Q' -> {
                    4
                }

                'C' -> {
                    6
                }

                'A' -> {
                    7
                }

                else -> {
                    fail()
                }
            }
        for (index in 0 until count) {
            if (index > 0 || !explicit) numbers.separator()
            values[index] = numbers.number(flag = upper == 'A' && index in 3..4)
        }
        val relative = command.isLowerCase()
        val endX = if (upper == 'V') x else checkedCoordinate(values[if (upper == 'H') 0 else count - 2] + if (relative) x else 0f)
        val endY = if (upper == 'H') y else checkedCoordinate(values[count - 1] + if (relative) y else 0f)
        when {
            upper == 'A' -> {
                svgRequire(values[0] >= 0 && values[1] >= 0)
                when {
                    endX == x && endY == y -> {
                        // A coincident arc draws nothing but still resets a trailing smooth curve's control.
                        canonical
                            .append("A1 1 0 0 0 ")
                            .append(endX)
                            .append(' ')
                            .append(endY)
                            .append(' ')
                        addSegments(1)
                    }

                    values[0] == 0f || values[1] == 0f -> {
                        canonical
                            .append('L')
                            .append(endX)
                            .append(' ')
                            .append(endY)
                            .append(' ')
                        addSegments(1)
                    }

                    else -> {
                        addSegments(arcSegments(values, endX - x, endY - y))
                        appendCommand(canonical, command, values, count)
                    }
                }
            }

            else -> {
                appendCommand(canonical, command, values, count)
                if (upper != 'M') addSegments(1)
            }
        }
        x = endX
        y = endY
        if (upper == 'M') {
            startX = x
            startY = y
            command = if (relative) 'l' else 'L'
        }
    }
    return PathParser().parsePathString(canonical.toString()).toPath().asAndroidPath()
}

private fun appendCommand(
    output: StringBuilder,
    command: Char,
    values: FloatArray,
    count: Int,
) {
    output.append(command)
    for (index in 0 until count) {
        if (command.uppercaseChar() == 'A' && index in 3..4) output.append(values[index].toInt()) else output.append(values[index])
        output.append(' ')
    }
}

/** Only budgets Compose's expanded arc verbs; PathParser remains the geometry interpreter. */
private fun arcSegments(
    values: FloatArray,
    dx: Float,
    dy: Float,
): Int {
    val angle = values[2] * PI / 180
    val cosine = cos(angle)
    val sine = sin(angle)
    val x = (dx * cosine + dy * sine) / 2
    val y = (-dx * sine + dy * cosine) / 2
    val chord = hypot(x / values[0], y / values[1])
    // Compose corrects undersized radii with a Float multiplier. Reject overflow before delegation.
    svgRequire(chord * (2.0 / 1.99999) < Float.MAX_VALUE)
    val shortSweep = 2 * asin(if (chord > 1) 0.999995 else chord)
    val sweep = if (values[3] == 0f) shortSweep else 2 * PI - shortSweep
    return ceil(sweep * 4 / PI).toInt().coerceIn(1, 8)
}

private fun parseTransform(source: String): Matrix {
    val result = Matrix()
    var offset = 0
    var count = 0
    while (offset < source.length && source[offset].svgSpace()) offset++
    while (offset < source.length) {
        svgRequire(++count <= 8)
        val call = TRANSFORM.matchAt(source, offset) ?: fail()
        val args = NumberList(call.groupValues[2]).all(6)
        val next = Matrix()
        when (call.groupValues[1]) {
            "translate" -> {
                svgRequire(args.size in 1..2)
                next.setTranslate(args[0], args.getOrElse(1) { 0f })
            }

            "scale" -> {
                svgRequire(args.size in 1..2)
                next.setScale(args[0], args.getOrElse(1) { args[0] })
            }

            "rotate" -> {
                svgRequire(args.size == 1 || args.size == 3)
                if (args.size == 1) next.setRotate(args[0]) else next.setRotate(args[0], args[1], args[2])
            }

            "matrix" -> {
                svgRequire(args.size == 6)
                next.setValues(floatArrayOf(args[0], args[2], args[4], args[1], args[3], args[5], 0f, 0f, 1f))
            }

            else -> {
                fail()
            }
        }
        result.preConcat(next)
        offset = call.range.last + 1
        val separator = offset
        while (offset < source.length && source[offset].svgSpace()) offset++
        if (offset < source.length) {
            if (source[offset] == ',') {
                offset++
                while (offset < source.length && source[offset].svgSpace()) offset++
                svgRequire(offset < source.length)
            } else {
                svgRequire(offset > separator)
            }
        }
    }
    svgRequire(count > 0)
    return result
}

/** Reads the SVG number grammar, handling implicit signs and compact arc flags without skipping junk. */
private class NumberList(
    private val source: String,
) {
    private var offset = 0

    fun peek(): Char = source.getOrElse(offset) { '\u0000' }

    fun take(): Char = source[offset++]

    private fun space() {
        while (peek().svgSpace()) offset++
    }

    fun hasMore(): Boolean {
        space()
        return offset < source.length
    }

    fun separator() {
        space()
        if (peek() == ',') {
            offset++
            space()
        }
    }

    fun number(flag: Boolean = false): Float {
        space()
        if (flag) {
            val token = peek()
            svgRequire(token == '0' || token == '1')
            offset++
            return if (token == '0') 0f else 1f
        }
        val token = NUMBER.matchAt(source, offset) ?: fail()
        offset = token.range.last + 1
        return numericCoordinate(token.value)
    }

    fun all(
        max: Int,
        coordinatePairs: Boolean = false,
    ): FloatArray {
        val result = ArrayList<Float>(minOf(max, 8))
        if (!hasMore()) return FloatArray(0)
        while (true) {
            svgRequire(result.size < max)
            result += number()
            val previous = offset
            if (!hasMore()) break
            if (peek() == ',') {
                offset++
            } else if (!coordinatePairs || result.size % 2 == 0) {
                svgRequire(offset > previous)
            }
        }
        return result.toFloatArray()
    }
}

private fun coordinate(value: String): Float = coordinateToken(value.trim(Char::svgSpace))

private fun coordinateToken(token: String): Float {
    svgRequire(NUMBER.matches(token))
    return numericCoordinate(token)
}

private fun numericCoordinate(token: String): Float {
    val number = token.toDoubleOrNull() ?: fail()
    svgRequire(abs(number) <= MAX_COORDINATE)
    return number.toFloat()
}

private fun checkedCoordinate(value: Float): Float {
    svgRequire(abs(value) <= MAX_COORDINATE)
    return value
}

private fun checkCoordinates(rect: RectF) {
    checkedCoordinate(rect.left)
    checkedCoordinate(rect.top)
    checkedCoordinate(rect.right)
    checkedCoordinate(rect.bottom)
}

private fun length(value: String): Float = coordinateToken(value.trim(Char::svgSpace).removeSuffix("px"))

private fun opacity(value: String): Float {
    val token = value.trim(Char::svgSpace)
    val alpha = if (token.endsWith('%')) coordinateToken(token.dropLast(1)) / 100f else coordinateToken(token)
    svgRequire(alpha in 0f..1f)
    return alpha
}

/** Pigment is always replaced by wallpaper ink; only the validated color's alpha is kept. */
private fun pigmentAlpha(value: String): Float {
    val color = value.trim(Char::svgSpace).lowercase()
    if (color == "none" || color == "transparent") return 0f
    if (color in NAMED_COLORS) return 1f
    if (color.startsWith('#')) {
        val hex = color.substring(1)
        svgRequire(hex.length in HEX_LENGTHS && hex.all { it in HEX })
        return when (hex.length) {
            4 -> hex.last().digitToInt(16) / 15f
            8 -> hex.takeLast(2).toInt(16) / 255f
            else -> 1f
        }
    }
    val function = COLOR_FUNCTION.matchEntire(color) ?: fail()
    val hsl = function.groupValues[1].startsWith("hsl")
    val body = function.groupValues[2].trim(Char::svgSpace)
    val parts =
        if (',' in body) {
            svgRequire('/' !in body)
            body.split(',').map { it.trim(Char::svgSpace) }
        } else {
            val sides = body.split('/')
            svgRequire(sides.size <= 2)
            val channels = sides[0].trim(Char::svgSpace).split(SPACE_RUN)
            svgRequire(channels.size == 3)
            if (sides.size == 2) channels + sides[1].trim(Char::svgSpace) else channels
        }
    svgRequire(parts.size in 3..4)
    if (!hsl && ',' in body) svgRequire((1..2).all { parts[it].endsWith('%') == parts[0].endsWith('%') })
    for (index in 0..2) {
        if (hsl && index == 0) {
            coordinateToken(parts[index].removeSuffix("deg"))
        } else {
            val percentage = parts[index].endsWith('%')
            svgRequire(!hsl || percentage)
            val number = coordinateToken(parts[index].removeSuffix("%"))
            svgRequire(number in 0f..(if (percentage) 100f else 255f))
        }
    }
    return if (parts.size == 4) opacity(parts[3]) else 1f
}

private fun checkXmlMarkup(xml: String) {
    checkXmlCharacters(xml)
    var offset = xml.indexOf('<')
    while (offset >= 0) {
        // Reject declarations before the parser can process even a DTD internal subset.
        val end =
            when {
                xml.startsWith("<!--", offset) -> {
                    val close = xml.indexOf("--", offset + 4)
                    svgRequire(close >= 0 && xml.startsWith("-->", close))
                    close + 3
                }

                xml.startsWith("<![CDATA[", offset) -> {
                    xml.indexOf("]]>", offset + 9).also { svgRequire(it >= 0) } + 3
                }

                xml.startsWith("<?", offset) -> {
                    svgRequire(offset == 0)
                    val declaration = XML_DECLARATION.matchAt(xml, offset) ?: fail()
                    val encoding = declaration.groupValues[3]
                    svgRequire(encoding.isEmpty() || encoding.equals("utf-8", ignoreCase = true))
                    declaration.range.last + 1
                }

                xml.startsWith("<!", offset) -> {
                    fail()
                }

                else -> {
                    offset + 1
                }
            }
        offset = xml.indexOf('<', end)
    }
}

private fun checkXmlCharacters(text: String) {
    var offset = 0
    while (offset < text.length) {
        val point = text.codePointAt(offset)
        svgRequire(xmlChar(point))
        offset += Character.charCount(point)
    }
}

private fun xmlChar(point: Int): Boolean = point == 9 || point == 10 || point == 13 || point in 0x20..0xD7FF || point in 0xE000..0xFFFD || point in 0x10000..0x10FFFF

private fun Char.svgSpace(): Boolean = this == ' ' || this == '\t' || this == '\n' || this == '\r'

private fun svgRequire(condition: Boolean) {
    if (!condition) fail()
}

private fun fail(error: WallpaperSvgError = WallpaperSvgError.UNSUPPORTED): Nothing = throw WallpaperSvgException(error)

private const val MAX_BYTES = 16_384
private const val MAX_COORDINATE = 16_384f
private const val MAX_SHAPES = 96
private const val MAX_SEGMENTS = 1_024
private const val MAX_DEPTH = 8
private const val SVG_NS = "http://www.w3.org/2000/svg"
private const val SQRT_HALF = 0.7071067811865476
private const val COS_SIXTEENTH_TURN = 0.9238795325112867
private const val HEX = "0123456789abcdef"
private const val PATH_COMMANDS = "MmZzLlHhVvCcSsQqTtAa"
private val HEX_LENGTHS = setOf(3, 4, 6, 8)
private val NUMBER = Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")
private val TRANSFORM = Regex("([a-z]+)[ \\t\\n\\r]*\\(([^()]*)\\)")
private val COLOR_FUNCTION = Regex("(rgba?|hsla?)\\(([^()]*)\\)")
private val SPACE_RUN = Regex("[ \\t\\n\\r]+")
private val XML_DECLARATION =
    Regex(
        """<\?xml\s+version\s*=\s*(['"])1\.0\1(?:\s+encoding\s*=\s*(['"])([A-Za-z][A-Za-z0-9._-]*)\2)?(?:\s+standalone\s*=\s*(['"])(?:yes|no)\4)?\s*\?>""",
    )
private val NAMED_COLORS =
    (
        "currentcolor aliceblue antiquewhite aqua aquamarine azure beige bisque black blanchedalmond blue blueviolet brown burlywood " +
            "cadetblue chartreuse chocolate coral cornflowerblue cornsilk crimson cyan darkblue darkcyan darkgoldenrod darkgray darkgreen " +
            "darkgrey darkkhaki darkmagenta darkolivegreen darkorange darkorchid darkred darksalmon darkseagreen darkslateblue darkslategray " +
            "darkslategrey darkturquoise darkviolet deeppink deepskyblue dimgray dimgrey dodgerblue firebrick floralwhite forestgreen fuchsia " +
            "gainsboro ghostwhite gold goldenrod gray green greenyellow grey honeydew hotpink indianred indigo ivory khaki lavender " +
            "lavenderblush lawngreen lemonchiffon lightblue lightcoral lightcyan lightgoldenrodyellow lightgray lightgreen lightgrey lightpink " +
            "lightsalmon lightseagreen lightskyblue lightslategray lightslategrey lightsteelblue lightyellow lime limegreen linen magenta " +
            "maroon mediumaquamarine mediumblue mediumorchid mediumpurple mediumseagreen mediumslateblue mediumspringgreen mediumturquoise " +
            "mediumvioletred midnightblue mintcream mistyrose moccasin navajowhite navy oldlace olive olivedrab orange orangered orchid " +
            "palegoldenrod palegreen paleturquoise palevioletred papayawhipp peachpuff peru pink plum powderblue purple rebeccapurple red " +
            "rosybrown royalblue saddlebrown salmon sandybrown seagreen seashell sienna silver skyblue slateblue slategray slategrey snow " +
            "springgreen steelblue tan teal thistle tomato turquoise violet wheat white whitesmoke yellow yellowgreen"
    ).split(' ').toHashSet()
