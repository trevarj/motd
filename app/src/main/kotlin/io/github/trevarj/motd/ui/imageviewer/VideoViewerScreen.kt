package io.github.trevarj.motd.ui.imageviewer

import android.view.View
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import io.github.trevarj.motd.R
import io.github.trevarj.motd.ui.components.LocalNetworkMediaHttp
import io.github.trevarj.motd.ui.components.openChatLinkInBrowser
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@OptIn(UnstableApi::class)
@Composable
internal fun VideoViewerScreen(
    url: String,
    networkId: Long?,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val http = LocalNetworkMediaHttp.current
    val mediaUrl = remember(url) { url.toHttpUrlOrNull() }
    val canPlay = http != null && networkId != null && mediaUrl != null
    var position by rememberSaveable(url, networkId) { mutableLongStateOf(0L) }
    var resumePlayback by rememberSaveable(url, networkId) { mutableStateOf(true) }
    var failed by rememberSaveable(url, networkId) { mutableStateOf(false) }
    var fullscreen by rememberSaveable(url, networkId) { mutableStateOf(false) }
    val immersive = fullscreen && canPlay && !failed
    var buffering by remember(url, networkId, http) { mutableStateOf(true) }
    var controlsVisible by remember(url, networkId) { mutableStateOf(true) }
    var player by remember(context, url, networkId, http) { mutableStateOf<ExoPlayer?>(null) }
    var playerView by remember { mutableStateOf<PlayerView?>(null) }

    if (http != null && networkId != null && mediaUrl != null && !failed) {
        DisposableEffect(lifecycle, context, mediaUrl, networkId, http) {
            val listener =
                object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        buffering = playbackState == Player.STATE_BUFFERING
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        fullscreen = false
                        failed = true
                    }
                }
            var resumed = false

            fun pause() {
                if (!resumed) return
                resumed = false
                playerView?.useController = false
                player?.let {
                    position = it.currentPosition
                    resumePlayback = it.playWhenReady && it.playbackState != Player.STATE_ENDED
                    it.pause()
                    it.setAudioAttributes(AudioAttributes.DEFAULT, false)
                }
            }

            fun release() {
                pause()
                player?.let {
                    playerView?.player = null
                    player = null
                    it.removeListener(listener)
                    it.release()
                }
            }
            val observer =
                LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_RESUME -> {
                            resumed = true
                            val activePlayer =
                                player ?: ExoPlayer
                                    .Builder(context)
                                    .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(http.callFactory(networkId))))
                                    .setHandleAudioBecomingNoisy(true)
                                    .build()
                                    .also {
                                        buffering = true
                                        it.addListener(listener)
                                        it.setMediaItem(MediaItem.fromUri(mediaUrl.toString()), position)
                                        player = it
                                        it.prepare()
                                    }
                            activePlayer.setAudioAttributes(AudioAttributes.DEFAULT, true)
                            activePlayer.playWhenReady = resumePlayback
                            playerView?.apply {
                                useController = true
                                showController()
                            }
                        }

                        Lifecycle.Event.ON_PAUSE -> {
                            pause()
                        }

                        Lifecycle.Event.ON_STOP -> {
                            release()
                        }

                        else -> {
                            Unit
                        }
                    }
                }
            lifecycle.addObserver(observer)
            onDispose {
                lifecycle.removeObserver(observer)
                release()
            }
        }
    }

    val window = LocalActivity.current?.window
    val view = LocalView.current
    DisposableEffect(window, view) {
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val lightStatusBars = controller?.isAppearanceLightStatusBars
        val lightNavigationBars = controller?.isAppearanceLightNavigationBars
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        onDispose {
            if (controller != null && lightStatusBars != null && lightNavigationBars != null) {
                controller.isAppearanceLightStatusBars = lightStatusBars
                controller.isAppearanceLightNavigationBars = lightNavigationBars
            }
        }
    }
    if (immersive && window != null) {
        LifecycleResumeEffect(window, view) {
            val controller = WindowCompat.getInsetsController(window, view)
            val previousBehavior = controller.systemBarsBehavior
            val bars = WindowInsetsCompat.Type.systemBars()
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(bars)
            onPauseOrDispose {
                controller.show(bars)
                controller.systemBarsBehavior = previousBehavior
            }
        }
    }
    BackHandler(immersive) { fullscreen = false }

    val backSize = 48.dp
    val backPadding = 8.dp
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .safeDrawingPadding(),
        contentAlignment = Alignment.Center,
    ) {
        if (!canPlay || failed) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = backSize + backPadding * 2)
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp)
                        .testTag("video_viewer_failed"),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    stringResource(R.string.video_viewer_failed),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
                if (canPlay) {
                    Button(onClick = { failed = false }, modifier = Modifier.padding(top = 12.dp)) {
                        Text(stringResource(R.string.chat_retry))
                    }
                }
                if (mediaUrl != null) {
                    Text(
                        stringResource(R.string.video_viewer_browser_warning),
                        modifier = Modifier.padding(top = 12.dp),
                        color = Color.White,
                        textAlign = TextAlign.Center,
                    )
                    TextButton(onClick = { context.openChatLinkInBrowser(url) }) {
                        Text(stringResource(R.string.chat_link_open_browser), color = Color.White)
                    }
                }
            }
        } else {
            val activePlayer = player
            if (activePlayer != null) {
                AndroidView(
                    factory = {
                        PlayerView(it).apply {
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                            setControllerAnimationEnabled(false)
                            setEnableComposeSurfaceSyncWorkaround(true)
                            setShowPreviousButton(false)
                            setShowNextButton(false)
                            setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { controlsVisible = it == View.VISIBLE })
                            playerView = this
                        }
                    },
                    update = {
                        it.player = activePlayer
                        it.controllerHideOnTouch = immersive
                        it.controllerShowTimeoutMs = if (immersive) 5_000 else 0
                        it.useController = lifecycle.currentState == Lifecycle.State.RESUMED
                        if (it.useController) it.showController()
                    },
                    onReset = null,
                    onRelease = {
                        it.player = null
                        if (playerView === it) playerView = null
                    },
                    modifier = Modifier.fillMaxSize().testTag("video_viewer_player"),
                )
            }
            if (activePlayer == null || buffering) {
                val loading = stringResource(R.string.video_viewer_loading)
                CircularProgressIndicator(color = Color.White, modifier = Modifier.semantics { contentDescription = loading })
            }
        }
        if (!immersive) {
            IconButton(
                onClick = {
                    playerView?.useController = false
                    player?.pause()
                    onBack()
                },
                modifier =
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(backPadding)
                        .size(backSize)
                        .background(Color.Black.copy(alpha = 0.72f), CircleShape),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.image_viewer_back),
                    tint = Color.White,
                )
            }
        }
        if (canPlay && !failed && (!immersive || controlsVisible)) {
            Row(Modifier.align(Alignment.TopEnd).padding(backPadding)) {
                val buttonModifier = Modifier.size(backSize).background(Color.Black.copy(alpha = 0.72f), CircleShape)
                IconButton(onClick = { fullscreen = !immersive }, modifier = buttonModifier) {
                    Icon(
                        if (immersive) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                        contentDescription =
                            stringResource(if (immersive) R.string.video_viewer_exit_fullscreen else R.string.video_viewer_fullscreen),
                        tint = Color.White,
                    )
                }
                if (!immersive) {
                    Box {
                        var showMenu by remember { mutableStateOf(false) }
                        IconButton(onClick = { showMenu = true }, modifier = buttonModifier) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more), tint = Color.White)
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(stringResource(R.string.chat_link_open_browser))
                                        Text(
                                            stringResource(R.string.video_viewer_browser_warning),
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                },
                                onClick = {
                                    showMenu = false
                                    context.openChatLinkInBrowser(url)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
