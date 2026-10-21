package io.github.trevarj.motd.ui.chatlist

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.ChatFolderEntity
import io.github.trevarj.motd.data.db.ChatListRow
import io.github.trevarj.motd.data.prefs.ChatListSwipeAction
import io.github.trevarj.motd.data.prefs.FolderDisplayMode
import io.github.trevarj.motd.data.prefs.MentionsPlacement
import io.github.trevarj.motd.ui.components.LocalAutomaticRemoteMedia
import io.github.trevarj.motd.ui.components.LocalBehaviorExample
import io.github.trevarj.motd.ui.components.LocalRemoteAvatars
import io.github.trevarj.motd.ui.components.RemoteAvatarState

internal val settingSampleChats =
    listOf(
        ChatListRow(-1, -1, "Example", "#garden", BufferType.CHANNEL, false, false, "Welcome to the garden", "Alex", null, 2, 0, folderId = -1),
        ChatListRow(-2, -1, "Example", "Sam", BufferType.QUERY, false, false, "See you soon", "Sam", null, 0, 0),
    )

private val sampleFolder = ChatFolderEntity(-1, "Garden", "garden")
private val ungroupedSampleChats = settingSampleChats.filter { it.folderId == null }

@Composable
internal fun FolderLayoutPreview(
    mode: FolderDisplayMode,
    includeFolderChats: Boolean,
) {
    var selected by rememberSaveable(mode) { mutableStateOf<Long?>(null) }
    var expanded by rememberSaveable { mutableStateOf(true) }
    val avatars = remember { RemoteAvatarState() }
    val folders = remember(expanded) { listOf(sampleFolder.copy(expanded = expanded)) }
    val presentation = remember(folders) { presentChatFolders(settingSampleChats, folders, emptySet(), emptySet()) }
    val tabs = remember(folders) { presentFolderTabs(settingSampleChats, folders) }
    val all = remember(includeFolderChats) { folderAllRows(settingSampleChats, includeFolderChats) }
    val allSummary = remember(all) { summarizeFolder(all) }
    CompositionLocalProvider(LocalRemoteAvatars provides avatars, LocalAutomaticRemoteMedia provides false) {
        Column(Modifier.testTag("settings_folder_preview")) {
            if (mode == FolderDisplayMode.INLINE) {
                presentation.folders.forEach { group ->
                    ChatFolderHeader(group, onToggle = { expanded = !expanded }, onEdit = null)
                    if (group.expanded) group.children.forEach { SettingSampleChatRow(it) }
                }
                ungroupedSampleChats.forEach { SettingSampleChatRow(it) }
            } else {
                FolderTabStrip(
                    folders = tabs,
                    allSummary = allSummary,
                    showAllTab = true,
                    dickordSummary = null,
                    mentionsEnabled = false,
                    mentionsCount = 0,
                    mentionsIncomplete = false,
                    selectedFolderId = selected,
                    onSelect = { selected = it },
                    onOpenDickord = {},
                    onOpenMentions = {},
                )
                (if (selected == null) all else tabs.single().children).forEach { SettingSampleChatRow(it) }
            }
        }
    }
}

@Composable
internal fun SettingSampleChatRow(
    row: ChatListRow,
    modifier: Modifier = Modifier,
) {
    ChatListRowItem(row, showNetworkChip = false, onClick = {}, onLongClick = {}, interactionsEnabled = false, modifier = modifier)
}

@Composable
internal fun MentionsPlacementExample(placement: MentionsPlacement) {
    var opened by rememberSaveable(placement) { mutableStateOf(false) }
    LocalBehaviorExample("mentions_example_${placement.name.lowercase()}") {
        when (placement) {
            MentionsPlacement.DRAWER -> {
                Text(stringResource(R.string.settings_mentions_drawer))
                DrawerActionTile(Icons.Outlined.AlternateEmail, stringResource(R.string.mentions_title), stringResource(R.string.mentions_title), { opened = !opened }, Modifier)
            }

            MentionsPlacement.CHAT_LIST -> {
                MentionsPinnedRow(1, false) { opened = !opened }
                SettingSampleChatRow(settingSampleChats[0])
            }

            MentionsPlacement.FOLDER_TAB -> {
                val summary = remember { summarizeFolder(settingSampleChats) }
                FolderTabStrip(emptyList(), summary, true, null, true, 1, false, null, { opened = false }, {}, { opened = true }, mentionsSelected = opened)
                SettingSampleChatRow(settingSampleChats[0])
            }
        }
        if (opened) Text(stringResource(R.string.settings_mentions_example_feed))
    }
}

@Composable
internal fun ChatListSwipeExample(action: ChatListSwipeAction) {
    val row = settingSampleChats[0]
    LocalBehaviorExample("swipe_example_${action.name.lowercase()}") {
        Box(Modifier.fillMaxWidth().clipToBounds()) {
            Box(Modifier.matchParentSize()) { ChatListSwipeBackground(row, action, archiveMode = false) }
            SettingSampleChatRow(row, Modifier.offset(x = if (action == ChatListSwipeAction.NONE) 0.dp else (-96).dp))
        }
        Text(
            stringResource(
                when (action) {
                    ChatListSwipeAction.ARCHIVE -> R.string.settings_swipe_example_archive
                    ChatListSwipeAction.MARK_READ -> R.string.settings_swipe_example_read
                    ChatListSwipeAction.MUTE -> R.string.settings_swipe_example_mute
                    ChatListSwipeAction.PIN -> R.string.settings_swipe_example_pin
                    ChatListSwipeAction.DELETE -> R.string.settings_swipe_example_delete
                    ChatListSwipeAction.NONE -> R.string.settings_swipe_example_none
                },
            ),
        )
        if (action == ChatListSwipeAction.DELETE) Text(stringResource(chatRemovalCopy(row.type).message, row.displayName))
    }
}
