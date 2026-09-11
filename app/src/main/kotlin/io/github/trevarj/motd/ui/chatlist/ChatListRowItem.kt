package io.github.trevarj.motd.ui.chatlist

import android.text.format.DateFormat
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.trevarj.motd.R
import io.github.trevarj.motd.audio.displayTextForAudioMessage
import io.github.trevarj.motd.audio.formatAudioDuration
import io.github.trevarj.motd.audio.parseAudioAttachments
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.ChatListRow
import io.github.trevarj.motd.data.prefs.TimeFormat
import io.github.trevarj.motd.dickord.LocalDickordLabsEnabled
import io.github.trevarj.motd.dickord.dickordChannelLabel
import io.github.trevarj.motd.dickord.dickordNickLabel
import io.github.trevarj.motd.dickord.isDickordChannel
import io.github.trevarj.motd.service.PresenceState
import io.github.trevarj.motd.ui.components.AdvertisedActivityDot
import io.github.trevarj.motd.ui.components.Avatar
import io.github.trevarj.motd.ui.components.HistorySyncSpinner
import io.github.trevarj.motd.ui.components.MentionBadge
import io.github.trevarj.motd.ui.components.MutedActivityBadge
import io.github.trevarj.motd.ui.components.NetworkChip
import io.github.trevarj.motd.ui.components.UnreadBadge
import io.github.trevarj.motd.ui.components.avatarsHidden
import io.github.trevarj.motd.ui.components.rememberMessageTimeFormatter
import io.github.trevarj.motd.ui.components.resolveIs24Hour
import io.github.trevarj.motd.ui.theme.LocalMotdSemanticColors
import io.github.trevarj.motd.ui.theme.LocalNickColors
import io.github.trevarj.motd.ui.theme.LocalSpacing
import io.github.trevarj.motd.ui.theme.LocalTimestampConfig
import io.github.trevarj.motd.ui.theme.MotdMotion
import io.github.trevarj.motd.ui.theme.MotdShapes
import io.github.trevarj.motd.ui.theme.MotdTheme

internal data class ChatListBadgeState(
    val mutedActivity: Int? = null,
    val mentions: Int? = null,
    val unread: Int? = null,
    val mutedActivityIncomplete: Boolean = false,
    val mentionsIncomplete: Boolean = false,
    val unreadIncomplete: Boolean = false,
    /**
     * Discovery says this room has activity the device has not fetched yet, and no local row backs
     * a count. Shown as a dot rather than a number: a count would be invented.
     */
    val advertisedActivity: Boolean = false,
)

internal enum class ChatListRowVisualState { DEFAULT, UNREAD, ACTIVE, SELECTED }

internal fun chatListRowVisualState(
    selected: Boolean,
    active: Boolean,
    unread: Boolean,
): ChatListRowVisualState =
    when {
        selected -> ChatListRowVisualState.SELECTED
        active -> ChatListRowVisualState.ACTIVE
        unread -> ChatListRowVisualState.UNREAD
        else -> ChatListRowVisualState.DEFAULT
    }

internal fun chatListRowContainer(
    state: ChatListRowVisualState,
    scheme: ColorScheme,
): Color =
    when (state) {
        ChatListRowVisualState.SELECTED -> scheme.secondaryContainer

        ChatListRowVisualState.ACTIVE -> scheme.primaryContainer

        ChatListRowVisualState.UNREAD -> lerp(scheme.surface, scheme.primaryContainer, 0.48f)

        // Alpha-zero surface, not Color.Transparent: the row's colorFade interpolates color channels
        // independently of alpha, and Color.Transparent is transparent BLACK — fading a tint from it
        // dragged every unread/select transition through a semi-opaque dark blend, which flashed the
        // row dark each time a message landed. Alpha 0 still draws nothing, so resting rows render
        // exactly as before over any backing.
        ChatListRowVisualState.DEFAULT -> scheme.surface.copy(alpha = 0f)
    }

/**
 * The advertised cue only ever stands IN for a count, never beside one: once a real unread count
 * exists the dot would be redundant noise, and by then the fetched rows have usually caught the
 * room up anyway. A muted row keeps its subdued treatment and shows no advertised cue at all — the
 * whole point of muting is not to be told about activity ahead of time.
 */
internal fun chatListBadgeState(row: ChatListRow): ChatListBadgeState =
    if (row.muted) {
        ChatListBadgeState(
            mutedActivity = row.unreadCount.takeIf { it > 0 },
            mutedActivityIncomplete = row.unreadCountIncomplete,
        )
    } else {
        ChatListBadgeState(
            mentions = row.mentionCount.takeIf { it > 0 },
            unread = row.unreadCount.takeIf { it > 0 },
            mentionsIncomplete = row.mentionCountIncomplete,
            unreadIncomplete = row.unreadCountIncomplete,
            advertisedActivity = row.advertisedUnread && row.unreadCount == 0,
        )
    }

internal sealed interface ChatListMessagePreview {
    data class Text(
        val value: String,
    ) : ChatListMessagePreview

    data class Voice(
        val durationMs: Long?,
    ) : ChatListMessagePreview
}

internal fun chatListMessagePreview(text: String?): ChatListMessagePreview {
    val value = text.orEmpty()
    val attachments = parseAudioAttachments(value)
    val voice =
        attachments.singleOrNull()?.takeIf { attachment ->
            attachment.voice && displayTextForAudioMessage(value, attachments).isBlank()
        }
    return voice?.let { ChatListMessagePreview.Voice(it.durationMs) }
        ?: ChatListMessagePreview.Text(value)
}

internal fun chatListPreviewSender(
    type: BufferType,
    messageText: String?,
    sender: String?,
): String? =
    sender?.takeIf {
        // System events use an empty sender, which must not leave an empty label chip.
        type == BufferType.CHANNEL && messageText != null && it.isNotBlank()
    }

/**
 * One chat-list row: avatar, display name, supporting network/last-message line, relative time, and
 * unread/mention badges. Muted rows use subdued semantic colors with a bell-off glyph.
 * Pinned rows carry a small inline [Icons.Outlined.PushPin] beside the name (there is no separate
 * "Pinned" section; pinning gives the row global list priority).
 *
 * Round 4: a friend row gets a trailing [Icons.Filled.Star]
 * plus a quiet raised-surface background behind the display name, layered under the nick color.
 */
@Composable
fun ChatListRowItem(
    row: ChatListRow,
    showNetworkChip: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    isFriend: Boolean = false,
    presence: PresenceState? = null,
    selected: Boolean = false,
    active: Boolean = false,
    syncIndicator: ChatListSyncIndicator = ChatListSyncIndicator.NONE,
    displayTitle: String? = null,
    showDickordBadge: Boolean = true,
    avatarName: String = row.displayName,
    avatarIsChannel: Boolean = row.type == BufferType.CHANNEL,
    avatarModel: String? = row.avatarOverrideModel,
) {
    // Resolved per-nick color (also used to tint the friend star), matching sender coloring.
    val nickColor = LocalNickColors.current.nick(row.displayName, MaterialTheme.colorScheme.onSurfaceVariant)
    val spacing = LocalSpacing.current
    val dickordEnabled = LocalDickordLabsEnabled.current
    val activeDickordChannel = dickordEnabled && isDickordChannel(row.displayName)
    // Chat-list time always shows regardless of the in-chat "show timestamps" toggle.
    val context = LocalContext.current
    val timestampConfig = LocalTimestampConfig.current
    // Reads a system setting via a Binder call; memoize per row rather than re-querying on every
    // recomposition (this composable is invoked once per visible row).
    val is24HourDevice = remember(context) { DateFormat.is24HourFormat(context) }
    val is24Hour = resolveIs24Hour(timestampConfig.format, is24HourDevice)
    val formatTimestamp = rememberMessageTimeFormatter()
    val queryPresence = presence.takeIf { row.type == BufferType.QUERY }
    val badges = chatListBadgeState(row)
    val isUnread = !row.muted && row.unreadCount > 0
    val visualState = chatListRowVisualState(selected, active, isUnread)
    // Ease select/deselect, unread clearing, and the active highlight moving between rows. Rows
    // are keyed by bufferId, so recycling can never cross-fade one buffer's tint into another's.
    val rowContainer by animateColorAsState(
        targetValue = chatListRowContainer(visualState, MaterialTheme.colorScheme),
        animationSpec = MotdMotion.colorFade,
        label = "chatlist_row_container",
    )
    val activeIndicator = MaterialTheme.colorScheme.primary
    val hideAvatar = avatarsHidden()
    val presenceDescription =
        queryPresence?.let {
            stringResource(
                when (it) {
                    PresenceState.ONLINE -> R.string.presence_online
                    PresenceState.OFFLINE -> R.string.presence_offline
                    PresenceState.UNKNOWN -> R.string.presence_unknown
                },
            )
        }
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 2.dp)
                .clip(MotdShapes.card)
                .background(rowContainer)
                .drawBehind {
                    if (active) {
                        val width = 3.dp.toPx()
                        val height = size.height * 0.58f
                        drawRoundRect(
                            color = activeIndicator,
                            topLeft = Offset(0f, (size.height - height) / 2f),
                            size = Size(width, height),
                            cornerRadius = CornerRadius(width / 2f),
                        )
                    }
                }
                // Per-buffer handle so the harness selects a specific row (display names collide).
                .testTag("chatlist_row_${row.bufferId}")
                .semantics { this.selected = selected || active }
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .defaultMinSize(minHeight = 48.dp)
                .then(
                    if (presenceDescription != null) {
                        Modifier.semantics { stateDescription = presenceDescription }
                    } else {
                        Modifier
                    },
                ).padding(horizontal = 12.dp, vertical = spacing.chatListVPad),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!hideAvatar) {
            PresenceAvatar(
                name = avatarName,
                isChannel = avatarIsChannel,
                networkId = row.networkId,
                conversationModel = avatarModel,
                presence = queryPresence,
                size = spacing.chatListAvatar,
            )
            Spacer(Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The presence dot loses its avatar anchor when avatars are hidden, so it leads the
                // title line instead: query presence stays visible either way.
                if (hideAvatar && queryPresence != null) {
                    PresenceBadge(
                        visual = presenceBadgeVisual(queryPresence),
                        tag = "chatlist_presence_${queryPresence.name.lowercase()}",
                        modifier = Modifier.padding(end = 6.dp),
                    )
                }
                // Quiet raised surface behind a non-muted friend's name.
                val nameModifier =
                    if (isFriend && !row.muted) {
                        Modifier
                            .weight(1f, fill = false)
                            .clip(MotdShapes.tag)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    } else {
                        Modifier.weight(1f, fill = false)
                    }
                Text(
                    text = displayTitle ?: dickordChannelLabel(row.displayName, dickordEnabled),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (isUnread) FontWeight.Bold else FontWeight.Medium,
                    color =
                        when {
                            row.muted -> MaterialTheme.colorScheme.onSurfaceVariant
                            isFriend -> nickColor
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = nameModifier,
                )
                if (isFriend) {
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = null,
                        modifier =
                            Modifier
                                .padding(start = 4.dp)
                                .size(14.dp),
                        tint = if (row.muted) MaterialTheme.colorScheme.onSurfaceVariant else nickColor,
                    )
                }
                if (row.pinned) {
                    // Inline pin marker (replaces the former "Pinned" section); subtle, unread-neutral.
                    Icon(
                        imageVector = Icons.Outlined.PushPin,
                        contentDescription = stringResource(R.string.chatlist_pinned),
                        modifier =
                            Modifier
                                .testTag("chatlist_row_pin")
                                .padding(start = 4.dp)
                                .size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (row.muted) {
                    Icon(
                        imageVector = Icons.Filled.NotificationsOff,
                        contentDescription = null,
                        modifier =
                            Modifier
                                .padding(start = 4.dp)
                                .size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Network belongs to buffer identity, so it rides the title line (trailing the
                // status icons); keeping it off the preview line avoids a two-chip collision
                // with the sender label.
                if (showDickordBadge && activeDickordChannel) {
                    Spacer(Modifier.width(6.dp))
                    NetworkChip(
                        name = stringResource(R.string.dickord_badge),
                        dimmed = true,
                        emphasized = isUnread,
                    )
                }
                if (showNetworkChip) {
                    Spacer(Modifier.width(6.dp))
                    NetworkChip(
                        name = row.networkName,
                        dimmed = true,
                        emphasized = isUnread,
                    )
                }
                // The sync cue trails the network chip on the title line, well away from the
                // trailing unread badge it used to overlap.
                if (syncIndicator != ChatListSyncIndicator.NONE) {
                    Spacer(Modifier.width(6.dp))
                    SyncStatusBadgeContent(syncIndicator)
                }
            }
            Spacer(Modifier.size(2.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Channel previews lead with a sender label chip (no colon, which collided
                // with nick mentions in the message text); queries read cleaner without it.
                val lastMessage = row.lastMessageText
                val preview = chatListMessagePreview(lastMessage)
                val sender = chatListPreviewSender(row.type, lastMessage, row.lastMessageSender)
                if (sender != null) {
                    SenderLabel(
                        sender = dickordNickLabel(sender, dickordEnabled),
                        color =
                            LocalNickColors.current.nick(
                                sender,
                                MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        unread = isUnread,
                    )
                    Spacer(Modifier.width(6.dp))
                }
                if (preview is ChatListMessagePreview.Voice) {
                    Box(
                        modifier =
                            Modifier
                                .size(18.dp)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                    Spacer(Modifier.width(5.dp))
                }
                Text(
                    text =
                        when (preview) {
                            is ChatListMessagePreview.Text -> {
                                androidx.compose.ui.text
                                    .AnnotatedString(preview.value)
                            }

                            is ChatListMessagePreview.Voice -> {
                                androidx.compose.ui.text.AnnotatedString(
                                    buildString {
                                        append(stringResource(R.string.chatlist_voice_message))
                                        preview.durationMs?.let { append(" · ${formatAudioDuration(it)}") }
                                    },
                                )
                            }
                        },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isUnread) FontWeight.Medium else FontWeight.Normal,
                    color =
                        if (isUnread) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            row.lastMessageTime?.let { time ->
                Text(
                    text =
                        if (timestampConfig.format == TimeFormat.CUSTOM) {
                            formatTimestamp(time)
                        } else {
                            relativeChatTime(time, is24Hour = is24Hour)
                        },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                badges.mutedActivity?.let { count ->
                    MutedActivityBadge(
                        count = count,
                        lowerBound = badges.mutedActivityIncomplete,
                        modifier = Modifier.testTag("chatlist_row_muted_activity_badge"),
                    )
                }
                badges.mentions?.let { count ->
                    MentionBadge(
                        count = count,
                        lowerBound = badges.mentionsIncomplete,
                        modifier = Modifier.testTag("chatlist_row_mention_badge"),
                    )
                }
                badges.unread?.let { count ->
                    UnreadBadge(
                        count = count,
                        lowerBound = badges.unreadIncomplete,
                        modifier = Modifier.testTag("chatlist_row_unread_badge"),
                    )
                }
                if (badges.advertisedActivity) {
                    AdvertisedActivityDot(
                        modifier = Modifier.testTag("chatlist_row_advertised_activity_dot"),
                    )
                }
            }
        }
    }
}

internal enum class PresenceBadgeVisual { FILLED, HOLLOW, UNKNOWN }

internal fun presenceBadgeVisual(presence: PresenceState): PresenceBadgeVisual =
    when (presence) {
        PresenceState.ONLINE -> PresenceBadgeVisual.FILLED
        PresenceState.OFFLINE -> PresenceBadgeVisual.HOLLOW
        PresenceState.UNKNOWN -> PresenceBadgeVisual.UNKNOWN
    }

/**
 * Avatar with the query presence dot overlaid on its corner. With avatars hidden the box collapses
 * and the dot is re-anchored inline by the caller, since presence is row state that must survive
 * losing its anchor.
 */
@Composable
private fun PresenceAvatar(
    name: String,
    isChannel: Boolean,
    networkId: Long,
    conversationModel: String?,
    presence: PresenceState?,
    size: Dp,
) {
    Box(modifier = Modifier.size(size)) {
        Avatar(
            name = name,
            isChannel = isChannel,
            networkId = networkId,
            conversationModel = conversationModel,
            size = size,
            modifier = Modifier.align(Alignment.Center),
        )
        presence?.let { state ->
            PresenceBadge(
                visual = presenceBadgeVisual(state),
                tag = "chatlist_presence_${state.name.lowercase()}",
                modifier = Modifier.align(Alignment.BottomEnd),
            )
        }
    }
}

/**
 * Per-row history-sync cue, rendered inline on the title line after the network chip so it never
 * collides with the trailing unread badge. No live region: rows churn constantly during a resync
 * pass, and announcing every transition would spam TalkBack.
 */
@Composable
private fun SyncStatusBadgeContent(indicator: ChatListSyncIndicator) {
    when (indicator) {
        ChatListSyncIndicator.SYNCING -> {
            HistorySyncSpinner(
                contentDescription = stringResource(R.string.chatlist_sync_syncing),
                modifier = Modifier.testTag("chatlist_row_sync_syncing"),
            )
        }

        ChatListSyncIndicator.QUEUED -> {
            val description = stringResource(R.string.chatlist_sync_queued)
            Box(
                modifier =
                    Modifier
                        .size(10.dp)
                        .testTag("chatlist_row_sync_queued")
                        .semantics { contentDescription = description }
                        .border(1.5.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), CircleShape),
            )
        }

        ChatListSyncIndicator.WAITING -> {
            // The same ring as QUEUED, dimmer: nothing is moving yet, and it must not read as an
            // error just because the app opened offline.
            val description = stringResource(R.string.chatlist_sync_waiting)
            Box(
                modifier =
                    Modifier
                        .size(10.dp)
                        .testTag("chatlist_row_sync_waiting")
                        .semantics { contentDescription = description }
                        .border(1.5.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f), CircleShape),
            )
        }

        ChatListSyncIndicator.ERROR -> {
            val description = stringResource(R.string.chatlist_sync_error)
            Box(
                modifier =
                    Modifier
                        .size(8.dp)
                        .testTag("chatlist_row_sync_error")
                        .semantics { contentDescription = description }
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.error),
            )
        }

        ChatListSyncIndicator.UNAVAILABLE -> {
            // Terminal but not a failure: the server simply refuses history for this target.
            val description = stringResource(R.string.chatlist_sync_unavailable)
            Icon(
                imageVector = Icons.Outlined.Block,
                contentDescription = description,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier =
                    Modifier
                        .size(10.dp)
                        .testTag("chatlist_row_sync_unavailable"),
            )
        }

        ChatListSyncIndicator.NONE -> {}
    }
}

@Composable
private fun PresenceBadge(
    visual: PresenceBadgeVisual,
    tag: String,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val online = LocalMotdSemanticColors.current.success
    Box(
        modifier =
            modifier
                .size(14.dp)
                .clip(CircleShape)
                .background(scheme.background)
                .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        when (visual) {
            PresenceBadgeVisual.FILLED -> {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(online)
                        .clearAndSetSemantics {},
                )
            }

            PresenceBadgeVisual.HOLLOW -> {
                Box(
                    Modifier
                        .size(10.dp)
                        .border(2.dp, scheme.onSurfaceVariant, CircleShape)
                        .clearAndSetSemantics {},
                )
            }

            PresenceBadgeVisual.UNKNOWN -> {
                Box(
                    modifier =
                        Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(scheme.surfaceContainerHighest)
                            .clearAndSetSemantics {},
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "?",
                        color = scheme.onSurfaceVariant,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 8.sp,
                    )
                }
            }
        }
    }
}

/**
 * Sender label chip for channel previews: nick-tinted rounded chip (mirrors
 * [NetworkChip] metrics), no colon so a nick mention in the text no longer reads
 * as a double `nick: nick:`. Dimmed to match the message-preview prominence:
 * the nick hue stays opaque while weight tracks the row's unread state, so
 * the label reads as part of the preview line rather than a louder element.
 */
@Composable
internal fun SenderLabel(
    sender: String,
    color: Color,
    unread: Boolean,
    modifier: Modifier = Modifier,
) {
    val container =
        if (unread) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        }
    Box(
        modifier =
            modifier
                .background(container, MotdShapes.tag)
                .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Text(
            text = sender,
            color = color,
            fontWeight = if (unread) FontWeight.Medium else FontWeight.Normal,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@PreviewLightDark
@Composable
private fun ChatListPresencePreview() {
    MotdTheme {
        Column {
            ChatListRowItem(
                row =
                    ChatListRow(
                        bufferId = 1,
                        networkId = 1,
                        networkName = "Libera",
                        displayName = "alice",
                        type = BufferType.QUERY,
                        pinned = true,
                        muted = false,
                        lastMessageText = "I am around",
                        lastMessageSender = "alice",
                        lastMessageTime = System.currentTimeMillis() - 120_000,
                        unreadCount = 12,
                        mentionCount = 2,
                    ),
                showNetworkChip = true,
                onClick = {},
                onLongClick = {},
                isFriend = true,
                presence = PresenceState.ONLINE,
            )
            ChatListRowItem(
                row =
                    ChatListRow(
                        bufferId = 2,
                        networkId = 1,
                        networkName = "Libera",
                        displayName = "bob",
                        type = BufferType.QUERY,
                        pinned = false,
                        muted = false,
                        lastMessageText = "see you later",
                        lastMessageSender = "bob",
                        lastMessageTime = System.currentTimeMillis() - 3_600_000 * 26,
                        unreadCount = 0,
                        mentionCount = 0,
                    ),
                showNetworkChip = false,
                onClick = {},
                onLongClick = {},
                presence = PresenceState.OFFLINE,
            )
            ChatListRowItem(
                row =
                    ChatListRow(
                        bufferId = 3,
                        networkId = 1,
                        networkName = "Libera",
                        displayName = "carol",
                        type = BufferType.QUERY,
                        pinned = false,
                        muted = true,
                        lastMessageText = "reconnecting",
                        lastMessageSender = "carol",
                        lastMessageTime = System.currentTimeMillis() - 60_000,
                        unreadCount = 7,
                        mentionCount = 1,
                    ),
                showNetworkChip = true,
                onClick = {},
                onLongClick = {},
                presence = PresenceState.UNKNOWN,
            )
            ChatListRowItem(
                row =
                    ChatListRow(
                        bufferId = 4,
                        networkId = 1,
                        networkName = "Libera",
                        displayName = "#motd",
                        type = BufferType.CHANNEL,
                        pinned = false,
                        muted = false,
                        lastMessageText = "alice: welcome @bob",
                        lastMessageSender = "alice",
                        lastMessageTime = System.currentTimeMillis() - 30_000,
                        unreadCount = 3,
                        mentionCount = 1,
                    ),
                showNetworkChip = true,
                onClick = {},
                onLongClick = {},
            )
        }
    }
}
