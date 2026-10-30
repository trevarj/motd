package io.github.trevarj.motd.ui.chat

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.LruCache
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.trevarj.motd.data.prefs.ChatWallpaperPreset
import io.github.trevarj.motd.data.prefs.WallpaperSelection
import io.github.trevarj.motd.ui.theme.LocalLottieMotionEnabled
import io.github.trevarj.motd.ui.theme.LocalMotdDark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Duration
import java.time.LocalDate
import java.time.ZonedDateTime
import kotlin.random.Random

internal enum class CountrysideSeason { SPRING, SUMMER, AUTUMN, WINTER }

/** Japan/northern meteorological seasons, evaluated in the phone's local calendar. */
internal fun countrysideSeason(date: LocalDate): CountrysideSeason =
    when (date.monthValue) {
        3, 4, 5 -> CountrysideSeason.SPRING
        6, 7, 8 -> CountrysideSeason.SUMMER
        9, 10, 11 -> CountrysideSeason.AUTUMN
        else -> CountrysideSeason.WINTER
    }

internal val ChatWallpaperPreset.isScenic: Boolean
    get() =
        this in
            setOf(
                ChatWallpaperPreset.JAPANESE_COUNTRYSIDE,
                ChatWallpaperPreset.RAINY_CAFE,
                ChatWallpaperPreset.MOTD_ARCADE,
                ChatWallpaperPreset.MIDNIGHT_TERMINAL,
                ChatWallpaperPreset.CYBERPUNK_CANAL,
            )

internal data class ScenicKey(
    val preset: ChatWallpaperPreset,
    val season: CountrysideSeason?,
    val dark: Boolean,
    val loadEvents: Boolean,
)

internal data class ScenicPixels(
    val x: Int,
    val y: Int,
    val color: Int,
)

internal data class ScenicFrame(
    val durationMs: Long,
    val pixels: List<ScenicPixels>,
)

internal data class ScenicEvent(
    val frames: List<ScenicFrame>,
)

internal data class ScenicBundle(
    val bitmap: Bitmap,
    val initialDelayMs: LongRange,
    val quietIntervalMs: LongRange,
    val events: List<ScenicEvent>,
)

private object ScenicWallpaperCache : LruCache<ScenicKey, ScenicBundle>(10 * 1024) {
    private val mutex = Mutex()

    override fun sizeOf(
        key: ScenicKey,
        value: ScenicBundle,
    ): Int = (value.bitmap.allocationByteCount / 1024).coerceAtLeast(1)

    suspend fun getOrLoad(
        context: Context,
        key: ScenicKey,
    ): ScenicBundle {
        get(key)?.let { return it }
        return mutex.withLock {
            get(key) ?: loadScenicBundle(context.assets, key).also { put(key, it) }
        }
    }
}

private fun scenicStem(key: ScenicKey): String =
    when (key.preset) {
        ChatWallpaperPreset.JAPANESE_COUNTRYSIDE -> {
            "japanese-countryside-${key.season!!.name.lowercase()}-v3"
        }

        ChatWallpaperPreset.RAINY_CAFE -> {
            "rainy-cafe"
        }

        ChatWallpaperPreset.MOTD_ARCADE -> {
            "neon-arcade-v3"
        }

        ChatWallpaperPreset.MIDNIGHT_TERMINAL -> {
            "retro-computer-room-v3"
        }

        ChatWallpaperPreset.CYBERPUNK_CANAL -> {
            "cyberpunk-canal"
        }

        else -> {
            error("$key is not a scenic wallpaper")
        }
    }

private fun loadScenicBundle(
    assets: android.content.res.AssetManager,
    key: ScenicKey,
): ScenicBundle {
    val stem = scenicStem(key)
    val bitmap =
        assets.open("chat-wallpapers/scenic/$stem-${if (key.dark) "dark" else "light"}.png").use { input ->
            BitmapFactory.decodeStream(
                input,
                null,
                BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inScaled = false
                },
            ) ?: error("scenic wallpaper PNG could not be decoded")
        }
    require(bitmap.width == 256 && bitmap.height == 448) { "scenic wallpaper must remain 256 by 448" }
    if (!key.loadEvents) return ScenicBundle(bitmap, 0L..0L, 0L..0L, emptyList())
    val document =
        assets.open("chat-wallpapers/scenic/$stem-events.json").bufferedReader().use { JSONObject(it.readText()) }
    val events =
        document.getJSONArray("events").let { source ->
            List(source.length()) { eventIndex ->
                val event = source.getJSONObject(eventIndex)
                ScenicEvent(
                    List(event.getJSONArray("frames").length()) { frameIndex ->
                        val frame = event.getJSONArray("frames").getJSONObject(frameIndex)
                        val pixels = frame.getJSONArray(if (key.dark) "dark" else "light")
                        ScenicFrame(
                            durationMs = frame.getLong("durationMs"),
                            pixels =
                                List(pixels.length()) { pixelIndex ->
                                    val pixel = pixels.getJSONArray(pixelIndex)
                                    ScenicPixels(pixel.getInt(0), pixel.getInt(1), Color.parseColor(pixel.getString(2)))
                                },
                        )
                    },
                )
            }
        }

    fun range(name: String): LongRange {
        val values = document.getJSONArray(name)
        return values.getLong(0)..values.getLong(1)
    }
    return ScenicBundle(bitmap, range("initialDelayMs"), range("quietIntervalMs"), events)
}

private data class ScenicCalendar(
    val date: LocalDate,
    val resumed: Boolean,
    val revision: Long,
)

@Composable
private fun rememberScenicCalendar(): ScenicCalendar {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var date by remember { mutableStateOf(LocalDate.now()) }
    var resumed by remember { mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var revision by remember { mutableStateOf(0L) }
    DisposableEffect(context, lifecycleOwner) {
        fun refresh() {
            date = LocalDate.now()
            revision++
        }
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) = refresh()
            }
        val observer =
            object : DefaultLifecycleObserver {
                override fun onResume(owner: LifecycleOwner) {
                    resumed = true
                    refresh()
                }

                override fun onPause(owner: LifecycleOwner) {
                    resumed = false
                }
            }
        val filter =
            IntentFilter().apply {
                addAction(Intent.ACTION_DATE_CHANGED)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            context.unregisterReceiver(receiver)
        }
    }
    LaunchedEffect(resumed, date, revision) {
        if (!resumed) return@LaunchedEffect
        val now = ZonedDateTime.now()
        val untilNextDate = Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay(now.zone)).toMillis()
        delay(untilNextDate.coerceAtLeast(1L))
        date = LocalDate.now()
    }
    return ScenicCalendar(date, resumed, revision)
}

@Composable
internal fun ScenicWallpaperBackground(
    wallpaper: WallpaperSelection,
    modifier: Modifier = Modifier,
    animate: Boolean = true,
) {
    if (animate) {
        AnimatedScenicWallpaperBackground(wallpaper, modifier)
    } else {
        StaticScenicWallpaperBackground(wallpaper, modifier)
    }
}

@Composable
private fun StaticScenicWallpaperBackground(
    wallpaper: WallpaperSelection,
    modifier: Modifier,
) {
    val systemDark = isSystemInDarkTheme()
    val appDark = LocalMotdDark.current
    val date = LocalDate.now()
    val key =
        remember(wallpaper.preset, wallpaper.scenicFollowSystem, systemDark, appDark, date) {
            scenicKey(wallpaper, date, systemDark, appDark, loadEvents = false)
        }
    ScenicWallpaperContent(wallpaper, modifier, key, motionEnabled = false)
}

@Composable
private fun AnimatedScenicWallpaperBackground(
    wallpaper: WallpaperSelection,
    modifier: Modifier,
) {
    val calendar = rememberScenicCalendar()
    val systemDark = isSystemInDarkTheme()
    val appDark = LocalMotdDark.current
    val key =
        remember(wallpaper.preset, wallpaper.scenicFollowSystem, systemDark, appDark, calendar.date) {
            scenicKey(wallpaper, calendar.date, systemDark, appDark, loadEvents = true)
        }
    ScenicWallpaperContent(
        wallpaper,
        modifier,
        key,
        motionEnabled = LocalLottieMotionEnabled.current && wallpaper.scenicAnimationEnabled && calendar.resumed,
    )
}

internal fun scenicKey(
    wallpaper: WallpaperSelection,
    date: LocalDate,
    systemDark: Boolean,
    appDark: Boolean,
    loadEvents: Boolean,
): ScenicKey =
    ScenicKey(
        preset = wallpaper.preset,
        season = if (wallpaper.preset == ChatWallpaperPreset.JAPANESE_COUNTRYSIDE) countrysideSeason(date) else null,
        dark = if (wallpaper.scenicFollowSystem) systemDark else appDark,
        loadEvents = loadEvents,
    )

@Composable
private fun ScenicWallpaperContent(
    wallpaper: WallpaperSelection,
    modifier: Modifier,
    key: ScenicKey,
    motionEnabled: Boolean,
) {
    val context = LocalContext.current
    val bundle by produceState<ScenicBundle?>(initialValue = null, key) {
        value = withContext(Dispatchers.IO) { ScenicWallpaperCache.getOrLoad(context.applicationContext, key) }
    }
    var frame by remember(key) { mutableStateOf<ScenicFrame?>(null) }
    // An authored event is rare. Compose it once at the source pixel grid so the base and
    // replacement pixels share the same nearest-neighbor sampler at every display size.
    val composedBitmap =
        remember(bundle, frame) {
            bundle?.let { loaded ->
                frame?.let { composeScenicFrame(loaded.bitmap, it.pixels) } ?: loaded.bitmap
            }
        }
    LaunchedEffect(bundle, motionEnabled) {
        frame = null
        val loaded = bundle ?: return@LaunchedEffect
        if (!motionEnabled || loaded.events.isEmpty()) return@LaunchedEffect
        playScenicEvents(
            initialDelayMs = loaded.initialDelayMs,
            quietIntervalMs = loaded.quietIntervalMs,
            events = loaded.events,
            onFrame = { frame = it },
            delayFor = ::delay,
        )
    }
    var coverage by remember { mutableStateOf(IntSize.Zero) }
    val scenicModifier =
        modifier
            .onSizeChanged { measured ->
                val expanded = expandedWallpaperCoverage(coverage, measured)
                if (coverage != expanded) coverage = expanded
            }.drawWithCache {
                val stableCoverage = expandedWallpaperCoverage(coverage, IntSize(size.width.toInt(), size.height.toInt()))
                onDrawBehind {
                    val bitmap = composedBitmap ?: return@onDrawBehind
                    val scale = maxOf(stableCoverage.width / 256f, stableCoverage.height / 448f)
                    val width = 256 * scale
                    val height = 448 * scale
                    val left = (stableCoverage.width - width) / 2f
                    val top = (stableCoverage.height - height) / 2f
                    drawIntoCanvas { canvas ->
                        drawScenicFrame(
                            canvas.nativeCanvas,
                            bitmap,
                            RectF(left, top, left + width, top + height),
                            wallpaper.intensity,
                        )
                    }
                }
            }
    Box(scenicModifier.fillMaxSize())
}

internal fun composeScenicFrame(
    bitmap: Bitmap,
    replacements: List<ScenicPixels>,
): Bitmap {
    if (replacements.isEmpty()) return bitmap
    return requireNotNull(bitmap.copy(Bitmap.Config.ARGB_8888, true)).also { composed ->
        replacements.forEach { pixel -> composed.setPixel(pixel.x, pixel.y, pixel.color) }
    }
}

internal fun drawScenicFrame(
    canvas: android.graphics.Canvas,
    bitmap: Bitmap,
    destination: RectF,
    intensity: Int,
) {
    // Apply intensity once after the immutable base and exact replacement pixels are composed.
    canvas.drawBitmap(
        bitmap,
        null,
        destination,
        Paint().apply {
            alpha = scenicLayerAlpha(intensity)
            isFilterBitmap = false
        },
    )
}

internal suspend fun playScenicEvents(
    initialDelayMs: LongRange,
    quietIntervalMs: LongRange,
    events: List<ScenicEvent>,
    onFrame: (ScenicFrame?) -> Unit,
    delayFor: suspend (Long) -> Unit,
    selectEvent: (List<ScenicEvent>) -> ScenicEvent = { it.random() },
) {
    try {
        delayFor(randomDelay(initialDelayMs))
        while (true) {
            for (next in selectEvent(events).frames) {
                onFrame(next.takeIf { it.pixels.isNotEmpty() })
                delayFor(next.durationMs)
            }
            onFrame(null)
            delayFor(randomDelay(quietIntervalMs))
        }
    } finally {
        // A lifecycle, date, mode, or preset transition must never leave a transient frame drawn.
        onFrame(null)
    }
}

private fun randomDelay(range: LongRange): Long = if (range.first == range.last) range.first else Random.nextLong(range.first, range.last + 1)

/** Alpha used for the completed scenic layer, shared by every base and event pixel. */
internal fun scenicLayerAlpha(intensity: Int): Int = intensity.coerceIn(0, 100) * 255 / 100
