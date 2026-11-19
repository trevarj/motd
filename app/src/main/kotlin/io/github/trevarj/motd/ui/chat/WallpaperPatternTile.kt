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
                    val bitmap = Bitmap.createBitmap(key.edge, key.edge, Bitmap.Config.ARGB_8888)
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
            val glyph = vectors[icon]
            val radius = stamp.orbit.radius.toFloat() + WRAP_SLOP
            val scale = (stamp.orbit.radius / glyph.inkRadius).toFloat()
            val xWrap = wrapOffset(stamp.x, radius)
            val yWrap = wrapOffset(stamp.y, radius)
            drawStamp(canvas, glyph, inks[icon], paint, stamp, scale, 0f, 0f)
            if (xWrap != 0f) drawStamp(canvas, glyph, inks[icon], paint, stamp, scale, xWrap, 0f)
            if (yWrap != 0f) drawStamp(canvas, glyph, inks[icon], paint, stamp, scale, 0f, yWrap)
            if (xWrap != 0f && yWrap != 0f) drawStamp(canvas, glyph, inks[icon], paint, stamp, scale, xWrap, yWrap)
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
    val save = canvas.save()
    canvas.translate(stamp.x + dx, stamp.y + dy)
    canvas.rotate(stamp.rotation)
    canvas.scale(scale, if (stamp.mirror) -scale else scale)
    canvas.translate(-glyph.width / 2f, -glyph.height / 2f)
    glyph.draw(canvas, ink, paint)
    canvas.restoreToCount(save)
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
    other: Stamp,
): Double {
    val dx = abs(x - other.x).toDouble()
    val dy = abs(y - other.y).toDouble()
    val wrappedX = min(dx, PERIOD - dx)
    val wrappedY = min(dy, PERIOD - dy)
    return wrappedX * wrappedX + wrappedY * wrappedY
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

    fun wrapped(value: Double): Float {
        val coordinate = value.toFloat()
        return when {
            coordinate < 0f -> (PERIOD + coordinate).let { if (it == PERIOD) 0f else it }
            coordinate >= PERIOD -> coordinate - PERIOD
            else -> coordinate
        }
    }

    fun mirrorOrbit(
        x: Double,
        y: Double,
        orbit: Orbit,
    ) {
        for (mirror in 0..1) {
            var dx = x
            var dy = if (mirror == 0) y else -y
            val rotation = if (mirror == 0) orbit.angle else -orbit.angle
            for (quarter in 0..3) {
                stamps += Stamp(wrapped(center + dx), wrapped(center + dy), rotation + 90f * quarter, mirror != 0, orbit)
                val nextX = -dy
                dy = dx
                dx = nextX
            }
        }
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
        if (symmetric) mirrorOrbit(x, y, orbit) else stamps += Stamp(wrapped(x), wrapped(y), rotation, false, orbit)
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

    if (fillers == null || fillerTarget == 0) return stamps
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

    // ponytail: one sample per cell; prefer more candidates only if small gaps need finer placement.
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
            val sample = fillerRandom.nextDouble()
            val radiusFactor = if (pattern.icons[icon].scale == null) 0.10 + 0.23 * sample else 0.20
            val intended = min(MAX_FILLER_RADIUS, c * radiusFactor * sizes[icon]) * inkFractions[icon]
            val minimum = min(6.0, intended * 0.67)
            val tie = (fillerRandom.nextInt().toLong() shl 32) + index++
            val selfDistance = if (symmetric) minOf(2 * y, PERIOD - 2 * x, sqrt(2.0) * (x - y)) else PERIOD.toDouble()
            val selfClearance = (selfDistance - FILLER_GAP) / 2
            if (selfClearance < minimum) continue
            val radius = clearance(cx, cy, selfClearance, minimum, 0)
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
        if (symmetric) {
            mirrorOrbit(candidate.x - center, candidate.y - center, orbit)
        } else {
            stamps += Stamp(candidate.x, candidate.y, orbit.angle, false, orbit)
        }
        added++
    }
    return stamps
}
