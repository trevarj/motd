package io.github.trevarj.motd.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withTranslation
import io.github.trevarj.motd.data.prefs.ImportedWallpaperIcon
import io.github.trevarj.motd.data.prefs.WallpaperCatalog
import io.github.trevarj.motd.data.prefs.WallpaperGlyph
import io.github.trevarj.motd.data.prefs.WallpaperPattern
import io.github.trevarj.motd.data.prefs.WallpaperPatternLayout
import io.github.trevarj.motd.data.prefs.WallpaperRenderSpec
import io.github.trevarj.motd.data.prefs.WallpaperSvg
import io.github.trevarj.motd.data.prefs.WallpaperTexture
import io.github.trevarj.motd.data.prefs.effective
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.PriorityQueue
import java.util.Random
import kotlin.coroutines.coroutineContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

private const val PERIOD = WALLPAPER_TILE_PERIOD_DP
private const val MAX_EDGE_PX = 1536
private const val MAX_BITMAP_BYTES = 16 * 1024 * 1024
private const val STAMP_GAP = 1.75
private const val FILLER_GAP = 1.1
private const val MAX_LEAD_RADIUS = 42.0
private const val MAX_FILLER_RADIUS = 20.0
private const val MAX_VIEWPORT_SCALE_GAIN = 1.55
private const val WRAP_SLOP = 0.001f // Allow for the float canvas transform at the seam.
private const val FILLER_GRID = 64
private const val REFINEMENT_EDGE = 128
private const val REFINEMENT_STEP = PERIOD / REFINEMENT_EDGE
private const val REFINEMENT_GAPS = 3
private const val NO_INK = REFINEMENT_EDGE * REFINEMENT_EDGE
private const val INK_THRESHOLD = 32
private const val TURN = 2 * PI

/** Geometry is normalized to omit all paint-only values, including ink on individual icons. */
internal data class WallpaperPatternTileKey(
    val geometry: WallpaperPattern,
    val edge: Int,
    val inks: List<Int>,
)

internal fun wallpaperPatternTileKey(
    pattern: WallpaperPattern,
    themeInk: Color,
    periodPx: Float,
): WallpaperPatternTileKey {
    val defaultInk = pattern.ink ?: themeInk.toArgb()
    return WallpaperPatternTileKey(
        pattern.copy(
            layout = pattern.layout.effective,
            intensity = 0,
            background = null,
            ink = null,
            texture = WallpaperTexture.NONE,
            icons = pattern.icons.map { if (it.ink == null) it else it.copy(ink = null) },
        ),
        periodPx.roundToInt().coerceIn(1, MAX_EDGE_PX),
        pattern.icons.map { it.ink ?: defaultInk },
    )
}

@Composable
internal fun rememberWallpaperPatternPaint(
    wallpaper: WallpaperRenderSpec,
    themeInk: Color,
    tilePeriodDp: Float,
): Paint? {
    val pattern = wallpaper.pattern ?: return null
    if (pattern.icons.isEmpty()) return null
    val context = LocalContext.current.applicationContext
    val periodPx = LocalDensity.current.density * tilePeriodDp
    val key = remember(pattern, themeInk, periodPx) { wallpaperPatternTileKey(pattern, themeInk, periodPx) }
    val imports = wallpaper.imports
    if (!remember(key.geometry, imports) { key.hasAllImports(imports) }) return null
    var previous by remember { mutableStateOf<Bitmap?>(null) }
    val ready = tiles[key]
    LaunchedEffect(key, imports) {
        // A cancelled load never changes the visible tile; a failed current load clears it.
        val bitmap = ready ?: renderWallpaperPatternTile(context, key, imports)
        ensureActive()
        previous = bitmap
    }
    val bitmap = ready ?: previous ?: return null
    val shader = remember(bitmap, periodPx) { wallpaperPatternShader(bitmap, periodPx) }
    return remember(shader, pattern.intensity) { wallpaperPatternPaint(shader, pattern.intensity) }
}

@Composable
internal fun PreloadWallpaperPatternTile(
    wallpaper: WallpaperRenderSpec,
    themeInk: Color,
) {
    val pattern = wallpaper.pattern ?: return
    if (wallpaper.selection.localImageName != null || pattern.icons.isEmpty()) return
    val context = LocalContext.current.applicationContext
    val periodPx = LocalDensity.current.density * PERIOD
    val key = remember(pattern, themeInk, periodPx) { wallpaperPatternTileKey(pattern, themeInk, periodPx) }
    LaunchedEffect(key, wallpaper.imports) { renderWallpaperPatternTile(context, key, wallpaper.imports) }
}

internal fun wallpaperPatternPaint(
    bitmap: Bitmap,
    periodPx: Float,
    intensity: Int,
): Paint = wallpaperPatternPaint(wallpaperPatternShader(bitmap, periodPx), intensity)

private fun wallpaperPatternShader(
    bitmap: Bitmap,
    periodPx: Float,
): BitmapShader =
    BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).apply {
        val scale = periodPx / bitmap.width
        if (scale != 1f) setLocalMatrix(Matrix().apply { setScale(scale, scale) })
    }

private fun wallpaperPatternPaint(
    shader: BitmapShader,
    intensity: Int,
): Paint =
    Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        this.shader = shader
        alpha = (255 * intensity + 50) / 100
    }

private val glyphs = LruCache<String, WallpaperGlyph>(256)
private val glyphLoad = Mutex()
private val placements = LruCache<WallpaperPattern, List<Stamp>>(8)
private val placementLoad = Mutex()
private val tiles =
    object : LruCache<WallpaperPatternTileKey, Bitmap>(MAX_BITMAP_BYTES) {
        override fun sizeOf(
            key: WallpaperPatternTileKey,
            value: Bitmap,
        ): Int = value.allocationByteCount
    }
private val rasterSlots = Semaphore(2)
private val rasterRequests = HashMap<WallpaperPatternTileKey, RasterRequest>()

private class RasterRequest {
    val mutex = Mutex()
    var callers = 0
}

/** Imports accepted by the SVG intake are content-addressed; bundled IDs are immutable within this APK. */
internal suspend fun loadWallpaperGlyph(
    context: Context,
    assetId: String,
    imported: ImportedWallpaperIcon?,
): WallpaperGlyph {
    if (assetId.startsWith("svg:")) require(imported?.id == assetId) { "Missing wallpaper icon $assetId" }
    return glyphs[assetId] ?: withContext(Dispatchers.IO) {
        glyphLoad.withLock {
            glyphs[assetId]?.let { return@withLock it }
            ensureActive()
            val glyph =
                if (imported != null && imported.id == assetId) {
                    WallpaperSvg.validate(imported)
                } else {
                    val entry = requireNotNull(WallpaperCatalog.entry(context, assetId)) { "Unknown wallpaper icon $assetId" }
                    WallpaperSvg.decode(context.assets.open(entry.svgPath).use { it.readBytes() })
                }
            glyphs.put(assetId, glyph)
            glyph
        }
    }
}

private fun WallpaperPatternTileKey.hasAllImports(imports: List<ImportedWallpaperIcon>): Boolean = geometry.icons.all { icon -> !icon.assetId.startsWith("svg:") || imports.any { it.id == icon.assetId } }

internal suspend fun renderWallpaperPatternTile(
    context: Context,
    key: WallpaperPatternTileKey,
    imports: List<ImportedWallpaperIcon>,
): Bitmap? {
    if (key.geometry.icons.isEmpty() || !key.hasAllImports(imports)) return null
    tiles[key]?.let { return it }
    val request = synchronized(rasterRequests) { rasterRequests.getOrPut(key, ::RasterRequest).also { it.callers++ } }
    return try {
        request.mutex.withLock {
            tiles[key] ?: rasterSlots.withPermit {
                withContext(Dispatchers.Default) {
                    val bitmap = createBitmap(key.edge, key.edge)
                    var complete = false
                    try {
                        drawWallpaperPatternPeriod(context, key, imports, Canvas(bitmap), key.edge.toFloat())
                        ensureActive()
                        tiles.put(key, bitmap)
                        complete = true
                        bitmap
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: IllegalArgumentException) {
                        null
                    } catch (_: IOException) {
                        null
                    } catch (_: IllegalStateException) {
                        null
                    } finally {
                        if (!complete) bitmap.recycle()
                    }
                }
            }
        }
    } finally {
        synchronized(rasterRequests) {
            if (--request.callers == 0) rasterRequests.remove(key)
        }
    }
}

/** Draw one period, including translated copies on touched edges (and both translations at a corner). */
internal suspend fun drawWallpaperPatternPeriod(
    context: Context,
    key: WallpaperPatternTileKey,
    imports: List<ImportedWallpaperIcon>,
    canvas: Canvas,
    periodPx: Float,
) {
    val geometry = key.geometry
    val vectors =
        Array(geometry.icons.size) { index ->
            val assetId = geometry.icons[index].assetId
            loadWallpaperGlyph(context, assetId, imports.firstOrNull { it.id == assetId })
        }
    val stamps =
        placements[geometry] ?: placementLoad.withLock {
            placements[geometry] ?: layout(geometry, vectors).also { placements.put(geometry, it) }
        }
    val inks = key.inks.toIntArray()
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val save = canvas.save()
    try {
        canvas.scale(periodPx / PERIOD, periodPx / PERIOD)
        canvas.clipRect(0f, 0f, PERIOD, PERIOD)
        for (index in stamps.indices) {
            if (index % 16 == 0) coroutineContext.ensureActive()
            val stamp = stamps[index]
            val icon = stamp.orbit.icon
            drawWrappedStamp(canvas, vectors[icon], inks[icon], paint, stamp)
        }
    } finally {
        canvas.restoreToCount(save)
    }
}

private fun wrapOffset(
    center: Float,
    radius: Float,
): Float =
    when {
        center < radius -> PERIOD
        center + radius > PERIOD -> -PERIOD
        else -> 0f
    }

private fun drawWrappedStamp(
    canvas: Canvas,
    glyph: WallpaperGlyph,
    ink: Int,
    paint: Paint,
    stamp: Stamp,
) {
    val radius = stamp.orbit.radius.toFloat() + WRAP_SLOP
    val scale = (stamp.orbit.radius / glyph.inkRadius).toFloat()
    val xWrap = wrapOffset(stamp.x, radius)
    val yWrap = wrapOffset(stamp.y, radius)
    drawStamp(canvas, glyph, ink, paint, stamp, scale, 0f, 0f)
    if (xWrap != 0f) drawStamp(canvas, glyph, ink, paint, stamp, scale, xWrap, 0f)
    if (yWrap != 0f) drawStamp(canvas, glyph, ink, paint, stamp, scale, 0f, yWrap)
    if (xWrap != 0f && yWrap != 0f) drawStamp(canvas, glyph, ink, paint, stamp, scale, xWrap, yWrap)
}

private fun drawStamp(
    canvas: Canvas,
    glyph: WallpaperGlyph,
    ink: Int,
    paint: Paint,
    stamp: Stamp,
    scale: Float,
    dx: Float,
    dy: Float,
) {
    canvas.withTranslation(stamp.x + dx, stamp.y + dy) {
        rotate(stamp.rotation)
        scale(scale, if (stamp.mirror) -scale else scale)
        translate(-glyph.width / 2f, -glyph.height / 2f)
        glyph.draw(this, ink, paint)
    }
}

private class Orbit(
    val icon: Int,
    val angle: Float,
    val intendedRadius: Double,
    var radius: Double = intendedRadius,
)

private class Stamp(
    val x: Float,
    val y: Float,
    val rotation: Float,
    val mirror: Boolean,
    val orbit: Orbit,
)

private class FillerCandidate(
    val x: Float,
    val y: Float,
    val icon: Int,
    val rotation: Float,
    val intendedRadius: Double,
    val minimumRadius: Double,
    val tie: Long,
    var clearance: Double,
    var checkedStamps: Int,
)

private class WeightedIcons(
    pattern: WallpaperPattern,
    indices: List<Int>,
) {
    private val iconIndices = indices.toIntArray()
    private val cumulative = IntArray(indices.size)

    init {
        var total = 0
        for (i in iconIndices.indices) {
            total += pattern.icons[iconIndices[i]].weight
            cumulative[i] = total
        }
    }

    fun choose(random: Random): Int {
        val draw = random.nextInt(cumulative.last())
        for (i in cumulative.indices) if (draw < cumulative[i]) return iconIndices[i]
        error("Wallpaper weight total changed")
    }
}

private fun toroidalDistanceSquared(
    x: Float,
    y: Float,
    otherX: Float,
    otherY: Float,
): Double {
    val dx = abs(x - otherX).toDouble()
    val dy = abs(y - otherY).toDouble()
    val wrappedX = min(dx, PERIOD - dx)
    val wrappedY = min(dy, PERIOD - dy)
    return wrappedX * wrappedX + wrappedY * wrappedY
}

private fun toroidalDistanceSquared(
    x: Float,
    y: Float,
    other: Stamp,
): Double = toroidalDistanceSquared(x, y, other.x, other.y)

private fun wrapped(value: Double): Float {
    val coordinate = value.toFloat()
    return when {
        coordinate < 0f -> (PERIOD + coordinate).let { if (it == PERIOD) 0f else it }
        coordinate >= PERIOD -> coordinate - PERIOD
        else -> coordinate
    }
}

private inline fun mirrorPositions(
    x: Double,
    y: Double,
    place: (Float, Float, Int, Boolean) -> Unit,
) {
    val center = PERIOD / 2.0
    for (mirror in 0..1) {
        var dx = x
        var dy = if (mirror == 0) y else -y
        for (quarter in 0..3) {
            place(wrapped(center + dx), wrapped(center + dy), quarter, mirror != 0)
            val nextX = -dy
            dy = dx
            dx = nextX
        }
    }
}

private fun mirrorOrbit(
    x: Double,
    y: Double,
    orbit: Orbit,
    stamps: MutableList<Stamp>,
) {
    mirrorPositions(x, y) { cx, cy, quarter, mirror ->
        val rotation = if (mirror) -orbit.angle else orbit.angle
        stamps += Stamp(cx, cy, rotation + 90f * quarter, mirror, orbit)
    }
}

private class InkScore(
    val largest: Int,
    val atLargest: Int,
    val coverage: Int,
) {
    fun improves(other: InkScore): Boolean =
        when {
            largest != other.largest -> largest < other.largest
            atLargest != other.atLargest -> atLargest < other.atLargest
            else -> coverage > other.coverage
        }
}

private class RefinementInk {
    val bitmap = createBitmap(REFINEMENT_EDGE, REFINEMENT_EDGE)
    val distances = IntArray(REFINEMENT_EDGE * REFINEMENT_EDGE)
    private val pixels = IntArray(distances.size)
    private val sites = IntArray(3 * REFINEMENT_EDGE)
    private val costs = IntArray(sites.size)
    private val starts = IntArray(sites.size)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val canvas =
        Canvas(bitmap).apply {
            scale(REFINEMENT_EDGE / PERIOD, REFINEMENT_EDGE / PERIOD)
        }

    suspend fun measure(
        stamps: List<Stamp>,
        glyphs: Array<WallpaperGlyph>,
    ): InkScore {
        bitmap.eraseColor(0)
        for (i in stamps.indices) {
            if (i % 16 == 0) coroutineContext.ensureActive()
            val stamp = stamps[i]
            drawWrappedStamp(canvas, glyphs[stamp.orbit.icon], android.graphics.Color.WHITE, paint, stamp)
        }
        val edge = REFINEMENT_EDGE
        bitmap.getPixels(pixels, 0, edge, 0, 0, edge, edge)
        var coverage = 0
        var hasInk = false
        // Horizontal squared distances: scan both ways, seeded by the wrapped ink at each end.
        for (row in distances.indices step edge) {
            var first = -1
            var last = -1
            for (x in 0 until edge) {
                val alpha = pixels[row + x] ushr 24
                coverage += alpha
                if (alpha < INK_THRESHOLD) continue
                if (first == -1) first = x
                last = x
            }
            if (first == -1) {
                distances.fill(NO_INK, row, row + edge)
                continue
            }
            hasInk = true
            var site = last - edge
            for (x in 0 until edge) {
                if (pixels[row + x] ushr 24 >= INK_THRESHOLD) site = x
                distances[row + x] = x - site
            }
            site = first + edge
            for (x in edge - 1 downTo 0) {
                if (distances[row + x] == 0) site = x
                val dx = min(distances[row + x], site - x)
                distances[row + x] = dx * dx
            }
        }
        if (!hasInk) return InkScore(NO_INK, distances.size, coverage)

        var largest = 0
        var atLargest = 0
        for (x in 0 until edge) {
            if (x % 16 == 0) coroutineContext.ensureActive()
            // Each row's horizontal cost is the bottom of a parabola. Keep only
            // those that win somewhere, including rows in the two wrapped copies.
            var size = 0
            for (y in -edge until 2 * edge) {
                val cost = distances[(y and (edge - 1)) * edge + x]
                if (cost == NO_INK) continue
                var start = Int.MIN_VALUE
                while (size > 0) {
                    val previous = sites[size - 1]
                    val numerator = cost + y * y - costs[size - 1] - previous * previous
                    start = Math.floorDiv(numerator, 2 * (y - previous)) + 1
                    if (start > starts[size - 1]) break
                    size--
                }
                sites[size] = y
                costs[size] = cost
                starts[size] = if (size == 0) Int.MIN_VALUE else start
                size++
            }
            var best = 0
            for (y in 0 until edge) {
                while (best + 1 < size && starts[best + 1] <= y) best++
                val dy = y - sites[best]
                val distance = dy * dy + costs[best]
                distances[y * edge + x] = distance
                if (distance > largest) {
                    largest = distance
                    atLargest = 1
                } else if (distance == largest) {
                    atLargest++
                }
            }
        }
        return InkScore(largest, atLargest, coverage)
    }
}

private class FillerMove(
    val donor: Int,
    val x: Float,
    val y: Float,
    val radius: Double,
    val rank: Double,
)

private suspend fun refineFillers(
    stamps: MutableList<Stamp>,
    firstFiller: Int,
    stampsPerOrbit: Int,
    glyphs: Array<WallpaperGlyph>,
) {
    if (firstFiller == stamps.size) return
    val symmetric = stampsPerOrbit > 1
    val center = PERIOD / 2.0
    val edge = REFINEMENT_EDGE
    val step = REFINEMENT_STEP
    val ink = RefinementInk()
    val donorCost = DoubleArray((stamps.size - firstFiller) / stampsPerOrbit)
    val gapCenters = IntArray(REFINEMENT_GAPS)
    val offsets = intArrayOf(0, -1, 1, -2, 2, -4, 4)
    val originals = arrayOfNulls<Stamp>(stampsPerOrbit)
    val copies = ArrayList<Stamp>(stampsPerOrbit)

    fun sample(
        x: Int,
        y: Int,
    ): Int = ink.distances[(y and (edge - 1)) * edge + (x and (edge - 1))]

    suspend fun tryMove(
        move: FillerMove,
        baseline: InkScore,
    ): InkScore? {
        val orbit = stamps[move.donor].orbit
        val oldRadius = orbit.radius
        copies.clear()
        if (symmetric) {
            mirrorOrbit(move.x - center, move.y - center, orbit, copies)
        } else {
            copies += Stamp(move.x, move.y, orbit.angle, false, orbit)
        }
        var fit = move.radius
        // The representative is sufficient for ranking D4 moves; check every
        // rendered copy before accepting to account for float coordinates at seams.
        for (i in copies.indices) {
            val copy = copies[i]
            for (j in 0 until i) {
                val limit = 2 * fit + FILLER_GAP
                val d2 = toroidalDistanceSquared(copy.x, copy.y, copies[j])
                if (d2 < limit * limit) {
                    fit = (sqrt(d2) - FILLER_GAP) / 2
                    if (fit < oldRadius) return null
                }
            }
            for (other in stamps) {
                if (other.orbit === orbit) continue
                val occupied = other.orbit.radius + FILLER_GAP
                val limit = fit + occupied
                val d2 = toroidalDistanceSquared(copy.x, copy.y, other)
                if (d2 < limit * limit) {
                    fit = sqrt(d2) - occupied
                    if (fit < oldRadius) return null
                }
            }
        }
        for (i in copies.indices) {
            originals[i] = stamps[move.donor + i]
            stamps[move.donor + i] = copies[i]
        }
        orbit.radius = fit
        var accepted = false
        try {
            val result = ink.measure(stamps, glyphs)
            accepted = result.improves(baseline)
            return if (accepted) result else null
        } finally {
            if (!accepted) {
                orbit.radius = oldRadius
                for (i in originals.indices) stamps[move.donor + i] = checkNotNull(originals[i])
            }
        }
    }

    try {
        var baseline = ink.measure(stamps, glyphs)
        if (baseline.largest == NO_INK) return
        // ponytail: scout a few pixels around the worst ink holes, then re-raster
        // at most two promising moves per round rather than optimizing continuously.
        repeat(2) {
            var smallest = Double.POSITIVE_INFINITY
            for (i in firstFiller until stamps.size step stampsPerOrbit) {
                val donor = stamps[i]
                val x = (donor.x / step).toInt()
                val y = (donor.y / step).toInt()
                val radius = donor.orbit.radius
                smallest = min(smallest, radius)
                val reach = (radius / step).toInt() + 2
                val nearby = minOf(sample(x - reach, y), sample(x + reach, y), sample(x, y - reach), sample(x, y + reach))
                donorCost[(i - firstFiller) / stampsPerOrbit] = 0.55 * radius + 0.25 * step * sqrt(nearby.toDouble())
            }
            val moves = ArrayList<FillerMove>(REFINEMENT_GAPS + 1)
            for (slot in gapCenters.indices) {
                coroutineContext.ensureActive()
                var target = -1
                var severity = baseline.largest * 2 / 3
                for (i in ink.distances.indices) {
                    val distance = ink.distances[i]
                    if (distance <= severity) continue
                    var px = i % edge
                    var py = i / edge
                    if (symmetric) {
                        px = maxOf(px, edge - 1 - px)
                        py = maxOf(py, edge - 1 - py)
                        if (px < py) {
                            val swap = px
                            px = py
                            py = swap
                        }
                    }
                    var separate = true
                    for (j in 0 until slot) {
                        val dx = abs(px - gapCenters[j] % edge)
                        val dy = abs(py - gapCenters[j] / edge)
                        val wrapX = min(dx, edge - dx)
                        val wrapY = min(dy, edge - dy)
                        if (wrapX * wrapX + wrapY * wrapY < 64) {
                            separate = false
                            break
                        }
                    }
                    if (separate) {
                        target = py * edge + px
                        severity = distance
                    }
                }
                if (target == -1) break
                gapCenters[slot] = target
                val depth = 2 * step * sqrt(severity.toDouble())
                var best: FillerMove? = null
                var alternate: FillerMove? = null
                for (dy in offsets) {
                    position@ for (dx in offsets) {
                        val px = (target % edge + dx) and (edge - 1)
                        val py = (target / edge + dy) and (edge - 1)
                        if (symmetric && (py < edge / 2 || px <= py)) continue
                        val x = (px + 0.5f) * step
                        val y = (py + 0.5f) * step
                        val offset = step * sqrt((dx * dx + dy * dy).toDouble())
                        val minimum = maxOf(smallest, offset - step)
                        var free = min(MAX_FILLER_RADIUS, fillerSelfClearance(x, y, symmetric))
                        var next = free
                        if (next < minimum) continue
                        var blocker: Orbit? = null
                        for (other in stamps) {
                            val occupied = other.orbit.radius + FILLER_GAP
                            val sameBlocker = other.orbit === blocker
                            val limit = (if (sameBlocker) free else next) + occupied
                            if (limit <= 0) continue
                            val d2 = toroidalDistanceSquared(x, y, other)
                            if (d2 >= limit * limit) continue
                            val room = sqrt(d2) - occupied
                            if (room < free) {
                                if (!sameBlocker) {
                                    next = free
                                    blocker = other.orbit
                                }
                                free = room
                            } else if (!sameBlocker) {
                                next = room
                            }
                            if (next < minimum) continue@position
                        }
                        for (i in firstFiller until stamps.size step stampsPerOrbit) {
                            val donor = stamps[i].orbit
                            val fit = min(donor.intendedRadius, if (donor === blocker) next else free)
                            if (fit < donor.radius || fit + step < offset) continue
                            val cost = donorCost[(i - firstFiller) / stampsPerOrbit]
                            val rank = depth + fit - offset + 0.5 * (fit - donor.radius) - cost
                            if (best == null || rank > best.rank) {
                                if (best != null && best.donor != i) alternate = best
                                best = FillerMove(i, x, y, fit, rank)
                            } else if (slot == 0 && i != best.donor && (alternate == null || rank > alternate.rank)) {
                                alternate = FillerMove(i, x, y, fit, rank)
                            }
                        }
                    }
                }
                if (best != null) moves += best
                if (slot == 0 && alternate != null) moves += alternate
            }
            var improvement: InkScore? = null
            for (attempt in 0 until 2) {
                val move = moves.maxByOrNull { it.rank } ?: break
                moves.remove(move)
                improvement = tryMove(move, baseline)
                if (improvement != null) break
            }
            baseline = improvement ?: return
        }
    } finally {
        ink.bitmap.recycle()
    }
}

// The closest D4 copy is across a horizontal, diagonal, or periodic vertical edge of the wedge.
private fun fillerSelfClearance(
    x: Float,
    y: Float,
    symmetric: Boolean,
): Double {
    if (!symmetric) return PERIOD.toDouble()
    val center = PERIOD / 2.0
    val distance = minOf(2 * (y - center), PERIOD - 2 * (x - center), sqrt(2.0) * (x - y))
    return (distance - FILLER_GAP) / 2
}

private suspend fun layout(
    pattern: WallpaperPattern,
    glyphs: Array<WallpaperGlyph>,
): List<Stamp> {
    val random = Random(pattern.seed.toLong())
    val leadIndices = pattern.icons.indices.filterNot { pattern.icons[it].filler }
    val fillerIndices = pattern.icons.indices.filter { pattern.icons[it].filler }
    val fillers = if (fillerIndices.isEmpty()) null else WeightedIcons(pattern, fillerIndices)
    val leads = if (leadIndices.isEmpty()) null else WeightedIcons(pattern, leadIndices)
    val mode = pattern.layout.effective
    var n = if (fillers == null) 7 + (8 * pattern.density / 100.0).roundToInt() else 12
    if (n % 2 != 0 && (mode == WallpaperPatternLayout.DIAGONAL || mode == WallpaperPatternLayout.KALEIDOSCOPE)) n++
    val c = PERIOD.toDouble() / n
    val center = PERIOD / 2.0
    val symmetric = mode == WallpaperPatternLayout.KALEIDOSCOPE
    val stampsPerOrbit = if (symmetric) 8 else 1
    val minimumFillers = if (leads == null) n * n / 2 else 0
    // Density stops the same greedy sequence earlier; it must not change any inputs to it.
    val fillerCount = minimumFillers + ((3 * n * n - minimumFillers) * pattern.density + 50) / 100
    val fillerTarget = if (fillers == null) 0 else (fillerCount + stampsPerOrbit - 1) / stampsPerOrbit
    val leadCount =
        when {
            leads == null -> 0
            symmetric -> n * (n + 2)
            else -> n * n
        }
    val stamps = ArrayList<Stamp>(leadCount + fillerTarget * stampsPerOrbit)
    val sizes = DoubleArray(pattern.icons.size) { (pattern.size * (pattern.icons[it].scale ?: 100) / 10000.0).pow(0.65) }
    // A nominal radius used to fit half the viewport diagonal, transparent margins included.
    // Fit the visible envelope now, but tiny centered artwork may grow by at most 1.55× at the same nominal size.
    val inkFractions =
        DoubleArray(glyphs.size) { index ->
            val glyph = glyphs[index]
            val viewportRadius = hypot(glyph.width.toDouble(), glyph.height.toDouble()) / 2.0
            min(1.0, MAX_VIEWPORT_SCALE_GAIN * glyph.inkRadius / viewportRadius)
        }

    fun angle(
        source: Random,
        icon: Int,
        baseAngle: Double,
    ): Float {
        val variation = ((2 * pattern.variation + 1) * source.nextDouble()).toInt() - pattern.variation
        return (pattern.rotation + (pattern.icons[icon].rotation?.toDouble() ?: (baseAngle + variation))).toFloat()
    }

    fun waveAngle(phase: Double): Double = atan(0.26 * TURN / n * cos(phase)) * 180 / PI

    fun lead(
        pool: WeightedIcons,
        x: Double,
        y: Double,
        baseAngle: Double,
    ) {
        val icon = pool.choose(random)
        val rotation = angle(random, icon, baseAngle)
        val sample = random.nextDouble()
        val radiusFactor = if (pattern.icons[icon].scale == null) 0.23 + 0.48 * sample else 0.43
        val radius = min(MAX_LEAD_RADIUS, c * radiusFactor * sizes[icon]) * inkFractions[icon]
        val orbit = Orbit(icon, rotation, radius)
        if (symmetric) mirrorOrbit(x, y, orbit, stamps) else stamps += Stamp(wrapped(x), wrapped(y), rotation, false, orbit)
    }

    if (leads != null && symmetric) {
        for (i in 0 until n / 2) {
            for (j in 0..i) lead(leads, (i + 0.68) * c, (j + 0.32) * c, 0.0)
        }
    } else if (leads != null) {
        var phaseX = 0.0
        var phaseY = 0.0
        for (j in 0 until n) {
            for (i in 0 until n) {
                var x = c * (i + 0.5)
                var y = c * (j + 0.5)
                var baseAngle = 0.0
                when (mode) {
                    WallpaperPatternLayout.DIAGONAL -> {
                        x += c * (j % 2) / 2
                        baseAngle = 15.0
                    }

                    WallpaperPatternLayout.WAVES -> {
                        val phase = TURN * i / n
                        y += 0.26 * c * sin(phase)
                        baseAngle = waveAngle(phase)
                    }

                    WallpaperPatternLayout.SCATTERED -> {
                        x += c * 0.38 * (2 * random.nextDouble() - 1)
                        y += c * 0.38 * (2 * random.nextDouble() - 1)
                        if (i == 0 && j == 0) {
                            phaseX = x
                            phaseY = y
                        }
                        x -= phaseX
                        y -= phaseY
                    }

                    else -> {
                    }
                }
                lead(leads, x, y, baseAngle)
            }
        }
    }

    // ponytail: at most 288 leads; visit each pair once rather than indexing the torus.
    for (i in stamps.indices) {
        if (i % 16 == 0) coroutineContext.ensureActive()
        val a = stamps[i]
        for (j in 0 until i) {
            val b = stamps[j]
            val distanceSquared = toroidalDistanceSquared(a.x, a.y, b)
            val desired = a.orbit.intendedRadius + b.orbit.intendedRadius
            val fullClearance = desired + STAMP_GAP
            if (distanceSquared < fullClearance * fullClearance) {
                val ratio = ((sqrt(distanceSquared) - STAMP_GAP) / desired).coerceAtLeast(0.0)
                a.orbit.radius = min(a.orbit.radius, a.orbit.intendedRadius * ratio)
                b.orbit.radius = min(b.orbit.radius, b.orbit.intendedRadius * ratio)
            }
        }
    }

    // A neighbour may itself have shrunk against a third lead. Reclaim that slack
    // without moving anchors or exceeding the requested size. D4 copies grow together.
    for (first in stamps.indices step stampsPerOrbit) {
        val orbit = stamps[first].orbit
        if (orbit.radius >= orbit.intendedRadius) continue
        coroutineContext.ensureActive()
        var fit = orbit.intendedRadius
        val a = stamps[first]
        for (j in stamps.indices) {
            if (j == first) continue
            val b = stamps[j]
            val sameOrbit = b.orbit === orbit
            val occupied = if (sameOrbit) fit else b.orbit.radius
            val limit = fit + occupied + STAMP_GAP
            val d2 = toroidalDistanceSquared(a.x, a.y, b)
            if (d2 < limit * limit) {
                val free = sqrt(d2) - STAMP_GAP
                fit = if (sameOrbit) free / 2 else free - occupied
                if (fit <= orbit.radius) break
            }
        }
        orbit.radius = fit.coerceAtLeast(orbit.radius)
    }

    if (fillers == null || fillerTarget == 0) return stamps
    val firstFiller = stamps.size
    val fillerRandom = Random(pattern.seed.toLong() xor 0x46494C4CL)
    val automaticLeads = leadIndices.filter { pattern.icons[it].scale == null }
    val mediumPool = if (automaticLeads.isEmpty()) null else WeightedIcons(pattern, automaticLeads)
    val mediumRandom = mediumPool?.let { Random(pattern.seed.toLong() xor 0x4D49444CL) }
    val mediumRadius = 0.23 * c * (pattern.size / 100.0).pow(0.65)
    val mediumLimit = 2 * n * n / (3 * stampsPerOrbit)
    val gridSide = if (symmetric) FILLER_GRID / 2 else FILLER_GRID
    val step = PERIOD.toDouble() / FILLER_GRID
    val queue =
        PriorityQueue<FillerCandidate> { left, right ->
            val byClearance = right.clearance.compareTo(left.clearance)
            if (byClearance == 0) left.tie.compareTo(right.tie) else byClearance
        }

    fun clearance(
        x: Float,
        y: Float,
        upperBound: Double,
        minimum: Double,
        from: Int,
    ): Double {
        var radius = upperBound
        for (i in from until stamps.size) {
            val other = stamps[i]
            val distanceSquared = toroidalDistanceSquared(x, y, other)
            val occupied = other.orbit.radius + FILLER_GAP
            val fullClearance = radius + occupied
            if (distanceSquared < fullClearance * fullClearance) {
                radius = sqrt(distanceSquared) - occupied
                if (radius < minimum) break
            }
        }
        return radius
    }

    fun fillerAngle(x: Double): Double =
        when (mode) {
            WallpaperPatternLayout.DIAGONAL -> 15.0
            WallpaperPatternLayout.WAVES -> waveAngle(TURN * (x / c - 0.5) / n)
            else -> 0.0
        }

    var index = 0
    for (i in 0 until gridSide) {
        coroutineContext.ensureActive()
        for (j in 0 until (if (symmetric) i + 1 else gridSide)) {
            var x = step * (i + 0.1 + 0.8 * fillerRandom.nextDouble())
            var y = step * (j + 0.1 + 0.8 * fillerRandom.nextDouble())
            if (symmetric && y > x) {
                val swap = x
                x = y
                y = swap
            }
            val cx = wrapped(if (symmetric) center + x else x)
            val cy = wrapped(if (symmetric) center + y else y)
            val icon = fillers.choose(fillerRandom)
            val rotation = angle(fillerRandom, icon, fillerAngle(x))
            val automatic = pattern.icons[icon].scale == null
            val sample = fillerRandom.nextDouble()
            val unit = c * sizes[icon]
            // Auto picks a size from the space actually available: don't spend a
            // large hole on a tiny random accent. Retain some variety in open space.
            val factor = if (automatic) 0.26 + 0.07 * sample else 0.20
            val intended = min(MAX_FILLER_RADIUS, unit * factor) * inkFractions[icon]
            val minimum = min(6.0, if (automatic) unit * 0.10 * inkFractions[icon] * 0.67 else intended * 0.67)
            val tie = (fillerRandom.nextInt().toLong() shl 32) + index++
            val self = fillerSelfClearance(cx, cy, symmetric)
            if (self < minimum) continue
            val radius = clearance(cx, cy, self, minimum, 0)
            if (radius >= minimum) queue.add(FillerCandidate(cx, cy, icon, rotation, intended, minimum, tie, radius, stamps.size))
        }
    }

    var added = 0
    var mediumAdded = 0
    var attempts = 0
    while (added < fillerTarget) {
        if (attempts++ % 16 == 0) coroutineContext.ensureActive()
        val candidate = queue.poll() ?: break
        val radius = clearance(candidate.x, candidate.y, candidate.clearance, candidate.minimumRadius, candidate.checkedStamps)
        if (radius < candidate.minimumRadius) continue
        if (radius < candidate.clearance) {
            candidate.clearance = radius
            candidate.checkedStamps = stamps.size
            queue.add(candidate)
            continue
        }
        val orbit =
            if (mediumPool != null && mediumRandom != null && mediumAdded < mediumLimit && radius >= mediumRadius * 0.8) {
                val icon = mediumPool.choose(mediumRandom)
                val rotation = angle(mediumRandom, icon, fillerAngle(candidate.x.toDouble()))
                val intended = mediumRadius * (1 + 0.75 * mediumRandom.nextDouble()) * inkFractions[icon]
                mediumAdded++
                Orbit(icon, rotation, intended, min(intended, radius))
            } else {
                val intended = candidate.intendedRadius
                Orbit(candidate.icon, candidate.rotation, intended, min(intended, radius))
            }
        var x = candidate.x
        var y = candidate.y
        // One bounded sub-cell search only for an accepted, undersized stamp.
        // Earlier stamps stay fixed during packing; the jittered grid need not hit the exact gap center.
        if (orbit.radius < orbit.intendedRadius) {
            for (direction in 0..3) {
                val offset = if (direction % 2 == 0) step / 3 else -step / 3
                val cx = wrapped(candidate.x + if (direction < 2) offset else 0.0)
                val cy = wrapped(candidate.y + if (direction >= 2) offset else 0.0)
                val upper = min(orbit.intendedRadius, fillerSelfClearance(cx, cy, symmetric))
                if (upper <= orbit.radius) continue
                val fit = clearance(cx, cy, upper, orbit.radius, 0)
                if (fit > orbit.radius) {
                    x = cx
                    y = cy
                    orbit.radius = fit
                    if (fit == orbit.intendedRadius) break
                }
            }
        }
        if (symmetric) {
            mirrorOrbit(x - center, y - center, orbit, stamps)
        } else {
            stamps += Stamp(x, y, orbit.angle, false, orbit)
        }
        added++
    }
    refineFillers(stamps, firstFiller, stampsPerOrbit, glyphs)
    return stamps
}
