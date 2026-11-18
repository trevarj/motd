package io.github.trevarj.motd.gesture

import android.content.Context
import android.content.res.Configuration
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.prefs.AppearancePrefs
import io.github.trevarj.motd.data.prefs.PaletteMode
import io.github.trevarj.motd.data.prefs.paletteSelection
import io.github.trevarj.motd.data.repo.BufferRepository
import io.github.trevarj.motd.data.repo.NetworkRepository
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.service.ConnectionManager
import io.github.trevarj.motd.service.ForegroundBufferTracker
import io.github.trevarj.motd.service.ReadMarkerSnapshotter
import io.github.trevarj.motd.service.markChatsRead
import io.github.trevarj.motd.service.unreadBufferIds
import io.github.trevarj.motd.service.unreadChatRows
import io.github.trevarj.motd.ui.chat.AttachmentRequestStore
import io.github.trevarj.motd.ui.chat.ComposerDraftStore
import io.github.trevarj.motd.ui.chat.ComposerPrefill
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** What running a leaf actually achieved, so the overlay can say something useful on release. */
sealed interface GestureActionResult {
    /** The action ran (or its navigation request was published). */
    data object Done : GestureActionResult

    /** The action only means something inside a conversation, and none is on screen. */
    data object NeedsChatContext : GestureActionResult

    /** Nothing to act on: no unread chat, no connected network, no light/dark partner, … */
    data object Unavailable : GestureActionResult
}

/**
 * Runs a [GestureAction] against the app's real state.
 *
 * Everything that touches IRC goes through [ConnectionManager] — the orb is a UI affordance, not a
 * second protocol client — and everything that needs a `NavController` is published on [navRequests]
 * for the overlay host to perform, because this object outlives any composition.
 *
 * Buffer ids stored in a menu are resolved through [BufferRepository.canonicalBufferId] before they
 * are opened or written to: a menu authored months ago can name a room that has since been merged
 * into another, and the redirect is exactly the case a stale id hits.
 */
@Singleton
class GestureActionDispatcher internal constructor(
    private val connections: ConnectionManager,
    private val buffers: BufferRepository,
    private val networks: NetworkRepository,
    private val drafts: ComposerDraftStore,
    private val attachments: AttachmentRequestStore,
    private val appearance: AppearancePrefs,
    foregroundBuffer: ForegroundBufferTracker,
    private val readMarkers: ReadMarkerSnapshotter,
    private val defaultAwayMessage: () -> String,
    private val systemDark: () -> Boolean,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        connections: ConnectionManager,
        buffers: BufferRepository,
        networks: NetworkRepository,
        drafts: ComposerDraftStore,
        attachments: AttachmentRequestStore,
        appearance: AppearancePrefs,
        foregroundBuffer: ForegroundBufferTracker,
        readMarkers: ReadMarkerSnapshotter,
    ) : this(
        connections = connections,
        buffers = buffers,
        networks = networks,
        drafts = drafts,
        attachments = attachments,
        appearance = appearance,
        foregroundBuffer = foregroundBuffer,
        readMarkers = readMarkers,
        defaultAwayMessage = { context.getString(R.string.gesture_away_default_message) },
        systemDark = {
            val mode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            mode == Configuration.UI_MODE_NIGHT_YES
        },
    )

    private val _navRequests =
        MutableSharedFlow<GestureNavRequest>(
            extraBufferCapacity = NAV_BUFFER,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    /**
     * One-shot navigation requests. Zero replay on purpose: a request the host was not around to
     * hear belongs to a UI that no longer exists, and replaying it would navigate on the next launch.
     */
    val navRequests: SharedFlow<GestureNavRequest> = _navRequests.asSharedFlow()

    /** The conversation behind the orb, or null when no chat is on screen. */
    val currentBufferId: StateFlow<Long?> = foregroundBuffer.foregroundBufferId

    suspend fun execute(action: GestureAction): GestureActionResult =
        when (action) {
            is GestureAction.OpenChat -> {
                openChat(action.bufferId)
            }

            GestureAction.OpenSearch -> {
                navigate(GestureNavRequest.OpenSearch)
            }

            GestureAction.OpenChatList -> {
                navigate(GestureNavRequest.OpenChatList)
            }

            GestureAction.ChannelInfoCurrent -> {
                withCurrentChat { navigate(GestureNavRequest.OpenChannelInfo(it)) }
            }

            GestureAction.NextUnread -> {
                nextUnread()
            }

            is GestureAction.InsertMention -> {
                prefillCurrentChat(ComposerPrefill(action.nick, isNickname = true))
            }

            is GestureAction.InsertSnippet -> {
                prefillCurrentChat(ComposerPrefill(action.text))
            }

            is GestureAction.StartQuery -> {
                startQuery(action.networkId, action.nick)
            }

            is GestureAction.ToggleAway -> {
                toggleAway(action.message)
            }

            is GestureAction.ReconnectNetwork -> {
                onNetwork(action.networkId) { connections.connect(it) }
            }

            is GestureAction.DisconnectNetwork -> {
                onNetwork(action.networkId) { connections.disconnect(it) }
            }

            is GestureAction.JoinChannel -> {
                onNetwork(action.networkId) {
                    connections.joinChannel(it, action.channel, action.key)
                }
            }

            GestureAction.MarkAllRead -> {
                markAllRead()
            }

            GestureAction.ToggleTheme -> {
                toggleTheme()
            }

            GestureAction.AttachCurrent -> {
                withCurrentChat { bufferId ->
                    attachments.push(bufferId)
                    GestureActionResult.Done
                }
            }

            // A leaf this build cannot interpret is inert rather than fatal; the node itself is kept.
            is GestureAction.Unknown -> {
                GestureActionResult.Unavailable
            }
        }

    private suspend fun openChat(bufferId: Long): GestureActionResult {
        val canonical = canonical(bufferId) ?: return GestureActionResult.Unavailable
        return navigate(GestureNavRequest.OpenChat(canonical))
    }

    private suspend fun nextUnread(): GestureActionResult {
        val row =
            unreadChatRows(buffers.observeChatList().first()).firstOrNull()
                ?: return GestureActionResult.Unavailable
        return openChat(row.bufferId)
    }

    /**
     * Queue composer text for the chat behind the orb and ask for it to be shown.
     *
     * The navigation request matters even when that chat is already the visible one: on a phone the
     * orb can be held over a chat whose screen is still composed, and the request is a no-op there,
     * while [ComposerDraftStore.prefillPushes] is what actually delivers the text into an open
     * composer (a `launchSingleTop` re-entry runs no entry effect to drain it).
     */
    private suspend fun prefillCurrentChat(prefill: ComposerPrefill): GestureActionResult =
        withCurrentChat { bufferId ->
            drafts.push(bufferId, prefill)
            navigate(GestureNavRequest.OpenChat(bufferId))
        }

    private suspend fun startQuery(
        networkId: Long,
        nick: String,
    ): GestureActionResult {
        if (nick.isBlank()) return GestureActionResult.Unavailable
        if (networks.networkById(networkId) == null) return GestureActionResult.Unavailable
        val bufferId =
            try {
                connections.ensureQueryBuffer(networkId, nick)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return GestureActionResult.Unavailable
            }
        return openChat(bufferId)
    }

    /**
     * Flip self-away across connected networks as one switch.
     *
     * Away wins as the "off" direction: if *any* connected network still reports us away, the
     * gesture brings those back rather than adding more. A single leaf must not leave the account
     * half-away, which is what an independent per-network toggle would do.
     */
    private suspend fun toggleAway(message: String?): GestureActionResult {
        val ready =
            connections.connectionStates.value
                .filterValues { it is IrcClientState.Ready }
                .keys
        if (ready.isEmpty()) return GestureActionResult.Unavailable
        val away = ready intersect connections.selfAwayStates.value.keys
        val targets = away.ifEmpty { ready }
        // AWAY with no parameter *is* "back", so going away always needs text of some kind.
        val text = if (away.isEmpty()) message?.takeIf { it.isNotBlank() } ?: defaultAwayMessage() else null
        targets.sorted().forEach { networkId -> writeAway(networkId, text) }
        return GestureActionResult.Done
    }

    private suspend fun markAllRead(): GestureActionResult {
        val bufferIds = unreadBufferIds(buffers.observeChatList().first())
        if (bufferIds.isEmpty()) return GestureActionResult.Unavailable
        markChatsRead(bufferIds, readMarkers, connections)
        return GestureActionResult.Done
    }

    /** Pin the opposite of the displayed mode without changing palette identity. */
    private suspend fun toggleTheme(): GestureActionResult {
        val config = appearance.config.first()
        val selection = config.paletteSelection
        val dark =
            when (selection.mode) {
                PaletteMode.SYSTEM -> systemDark()
                PaletteMode.LIGHT -> false
                PaletteMode.DARK -> true
            }
        appearance.selectPalette(selection.copy(mode = if (dark) PaletteMode.LIGHT else PaletteMode.DARK))
        return GestureActionResult.Done
    }

    private suspend fun onNetwork(
        networkId: Long,
        act: suspend (Long) -> Unit,
    ): GestureActionResult {
        if (networks.networkById(networkId) == null) return GestureActionResult.Unavailable
        return try {
            act(networkId)
            GestureActionResult.Done
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            GestureActionResult.Unavailable
        }
    }

    private suspend fun writeAway(
        networkId: Long,
        message: String?,
    ) {
        try {
            connections.setAway(networkId, message)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // selfAwayStates only moves on server confirmation, so a lost write simply does nothing.
        }
    }

    private suspend fun withCurrentChat(
        act: (Long) -> GestureActionResult,
    ): GestureActionResult {
        val bufferId = currentBufferId.value ?: return GestureActionResult.NeedsChatContext
        val canonical = canonical(bufferId) ?: return GestureActionResult.NeedsChatContext
        return act(canonical)
    }

    private suspend fun canonical(bufferId: Long): Long? =
        try {
            buffers.canonicalBufferId(bufferId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }

    private fun navigate(request: GestureNavRequest): GestureActionResult {
        _navRequests.tryEmit(request)
        return GestureActionResult.Done
    }

    private companion object {
        const val NAV_BUFFER = 4
    }
}
