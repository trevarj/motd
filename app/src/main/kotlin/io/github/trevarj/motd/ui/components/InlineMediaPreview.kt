package io.github.trevarj.motd.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import io.github.trevarj.motd.R
import io.github.trevarj.motd.ui.chat.isVideoUrl

internal enum class RemoteMediaLoadState {
    AWAITING,
    LOADING,
    LOADED,
    FAILED,
}

/** Inline media shared by every chat density, with cache-only first load when auto-load is off. */
@Composable
internal fun InlineMediaPreview(
    url: String,
    modifier: Modifier,
    onImageClick: (String) -> Unit,
    onLongPress: () -> Unit,
) {
    val automatic = LocalAutomaticRemoteMedia.current
    val consent = LocalInlineMediaConsent.current
    val networkAllowed = automatic || consent.granted
    if (isVideoUrl(url)) {
        InlineVideoPreview(url, networkAllowed, consent.grant, modifier, onLongPress)
    } else {
        InlineImagePreview(url, networkAllowed, consent.grant, modifier, onImageClick, onLongPress)
    }
}

@Composable
private fun InlineImagePreview(
    url: String,
    networkAllowed: Boolean,
    requestNetwork: () -> Unit,
    modifier: Modifier,
    onImageClick: (String) -> Unit,
    onLongPress: () -> Unit,
) {
    var state by remember(url) { mutableStateOf(RemoteMediaLoadState.AWAITING) }
    var retry by rememberSaveable(url) { mutableIntStateOf(0) }
    RemoteMediaImage(
        url = url,
        videoFrame = false,
        networkAllowed = networkAllowed,
        retry = retry,
        state = state,
        onState = { state = it },
        modifier =
            modifier.remoteMediaClicks(
                state = state,
                loadedLabel = stringResource(R.string.chat_image_open),
                onLoaded = { onImageClick(url) },
                requestNetwork = requestNetwork,
                retry = { retry++ },
                onLongPress = onLongPress,
            ),
    )
}

@Composable
private fun InlineVideoPreview(
    url: String,
    networkAllowed: Boolean,
    requestNetwork: () -> Unit,
    modifier: Modifier,
    onLongPress: () -> Unit,
) {
    var playing by rememberSaveable(url) { mutableStateOf(false) }
    if (playing) {
        val context = LocalContext.current
        val player =
            remember(url) {
                ExoPlayer.Builder(context).build().apply {
                    setMediaItem(MediaItem.fromUri(url))
                    prepare()
                    play()
                }
            }
        DisposableEffect(player) { onDispose(player::release) }
        AndroidView(
            factory = { PlayerView(it).apply { this.player = player } },
            update = { it.player = player },
            modifier = modifier.testTag("inline_video_preview"),
        )
        return
    }

    var state by remember(url) { mutableStateOf(RemoteMediaLoadState.AWAITING) }
    var retry by rememberSaveable(url) { mutableIntStateOf(0) }
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .testTag("inline_video_preview")
                .remoteMediaClicks(
                    state = state,
                    loadedLabel = stringResource(R.string.chat_video_play),
                    onLoaded = { playing = true },
                    requestNetwork = requestNetwork,
                    retry = { retry++ },
                    onLongPress = onLongPress,
                ),
    ) {
        RemoteMediaImage(
            url = url,
            videoFrame = true,
            networkAllowed = networkAllowed,
            retry = retry,
            state = state,
            onState = { state = it },
            modifier = Modifier.fillMaxSize(),
        )
        if (state == RemoteMediaLoadState.LOADED) {
            Icon(
                imageVector = Icons.Filled.PlayCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = 0.56f)),
            )
        }
    }
}

@Composable
private fun RemoteMediaImage(
    url: String,
    videoFrame: Boolean,
    networkAllowed: Boolean,
    retry: Int,
    state: RemoteMediaLoadState,
    onState: (RemoteMediaLoadState) -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val request =
        remember(context, url, videoFrame, networkAllowed, retry) {
            ImageRequest
                .Builder(context)
                .remoteMediaData(url, networkAllowed)
                .apply {
                    if (videoFrame) videoFrameMillis(0)
                    if (retry > 0) memoryCacheKey("$url#motd-retry=$retry")
                }.build()
        }
    Box(modifier = modifier.testTag("inline_media_${state.name.lowercase()}"), contentAlignment = Alignment.Center) {
        AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = if (videoFrame) ContentScale.Crop else ContentScale.FillWidth,
            onLoading = {
                onState(
                    if (networkAllowed) {
                        RemoteMediaLoadState.LOADING
                    } else {
                        RemoteMediaLoadState.AWAITING
                    },
                )
            },
            onSuccess = { onState(RemoteMediaLoadState.LOADED) },
            onError = {
                onState(
                    if (networkAllowed) {
                        RemoteMediaLoadState.FAILED
                    } else {
                        RemoteMediaLoadState.AWAITING
                    },
                )
            },
            modifier = Modifier.fillMaxSize(),
        )
        when (state) {
            RemoteMediaLoadState.AWAITING -> {
                Icon(
                    imageVector = Icons.Outlined.Download,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier =
                        Modifier
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.86f))
                            .padding(8.dp),
                )
            }

            RemoteMediaLoadState.LOADING -> {
                CircularProgressIndicator(Modifier.testTag("inline_media_progress"))
            }

            RemoteMediaLoadState.FAILED -> {
                MediaStatus(stringResource(R.string.chat_remote_media_failed))
            }

            RemoteMediaLoadState.LOADED -> {}
        }
    }
}

@Composable
private fun MediaStatus(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier =
            Modifier
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.86f))
                .padding(8.dp),
    )
}

@Composable
private fun Modifier.remoteMediaClicks(
    state: RemoteMediaLoadState,
    loadedLabel: String,
    onLoaded: () -> Unit,
    requestNetwork: () -> Unit,
    retry: () -> Unit,
    onLongPress: () -> Unit,
): Modifier {
    val clickLabel =
        when (state) {
            RemoteMediaLoadState.AWAITING -> stringResource(R.string.chat_remote_media_download)
            RemoteMediaLoadState.LOADING -> stringResource(R.string.chat_remote_media_loading)
            RemoteMediaLoadState.LOADED -> loadedLabel
            RemoteMediaLoadState.FAILED -> stringResource(R.string.chat_remote_media_retry)
        }
    val click =
        when (state) {
            RemoteMediaLoadState.AWAITING -> requestNetwork
            RemoteMediaLoadState.LOADING -> ({})
            RemoteMediaLoadState.LOADED -> onLoaded
            RemoteMediaLoadState.FAILED -> retry
        }
    return combinedClickable(
        enabled = state != RemoteMediaLoadState.LOADING,
        onClick = click,
        onClickLabel = clickLabel,
        onLongClick = onLongPress,
    ).semantics { contentDescription = clickLabel }
}
