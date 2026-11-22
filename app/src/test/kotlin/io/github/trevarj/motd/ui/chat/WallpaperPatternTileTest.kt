package io.github.trevarj.motd.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.data.prefs.AppearanceConfig
import io.github.trevarj.motd.data.prefs.ChatWallpaperPreset
import io.github.trevarj.motd.data.prefs.ImportedWallpaperIcon
import io.github.trevarj.motd.data.prefs.WallpaperCatalog
import io.github.trevarj.motd.data.prefs.WallpaperPattern
import io.github.trevarj.motd.data.prefs.WallpaperPatternIcon
import io.github.trevarj.motd.data.prefs.WallpaperPatternLayout
import io.github.trevarj.motd.data.prefs.WallpaperPatternRef
import io.github.trevarj.motd.data.prefs.WallpaperPatternRefKind
import io.github.trevarj.motd.data.prefs.WallpaperRenderSpec
import io.github.trevarj.motd.data.prefs.WallpaperSelection
import io.github.trevarj.motd.data.prefs.WallpaperStarters
import io.github.trevarj.motd.data.prefs.WallpaperTexture
import io.github.trevarj.motd.data.prefs.resolveWallpaper
import io.github.trevarj.motd.ui.theme.motdThemeScheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import android.graphics.Color as AndroidColor

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WallpaperPatternTileTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dark = Color(0xFF132735)
    private val magenta = 0xFFC02372.toInt()
    private val green = 0xFF15C389.toInt()
    private val imports =
        listOf(
            outline("M12 11V13 M2 5H8V17H21 M17 3l5 3-5 4"),
            outline("M11 12H13 M4 3V9L19 19 M3 19H10 M19 4L22 8"),
        )
    private val fixture =
        WallpaperRenderSpec(
            WallpaperSelection(ChatWallpaperPreset.PATTERN),
            WallpaperPattern(
                seed = 0x4D4F5444,
                size = 150,
                density = 100,
                rotation = 25,
                variation = 47,
                background = dark.toArgb(),
                ink = magenta,
                icons = listOf(WallpaperPatternIcon(imports[0].id, 150, 15, 2), WallpaperPatternIcon(imports[1].id, 150, -35, 5, green)),
            ),
            imports,
        )

    @Test fun allLayoutsRepeatAcrossEdgesAndCorners() =
        runBlocking {
            val pattern = requireNotNull(fixture.pattern)
            val (lead, filler) = pattern.icons
            val twoRoles = pattern.copy(icons = listOf(lead, filler.copy(filler = true)))
            // Exact-pixel and fractional-pixel periods, both at fractional display densities.
            for (layout in WallpaperPatternLayout.entries) {
                for (density in listOf(1.125f, 1.341f)) {
                    val recipe = twoRoles.copy(layout = layout)
                    val period = 128f * density
                    val key = wallpaperPatternTileKey(recipe, Color.White, period)
                    val tile = requireNotNull(renderWallpaperPatternTile(context, key, fixture.imports))
                    val side = ceil(period * 3).toInt()
                    val tiled = blank(side, side, dark.toArgb())
                    val direct = blank(side, side, dark.toArgb())
                    val dx = 11f
                    val dy = 23f
                    val actualCanvas = Canvas(tiled)
                    actualCanvas.translate(-dx, -dy)
                    actualCanvas.drawRect(0f, 0f, side + dx, side + dy, wallpaperPatternPaint(tile, period, 100))
                    // Rasterize the vectors directly on the tile's pixel grid, then filter the
                    // whole periodic quilt once. Re-rasterizing vector edges at fractional device
                    // coordinates is not equivalent to filtering an already antialiased texture.
                    val quilt = blank(key.edge * 5, key.edge * 5)
                    val quiltCanvas = Canvas(quilt)
                    for (j in 0..4) {
                        for (i in 0..4) {
                            val saved = quiltCanvas.save()
                            quiltCanvas.translate((i * key.edge).toFloat(), (j * key.edge).toFloat())
                            drawWallpaperPatternPeriod(context, key, fixture.imports, quiltCanvas, key.edge.toFloat())
                            quiltCanvas.restoreToCount(saved)
                        }
                    }
                    // Leave a whole guard period before the origin so no sample sees the quilt's outer edge.
                    val bounds = RectF(-period - dx, -period - dy, 4 * period - dx, 4 * period - dy)
                    Canvas(direct).drawBitmap(quilt, null, bounds, Paint(Paint.FILTER_BITMAP_FLAG))
                    quilt.recycle()
                    val actual = tiled.pixels()
                    val expected = direct.pixels()
                    assertTrue("$layout/$density lost ink", actual.count { it != dark.toArgb() } > actual.size / 100)
                    val error = meanRgbDelta(actual, expected)
                    assertTrue("$layout/$density repeat error $error", error < 0.35)
                    val outliers = actual.indices.count { rgbDelta(actual[it], expected[it]) > 12 }
                    assertTrue("$layout/$density repeated $outliers pixels incorrectly", outliers < actual.size / 1000)
                    tiled.recycle()
                    direct.recycle()
                }
            }

            // Scattered's interleaved grid includes (0,0) and both edges; staggered rows include the x seam.
            val seamPeriod = 576f
            for (layout in listOf(WallpaperPatternLayout.SCATTERED, WallpaperPatternLayout.DIAGONAL)) {
                val tile = requireNotNull(renderWallpaperPatternTile(context, wallpaperPatternTileKey(pattern.copy(layout = layout), Color.White, seamPeriod), imports))
                val seam = seamPeriod.toInt()
                val repeated = blank(2 * seam, 2 * seam)
                Canvas(repeated).drawRect(0f, 0f, 2 * seamPeriod, 2 * seamPeriod, wallpaperPatternPaint(tile, seamPeriod, 100))

                fun hasInk(
                    x: Int,
                    y: Int,
                ) = AndroidColor.alpha(repeated.getPixel(x, y)) > 0
                assertTrue("$layout missed the vertical joint", (4 until seam - 4).any { y -> hasInk(seam - 1, y) && hasInk(seam, y) })
                if (layout == WallpaperPatternLayout.SCATTERED) {
                    assertTrue("Scattered missed the horizontal joint", (4 until seam - 4).any { x -> hasInk(x, seam - 1) && hasInk(x, seam) })
                    for (y in seam - 1..seam) for (x in seam - 1..seam) assertTrue("Missing corner ink at $x,$y", hasInk(x, y))
                }
                repeated.recycle()
            }

            val period = 512f
            val key = wallpaperPatternTileKey(twoRoles.copy(layout = WallpaperPatternLayout.KALEIDOSCOPE), Color.White, period)
            val symmetricalTile = requireNotNull(renderWallpaperPatternTile(context, key, imports))
            val image = blank(key.edge, key.edge, dark.toArgb())
            Canvas(image).drawRect(0f, 0f, period, period, wallpaperPatternPaint(symmetricalTile, period, 100))
            val pixels = image.pixels()
            val n = key.edge
            var offAxisMatches = 0
            var reflectionError = 0L
            for (y in 0 until n / 2) {
                for (x in y + 5 until n / 2 - 5) {
                    val original = pixels[y * n + x]
                    val reflectedX = pixels[y * n + n - 1 - x]
                    val reflectedY = pixels[(n - 1 - y) * n + x]
                    val reflectedDiagonal = pixels[x * n + y]
                    val delta = rgbDelta(original, reflectedX) + rgbDelta(original, reflectedY) + rgbDelta(original, reflectedDiagonal)
                    reflectionError += delta
                    if ((original == magenta || original == green) && delta <= 6) offAxisMatches++
                }
            }
            assertTrue("Asymmetric glyphs did not mirror at off-axis points", offAxisMatches > 200)
            assertTrue("Kaleidoscope reflection error $reflectionError", reflectionError < n * n)
            image.recycle()

            val originalKey = wallpaperPatternTileKey(pattern, Color.Black, period)
            val original = requireNotNull(renderWallpaperPatternTile(context, originalKey, imports))
            for (changed in listOf(pattern.copy(seed = pattern.seed + 1), pattern.copy(layout = WallpaperPatternLayout.ROWS), pattern.copy(rotation = 55))) {
                val diff = requireNotNull(renderWallpaperPatternTile(context, wallpaperPatternTileKey(changed, Color.Black, period), imports))
                assertFalse("Geometry changes must affect pixels", original.sameAs(diff))
            }
        }

    @Test fun densityAddsFillersWithoutMovingOrShrinkingLeads() =
        runBlocking {
            val lead = WallpaperPatternIcon(imports[0].id, scale = 175, rotation = 0, ink = magenta)
            val filler = WallpaperPatternIcon(imports[1].id, ink = green, filler = true)
            val recipe = WallpaperPattern(seed = 73, density = 0, icons = listOf(lead, filler))

            fun Int.isLeadInk() = AndroidColor.alpha(this) != 0 && AndroidColor.red(this) > AndroidColor.green(this)

            val sparse = renderFixture(recipe).pixels()
            val medium = renderFixture(recipe.copy(density = 50)).pixels()
            val dense = renderFixture(recipe.copy(density = 100)).pixels()
            val leadPixels = sparse.count { it.isLeadInk() }
            assertTrue("Lead motifs must be visible", leadPixels > 1000)
            val changedLeadPixels = sparse.indices.count { (sparse[it].isLeadInk() || dense[it].isLeadInk()) && sparse[it] != dense[it] }
            assertEquals("Filler may not cover or resize lead artwork", 0, changedLeadPixels)
            assertTrue("Minimum density must leave all filler ink out", sparse.none { AndroidColor.green(it) > AndroidColor.red(it) })
            val mediumFillerInk = medium.count { AndroidColor.green(it) > AndroidColor.red(it) }
            assertTrue("Medium density must add visible filler ink", mediumFillerInk > 500)
            assertTrue("Maximum density must close more gaps", dense.count { AndroidColor.green(it) > AndroidColor.red(it) } > mediumFillerInk)
            val changedLeadPixelsAtMedium = sparse.indices.count { (sparse[it].isLeadInk() || medium[it].isLeadInk()) && sparse[it] != medium[it] }
            assertEquals("Refinement must also preserve lead artwork at medium density", 0, changedLeadPixelsAtMedium)
            val fillersOnly = renderFixture(recipe.copy(icons = listOf(filler))).pixels()
            assertTrue("A mix of fillers alone still draws at minimum density", fillersOnly.any { it != 0 })

            val automaticLead = recipe.copy(icons = listOf(lead.copy(scale = null), filler))
            val automaticSparse = renderFixture(automaticLead).pixels()
            val automaticDense = renderFixture(automaticLead.copy(density = 100)).pixels()
            assertTrue("Larger gaps should fit medium copies of Auto leads", automaticDense.count { it.isLeadInk() } > automaticSparse.count { it.isLeadInk() })
            assertTrue("Gap filling must preserve the original Auto leads", automaticSparse.indices.all { automaticSparse[it] == 0 || automaticSparse[it] == automaticDense[it] })
        }

    @Test fun automaticFillersUseOpenSpaceWithoutOverpainting() =
        runBlocking {
            val filler = WallpaperPatternIcon(imports[1].id, filler = true)
            // Same seed, 72 filler stamps and 512dp tile. Alpha mass measures actual
            // vector coverage, not the area of the disks used for collision checks.
            val recipe = WallpaperPattern(seed = 73, density = 0, icons = listOf(filler))
            val auto = renderFixture(recipe).pixels().sumOf(AndroidColor::alpha)
            val fixed = renderFixture(recipe.copy(icons = listOf(filler.copy(scale = 100)))).pixels().sumOf(AndroidColor::alpha)
            assertTrue("Auto underfilled open gaps: $auto vs $fixed", auto > fixed * 1.85)

            // Each imported motif is a single drawPath. Two half-opacity stamps
            // on the same sample exceed alpha 128, even if they're the same icon.
            val mixed = listOf(WallpaperPatternIcon(imports[0].id), filler)
            for (layout in WallpaperPatternLayout.entries.filterNot { it == WallpaperPatternLayout.RINGS }) {
                val dense = recipe.copy(layout = layout, size = 175, density = 100, icons = mixed)
                for (edge in listOf(512f, 1536f)) {
                    val key = wallpaperPatternTileKey(dense, Color(0x80FFFFFF), edge)
                    val tile = requireNotNull(renderWallpaperPatternTile(context, key, imports))
                    assertEquals("Overlapping stamps or wrapped copies in $layout at $edge px", 128, tile.pixels().maxOf(AndroidColor::alpha))
                }
            }
        }

    @Test fun refinementFillsTheVisibleGapInDeepSpaceAtMediumDensity() =
        runBlocking {
            val recipe = checkNotNull(WallpaperStarters.find("deep-space")).pattern.copy(density = 50)
            val key = wallpaperPatternTileKey(recipe, Color.White, 512f)
            val tile = requireNotNull(renderWallpaperPatternTile(context, key, emptyList()))
            val pixels = tile.pixels()
            val nearestInk =
                pixels.indices.minOf { i ->
                    val dx = i % tile.width - 305
                    val dy = i / tile.width - 181
                    if (AndroidColor.alpha(pixels[i]) > 31) dx * dx + dy * dy else Int.MAX_VALUE
                }
            assertTrue("The original open gap was left unfilled: squared distance $nearestInk", nearestInk <= 17 * 17)
        }

    @Test fun largerScaleAddsVisibleInkAndExplicitAnglesIgnoreRandomRotation() =
        runBlocking {
            val icon = WallpaperPatternIcon(imports[0].id, scale = 25, rotation = 0)
            val recipe = WallpaperPattern(layout = WallpaperPatternLayout.ROWS, seed = 42, density = 0, variation = 0, icons = listOf(icon))

            val small = renderFixture(recipe)
            val largeIcon = icon.copy(scale = 250)
            val large = recipe.copy(icons = listOf(largeIcon))
            val upright = renderFixture(large)
            val smallInk = small.pixels().sumOf(AndroidColor::alpha)
            val largeInk = upright.pixels().sumOf(AndroidColor::alpha)
            assertTrue("At maximum scale, ink coverage $largeInk should exceed four times $smallInk", largeInk > 4 * smallInk)
            val fullVariation = large.copy(variation = 180)
            assertTrue("A fixed zero angle must stay upright even at full variation", upright.sameAs(renderFixture(fullVariation)))
            val auto = fullVariation.copy(icons = listOf(largeIcon.copy(rotation = null)))
            assertFalse("Automatic rotation must vary the asymmetric glyph", upright.sameAs(renderFixture(auto)))
        }

    @Test fun fourHundredPercentMakesIndividualMotifsLargerThanTheOldMaximum() =
        runBlocking {
            val stroke = outline("M3 12H21")
            val icon = WallpaperPatternIcon(stroke.id, rotation = 0)
            val recipe = WallpaperPattern(layout = WallpaperPatternLayout.ROWS, seed = 42, density = 0, variation = 0, icons = listOf(icon))

            suspend fun longestStroke(pattern: WallpaperPattern): Int {
                val key = wallpaperPatternTileKey(pattern, Color.White, 512f)
                val bitmap = requireNotNull(renderWallpaperPatternTile(context, key, listOf(stroke)))
                val pixels = bitmap.pixels()
                var longest = 0
                for (y in 0 until bitmap.height) {
                    var run = 0
                    for (x in 0 until bitmap.width) {
                        run = if (AndroidColor.alpha(pixels[y * bitmap.width + x]) > 127) run + 1 else 0
                        longest = maxOf(longest, run)
                    }
                }
                return longest
            }

            for (filler in listOf(false, true)) {
                val fixed = icon.copy(filler = filler)
                val previous = longestStroke(recipe.copy(icons = listOf(fixed.copy(scale = 250))))
                val larger = longestStroke(recipe.copy(icons = listOf(fixed.copy(scale = 400))))
                assertTrue("400% fixed scale must make larger motifs (filler=$filler): $previous → $larger", larger > previous * 1.15)
            }
            val previous = longestStroke(recipe.copy(size = 250))
            val larger = longestStroke(recipe.copy(size = 400))
            assertTrue("400% overall scale must make larger motifs: $previous → $larger", larger > previous * 1.15)
        }

    @Test fun paintUsesExactFixedColorsAndDoesNotBakeIntensityOrBackground() =
        runBlocking {
            val recipe = requireNotNull(fixture.pattern).copy(layout = WallpaperPatternLayout.ROWS, density = 0, rotation = 0, variation = 0)
            val period = 512f
            val key = wallpaperPatternTileKey(recipe, Color.White, period)
            val textured = recipe.copy(intensity = 73, background = AndroidColor.WHITE, texture = WallpaperTexture.SPECKS)
            assertEquals(key, wallpaperPatternTileKey(textured, Color.Black, period))
            val recolored = wallpaperPatternTileKey(recipe.copy(icons = recipe.icons.map { it.copy(ink = AndroidColor.RED) }), Color.Black, period)
            assertEquals(key.geometry, recolored.geometry)
            assertFalse(key == recolored)
            assertEquals(1536, wallpaperPatternTileKey(recipe, Color.Black, 1587.3f).edge)
            assertNull(renderWallpaperPatternTile(context, key, emptyList()))
            val tile = requireNotNull(renderWallpaperPatternTile(context, key, imports))
            val full = tile.pixels()
            val base = requireNotNull(recipe.background)
            val image = blank(key.edge, key.edge, base)
            val paint = wallpaperPatternPaint(tile, period, 73)
            Canvas(image).drawRect(0f, 0f, period, period, paint)
            assertEquals(186, paint.alpha)
            for (ink in listOf(magenta, green, AndroidColor.TRANSPARENT)) {
                val point = full.indexOf(ink)
                assertTrue("Missing exact ink $ink", point >= 0)
                val expected = if (ink == AndroidColor.TRANSPARENT) base else blend(ink, base, paint.alpha)
                assertTrue("Fixed background/pigment or uniform intensity changed", rgbDelta(expected, image.getPixel(point % key.edge, point / key.edge)) <= 3)
            }
            proofDirectory()?.let { image.saveTo(File(it, "ink-intensity.png")) }
            image.recycle()
        }

    @Test fun bundledTexturesRepeatAndStayWithinTheContrastBudget() =
        runBlocking {
            assertNull(loadWallpaperTexture(context, WallpaperTexture.NONE))
            val period = 128
            val proof = blank(2 * period, 2 * period)
            val canvas = Canvas(proof)
            for (texture in WallpaperTexture.entries) {
                if (texture == WallpaperTexture.NONE) continue
                val bitmap = requireNotNull(loadWallpaperTexture(context, texture))
                val alpha = bitmap.pixels().maxOf(AndroidColor::alpha)
                assertTrue("$texture exceeds the contrast budget or has no ink: $alpha", alpha in 1..WALLPAPER_TEXTURE_MAX_ALPHA)
                val paint = wallpaperPatternPaint(bitmap, period.toFloat(), 100)
                for (base in listOf(AndroidColor.WHITE, AndroidColor.BLACK)) {
                    proof.eraseColor(base)
                    val saved = canvas.save()
                    canvas.translate(-5f, -31f)
                    canvas.drawRect(0f, 0f, 2f * period + 5f, 2f * period + 31f, paint)
                    canvas.restoreToCount(saved)
                    val pixels = proof.pixels()
                    assertTrue("$texture isn't visible over $base", pixels.count { it != base } > pixels.size / 200)
                    for (y in 0 until period) {
                        for (x in 0 until period) {
                            val a = y * proof.width + x
                            assertEquals(pixels[a], pixels[a + period])
                            assertEquals(pixels[a], pixels[a + period * proof.width])
                            assertEquals(pixels[a], pixels[a + period * proof.width + period])
                        }
                    }
                }
            }
            proof.recycle()
        }

    @Test fun allCatalogMotifsAndStarterProofsRender() =
        runBlocking {
            val iconBitmap = blank(96, 96)
            val iconCanvas = Canvas(iconBitmap)
            val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            for (entry in WallpaperCatalog.entries(context)) {
                iconBitmap.eraseColor(AndroidColor.TRANSPARENT)
                val glyph = loadWallpaperGlyph(context, entry.id, null)
                val saved = iconCanvas.save()
                val scale = 84f / maxOf(glyph.width, glyph.height)
                iconCanvas.translate((96f - glyph.width * scale) / 2, (96f - glyph.height * scale) / 2)
                iconCanvas.scale(scale, scale)
                glyph.draw(iconCanvas, AndroidColor.WHITE, glyphPaint)
                iconCanvas.restoreToCount(saved)
                assertTrue("${entry.id} did not draw ink", iconBitmap.pixels().any { AndroidColor.alpha(it) > 0 })
            }
            iconBitmap.recycle()

            val directory = proofDirectory()
            val period = 512f
            val proofSize = (period * 3).toInt()
            val cardWidth = 360
            val cardHeight = 640
            val captionHeight = 28
            val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 18f }
            for (darkTheme in listOf(false, true)) {
                val name = if (darkTheme) "dark" else "light"
                val scheme = motdThemeScheme(darkTheme)
                val back =
                    gradientColors(
                        scheme.background,
                        scheme.primary,
                        scheme.secondary,
                        scheme.tertiary,
                        listOf(scheme.onBackground, scheme.onSurface, scheme.onSurfaceVariant),
                    ).map(Color::toArgb).toIntArray()
                val sheet = directory?.let { blank(cardWidth * 3, (cardHeight + captionHeight) * 3, scheme.background.toArgb()) }
                val sheetCanvas = sheet?.let(::Canvas)
                labelPaint.color = scheme.onBackground.toArgb()
                for ((index, starter) in WallpaperStarters.entries.withIndex()) {
                    val ref = WallpaperPatternRef(WallpaperPatternRefKind.STARTER, starter.id)
                    val spec = resolveWallpaper(AppearanceConfig(wallpaper = WallpaperSelection(ChatWallpaperPreset.PATTERN, patternRef = ref)))
                    assertEquals(starter.pattern, spec.pattern)
                    val key = wallpaperPatternTileKey(starter.pattern, scheme.onSurfaceVariant, period)
                    val bitmap = requireNotNull(renderWallpaperPatternTile(context, key, spec.imports))
                    val pixels = bitmap.pixels()
                    val covered = pixels.count { AndroidColor.alpha(it) > 31 }
                    assertTrue("${starter.id} lacked visible strokes", covered > pixels.size / 150)
                    if (starter.id == "motd") {
                        val inkCoverage = pixels.sumOf { AndroidColor.alpha(it).toLong() } / (255.0 * pixels.size)
                        assertTrue("MOTD's default tile must use at least 20.5% ink: $inkCoverage", inkCoverage >= 0.205)
                    }
                    assertTrue("${starter.id} covered the entire backdrop", pixels.count { it == AndroidColor.TRANSPARENT } > pixels.size / 2)
                    val ink = wallpaperPatternPaint(bitmap, period, starter.pattern.intensity)
                    if (sheetCanvas != null) {
                        val left = (index % 3 * cardWidth).toFloat()
                        val top = (index / 3 * (cardHeight + captionHeight)).toFloat()
                        sheetCanvas.drawText(context.getString(starter.label), left + 8f, top + 21f, labelPaint)
                        val save = sheetCanvas.save()
                        sheetCanvas.translate(left, top + captionHeight)
                        drawProof(sheetCanvas, cardWidth, cardHeight, back, ink, 0f, 0f)
                        sheetCanvas.restoreToCount(save)
                    }
                    if (directory != null) {
                        val proof = blank(proofSize, proofSize)
                        drawProof(Canvas(proof), proofSize, proofSize, back, ink, 0f, 0f)
                        proof.saveTo(File(directory, "${starter.id}-$name.png"))
                        drawProof(Canvas(proof), proofSize, proofSize, back, ink, 77f, 119f)
                        proof.saveTo(File(directory, "${starter.id}-$name-offset.png"))
                        proof.recycle()
                    }
                }
                if (directory != null && sheet != null) {
                    sheet.saveTo(File(directory, "starters-phone-$name.png"))
                    sheet.recycle()
                }
            }
        }

    private suspend fun renderFixture(pattern: WallpaperPattern): Bitmap {
        val key = wallpaperPatternTileKey(pattern, Color.White, 512f)
        return requireNotNull(renderWallpaperPatternTile(context, key, imports))
    }

    private fun blank(
        width: Int,
        height: Int,
        argb: Int = AndroidColor.TRANSPARENT,
    ): Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { if (argb != AndroidColor.TRANSPARENT) eraseColor(argb) }

    private fun Bitmap.pixels(): IntArray = IntArray(width * height).also { getPixels(it, 0, width, 0, 0, width, height) }

    private fun proofDirectory(): File? =
        System.getenv("MOTD_WORKSHOP_PROOF_DIR")?.takeIf(String::isNotBlank)?.let(::File)?.also {
            check(it.isDirectory || it.mkdirs())
        }

    private fun Bitmap.saveTo(file: File) {
        file.outputStream().use { check(compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    private fun drawProof(
        canvas: Canvas,
        width: Int,
        height: Int,
        gradient: IntArray,
        ink: Paint,
        offsetX: Float,
        offsetY: Float,
    ) {
        val backdrop =
            Paint().apply {
                shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(), gradient, null, Shader.TileMode.CLAMP)
            }
        val save = canvas.save()
        canvas.clipRect(0, 0, width, height)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backdrop)
        canvas.translate(-offsetX, -offsetY)
        canvas.drawRect(0f, 0f, width + offsetX, height + offsetY, ink)
        canvas.restoreToCount(save)
    }

    private fun rgbDelta(
        first: Int,
        second: Int,
    ): Int =
        abs(AndroidColor.red(first) - AndroidColor.red(second)) +
            abs(AndroidColor.green(first) - AndroidColor.green(second)) +
            abs(AndroidColor.blue(first) - AndroidColor.blue(second))

    private fun meanRgbDelta(
        first: IntArray,
        second: IntArray,
    ): Double {
        var sum = 0L
        for (i in first.indices) sum += rgbDelta(first[i], second[i])
        return sum / (first.size * 3.0)
    }

    private fun blend(
        ink: Int,
        base: Int,
        alpha: Int,
    ): Int {
        fun component(
            source: Int,
            destination: Int,
        ) = (destination + (source - destination) * alpha / 255f).roundToInt()
        return AndroidColor.rgb(
            component(AndroidColor.red(ink), AndroidColor.red(base)),
            component(AndroidColor.green(ink), AndroidColor.green(base)),
            component(AndroidColor.blue(ink), AndroidColor.blue(base)),
        )
    }

    private fun outline(path: String): ImportedWallpaperIcon {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="black" stroke-width="4" stroke-linecap="round"><path d="$path"/></svg>"""
        val digest = MessageDigest.getInstance("SHA-256").digest(svg.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return ImportedWallpaperIcon("svg:$digest", "Asymmetric outline", svg)
    }
}
