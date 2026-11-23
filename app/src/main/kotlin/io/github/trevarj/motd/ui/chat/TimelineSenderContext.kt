package io.github.trevarj.motd.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SnapshotMutationPolicy
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.ui.components.Avatar
import io.github.trevarj.motd.ui.components.avatarsHidden
import io.github.trevarj.motd.ui.components.dayLabelKind
import io.github.trevarj.motd.ui.components.localizedDayLabel
import io.github.trevarj.motd.ui.theme.readableForeground
import java.util.TimeZone

internal data class TimelineSender(
    val name: String,
    val label: String,
    val normalized: String,
    val account: String?,
    val isSelf: Boolean,
)

internal data class TimelineSenderRow(
    val sender: TimelineSender,
    val opensGroup: Boolean,
    val hasNick: Boolean,
    val hasLeadingDecoration: Boolean,
)

internal fun viewportSender(
    rows: List<LazyListItemInfo>,
    bottom: Int,
    top: Int,
    edgeSlackPx: Int,
    metricsFor: (Long) -> TimelineRowContentMetrics?,
): TimelineSender? {
    if (top - bottom <= 2 * edgeSlackPx) return null
    var previous: TimelineSenderRow? = null
    var sender: TimelineSender? = null
    var oldestBodyTop = bottom
    var newestBodyBottom = top
    for (item in rows) {
        if (item.size == 0 || item.offset >= top || item.offset + item.size <= bottom) continue
        val key = item.key as? Long ?: return null
        val metrics = metricsFor(key) ?: return null
        val row = metrics.senderRow ?: return null
        val body = metrics.senderBody ?: return null
        val rowTop = item.offset + item.size
        val bodyTop = rowTop - body.first
        val bodyBottom = rowTop - body.last
        if (bodyTop <= bottom) {
            if (row.hasLeadingDecoration) return null
            continue
        }
        if (bodyBottom >= top) return null
        if (row.hasLeadingDecoration && bodyTop < minOf(rowTop, top)) return null
        if (bodyBottom > maxOf(item.offset, bottom)) return null
        if (row.hasNick) {
            val nick = metrics.senderNick ?: return null
            if (rowTop - nick.first > bottom && rowTop - nick.last < top) return null
        }
        if (previous?.opensGroup == true) return null
        val first = sender
        if (first == null) {
            sender = row.sender
        } else if (
            first.normalized != row.sender.normalized || first.isSelf != row.sender.isSelf ||
            (first.account != null && row.sender.account != null && first.account != row.sender.account)
        ) {
            return null
        }
        previous = row
        oldestBodyTop = maxOf(oldestBodyTop, bodyTop)
        newestBodyBottom = minOf(newestBodyBottom, bodyBottom)
    }
    return sender.takeIf { oldestBodyTop >= top - edgeSlackPx && newestBodyBottom <= bottom + edgeSlackPx }
}

internal fun viewportDateTime(
    rows: List<LazyListItemInfo>,
    bottom: Int,
    top: Int,
    visibleMessageAt: (Int) -> MessageEntity?,
): Long? {
    if (bottom >= top) return null
    var leading: LazyListItemInfo? = null
    var leadingTop = bottom
    for (index in rows.indices) {
        val row = rows[index]
        val rowTop = row.offset + row.size
        if (row.size <= 0 || row.offset >= top || rowTop <= leadingTop) continue
        leading = row
        leadingTop = rowTop
    }
    // A skeleton at the leading edge owns that context until its message is loaded.
    val row = leading ?: return null
    val eventId = row.key as? Long ?: return null
    if (eventId <= 0L) return null
    val message = visibleMessageAt(row.index) ?: return null
    // Grouped system rows keep every expanded line on the slot owner's local day.
    return message.serverTime.takeIf { message.id == eventId }
}

internal fun timelineDatePolicy(zone: TimeZone): SnapshotMutationPolicy<Long?> =
    object : SnapshotMutationPolicy<Long?> {
        override fun equivalent(
            a: Long?,
            b: Long?,
        ): Boolean = a == b || (a != null && b != null && dayKey(a) == dayKey(b))

        // The offset at this instant assigns each timestamp to its local date, including DST.
        private fun dayKey(time: Long): Long = time / DAY_MS + Math.floorDiv(time % DAY_MS + zone.getOffset(time), DAY_MS)
    }

private const val DAY_MS = 86_400_000L

@Composable
internal fun TimelineSenderContext(
    listState: LazyListState,
    metrics: Map<Long, TimelineRowContentMetrics>,
    networkId: Long?,
    readTopHostHeightPx: () -> Int,
    readBottomHostHeightPx: () -> Int,
    precedingHeightPx: Int,
    edgeSlackPx: Int,
    userScrolling: Boolean,
    visibleMessageAt: (Int) -> MessageEntity?,
) {
    val preceding by rememberUpdatedState(precedingHeightPx)
    val slack by rememberUpdatedState(edgeSlackPx)
    val current: TimelineSender?
    val label: String
    if (userScrolling) {
        val messageAt by rememberUpdatedState(visibleMessageAt)
        val configuration = LocalConfiguration.current
        val time by remember(listState, readTopHostHeightPx, readBottomHostHeightPx, configuration) {
            derivedStateOf(timelineDatePolicy(TimeZone.getDefault())) {
                val layout = listState.layoutInfo
                viewportDateTime(
                    layout.visibleItemsInfo,
                    bottom = layout.viewportStartOffset + readBottomHostHeightPx(),
                    top = layout.viewportEndOffset - readTopHostHeightPx() - preceding,
                    visibleMessageAt = messageAt,
                )
            }
        }
        current = null
        val dayTime = time ?: return
        label = localizedDayLabel(remember(dayTime, configuration) { dayLabelKind(dayTime) })
    } else {
        val sender by remember(listState, metrics, readTopHostHeightPx, readBottomHostHeightPx) {
            derivedStateOf(structuralEqualityPolicy()) {
                val layout = listState.layoutInfo
                viewportSender(
                    layout.visibleItemsInfo,
                    bottom = layout.viewportStartOffset + readBottomHostHeightPx(),
                    top = layout.viewportEndOffset - readTopHostHeightPx() - preceding,
                    edgeSlackPx = slack,
                    metricsFor = metrics::get,
                )
            }
        }
        current = sender ?: return
        label = current.label
    }
    val scheme = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.extraLarge
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .padding(horizontal = 24.dp, vertical = 4.dp)
                .testTag(if (userScrolling) "chat_timeline_date" else "chat_timeline_sender")
                .shadow(2.dp, shape)
                .background(scheme.surfaceContainerHigh, shape)
                .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        if (current != null && !avatarsHidden()) {
            Avatar(current.name, modifier = Modifier.clearAndSetSemantics {}, size = 20.dp, networkId = networkId, account = current.account)
        }
        Text(
            label,
            color = scheme.readableForeground(scheme.onSurface, scheme.surfaceContainerHigh),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
