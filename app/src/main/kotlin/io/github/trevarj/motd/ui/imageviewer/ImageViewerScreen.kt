package io.github.trevarj.motd.ui.imageviewer

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil.imageLoader
import coil.request.ImageRequest
import io.github.trevarj.motd.R
import io.github.trevarj.motd.audio.networkMediaData
import io.github.trevarj.motd.ui.components.LocalNetworkMediaHttp
import io.github.trevarj.motd.ui.theme.MotdMotion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.saket.telephoto.ExperimentalTelephotoApi
import me.saket.telephoto.zoomable.DoubleClickToZoomListener
import me.saket.telephoto.zoomable.Viewport
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.ZoomableImageState
import me.saket.telephoto.zoomable.ZoomableState
import me.saket.telephoto.zoomable.coil.ZoomableAsyncImage
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableState
import me.saket.telephoto.zoomable.spatial.CoordinateSpace
import okhttp3.Call

/** Ceiling for pinch/double-tap zoom, as a factor of the image's original size. */
internal const val MAX_IMAGE_SCALE = 5f

/** Double-tap zoom, as a factor of the *fitted* image (see [doubleTapZoom]). */
private const val DOUBLE_TAP_ZOOM_FACTOR = 2.5f

/**
 * Telephoto never surfaces a decode failure that happens after Coil hands over a successful result
 * (its sub-sampling error reporter is a no-op in release builds), so a corrupt or half-evicted
 * disk-cache entry would otherwise spin forever. Give up and show the error text after this long.
 */
private const val IMAGE_DISPLAY_TIMEOUT_MS = 30_000L

internal const val IMAGE_VIEWER_IMAGE_TAG = "image_viewer_image"
internal const val IMAGE_VIEWER_SAVE_BUTTON_TAG = "image_viewer_save_button"
internal const val IMAGE_VIEWER_SAVE_FEEDBACK_TAG = "image_viewer_save_feedback"
internal val ImageViewerTransformKey =
    SemanticsPropertyKey<ImageViewerTransform>("ImageViewerTransform")
private var SemanticsPropertyReceiver.imageViewerTransform by ImageViewerTransformKey

/**
 * Cycle between the fitted image and [DOUBLE_TAP_ZOOM_FACTOR] on double-tap, anchored on the tap.
 *
 * Telephoto's stock [DoubleClickToZoomListener.cycle] takes an *absolute* factor, measured against
 * the image's original size rather than its fitted size. That would make double-tap dead on images
 * already displayed above the factor (icons/thumbnails, where `resetZoom` is a no-op on the first
 * tap) and overshoot wildly on large photos. Rebasing on `initialScale` restores the fitted-relative
 * 2.5x this screen has always used.
 */
private val doubleTapZoom =
    DoubleClickToZoomListener { state, centroid ->
        val metadata = state.contentTransformation.scaleMetadata
        if (metadata.userZoom > 1.01f) {
            state.resetZoom()
        } else {
            state.zoomTo(
                zoomFactor = DOUBLE_TAP_ZOOM_FACTOR * metadata.initialScale.scaleX,
                centroid = centroid,
            )
        }
    }

/**
 * The zoom and pan Telephoto currently applies, flattened so instrumentation can read it without
 * depending on Telephoto's experimental coordinate-system types.
 *
 * Telephoto reports an unspecified transformation until it has measured the content, so these read
 * `0f` / [Rect.Zero] before the image is laid out. Gate reads on [ZoomableImageState.isImageDisplayed].
 */
internal data class ImageViewerTransform(
    /** Zoom relative to the fitted image; `1f` once the image exactly fits its viewport. */
    val scale: Float,
    /** Translation of the transformed content, in viewport pixels. */
    val offset: Offset,
    /** Bounds of the transformed content in viewport pixels, unclipped by the viewport. */
    val contentBounds: Rect,
)

/**
 * Full-screen image viewer: black background, a Telephoto [ZoomableAsyncImage] for pinch/double-tap
 * zoom and bounded pan, share/save (MediaStore) actions, and a tap that toggles the chrome.
 *
 * Telephoto owns the gesture and transform math (focal-point anchored zoom, pan clamped to the
 * scaled bounds, rubber-band overzoom), so this screen only supplies the zoom spec and the
 * loading/error affordances.
 */
@Composable
fun ImageViewerScreen(
    url: String,
    networkId: Long? = null,
    onBack: () -> Unit = {},
) {
    val context = LocalContext.current
    val mediaHttp = LocalNetworkMediaHttp.current
    val request =
        remember(context, url, networkId, mediaHttp) {
            ImageRequest
                .Builder(context)
                .apply {
                    if (mediaHttp != null) networkMediaData(url, networkId)
                }.build()
        }

    ImageViewerContent(
        model = request,
        onBack = onBack,
        onShare = { shareImage(context, url) },
        onSave = { saveImage(context, url, mediaHttp?.callFactory(networkId)) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ImageViewerContent(
    model: ImageRequest,
    onBack: () -> Unit,
    onShare: () -> Unit,
    onSave: suspend () -> ImageSaveFeedback,
    state: ZoomableImageState =
        rememberZoomableImageState(
            rememberZoomableState(ZoomSpec(maxZoomFactor = MAX_IMAGE_SCALE)),
        ),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var chromeVisible by remember { mutableStateOf(true) }
    var saveFeedback by remember { mutableStateOf<ImageSaveFeedback?>(null) }
    var saveInProgress by remember { mutableStateOf(false) }
    // Telephoto reports when an image is displayed but not why one failed, so the request listener
    // is the only signal for the error affordance. Both reset whenever the request changes.
    var loadFailed by remember(context, model) { mutableStateOf(false) }
    val request =
        remember(context, model) {
            model
                .newBuilder(context)
                .crossfade(true)
                .listener(
                    onError = { _, _ -> loadFailed = true },
                    onSuccess = { _, _ -> loadFailed = false },
                ).build()
        }
    val zoomableState = state.zoomableState

    // Coil can report success while Telephoto's sub-sampling decode still fails silently, leaving
    // the spinner up forever. Bound the wait so the error affordance is always reachable.
    LaunchedEffect(request) {
        delay(IMAGE_DISPLAY_TIMEOUT_MS)
        if (!state.isImageDisplayed) loadFailed = true
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        ZoomableAsyncImage(
            model = request,
            contentDescription = stringResource(R.string.image_viewer_content_description),
            state = state,
            // The app-wide loader carries the GIF decoders registered in MotdApplication.
            imageLoader = context.imageLoader,
            contentScale = ContentScale.Fit,
            onClick = { chromeVisible = !chromeVisible },
            onDoubleClick = doubleTapZoom,
            modifier =
                Modifier
                    .fillMaxSize()
                    // Read inside the semantics lambda so gesture frames invalidate semantics only,
                    // instead of recomposing the whole screen.
                    .semantics { imageViewerTransform = zoomableState.currentTransform() }
                    .testTag(IMAGE_VIEWER_IMAGE_TAG),
        )

        // Loading / error affordances.
        when {
            loadFailed -> {
                Text(
                    text = stringResource(R.string.image_viewer_load_failed),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            !state.isImageDisplayed -> {
                CircularProgressIndicator(color = Color.White)
            }
        }

        // Fade only: the chrome overlays the image, so the default expand/shrink would clip the
        // bar diagonally toward its top-start corner instead of dissolving in place.
        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(MotdMotion.fadeIn),
            exit = fadeOut(MotdMotion.fadeOut),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.image_viewer_back),
                            tint = Color.White,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onShare) {
                        Icon(
                            Icons.Filled.Share,
                            contentDescription = stringResource(R.string.image_viewer_share),
                            tint = Color.White,
                        )
                    }
                    // MediaStore RELATIVE_PATH is API 29+; pre-29 would need WRITE_EXTERNAL_STORAGE,
                    // so hide Save there rather than request a legacy permission.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        IconButton(
                            enabled = !saveInProgress,
                            onClick = {
                                saveFeedback = null
                                saveInProgress = true
                                scope.launch {
                                    try {
                                        saveFeedback = onSave()
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (_: Exception) {
                                        saveFeedback = ImageSaveFeedback.FAILED
                                    } finally {
                                        saveInProgress = false
                                    }
                                }
                            },
                            modifier = Modifier.testTag(IMAGE_VIEWER_SAVE_BUTTON_TAG),
                        ) {
                            Icon(
                                Icons.Filled.Download,
                                contentDescription = stringResource(R.string.image_viewer_save),
                                tint = Color.White,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        }

        // The exit fade must not render an empty label after the state nulls out; hold the last
        // feedback for the outgoing frames, like the latched exits in ChatListScreen.
        var lastFeedback by remember { mutableStateOf<ImageSaveFeedback?>(null) }
        saveFeedback?.let { lastFeedback = it }
        AnimatedVisibility(
            visible = saveFeedback != null,
            enter = fadeIn(MotdMotion.fadeIn),
            exit = fadeOut(MotdMotion.microFadeOut),
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(24.dp),
        ) {
            lastFeedback?.let { feedback ->
                Text(
                    text =
                        stringResource(
                            if (feedback == ImageSaveFeedback.SAVED) {
                                R.string.image_viewer_saved
                            } else {
                                R.string.image_viewer_save_failed
                            },
                        ),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier =
                        Modifier
                            .background(Color.Black.copy(alpha = 0.72f), MaterialTheme.shapes.small)
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite }
                            .testTag(IMAGE_VIEWER_SAVE_FEEDBACK_TAG),
                )
            }
        }
    }
}

/** Snapshot Telephoto's live transform in the shape instrumentation asserts against. */
@OptIn(ExperimentalTelephotoApi::class)
private fun ZoomableState.currentTransform(): ImageViewerTransform {
    val transformation = contentTransformation
    return ImageViewerTransform(
        // userZoom is relative to the fitted image, so 1f means "exactly fits" on every image.
        scale = transformation.scaleMetadata.userZoom,
        offset = transformation.offset,
        contentBounds =
            with(coordinateSystem) {
                contentBounds(clipToViewport = false).rectIn(CoordinateSpace.Viewport)
            },
    )
}

/** Share the image URL via a plain-text intent (viewers resolve the link). */
private fun shareImage(
    context: Context,
    url: String,
) {
    val intent =
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
        }
    context.startActivity(
        Intent.createChooser(intent, context.getString(R.string.image_viewer_share_chooser)),
    )
}

/**
 * Stream the image into a pending MediaStore row. The result is shown only after finalization.
 * Only called on API 29+ (the caller hides Save below Q).
 */
@androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
private suspend fun saveImage(
    context: Context,
    url: String,
    callFactory: Call.Factory?,
): ImageSaveFeedback {
    if (callFactory == null) return ImageSaveFeedback.FAILED
    val result =
        withContext(Dispatchers.IO) {
            ImageSaveOperation(
                connectionFactory = OkHttpImageSaveConnectionFactory(callFactory),
                store = MediaStoreImageSaveStore(context.contentResolver),
            ).save(url)
        }
    return result.feedback()
}

@Preview
@Composable
private fun ImageViewerPreview() {
    ImageViewerScreen(url = "https://example.com/cat.png")
}
