package io.github.trevarj.motd.gesture

import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.ChatListRow
import io.github.trevarj.motd.data.db.MemberEntity
import io.github.trevarj.motd.data.db.MonitorQueryRow
import io.github.trevarj.motd.data.db.MuteBacklogSuppression
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.db.TimelineAnchor
import io.github.trevarj.motd.data.prefs.AppearanceConfig
import io.github.trevarj.motd.data.prefs.AppearancePrefs
import io.github.trevarj.motd.data.prefs.AvatarStyle
import io.github.trevarj.motd.data.prefs.BubbleCornerStyle
import io.github.trevarj.motd.data.prefs.ChatWallpaper
import io.github.trevarj.motd.data.prefs.ColorThemePreset
import io.github.trevarj.motd.data.prefs.FontChoice
import io.github.trevarj.motd.data.prefs.FoolsMode
import io.github.trevarj.motd.data.prefs.HistorySyncDepth
import io.github.trevarj.motd.data.prefs.HistorySyncMode
import io.github.trevarj.motd.data.prefs.LauncherIcon
import io.github.trevarj.motd.data.prefs.LayoutDensity
import io.github.trevarj.motd.data.prefs.MessageSpacing
import io.github.trevarj.motd.data.prefs.NickColorPalette
import io.github.trevarj.motd.data.prefs.PresenceMode
import io.github.trevarj.motd.data.prefs.Settings
import io.github.trevarj.motd.data.prefs.SettingsRepository
import io.github.trevarj.motd.data.prefs.ThemeMode
import io.github.trevarj.motd.data.prefs.TimeFormat
import io.github.trevarj.motd.data.prefs.WallpaperSelection
import io.github.trevarj.motd.data.repo.BufferRepository
import io.github.trevarj.motd.data.repo.NetworkRepository
import io.github.trevarj.motd.gesture.radial.OrbPlacement
import io.github.trevarj.motd.irc.client.IrcClient
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.service.BufferReadMarker
import io.github.trevarj.motd.service.CertPrompt
import io.github.trevarj.motd.service.ConnectionManager
import io.github.trevarj.motd.service.DeliveryMode
import io.github.trevarj.motd.service.ForegroundBufferTracker
import io.github.trevarj.motd.service.PresenceKey
import io.github.trevarj.motd.service.PresenceState
import io.github.trevarj.motd.service.ReadMarkerSnapshotter
import io.github.trevarj.motd.service.SendAcceptance
import io.github.trevarj.motd.testing.NoopConnectionManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Collaborator doubles shared by the gesture dispatcher and provider tests.
 *
 * Deliberately plain state holders rather than a mocking framework: every assertion in these tests
 * is about what the dispatcher *decided*, so the doubles only have to record calls and hand back
 * whatever snapshot the case set up.
 */

internal fun readyState(nick: String = "me"): IrcClientState = IrcClientState.Ready(nick, emptySet(), emptyMap())

internal fun chatRow(
    bufferId: Long,
    displayName: String = "#room$bufferId",
    networkId: Long = 1L,
    type: BufferType = BufferType.CHANNEL,
    pinned: Boolean = false,
    muted: Boolean = false,
    unreadCount: Int = 0,
): ChatListRow =
    ChatListRow(
        bufferId = bufferId,
        networkId = networkId,
        networkName = "libera",
        displayName = displayName,
        type = type,
        pinned = pinned,
        muted = muted,
        lastMessageText = null,
        lastMessageSender = null,
        lastMessageTime = null,
        unreadCount = unreadCount,
        mentionCount = 0,
    )

internal fun testNetwork(
    id: Long,
    name: String = "net$id",
): NetworkEntity =
    NetworkEntity(
        id = id,
        name = name,
        role = NetworkRole.DIRECT,
        host = "irc.example.org",
        port = 6697,
        nick = "me",
        username = "me",
        realname = "me",
    )

internal class FakeConnections : NoopConnectionManager() {
    val away = MutableStateFlow<Map<Long, String?>>(emptyMap())
    val presence = MutableStateFlow<Map<PresenceKey, PresenceState>>(emptyMap())

    val awayWrites = mutableListOf<Pair<Long, String?>>()
    val connected = mutableListOf<Long>()
    val disconnected = mutableListOf<Long>()
    val joins = mutableListOf<Triple<Long, String, String?>>()
    val queries = mutableListOf<Pair<Long, String>>()
    val marked = mutableListOf<Pair<Long, TimelineAnchor>>()

    /** Buffer id handed back by [ensureQueryBuffer]; null makes the call fail like a dead socket. */
    var queryBufferId: Long? = 42L

    override val selfAwayStates: StateFlow<Map<Long, String?>> = away
    override val presenceStates: StateFlow<Map<PresenceKey, PresenceState>> = presence

    override suspend fun setAway(
        networkId: Long,
        message: String?,
    ) {
        awayWrites += networkId to message
    }

    override suspend fun connect(networkId: Long) {
        connected += networkId
    }

    override suspend fun disconnect(networkId: Long) {
        disconnected += networkId
    }

    override suspend fun joinChannel(
        networkId: Long,
        channel: String,
        key: String?,
    ): Boolean {
        joins += Triple(networkId, channel, key)
        return true
    }

    override suspend fun ensureQueryBuffer(
        networkId: Long,
        nick: String,
    ): Long {
        queries += networkId to nick
        return queryBufferId ?: error("no live client")
    }

    override suspend fun ensureServerBuffer(networkId: Long): Long = 0

    override suspend fun markRead(
        bufferId: Long,
        anchor: TimelineAnchor,
    ) {
        marked += bufferId to anchor
    }
}

internal class FakeBuffers : BufferRepository {
    val chats = MutableStateFlow<List<ChatListRow>>(emptyList())
    val queryConversations = MutableStateFlow<List<MonitorQueryRow>>(emptyList())

    /** Durable redirects: requested id -> winner id. An id absent here resolves to itself. */
    val redirects = mutableMapOf<Long, Long>()

    /** Ids that no longer name a row at all. */
    val missing = mutableSetOf<Long>()

    override fun observeChatList(): Flow<List<ChatListRow>> = chats

    override fun observeQueryConversations(): Flow<List<MonitorQueryRow>> = queryConversations

    override suspend fun canonicalBufferId(id: Long): Long? = if (id in missing) null else redirects[id] ?: id

    override fun observeBuffer(id: Long): Flow<BufferEntity?> = MutableStateFlow(null)

    override fun observeMembers(bufferId: Long): Flow<List<MemberEntity>> = MutableStateFlow(emptyList())

    override suspend fun setPinned(
        id: Long,
        pinned: Boolean,
    ) = Unit

    override suspend fun setMuted(
        id: Long,
        muted: Boolean,
    ): MuteBacklogSuppression? = null

    override suspend fun setLayoutDensityOverride(
        id: Long,
        layout: LayoutDensity?,
    ): Boolean = false

    override suspend fun setPresenceModeOverride(
        id: Long,
        mode: PresenceMode?,
    ): Boolean = false

    override suspend fun setHistorySyncModeOverride(
        id: Long,
        mode: HistorySyncMode?,
    ): Boolean = error("Unexpected history sync mode override write")

    override suspend fun deleteBuffer(id: Long) = Unit
}

internal class FakeNetworks(
    networks: List<NetworkEntity> = emptyList(),
) : NetworkRepository {
    val rows = MutableStateFlow(networks)

    override fun observeNetworks(): Flow<List<NetworkEntity>> = rows

    override suspend fun addNetwork(n: NetworkEntity): Long = 0

    override suspend fun updateNetwork(n: NetworkEntity) = Unit

    override suspend fun deleteNetwork(id: Long) = Unit

    override suspend fun reorderNetworks(orderedIds: List<Long>) = Unit

    override suspend fun networkById(id: Long): NetworkEntity? = rows.value.firstOrNull { it.id == id }

    override suspend fun childrenOf(rootId: Long): List<NetworkEntity> = emptyList()
}

internal class FakeAppearance(
    initial: AppearanceConfig = AppearanceConfig(),
) : AppearancePrefs {
    val state = MutableStateFlow(initial)
    override val config: Flow<AppearanceConfig> = state

    override suspend fun setTheme(theme: ColorThemePreset) {
        state.value = state.value.copy(theme = theme)
    }

    override suspend fun setTrueBlack(enabled: Boolean) = Unit

    override suspend fun setFollowSystem(enabled: Boolean) {
        state.value = state.value.copy(followSystem = enabled)
    }

    override suspend fun setWallpaper(selection: WallpaperSelection) = Unit

    override suspend fun setUiFontScale(percent: Int) = Unit

    override suspend fun setConversationFontScale(percent: Int) = Unit

    override suspend fun setFontChoice(choice: FontChoice) = Unit

    override suspend fun setShowTimestamps(enabled: Boolean) = Unit

    override suspend fun setTimeFormat(format: TimeFormat) = Unit

    override suspend fun setCustomTimeFormatPattern(pattern: String) {
        state.value = state.value.copy(customTimeFormatPattern = pattern)
    }

    override suspend fun setMessageSpacing(spacing: MessageSpacing) = Unit

    override suspend fun setBubbleCornerStyle(style: BubbleCornerStyle) = Unit

    override suspend fun setLauncherIcon(icon: LauncherIcon) = Unit

    override suspend fun setCustomFontName(name: String) = Unit
}

internal class FakeForegroundBuffer(
    initial: Long? = null,
) : ForegroundBufferTracker {
    private val state = MutableStateFlow(initial)
    override val foregroundBufferId: StateFlow<Long?> = state

    override fun set(bufferId: Long?) {
        state.value = bufferId
    }
}

/** Every requested buffer gets a boundary unless it was listed in [withoutBoundary]. */
internal class FakeReadMarkers(
    private val withoutBoundary: Set<Long> = emptySet(),
) : ReadMarkerSnapshotter {
    override suspend fun latestIncoming(bufferIds: Collection<Long>): List<BufferReadMarker> =
        bufferIds.map { id ->
            val complete = id !in withoutBoundary
            BufferReadMarker(
                bufferId = id,
                target = "#room$id",
                timestamp = if (complete) 1_000L + id else null,
                eventId = if (complete) id else null,
            )
        }
}

internal class FakeGesturePrefs(
    enabled: Boolean = false,
    menu: GestureMenuConfig = GestureMenuConfig(),
    orb: OrbPlacement = OrbPlacement(),
) : GesturePrefs {
    val enabledState = MutableStateFlow(enabled)
    val menuState = MutableStateFlow(menu)
    val orbState = MutableStateFlow(orb)

    override val enabled: Flow<Boolean> = enabledState

    override suspend fun setEnabled(enabled: Boolean) {
        enabledState.value = enabled
    }

    override val menu: Flow<GestureMenuConfig> = menuState

    override suspend fun setMenu(config: GestureMenuConfig) {
        menuState.value = config
    }

    override suspend fun replaceMenu(transform: (GestureMenuConfig) -> GestureMenuConfig) {
        menuState.value = transform(menuState.value)
    }

    override val orb: Flow<OrbPlacement> = orbState

    override suspend fun setOrb(placement: OrbPlacement) {
        orbState.value = placement
    }
}

internal class FakeSettings(
    initial: Settings = Settings(),
) : SettingsRepository {
    val state = MutableStateFlow(initial)
    override val settings: StateFlow<Settings> = state

    override suspend fun setThemeMode(m: ThemeMode) = Unit

    override suspend fun setDynamicColor(enabled: Boolean) = Unit

    override suspend fun setDeliveryMode(m: DeliveryMode) = Unit

    override suspend fun setLayoutDensity(d: LayoutDensity) = Unit

    override suspend fun setNickColorsEnabled(enabled: Boolean) = Unit

    override suspend fun setNickColorPalette(p: NickColorPalette) = Unit

    override suspend fun setNickColorOverride(
        nick: String,
        hue: Int?,
    ) = Unit

    override suspend fun setFriend(
        nick: String,
        isFriend: Boolean,
    ) = Unit

    override suspend fun setFool(
        nick: String,
        isFool: Boolean,
    ) = Unit

    override suspend fun setFoolsMode(m: FoolsMode) = Unit

    override suspend fun setPresenceMode(m: PresenceMode) = Unit

    override suspend fun setAvatarStyle(style: AvatarStyle) = Unit

    override suspend fun setChatWallpaper(w: ChatWallpaper) = Unit

    override suspend fun setShowComposerEmoji(show: Boolean) = Unit

    override suspend fun setShowComposerFormattingTools(show: Boolean) = Unit

    override suspend fun setChatSoundsEnabled(enabled: Boolean) = Unit

    override suspend fun setHistorySyncDepth(d: HistorySyncDepth) = Unit

    override suspend fun setHistorySyncMode(mode: HistorySyncMode) {
        state.value = state.value.copy(historySyncMode = mode)
    }

    override suspend fun setAutoAwayEnabled(enabled: Boolean) = Unit

    override suspend fun setAutoAwayMinutes(minutes: Int) = Unit

    override suspend fun setAutoAwayMessage(message: String) = Unit
}
