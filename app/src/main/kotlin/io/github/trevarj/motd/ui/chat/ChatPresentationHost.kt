package io.github.trevarj.motd.ui.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.repo.ChatPageCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flatMapLatest

/** Stable lookup captured by NavHost's remembered graph builder, never a changing map snapshot. */
class ChatPresentationRegistry {
    private val entries = mutableStateMapOf<String, ChatPresentation>()

    // Lookup is not admission: a paused temporary-toggle predecessor still owns authorized drawing.
    internal fun find(lease: ChatPageCache.Lease): ChatPresentation? = entries[lease.presentationIdentity]

    internal fun attach(
        identity: String,
        presentation: ChatPresentation,
    ) {
        entries[identity] = presentation
    }

    internal fun detach(
        identity: String,
        presentation: ChatPresentation,
    ) {
        if (entries[identity] === presentation) entries.remove(identity)
    }
}

/** UI-only state: its lifetime is this root composition, never a data ViewModel or singleton. */
internal class ChatPresentation(
    val items: LazyPagingItems<MessageEntity>,
    val listState: LazyListState,
    headerExpansion: MutableFloatState,
) {
    var headerExpansion by headerExpansion
    var confirmedViewport by mutableStateOf<ChatScrollPosition?>(null)
}

/** Keeps native presenters, but no rows, input, read observers, or chat side effects composed. */
@OptIn(ExperimentalCoroutinesApi::class)
@Composable
internal fun ChatPresentationHost(
    cache: ChatPageCache,
    registry: ChatPresentationRegistry,
) {
    val slots by cache.slots.collectAsState()
    for (slot in slots) {
        key(slot.presentationIdentity) {
            val currentSlot by rememberUpdatedState(slot)
            val pages = remember { snapshotFlow { currentSlot }.flatMapLatest { it.pages } }
            val items = pages.collectAsLazyPagingItems(Dispatchers.Main.immediate)
            val listState = rememberLazyListState()
            val headerExpansion = rememberSaveable { mutableFloatStateOf(1f) }
            val presentation = remember(items, listState, headerExpansion) { ChatPresentation(items, listState, headerExpansion) }
            val proofRevision by slot.changes.collectAsState()
            var publisher by remember(slot, items) { mutableStateOf<Long?>(null) }
            DisposableEffect(registry, slot.presentationIdentity, presentation) {
                registry.attach(slot.presentationIdentity, presentation)
                onDispose { registry.detach(slot.presentationIdentity, presentation) }
            }
            DisposableEffect(slot, items) {
                val token = slot.attachPresenter()
                publisher = token
                onDispose {
                    slot.detachPresenter(token)
                }
            }
            LaunchedEffect(slot, items, proofRevision, publisher) {
                val token = publisher ?: return@LaunchedEffect
                snapshotFlow { items.itemSnapshotList to items.loadState }.collect { (snapshot, states) ->
                    slot.presented(token, snapshot, states)
                }
            }
        }
    }
}
