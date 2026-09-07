package io.github.trevarj.motd.ui.components

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import io.github.trevarj.motd.R
import io.github.trevarj.motd.ui.chat.isVideoUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URI

internal enum class RemoteMediaLoadState {
    AWAITING,
    LOADING,
    LOADED,
    FAILED,
}

internal fun mediaOriginLabel(url: String): String? {
    // Reject malformed authorities rather than letting HttpUrl repair them for display.
    if (runCatching { URI(url).rawAuthority }.getOrNull().isNullOrEmpty()) return null
    val parsed = url.toHttpUrlOrNull() ?: return null
    val host = if (':' in parsed.host) "[${parsed.host}]" else parsed.host
    val defaultPort = if (parsed.scheme == "https") 443 else 80
    return if (parsed.port == defaultPort) host else "$host:${parsed.port}"
}

@Composable
internal fun MediaOriginCaption(
    url: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val origin = remember(url) { mediaOriginLabel(url) } ?: return
    val caption =
        remember(url, origin, color) {
            buildAnnotatedString {
                withLink(LinkAnnotation.Url(url, styles = TextLinkStyles(SpanStyle(color = color, textDecoration = TextDecoration.None)))) {
                    append(origin)
                }
            }
        }
    Text(
        text = caption,
        modifier = modifier.padding(top = 4.dp).testTag("media_origin_caption"),
        style = MaterialTheme.typography.bodySmall,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.MiddleEllipsis,
    )
}

/** Inline media shared by every chat density, with cache-only first load when auto-load is off. */
@Composable
internal fun InlineMediaPreview(
    url: String,
    networkId: Long?,
    modifier: Modifier,
    onImageClick: (String) -> Unit,
    onLongPress: () -> Unit,
) {
    val automatic = LocalAutomaticRemoteMedia.current
    val consent = LocalInlineMediaConsent.current
    val networkAllowed = automatic || consent.granted
    if (isVideoUrl(url)) {
        InlineVideoPreview(url, networkId, networkAllowed, consent.grant, modifier, onLongPress)
    } else {
        InlineImagePreview(url, networkId, networkAllowed, consent.grant, modifier, onImageClick, onLongPress)
    }
}

@Composable
private fun InlineImagePreview(
    url: String,
    networkId: Long?,
    networkAllowed: Boolean,
    requestNetwork: () -> Unit,
    modifier: Modifier,
    onImageClick: (String) -> Unit,
    onLongPress: () -> Unit,
) {
    var state by remember(url, networkId) { mutableStateOf(RemoteMediaLoadState.AWAITING) }
    var retry by rememberSaveable(url, networkId) { mutableIntStateOf(0) }
    RemoteMediaImage(
        url = url,
        networkId = networkId,
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

@OptIn(UnstableApi::class)
@Composable
private fun InlineVideoPreview(
    url: String,
    networkId: Long?,
    networkAllowed: Boolean,
    requestNetwork: () -> Unit,
    modifier: Modifier,
    onLongPress: () -> Unit,
) {
    var playing by rememberSaveable(url, networkId) { mutableStateOf(false) }
    if (playing) {
        val context = LocalContext.current
        val http = LocalNetworkMediaHttp.current
        var playbackRetry by remember(url, networkId) { mutableIntStateOf(0) }
        val player =
            remember(context, url, networkId, http, playbackRetry) {
                http?.let {
                    ExoPlayer
                        .Builder(context)
                        .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(it.callFactory(networkId))))
                        .build()
                }
            }
        var playbackFailed by remember(player) { mutableStateOf(false) }
        if (player != null) {
            DisposableEffect(player) {
                val listener =
                    object : Player.Listener {
                        override fun onPlayerError(error: PlaybackException) {
                            playbackFailed = true
                        }
                    }
                player.addListener(listener)
                player.setMediaItem(MediaItem.fromUri(url))
                player.prepare()
                player.play()
                onDispose {
                    player.removeListener(listener)
                    player.release()
                }
            }
        }
        if (player == null || playbackFailed) {
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    modifier
                        .testTag("inline_video_failed")
                        .remoteMediaClicks(
                            state = RemoteMediaLoadState.FAILED,
                            loadedLabel = stringResource(R.string.chat_video_play),
                            onLoaded = {},
                            requestNetwork = requestNetwork,
                            retry = { playbackRetry++ },
                            onLongPress = onLongPress,
                        ),
            ) {
                MediaStatus(stringResource(R.string.chat_remote_media_failed))
            }
        } else {
            AndroidView(
                factory = { PlayerView(it).apply { this.player = player } },
                update = { it.player = player },
                modifier = modifier.testTag("inline_video_preview"),
            )
        }
        return
    }

    var state by remember(url, networkId) { mutableStateOf(RemoteMediaLoadState.AWAITING) }
    var retry by rememberSaveable(url, networkId) { mutableIntStateOf(0) }
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .testTag("inline_video_preview")
                .remoteMediaClicks(
                    state = state,
                    loadedLabel = stringResource(R.string.chat_video_play),
                    onLoaded = {
                        requestNetwork()
                        playing = true
                    },
                    requestNetwork = requestNetwork,
                    retry = { retry++ },
                    onLongPress = onLongPress,
                ),
    ) {
        RemoteMediaImage(
            url = url,
            networkId = networkId,
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
    networkId: Long?,
    videoFrame: Boolean,
    networkAllowed: Boolean,
    retry: Int,
    state: RemoteMediaLoadState,
    onState: (RemoteMediaLoadState) -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val request =
        remember(context, url, networkId, videoFrame, networkAllowed, retry) {
            ImageRequest
                .Builder(context)
                .routedRemoteMediaData(url, networkId, networkAllowed, retry)
                .apply {
                    if (videoFrame) videoFrameMillis(0)
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
                            .clip(RoundedCornerShape(8.dp))
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
