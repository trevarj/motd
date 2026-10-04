package io.github.trevarj.motd

import android.os.Looper
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.assertTouchWidthIsEqualTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import io.github.trevarj.motd.audio.AudioAttachment
import io.github.trevarj.motd.audio.AudioCacheStatus
import io.github.trevarj.motd.audio.AudioPlaybackOrigin
import io.github.trevarj.motd.audio.AudioPlaybackRequest
import io.github.trevarj.motd.audio.AudioPlaybackState
import io.github.trevarj.motd.audio.AudioWaveform
import io.github.trevarj.motd.audio.projectAudioPlaybackState
import io.github.trevarj.motd.ui.chat.VoiceTranscriptFailureKind
import io.github.trevarj.motd.ui.chat.VoiceTranscriptState
import io.github.trevarj.motd.ui.components.AudioAttachmentPlayers
import io.github.trevarj.motd.ui.components.AudioMiniPlayer
import io.github.trevarj.motd.ui.theme.MotdTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class AudioPlayerUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule val compose = createComposeRule()

    @Test fun uncached_primary_downloads_and_plays_the_requested_attachment() {
        val attachment = audio()
        var status by mutableStateOf(AudioCacheStatus.UNKNOWN)
        val toggles = mutableListOf<Pair<AudioAttachment, Long?>>()
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                AudioAttachmentPlayers(
                    attachments = listOf(attachment),
                    playbackState = AudioPlaybackState(),
                    cacheStatuses = mapOf(attachment.playbackId to status),
                    networkId = 7,
                    isSelf = false,
                    onToggle = { item, network -> toggles += item to network },
                    onSeek = { _, _ -> },
                )
            }
        }

        for (uncached in listOf(AudioCacheStatus.UNKNOWN, AudioCacheStatus.NOT_CACHED, AudioCacheStatus.PARTIAL)) {
            compose.runOnIdle { status = uncached }
            compose.onNodeWithContentDescription("Download and play audio").assertIsDisplayed().performClick()
        }
        compose.runOnIdle { assertEquals(List(3) { attachment to 7L }, toggles) }
    }

    @Test fun cached_audio_uses_play_action() {
        val attachment = audio()
        var played: AudioAttachment? = null
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                AudioAttachmentPlayers(
                    attachments = listOf(attachment),
                    playbackState = AudioPlaybackState(),
                    cacheStatuses = mapOf(attachment.playbackId to AudioCacheStatus.CACHED),
                    networkId = null,
                    isSelf = false,
                    onToggle = { item, _ -> played = item },
                    onSeek = { _, _ -> },
                )
            }
        }

        compose.onNodeWithContentDescription("Play audio").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(attachment, played) }
    }

    @Test fun encrypted_audio_uses_lock_icon_and_keeps_full_timestamp() {
        val attachment = audio().copy(encrypted = true)
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                AudioAttachmentPlayers(
                    attachments = listOf(attachment),
                    playbackState = AudioPlaybackState(),
                    cacheStatuses = mapOf(attachment.playbackId to AudioCacheStatus.CACHED),
                    networkId = null,
                    isSelf = false,
                    formattedTime = "12:34 PM",
                    onToggle = { _, _ -> },
                    onSeek = { _, _ -> },
                )
            }
        }

        compose.onNodeWithContentDescription("Encrypted audio").assertIsDisplayed()
        compose.onNodeWithText("12:34 PM").assertIsDisplayed()
    }

    @Test fun loading_cancels_and_failure_retries_through_the_primary_control() {
        val attachment = audio()
        var state by mutableStateOf(
            AudioPlaybackState(activeId = attachment.playbackId, loading = true, loadingFraction = 0.25f),
        )
        val toggles = mutableListOf<Pair<AudioAttachment, Long?>>()
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                AudioAttachmentPlayers(
                    attachments = listOf(attachment),
                    playbackState = state,
                    networkId = 7,
                    isSelf = false,
                    onToggle = { item, network -> toggles += item to network },
                    onSeek = { _, _ -> },
                )
            }
        }

        compose.onNodeWithContentDescription("Cancel audio loading").assertIsDisplayed().performClick()
        compose.runOnIdle { state = state.copy(loadingFraction = null) }
        compose.onNodeWithContentDescription("Cancel audio loading").assertIsDisplayed().performClick()
        compose.runOnIdle { state = state.copy(loading = false, error = "Unsupported codec") }
        compose.onNodeWithText("Couldn’t play · Unsupported codec").assertIsDisplayed()
        compose.onNodeWithContentDescription("Retry audio").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(List(3) { attachment to 7L }, toggles) }
    }

    @Test fun playback_http_consent_precedes_download_and_play_and_can_be_cancelled() {
        val attachment = voiceAudio("http://files.example/voice.opus#motd-key=secret").copy(encrypted = true)
        var played: AudioAttachment? = null
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                AudioAttachmentPlayers(
                    attachments = listOf(attachment),
                    playbackState = AudioPlaybackState(),
                    networkId = 7,
                    isSelf = false,
                    onToggle = { item, _ -> played = item },
                    onSeek = { _, _ -> },
                )
            }
        }

        compose.onNodeWithTag("audio_player_toggle").performClick()
        compose.onNodeWithText("Play cleartext audio?").assertIsDisplayed()
        compose.runOnIdle { assertNull(played) }
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertNull(played) }
        compose.onNodeWithTag("audio_player_toggle").performClick()
        compose.onNodeWithText("Play", substring = false).performClick()
        compose.runOnIdle { assertEquals(attachment, played) }
    }

    @Test fun inactive_loading_failed_and_unknown_duration_players_do_not_offer_seek() {
        val attachment = audio().copy(durationMs = 60_000)
        var state by mutableStateOf(AudioPlaybackState())
        var cache by mutableStateOf(AudioCacheStatus.CACHED)
        val seeks = mutableListOf<Long>()
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                AudioAttachmentPlayers(
                    attachments = listOf(attachment),
                    playbackState = state,
                    cacheStatuses = mapOf(attachment.playbackId to cache),
                    networkId = null,
                    isSelf = false,
                    onToggle = { _, _ -> },
                    onSeek = { _, position -> seeks += position },
                )
            }
        }

        val active = AudioPlaybackState(activeId = attachment.playbackId, durationMs = 60_000, canSeek = true)
        for ((next, status) in listOf(
            AudioPlaybackState() to AudioCacheStatus.CACHED,
            AudioPlaybackState() to AudioCacheStatus.NOT_CACHED,
            active.copy(loading = true, canSeek = false) to AudioCacheStatus.CACHED,
            active.copy(error = "Failed", canSeek = false) to AudioCacheStatus.CACHED,
            active.copy(durationMs = null, canSeek = false) to AudioCacheStatus.CACHED,
            active.copy(durationMs = 0, canSeek = false) to AudioCacheStatus.CACHED,
        )) {
            compose.runOnIdle {
                state = next
                cache = status
            }
            compose
                .onNodeWithTag("audio_player_scrubber", useUnmergedTree = true)
                .assertIsNotEnabled()
                .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.SetProgress))
                .performTouchInput { click(center) }
        }
        compose.runOnIdle { assertTrue(seeks.isEmpty()) }
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    @Test
    fun cancelled_download_and_prepare_disable_both_scrubbers_until_media_is_ready() {
        val attachment = audio().copy(durationMs = 60_000)
        val player = ProjectionPlayer()
        var state by mutableStateOf(
            AudioPlaybackState(
                activeId = attachment.playbackId,
                attachment = attachment,
                durationMs = 60_000,
                loading = true,
            ),
        )
        val inlineSeeks = mutableListOf<Long>()
        val miniSeeks = mutableListOf<Long>()
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                Column {
                    AudioAttachmentPlayers(
                        attachments = listOf(attachment),
                        playbackState = state,
                        networkId = null,
                        isSelf = false,
                        onToggle = { _, _ -> },
                        onSeek = { _, position -> inlineSeeks += position },
                    )
                    AudioMiniPlayer(
                        state = state,
                        onToggle = {},
                        onCancelLoading = {},
                        onRetry = {},
                        onDismiss = {},
                        onSeek = { miniSeeks += it },
                        onOpenOrigin = {},
                    )
                }
            }
        }

        val item =
            SimpleBasePlayer.MediaItemData
                .Builder(attachment.playbackId)
                .setMediaItem(MediaItem.Builder().setMediaId(attachment.playbackId).build())
                .setDurationUs(60_000_000)
                .build()
        val ready =
            SimpleBasePlayer.State
                .Builder()
                .setPlaylist(listOf(item))
                .setPlaybackState(Player.STATE_READY)
                .setContentPositionMs(15_000)
                .build()
        val buffering = ready.buildUpon().setPlaybackState(Player.STATE_BUFFERING).build()
        val cancelled = SimpleBasePlayer.State.Builder().build()
        val scrubbers = listOf("audio_player_scrubber", "audio_mini_scrubber")

        fun project(
            next: SimpleBasePlayer.State,
            cancel: Boolean = false,
        ) {
            compose.runOnIdle {
                player.publish(next)
                if (cancel) state = state.copy(loading = false, loadingFraction = null, playing = false, positionMs = 0)
                state = projectAudioPlaybackState(state, player)
            }
        }

        fun assertNoSeek() {
            for (tag in scrubbers) {
                compose
                    .onNodeWithTag(tag, useUnmergedTree = true)
                    .assertIsNotEnabled()
                    .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.SetProgress))
                    .performTouchInput { click(center) }
            }
            compose.runOnIdle {
                assertFalse(state.canSeek)
                assertEquals(attachment.playbackId, state.activeId)
                assertTrue(inlineSeeks.isEmpty())
                assertTrue(miniSeeks.isEmpty())
            }
        }

        // Downloading has no item; cancelling preserves the identity and displayed duration.
        project(cancelled)
        assertNoSeek()
        project(cancelled, cancel = true)
        assertNoSeek()
        compose.runOnIdle {
            assertFalse(state.loading)
            assertEquals(60_000L, state.durationMs)
            assertEquals(0, player.mediaItemCount)
        }

        // A known duration while preparing is still not prepared media.
        project(buffering)
        assertNoSeek()
        project(cancelled, cancel = true)
        assertNoSeek()
        compose.runOnIdle { assertEquals(60_000L, state.durationMs) }

        // Metadata duration cannot make idle, failed, or unknown-duration media seekable.
        for (unprepared in listOf(
            ready.buildUpon().setPlaybackState(Player.STATE_IDLE).build(),
            ready
                .buildUpon()
                .setPlaybackState(Player.STATE_IDLE)
                .setPlayerError(PlaybackException("Failed", null, PlaybackException.ERROR_CODE_UNSPECIFIED))
                .build(),
            ready.buildUpon().setPlaylist(listOf(item.buildUpon().setDurationUs(C.TIME_UNSET).build())).build(),
            ready.buildUpon().setPlaylist(listOf(item.buildUpon().setDurationUs(0).build())).build(),
        )) {
            project(unprepared)
            assertNoSeek()
        }

        for (playing in listOf(false, true)) {
            project(ready.buildUpon().setPlayWhenReady(playing, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST).build())
            compose.runOnIdle {
                assertTrue(state.canSeek)
                assertEquals(playing, state.playing)
            }
            for (tag in scrubbers) {
                val seeks = if (tag == "audio_player_scrubber") inlineSeeks else miniSeeks
                compose
                    .onNodeWithTag(tag, useUnmergedTree = true)
                    .assertIsEnabled()
                    .performTouchInput { click(Offset(width * 0.25f, center.y)) }
                compose.runOnIdle { assertEquals(15_000L, seeks.last()) }
                compose
                    .onNodeWithTag(tag, useUnmergedTree = true)
                    .performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(0.5f)) }
                compose.runOnIdle { assertEquals(30_000L, seeks.last()) }
            }
        }
        compose.runOnIdle {
            inlineSeeks.clear()
            miniSeeks.clear()
        }
        // Removing a previously ready item also withdraws the capability.
        project(cancelled)
        assertNoSeek()
    }

    @Test fun paused_and_playing_players_seek_by_touch_and_accessibility_and_keep_position() {
        val attachment = audio()
        var state by mutableStateOf(
            AudioPlaybackState(activeId = attachment.playbackId, durationMs = 60_000, positionMs = 15_000, canSeek = true),
        )
        val seeks = mutableListOf<Long>()
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                AudioAttachmentPlayers(
                    attachments = listOf(attachment),
                    playbackState = state,
                    networkId = null,
                    isSelf = false,
                    onToggle = { _, _ -> },
                    onSeek = { _, position ->
                        seeks += position
                        state = state.copy(positionMs = position)
                    },
                )
            }
        }

        val scrubber = compose.onNodeWithTag("audio_player_scrubber", useUnmergedTree = true)
        scrubber.assertIsEnabled().assert(
            SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(0.25f, 0f..1f)),
        )
        scrubber.performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(0.5f)) }
        compose.onNodeWithText("0:30 / 1:00").assertIsDisplayed()
        scrubber.performTouchInput { click(Offset(width * 0.25f, center.y)) }
        compose.runOnIdle {
            assertEquals(listOf(30_000L, 15_000L), seeks)
            state = state.copy(playing = true, durationMs = 120_000)
        }
        compose.onNodeWithContentDescription("Pause audio").assertIsDisplayed()
        scrubber.assertIsEnabled().performTouchInput { click(Offset(width * 0.75f, center.y)) }
        scrubber.performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(0.5f)) }
        compose.runOnIdle { assertEquals(listOf(30_000L, 15_000L, 90_000L, 60_000L), seeks) }
        compose.onNodeWithText("1:00 / 2:00").assertIsDisplayed()
    }

    @Test fun real_envelopes_arrive_in_place_and_never_leak_from_another_item() {
        var attachment by mutableStateOf(voiceAudio())
        val other = voiceAudio("https://files.example/other.opus")
        val envelope = AudioWaveform(listOf(0, 31, 0))
        var state by mutableStateOf(
            AudioPlaybackState(activeId = other.playbackId, attachment = other, waveform = envelope),
        )
        var derived by mutableStateOf<Map<String, AudioWaveform>>(emptyMap())
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                AudioAttachmentPlayers(
                    attachments = listOf(attachment),
                    playbackState = state,
                    derivedWaveforms = derived,
                    networkId = null,
                    isSelf = false,
                    onToggle = { _, _ -> },
                    onSeek = { _, _ -> },
                )
            }
        }

        val scrubber = compose.onNodeWithTag("audio_player_scrubber", useUnmergedTree = true)
        val flat = scrubber.captureToImage().asAndroidBitmap()
        val background = flat.getPixel(flat.width / 2, 0)
        val trackRows = (0 until flat.height).count { flat.getPixel(flat.width / 2, it) != background }
        assertTrue("no-data track had $trackRows painted rows", trackRows in 1..with(compose.density) { 4.dp.roundToPx() })
        compose.runOnIdle { derived = mapOf(attachment.playbackId to envelope) }
        val analyzed = scrubber.captureToImage().asAndroidBitmap()
        assertFalse("derived envelope must replace the flat track without remounting", flat.sameAs(analyzed))
        compose.runOnIdle {
            derived = emptyMap()
            state = state.copy(activeId = attachment.playbackId, attachment = attachment)
        }
        assertTrue("current-item envelope must be used", analyzed.sameAs(scrubber.captureToImage().asAndroidBitmap()))
        compose.runOnIdle { attachment = voiceAudio("https://files.example/third.opus") }
        assertTrue("other-item envelope must not leak into this timeline", flat.sameAs(scrubber.captureToImage().asAndroidBitmap()))
        compose.runOnIdle { attachment = attachment.copy(waveform = envelope) }
        assertTrue("supplied envelope must be used", analyzed.sameAs(scrubber.captureToImage().asAndroidBitmap()))
    }

    @Test fun voice_audio_player_remains_compact() {
        val attachment =
            audio().copy(
                voice = true,
                durationMs = 60_000,
                waveform = AudioWaveform(listOf(0, 31, 0)),
            )
        val state =
            AudioPlaybackState(
                activeId = attachment.playbackId,
                attachment = attachment,
                durationMs = 60_000,
                positionMs = 30_000,
                playing = true,
                canSeek = true,
                waveform = attachment.waveform,
            )
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                AudioAttachmentPlayers(
                    attachments = listOf(attachment),
                    playbackState = state,
                    cacheStatuses = mapOf(attachment.playbackId to AudioCacheStatus.CACHED),
                    networkId = null,
                    isSelf = false,
                    onToggle = { _, _ -> },
                    onSeek = { _, _ -> },
                )
            }
        }

        val bounds = compose.onNodeWithTag("audio_player").getUnclippedBoundsInRoot()
        val height = bounds.bottom - bounds.top
        assertTrue("voice player height was $height", height <= 84.dp)
        compose.onNodeWithTag("audio_player_radial_wave", useUnmergedTree = true).assertIsDisplayed()
        val toggle = compose.onNodeWithTag("audio_player_toggle", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val scrubber = compose.onNodeWithTag("audio_player_scrubber", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val details = compose.onNodeWithTag("audio_player_details", useUnmergedTree = true).getUnclippedBoundsInRoot()
        for (target in listOf(toggle, scrubber)) {
            assertTrue("target width was ${target.right - target.left}", target.right - target.left >= 48.dp)
            assertTrue("target height was ${target.bottom - target.top}", target.bottom - target.top >= 48.dp)
        }
        assertTrue(toggle.right <= scrubber.left)
        compose
            .onNodeWithTag("audio_player_details", useUnmergedTree = true)
            .assertTouchWidthIsEqualTo(48.dp)
            .assertTouchHeightIsEqualTo(48.dp)
        assertTrue(scrubber.bottom <= details.top)
    }

    @Test fun voice_and_generic_controls_keep_separate_touch_targets_with_enlarged_text() {
        var voice by mutableStateOf(false)
        var fontScale by mutableStateOf(1f)
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                    AudioAttachmentPlayers(
                        attachments = listOf(audio().copy(voice = voice, encrypted = true, durationMs = 60_000)),
                        playbackState = AudioPlaybackState(),
                        formattedTime = "12:34 PM",
                        networkId = null,
                        isSelf = false,
                        onToggle = { _, _ -> },
                        onSeek = { _, _ -> },
                    )
                }
            }
        }
        for ((nextVoice, scale) in listOf(false to 1f, true to 1f, false to 2f, true to 2f)) {
            compose.runOnIdle {
                voice = nextVoice
                fontScale = scale
            }
            val targets =
                listOf("audio_player_toggle", "audio_player_scrubber", "audio_player_details")
                    .map { compose.onNodeWithTag(it, useUnmergedTree = true).getUnclippedBoundsInRoot() }
            for (target in targets.take(2)) {
                assertTrue(target.right - target.left >= 48.dp)
                assertTrue(target.bottom - target.top >= 48.dp)
            }
            assertTrue(targets[0].right <= targets[1].left)
            compose
                .onNodeWithTag("audio_player_details", useUnmergedTree = true)
                .assertTouchWidthIsEqualTo(48.dp)
                .assertTouchHeightIsEqualTo(48.dp)
            assertTrue(targets[1].bottom <= targets[2].top)
            compose.onNodeWithText("12:34 PM").assertIsDisplayed()
            compose.onNodeWithContentDescription("Encrypted audio").assertIsDisplayed()
        }
    }

    @Test fun mini_player_scrubber_uses_the_full_player_height() {
        val attachment = audio().copy(voice = true, waveform = AudioWaveform(listOf(0, 31, 0)))
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                AudioMiniPlayer(
                    state =
                        AudioPlaybackState(
                            activeId = attachment.playbackId,
                            attachment = attachment,
                            durationMs = 60_000,
                            positionMs = 30_000,
                            playing = true,
                            canSeek = true,
                            waveform = attachment.waveform,
                        ),
                    onToggle = {},
                    onCancelLoading = {},
                    onRetry = {},
                    onDismiss = {},
                    onSeek = {},
                    onOpenOrigin = {},
                )
            }
        }

        val scrubberBounds = compose.onNodeWithTag("audio_mini_scrubber").getUnclippedBoundsInRoot()
        val scrubberHeight = scrubberBounds.bottom - scrubberBounds.top
        assertTrue("scrubber touch height was $scrubberHeight", scrubberHeight >= 48.dp)
        val bannerBounds = compose.onNodeWithTag("audio_mini_player").getUnclippedBoundsInRoot()
        val bannerHeight = bannerBounds.bottom - bannerBounds.top
        assertTrue("mini player height was $bannerHeight", bannerHeight >= 48.dp)
        compose.onNodeWithTag("audio_mini_radial_wave", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun mini_player_error_disables_seek_until_recovery() {
        val attachment = audio()
        var state by mutableStateOf(
            AudioPlaybackState(activeId = attachment.playbackId, attachment = attachment, durationMs = 60_000, error = "Failed"),
        )
        val seeks = mutableListOf<Long>()
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                AudioMiniPlayer(
                    state = state,
                    onToggle = {},
                    onCancelLoading = {},
                    onRetry = {},
                    onDismiss = {},
                    onSeek = { seeks += it },
                    onOpenOrigin = {},
                )
            }
        }
        val scrubber = compose.onNodeWithTag("audio_mini_scrubber", useUnmergedTree = true)
        scrubber
            .assertIsNotEnabled()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.SetProgress))
            .performTouchInput { click(center) }
        compose.runOnIdle {
            assertTrue(seeks.isEmpty())
            state = state.copy(error = null, canSeek = true)
        }
        scrubber.assertIsEnabled().performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(0.5f)) }
        compose.runOnIdle { assertEquals(listOf(30_000L), seeks) }
    }

    @Test fun voice_speed_is_only_available_in_the_mini_player() {
        val attachment = audio().copy(voice = true, waveform = AudioWaveform(listOf(0, 31, 0)))
        var requestedSpeed: Float? = null
        val state =
            AudioPlaybackState(
                activeId = attachment.playbackId,
                attachment = attachment,
                durationMs = 60_000,
                canSeek = true,
                waveform = attachment.waveform,
            )
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                AudioAttachmentPlayers(
                    attachments = listOf(attachment),
                    playbackState = state,
                    networkId = null,
                    isSelf = false,
                    onToggle = { _, _ -> },
                    onSeek = { _, _ -> },
                )
                AudioMiniPlayer(
                    state = state,
                    onToggle = {},
                    onCancelLoading = {},
                    onRetry = {},
                    onDismiss = {},
                    onSeek = {},
                    onOpenOrigin = {},
                    onSpeed = { requestedSpeed = it },
                )
            }
        }

        compose.onAllNodesWithTag("audio_mini_radial_wave", useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithTag("audio_player_radial_wave", useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithTag("audio_speed").assertCountEquals(0)
        compose.onNodeWithTag("audio_mini_speed").assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(requestedSpeed == 1.5f) }
    }

    @Test
    fun transcription_is_hidden_when_lab_is_off() {
        val attachment = voiceAudio()
        showDetails(attachment, transcriptionEnabled = false, transcriptionReady = true)

        compose.onNodeWithTag("audio_transcription_section").assertDoesNotExist()
    }

    @Test
    fun linked_ogg_audio_can_request_transcription() {
        val attachment = audio()
        val origin = audioOrigin(isSelf = false)
        var request: AudioPlaybackRequest? = null
        var force = true
        showDetails(
            attachment = attachment,
            origin = origin,
            onTranscribe = { value, rerun ->
                request = value
                force = rerun
            },
        )

        compose.onNodeWithTag("audio_transcription_start").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(AudioPlaybackRequest(attachment, 7, origin), request)
            assertFalse(force)
        }
    }

    @Test
    fun transcription_is_hidden_for_non_voice_non_ogg_audio() {
        showDetails(
            AudioAttachment(url = "https://files.example/song.mp3"),
            transcriptionEnabled = true,
            transcriptionReady = true,
        )

        compose.onNodeWithTag("audio_transcription_section").assertDoesNotExist()
    }

    @Test
    fun enabled_transcription_without_assignment_shows_setup_guidance() {
        showDetails(voiceAudio(), transcriptionEnabled = true, transcriptionReady = false)

        compose.onNodeWithTag("audio_transcription_setup").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun preparing_waiting_and_transcribing_show_cancel() {
        var transcript by mutableStateOf<VoiceTranscriptState>(VoiceTranscriptState.Preparing(25))
        var cancelled = 0
        showDetails(
            attachment = voiceAudio(),
            transcript = { transcript },
            onCancel = { cancelled++ },
        )

        compose.onNodeWithTag("audio_transcription_cancel").performScrollTo().performClick()
        compose.runOnIdle { transcript = VoiceTranscriptState.Waiting }
        compose.onNodeWithTag("audio_transcription_cancel").performScrollTo().performClick()
        compose.runOnIdle { transcript = VoiceTranscriptState.Transcribing(60) }
        compose.onNodeWithTag("audio_transcription_cancel").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(3, cancelled) }
    }

    @Test
    fun ready_transcript_is_selectable_and_self_rerun_keeps_request_identity() {
        val attachment = voiceAudio("https://files.example/self.opus#motd-key=stored")
        val origin = audioOrigin(isSelf = true)
        var transcript by mutableStateOf<VoiceTranscriptState>(
            VoiceTranscriptState.Ready("hello from the model", cached = true),
        )
        var request: AudioPlaybackRequest? = null
        var force = false
        showDetails(
            attachment = attachment,
            origin = origin,
            transcript = { transcript },
            onTranscribe = { value, rerun ->
                request = value
                force = rerun
            },
        )

        compose.onNodeWithTag("audio_transcription_text").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Loaded from the local transcript cache.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("audio_transcription_again").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(AudioPlaybackRequest(attachment, 7, origin), request)
            assertTrue(force)
            transcript = VoiceTranscriptState.Ready("", cached = false)
        }
        compose.onNodeWithText("No speech detected.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun failed_transcription_has_concrete_retry() {
        val attachment = voiceAudio()
        var request: AudioPlaybackRequest? = null
        var force = true
        showDetails(
            attachment = attachment,
            transcript = {
                VoiceTranscriptState.Failed(VoiceTranscriptFailureKind.AUTHENTICATION_FAILED)
            },
            onTranscribe = { value, rerun ->
                request = value
                force = rerun
            },
        )

        compose
            .onNodeWithText("The encrypted audio could not be authenticated.")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithTag("audio_transcription_retry").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(attachment, request?.attachment)
            assertFalse(force)
        }
    }

    @Test
    fun cleartext_confirmation_precedes_received_encrypted_request() {
        val attachment =
            voiceAudio("http://files.example/received.opus#motd-key=secret")
                .copy(encrypted = true)
        val origin = audioOrigin(isSelf = false)
        var request: AudioPlaybackRequest? = null
        showDetails(
            attachment = attachment,
            origin = origin,
            onTranscribe = { value, _ -> request = value },
        )

        compose.onNodeWithTag("audio_transcription_start").performClick()
        compose.onNodeWithTag("audio_transcription_http_confirm").assertIsDisplayed()
        compose.runOnIdle { assertNull(request) }
        compose.onNodeWithText("Transcribe").performClick()
        compose.runOnIdle {
            assertEquals(AudioPlaybackRequest(attachment, 7, origin), request)
        }
    }

    private fun showDetails(
        attachment: AudioAttachment,
        origin: AudioPlaybackOrigin = audioOrigin(isSelf = false),
        transcriptionEnabled: Boolean = true,
        transcriptionReady: Boolean = true,
        transcript: () -> VoiceTranscriptState? = { null },
        onTranscribe: (AudioPlaybackRequest, Boolean) -> Unit = { _, _ -> },
        onCancel: () -> Unit = {},
    ) {
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                AudioAttachmentPlayers(
                    attachments = listOf(attachment),
                    playbackState = AudioPlaybackState(),
                    networkId = 7,
                    isSelf = origin.isSelf,
                    onToggle = { _, _ -> },
                    onSeek = { _, _ -> },
                    origin = origin,
                    transcripts =
                        transcript()?.let { mapOf(attachment.playbackId to it) }.orEmpty(),
                    transcriptionEnabled = transcriptionEnabled,
                    transcriptionReady = transcriptionReady,
                    onTranscribe = onTranscribe,
                    onCancelTranscription = { onCancel() },
                )
            }
        }
        compose.onNodeWithTag("audio_player_details").performClick()
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    private class ProjectionPlayer : SimpleBasePlayer(Looper.getMainLooper()) {
        private var snapshot = State.Builder().build()

        override fun getState(): State = snapshot

        fun publish(state: State) {
            snapshot = state
            invalidateState()
        }
    }

    private fun voiceAudio(url: String = "https://files.example/voice.opus") =
        AudioAttachment(
            url = url,
            title = "voice.opus",
            mimeType = "audio/ogg",
            voice = true,
        )

    private fun audioOrigin(isSelf: Boolean) =
        AudioPlaybackOrigin(
            bufferId = 9,
            networkId = 7,
            conversation = "#voice",
            sender = if (isSelf) "me" else "alice",
            isSelf = isSelf,
            directMessage = false,
            eventId = if (isSelf) 2 else 1,
            msgid = if (isSelf) "self" else "received",
            serverTime = 10,
        )

    private fun audio() =
        AudioAttachment(
            url = "https://files.example/song.ogg",
            title = "song.ogg",
            mimeType = "audio/ogg",
        )
}
