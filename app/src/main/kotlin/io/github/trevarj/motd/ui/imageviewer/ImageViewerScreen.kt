package io.github.trevarj.motd.ui.imageviewer

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntSize
import androidx.compose.foundation.Image
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import io.github.trevarj.motd.R
import io.github.trevarj.motd.ui.theme.MotdMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

private const val MAX_SCALE = 5f
private const val DOUBLE_TAP_SCALE = 2.5f
private const val SAVE_TIMEOUT_MS = 15_000

/**
 * Full-screen image viewer (plans/07): black background, Coil image, hand-rolled pinch-zoom/pan via
 * [detectTransformGestures] + [graphicsLayer], share/save (MediaStore) actions, tap toggles chrome.
 *
 * Gestures are focal-point anchored: pinch and double-tap zoom around the touch point, and pan is
 * clamped to the scaled bounds so the image can't be flung off-screen (plans/15 #26).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageViewerScreen(
    url: String,
    onBack: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var chromeVisible by remember { mutableStateOf(true) }

    // Zoom/pan transform state; clamped so the image cannot be shrunk below 1x.
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var transformAnimationJob by remember { mutableStateOf<Job?>(null) }
    // Container size in px; used to clamp pan to the scaled bounds.
    var boxSize by remember { mutableStateOf(IntSize.Zero) }

    // Clamp pan so the (scaled) image edges never move inside the viewport.
    fun clampOffsets() {
        val maxX = ((scale - 1f) * boxSize.width / 2f).coerceAtLeast(0f)
        val maxY = ((scale - 1f) * boxSize.height / 2f).coerceAtLeast(0f)
        offsetX = offsetX.coerceIn(-maxX, maxX)
        offsetY = offsetY.coerceIn(-maxY, maxY)
    }

    val painter = rememberAsyncImagePainter(
        model = ImageRequest.Builder(context).data(url).crossfade(true).build(),
    )
    val imageState = painter.state

    val contentDesc = stringResource(R.string.image_viewer_content_description)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { boxSize = it },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painter,
            contentDescription = contentDesc,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offsetX,
                    translationY = offsetY,
                )
                .pointerInput(Unit) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        // A direct gesture takes ownership of transform state immediately. Without
                        // cancelling the double-tap animation, both paths can write scale/offsets.
                        transformAnimationJob?.cancel()
                        transformAnimationJob = null
                        val newScale = (scale * zoom).coerceIn(1f, MAX_SCALE)
                        if (newScale > 1f) {
                            // Anchor the zoom on the gesture centroid relative to the box center.
                            val focusX = centroid.x - size.width / 2f
                            val focusY = centroid.y - size.height / 2f
                            val factor = newScale / scale
                            offsetX = (offsetX + pan.x - focusX) * factor + focusX
                            offsetY = (offsetY + pan.y - focusY) * factor + focusY
                        } else {
                            offsetX = 0f
                            offsetY = 0f
                        }
                        scale = newScale
                        clampOffsets()
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { chromeVisible = !chromeVisible },
                        onDoubleTap = { tap ->
                            val targetScale: Float
                            val targetOffsetX: Float
                            val targetOffsetY: Float
                            if (scale > 1f) {
                                targetScale = 1f
                                targetOffsetX = 0f
                                targetOffsetY = 0f
                            } else {
                                // Zoom toward the tapped point, not the center (plans/15 #26).
                                val focusX = tap.x - size.width / 2f
                                val focusY = tap.y - size.height / 2f
                                targetScale = DOUBLE_TAP_SCALE
                                targetOffsetX = -focusX * (DOUBLE_TAP_SCALE - 1f)
                                targetOffsetY = -focusY * (DOUBLE_TAP_SCALE - 1f)
                            }
                            val maxTargetX = ((targetScale - 1f) * boxSize.width / 2f).coerceAtLeast(0f)
                            val maxTargetY = ((targetScale - 1f) * boxSize.height / 2f).coerceAtLeast(0f)
                            val clampedTargetOffsetX = targetOffsetX.coerceIn(-maxTargetX, maxTargetX)
                            val clampedTargetOffsetY = targetOffsetY.coerceIn(-maxTargetY, maxTargetY)
                            transformAnimationJob?.cancel()
                            transformAnimationJob = scope.launch {
                                coroutineScope {
                                    launch {
                                        animate(
                                            initialValue = scale,
                                            targetValue = targetScale,
                                            animationSpec = MotdMotion.softSpring,
                                        ) { value, _ -> scale = value }
                                    }
                                    launch {
                                        animate(
                                            initialValue = offsetX,
                                            targetValue = clampedTargetOffsetX,
                                            animationSpec = MotdMotion.softSpring,
                                        ) { value, _ -> offsetX = value }
                                    }
                                    launch {
                                        animate(
                                            initialValue = offsetY,
                                            targetValue = clampedTargetOffsetY,
                                            animationSpec = MotdMotion.softSpring,
                                        ) { value, _ -> offsetY = value }
                                    }
                                }
                            }
                        },
                    )
                },
        )

        // Loading / error affordances (plans/15 #26).
        when (imageState) {
            is AsyncImagePainter.State.Loading ->
                CircularProgressIndicator(color = Color.White)
            is AsyncImagePainter.State.Error ->
                Text(
                    text = stringResource(R.string.image_viewer_load_failed),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                )
            else -> Unit
        }

        AnimatedVisibility(
            visible = chromeVisible,
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
                    IconButton(onClick = { shareImage(context, url) }) {
                        Icon(
                            Icons.Filled.Share,
                            contentDescription = stringResource(R.string.image_viewer_share),
                            tint = Color.White,
                        )
                    }
                    // MediaStore RELATIVE_PATH is API 29+; pre-29 would need WRITE_EXTERNAL_STORAGE,
                    // so hide Save there rather than request a legacy permission (plans/15 #26).
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        IconButton(onClick = { scope.launch { saveImage(context, url) } }) {
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
    }
}

/** Share the image URL via a plain-text intent (viewers resolve the link). */
private fun shareImage(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, url)
    }
    context.startActivity(
        Intent.createChooser(intent, context.getString(R.string.image_viewer_share_chooser)),
    )
}

/**
 * Download the image bytes and insert them into the shared Pictures collection via MediaStore.
 * Only called on API 29+ (the caller hides Save below Q). A read timeout bounds a stalled fetch.
 */
@androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
private suspend fun saveImage(context: Context, url: String) {
    val ok = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = URL(url).openConnection().apply {
                connectTimeout = SAVE_TIMEOUT_MS
                readTimeout = SAVE_TIMEOUT_MS
            }.getInputStream().use { it.readBytes() }
            val name = url.substringAfterLast('/').substringBefore('?').ifEmpty { "image" }
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, mimeFor(name))
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/motd")
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return@runCatching false
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return@runCatching false
            true
        }.getOrDefault(false)
    }
    withContext(Dispatchers.Main) {
        Toast.makeText(
            context,
            if (ok) context.getString(R.string.image_viewer_saved)
            else context.getString(R.string.image_viewer_save_failed),
            Toast.LENGTH_SHORT,
        ).show()
    }
}

private fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "png" -> "image/png"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    else -> "image/jpeg"
}

@Preview
@Composable
private fun ImageViewerPreview() {
    ImageViewerScreen(url = "https://example.com/cat.png")
}
