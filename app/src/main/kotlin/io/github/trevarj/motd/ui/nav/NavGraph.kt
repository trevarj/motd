package io.github.trevarj.motd.ui.nav

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import io.github.trevarj.motd.agentwire.AgentwireGateScreen
import io.github.trevarj.motd.data.prefs.AppearanceConfig
import io.github.trevarj.motd.ui.about.AboutScreen
import io.github.trevarj.motd.ui.channelinfo.ChannelInfoScreen
import io.github.trevarj.motd.ui.channellist.ChannelListScreen
import io.github.trevarj.motd.ui.chat.ChatScreen
import io.github.trevarj.motd.ui.chatlist.AutoGroupScreen
import io.github.trevarj.motd.ui.chatlist.ChatListScreen
import io.github.trevarj.motd.ui.chatlist.FolderEditorScreen
import io.github.trevarj.motd.ui.chatlist.ManageFoldersScreen
import io.github.trevarj.motd.ui.feed.GlobalFeedScreen
import io.github.trevarj.motd.ui.imageviewer.ImageViewerScreen
import io.github.trevarj.motd.ui.invite.AccountSetupScreen
import io.github.trevarj.motd.ui.invite.CreateContactInviteScreen
import io.github.trevarj.motd.ui.invite.CreateInviteScreen
import io.github.trevarj.motd.ui.invite.JoinInviteScreen
import io.github.trevarj.motd.ui.invite.QrInviteScannerScreen
import io.github.trevarj.motd.ui.onboarding.OnboardingScreen
import io.github.trevarj.motd.ui.search.SearchScreen
import io.github.trevarj.motd.ui.settings.AppearanceSettingsScreen
import io.github.trevarj.motd.ui.settings.BackupRestoreScreen
import io.github.trevarj.motd.ui.settings.ChatSettingsScreen
import io.github.trevarj.motd.ui.settings.DeliverySettingsScreen
import io.github.trevarj.motd.ui.settings.DirectConnectionsScreen
import io.github.trevarj.motd.ui.settings.ManageNicksScreen
import io.github.trevarj.motd.ui.settings.NetworkSettingsScreen
import io.github.trevarj.motd.ui.settings.NetworkToolsScreen
import io.github.trevarj.motd.ui.settings.NetworksSettingsScreen
import io.github.trevarj.motd.ui.settings.NickListKind
import io.github.trevarj.motd.ui.settings.SettingsScreen
import io.github.trevarj.motd.ui.settings.SettingsSearchDestination
import io.github.trevarj.motd.ui.settings.SettingsSearchPage
import io.github.trevarj.motd.ui.settings.UploadsSettingsScreen
import io.github.trevarj.motd.ui.settings.addnetwork.AddNetworkScreen
import io.github.trevarj.motd.ui.settings.bouncer.BouncerNetworksScreen
import io.github.trevarj.motd.ui.settings.labs.AiLabsScreen
import io.github.trevarj.motd.ui.settings.labs.AiModelLibraryScreen
import io.github.trevarj.motd.ui.settings.labs.GestureMenuEditorScreen
import io.github.trevarj.motd.ui.settings.labs.LabsScreen
import io.github.trevarj.motd.ui.share.PendingShare
import io.github.trevarj.motd.ui.share.SharePickerScreen
import io.github.trevarj.motd.ui.theme.MotdMotion

/**
 * App navigation graph. Routes come from [Routes.kt] (frozen). Each destination is wired to its
 * screen composable; WP7/WP8 fill in their own screen bodies behind these signatures.
 */
// Material shared-axis X feel: forward pushes the new screen in from the right and the old one out
// to the left; back reverses it. Chat uses a drawer-style transition: only the chat surface moves,
// while the adjacent destination stays stationary beneath it. This avoids transforming two full
// Compose trees while the first Room and Paging emissions arrive.
@Composable
fun MotdNavGraph(
    appearance: AppearanceConfig,
    showComposerEmoji: Boolean,
    showComposerFormattingTools: Boolean,
    navController: NavHostController = rememberNavController(),
    // Notification-tap deep-link: open the buffer and jump to the message. Null when absent.
    notificationTarget: NotificationTarget? = null,
    onNotificationTargetHandled: () -> Unit = {},
    // Inbound ACTION_SEND payload: route to the chat picker. Null when absent.
    pendingShare: PendingShare? = null,
    onPendingShareHandled: () -> Unit = {},
    // External motd://invite payload, already syntax-validated by MainActivity. Empty renders error.
    pendingJoinInvite: String? = null,
    onPendingJoinInviteHandled: () -> Unit = {},
) {
    // Route a notification tap to ChatRoute so the existing jump path (local resolve → CHATHISTORY
    // AROUND fallback) scrolls to and highlights the message. Runs for both cold start (target
    // seeded before first composition) and warm start (target updated by onNewIntent). Clearing the
    // target after navigating lets a subsequent identical tap re-trigger (null → value transition).
    LaunchedEffect(notificationTarget) {
        val target = notificationTarget ?: return@LaunchedEffect
        // Replace an existing chat entry so a warm notification tap for the already-open buffer
        // receives a fresh route-scoped ViewModel and resolves the requested message. A
        // single-top navigation would retain the old ViewModel and silently ignore new jump args.
        navController.openChat(
            ChatRoute(
                target.bufferId,
                target.jumpToMsgid,
                target.jumpToTime,
                target.jumpToEventId,
            ),
            replaceCurrentChat = true,
        )
        onNotificationTargetHandled()
    }
    // The payload itself lives in PendingShareStore; this state only triggers the navigation, so
    // clearing it after routing lets a subsequent share re-trigger (null → value transition).
    LaunchedEffect(pendingShare) {
        if (pendingShare == null) return@LaunchedEffect
        navController.navigate(SharePickerRoute) { launchSingleTop = true }
        onPendingShareHandled()
    }
    LaunchedEffect(pendingJoinInvite) {
        val payload = pendingJoinInvite ?: return@LaunchedEffect
        navController.navigate(JoinInviteRoute(payload)) { launchSingleTop = true }
        onPendingJoinInviteHandled()
    }
    NavHost(
        navController = navController,
        startDestination = ChatListRoute,
        enterTransition = {
            if (isChatTarget() && isChatInitial()) {
                EnterTransition.None
            } else if (isChatTarget()) {
                slideIntoContainer(SlideDirection.Start, MotdMotion.navigationDrawerSpatial)
            } else {
                slideIntoContainer(SlideDirection.Start, MotdMotion.navigationDrawerSpatial)
            }
        },
        exitTransition = {
            if (isChatTarget() && isChatInitial()) {
                ExitTransition.None
            } else if (isChatTarget()) {
                // Keep the current screen in place until the incoming chat finishes, mirroring
                // ModalNavigationDrawer's single moving surface over stationary content.
                ExitTransition.KeepUntilTransitionsFinished
            } else {
                slideOutOfContainer(SlideDirection.Start, MotdMotion.navigationDrawerSpatial)
            }
        },
        popEnterTransition = {
            if (isChatInitial()) {
                // The destination is already visible beneath the outgoing chat surface.
                EnterTransition.None
            } else {
                slideIntoContainer(SlideDirection.End, MotdMotion.navigationDrawerSpatial)
            }
        },
        popExitTransition = {
            if (isChatInitial()) {
                slideOutOfContainer(SlideDirection.End, MotdMotion.chatBackSpatial)
            } else {
                slideOutOfContainer(SlideDirection.End, MotdMotion.navigationDrawerSpatial)
            }
        },
    ) {
        composable<ChatListRoute> {
            var openedDefault by rememberSaveable { mutableStateOf(false) }
            ChatWorkspace(
                listPane = { twoPane ->
                    ChatListPane(
                        navController = navController,
                        suppressOnboarding = pendingJoinInvite != null,
                        onDefaultBufferAvailable = { bufferId ->
                            if (twoPane && !openedDefault) {
                                openedDefault = true
                                navController.openChat(ChatRoute(bufferId), replaceCurrentChat = false)
                            }
                        },
                    )
                },
            )
        }
        composable<ChatRoute> { entry ->
            val route = entry.toRoute<ChatRoute>()
            ChatWorkspace(
                listPane = {
                    ChatListPane(
                        navController = navController,
                        selectedBufferId = route.bufferId,
                        replaceCurrentChat = true,
                    )
                },
                detailPane = { showBack ->
                    AgentwireGateScreen(
                        onBack = { navController.popBackStack() },
                        showBack = showBack,
                        showComposerEmoji = showComposerEmoji,
                        showComposerFormattingTools = showComposerFormattingTools,
                    ) {
                        ChatScreen(
                            bufferId = route.bufferId,
                            appearance = appearance,
                            onBack = { navController.popBackStack() },
                            showBack = showBack,
                            onOpenChannelInfo = { navController.navigate(ChannelInfoRoute(it)) },
                            onOpenSearch = { navController.navigate(SearchRoute(it)) },
                            onOpenSharePicker = { navController.navigate(SharePickerRoute) { launchSingleTop = true } },
                            onOpenImage = { navController.navigate(ImageViewerRoute(it)) },
                            // /msg and /query replace the detail on wide layouts and push on phones.
                            onOpenBuffer = {
                                navController.openChat(ChatRoute(it), replaceCurrentChat = !showBack)
                            },
                            onOpenAudioOrigin = { origin ->
                                navController.openChat(
                                    ChatRoute(origin.bufferId, origin.msgid, origin.serverTime, origin.eventId),
                                    replaceCurrentChat = !showBack,
                                )
                            },
                            onOpenChannelList = { navController.navigate(ChannelListRoute(it)) },
                            onOpenAccountSetup = { navController.navigate(AccountSetupRoute(it)) },
                        )
                    }
                },
            )
        }
        composable<OnboardingRoute> {
            // Finish lands on a fresh ChatList and clears onboarding (plus any duplicate
            // onboarding entries) from the backstack; a bare popBackStack could fall back to
            // the Welcome step instead of the chat list.
            OnboardingScreen(
                onDone = {
                    navController.navigate(ChatListRoute) {
                        popUpTo<ChatListRoute> { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onScanInvite = { navController.navigate(QrInviteScannerRoute) },
            )
        }
        composable<QrInviteScannerRoute> {
            QrInviteScannerScreen(
                onBack = { navController.popBackStack() },
                onInvite = { payload ->
                    navController.navigate(JoinInviteRoute(payload)) {
                        popUpTo<QrInviteScannerRoute> { inclusive = true }
                    }
                },
            )
        }
        composable<JoinInviteRoute> { entry ->
            val route = entry.toRoute<JoinInviteRoute>()
            val accountComplete by entry.savedStateHandle.getStateFlow("account_setup_complete", false).collectAsStateWithLifecycle()
            JoinInviteScreen(
                payload = route.payload,
                accountSetupComplete = accountComplete,
                onAccountSetupCompleteHandled = { entry.savedStateHandle["account_setup_complete"] = false },
                onBack = { navController.popBackStack() },
                onOpenBuffer = { bufferId ->
                    navController.navigate(ChatRoute(bufferId)) {
                        popUpTo<JoinInviteRoute> { inclusive = true }
                    }
                },
                onOpenAccountSetup = { networkId, channel ->
                    navController.navigate(AccountSetupRoute(networkId, channel))
                },
            )
        }
        composable<AccountSetupRoute> { entry ->
            val route = entry.toRoute<AccountSetupRoute>()
            AccountSetupScreen(
                networkId = route.networkId,
                onBack = { navController.popBackStack() },
                onComplete = {
                    if (route.returnChannel != null) {
                        navController.previousBackStackEntry?.savedStateHandle?.set("account_setup_complete", true)
                    }
                    navController.popBackStack()
                },
            )
        }
        composable<SettingsRoute> {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenAppearance = { navController.navigate(AppearanceSettingsRoute()) },
                onOpenChat = { navController.navigate(ChatSettingsRoute()) },
                onOpenDelivery = { navController.navigate(DeliverySettingsRoute()) },
                onOpenNetworks = { navController.navigate(NetworksSettingsRoute()) },
                onOpenUploads = { navController.navigate(UploadsSettingsRoute()) },
                onOpenBackupRestore = { navController.navigate(BackupRestoreRoute()) },
                onOpenLabs = { navController.navigate(LabsRoute()) },
                onOpenAbout = { navController.navigate(AboutRoute()) },
                onOpenSearchResult = { navController.openSettingsResult(it) },
            )
        }
        composable<AppearanceSettingsRoute> { entry ->
            val route = entry.toRoute<AppearanceSettingsRoute>()
            AppearanceSettingsScreen(
                target = route.target,
                onBack = { navController.popBackStack() },
                onOpenNickColors = { navController.navigate(NickColorsRoute) },
            )
        }
        composable<ChatSettingsRoute> { entry ->
            val route = entry.toRoute<ChatSettingsRoute>()
            ChatSettingsScreen(
                target = route.target,
                onBack = { navController.popBackStack() },
                onOpenFriends = { navController.navigate(FriendsRoute) },
                onOpenFools = { navController.navigate(FoolsRoute) },
                onOpenDirectConnections = { navController.navigate(DirectConnectionsRoute) },
            )
        }
        composable<DirectConnectionsRoute> {
            DirectConnectionsScreen(onBack = { navController.popBackStack() })
        }
        composable<DeliverySettingsRoute> { entry ->
            DeliverySettingsScreen(
                target = entry.toRoute<DeliverySettingsRoute>().target,
                onBack = { navController.popBackStack() },
            )
        }
        composable<UploadsSettingsRoute> { entry ->
            UploadsSettingsScreen(
                target = entry.toRoute<UploadsSettingsRoute>().target,
                onBack = { navController.popBackStack() },
            )
        }
        composable<NetworksSettingsRoute> { entry ->
            NetworksSettingsScreen(
                target = entry.toRoute<NetworksSettingsRoute>().target,
                onBack = { navController.popBackStack() },
                onOpenNetwork = { navController.navigate(NetworkSettingsRoute(it)) },
                onOpenAddNetwork = { navController.navigate(AddNetworkRoute) },
                onScanInvite = { navController.navigate(QrInviteScannerRoute) },
            )
        }
        composable<BackupRestoreRoute> { entry ->
            BackupRestoreScreen(
                target = entry.toRoute<BackupRestoreRoute>().target,
                onBack = { navController.popBackStack() },
                onReviewNetworks = { navController.navigate(NetworksSettingsRoute()) },
            )
        }
        composable<ManageFoldersRoute> { entry ->
            val networkId = entry.toRoute<ManageFoldersRoute>().networkId
            ManageFoldersScreen(
                onBack = { navController.popBackStack() },
                onEdit = { navController.navigate(FolderEditorRoute(it)) },
                onAutoGroup = { navController.navigate(AutoGroupRoute(networkId)) },
            )
        }
        composable<FolderEditorRoute> { entry ->
            FolderEditorScreen(
                folderId = entry.toRoute<FolderEditorRoute>().folderId,
                onBack = { navController.popBackStack() },
            )
        }
        composable<AutoGroupRoute> { entry ->
            AutoGroupScreen(
                networkId = entry.toRoute<AutoGroupRoute>().networkId,
                onBack = { navController.popBackStack() },
            )
        }
        composable<LabsRoute> { entry ->
            LabsScreen(
                target = entry.toRoute<LabsRoute>().target,
                onBack = { navController.popBackStack() },
                onOpenGestureMenu = { navController.navigate(GestureMenuEditorRoute) },
                onOpenAi = { navController.navigate(AiLabsRoute()) },
            )
        }
        composable<AiLabsRoute> { entry ->
            AiLabsScreen(
                target = entry.toRoute<AiLabsRoute>().target,
                onBack = { navController.popBackStack() },
                onOpenModelLibrary = { navController.navigate(AiModelLibraryRoute) },
            )
        }
        composable<AiModelLibraryRoute> {
            AiModelLibraryScreen(onBack = { navController.popBackStack() })
        }
        composable<GestureMenuEditorRoute> {
            GestureMenuEditorScreen(onBack = { navController.popBackStack() })
        }
        composable<FriendsRoute> {
            ManageNicksScreen(NickListKind.FRIENDS, onBack = { navController.popBackStack() })
        }
        composable<FoolsRoute> {
            ManageNicksScreen(NickListKind.FOOLS, onBack = { navController.popBackStack() })
        }
        composable<NickColorsRoute> {
            ManageNicksScreen(NickListKind.COLORS, onBack = { navController.popBackStack() })
        }
        composable<NetworkSettingsRoute> { entry ->
            val route = entry.toRoute<NetworkSettingsRoute>()
            NetworkSettingsScreen(
                networkId = route.networkId,
                target = route.target,
                onBack = { navController.popBackStack() },
                // Round 5: soju root -> bouncer manager; "Server messages" -> the SERVER buffer.
                onOpenBouncerNetworks = { navController.navigate(BouncerNetworksRoute(it)) },
                onOpenBuffer = { navController.navigate(ChatRoute(it)) },
                onOpenNetworkTools = { navController.navigate(NetworkToolsRoute(it)) },
                onOpenAccountSetup = { navController.navigate(AccountSetupRoute(it)) },
            )
        }
        composable<NetworkToolsRoute> { entry ->
            val route = entry.toRoute<NetworkToolsRoute>()
            NetworkToolsScreen(
                networkId = route.networkId,
                onBack = { navController.popBackStack() },
            )
        }
        composable<SearchRoute> { entry ->
            val route = entry.toRoute<SearchRoute>()
            SearchScreen(
                bufferId = route.bufferId,
                onBack = { navController.popBackStack() },
                onOpenHit = { bufferId, msgid, time, eventId ->
                    navController.navigate(ChatRoute(bufferId, msgid, time, eventId))
                },
            )
        }
        composable<GlobalFeedRoute> {
            GlobalFeedScreen(
                onBack = { navController.popBackStack() },
                // The search-hit deep-jump path: the canonical row id is identity, the time anchors.
                onOpenMessage = { bufferId, eventId, time ->
                    navController.navigate(
                        ChatRoute(bufferId, jumpToTime = time, jumpToEventId = eventId),
                    )
                },
            )
        }
        composable<ChannelInfoRoute> { entry ->
            val route = entry.toRoute<ChannelInfoRoute>()
            ChannelInfoScreen(
                bufferId = route.bufferId,
                onBack = { navController.popBackStack() },
                // Member "Message" action opens the DM's QUERY buffer.
                onOpenBuffer = { navController.navigate(ChatRoute(it)) },
                onCreateInvite = { navController.navigate(CreateInviteRoute(it)) },
            )
        }
        composable<CreateInviteRoute> { entry ->
            val route = entry.toRoute<CreateInviteRoute>()
            CreateInviteScreen(
                bufferId = route.bufferId,
                onBack = { navController.popBackStack() },
            )
        }
        composable<CreateContactInviteRoute> { entry ->
            val route = entry.toRoute<CreateContactInviteRoute>()
            CreateContactInviteScreen(
                preferredNetworkId = route.preferredNetworkId,
                onBack = { navController.popBackStack() },
            )
        }
        composable<ImageViewerRoute>(
            // Full-screen image reads better appearing/dismissing in place than sliding sideways.
            enterTransition = { fadeIn(MotdMotion.navigationFade) },
            exitTransition = { fadeOut(MotdMotion.navigationFade) },
            popEnterTransition = { fadeIn(MotdMotion.navigationFade) },
            popExitTransition = { fadeOut(MotdMotion.navigationFade) },
        ) { entry ->
            val route = entry.toRoute<ImageViewerRoute>()
            ImageViewerScreen(url = route.url, onBack = { navController.popBackStack() })
        }
        composable<SharePickerRoute> {
            SharePickerScreen(
                // Leave the picker before opening the chat so back returns to the previous screen
                // rather than the (now empty) picker.
                onPicked = { bufferId, isAgentContext ->
                    navController.completeShareNavigation(bufferId, preserveSourceChat = isAgentContext)
                },
                onCancel = { navController.popBackStack() },
            )
        }
        composable<AboutRoute> { entry ->
            AboutScreen(
                target = entry.toRoute<AboutRoute>().target,
                onBack = { navController.popBackStack() },
            )
        }
        // Round 5: app-shell / network-management destinations.
        composable<AddNetworkRoute> {
            AddNetworkScreen(
                onBack = { navController.popBackStack() },
                onScanInvite = { navController.navigate(QrInviteScannerRoute) },
                onOpenBouncerNetworks = { rootId, created ->
                    navController.navigate(BouncerNetworksRoute(rootId, importAllByDefault = created)) {
                        // The add-flow is replaced by the manager once the soju root exists.
                        popUpTo<AddNetworkRoute> { inclusive = true }
                    }
                },
            )
        }
        composable<BouncerNetworksRoute> { entry ->
            val route = entry.toRoute<BouncerNetworksRoute>()
            BouncerNetworksScreen(
                rootNetworkId = route.rootNetworkId,
                importAllByDefault = route.importAllByDefault,
                onBack = { navController.popBackStack() },
            )
        }
        composable<ChannelListRoute> { entry ->
            val route = entry.toRoute<ChannelListRoute>()
            ChannelListScreen(
                networkId = route.networkId,
                onBack = { navController.popBackStack() },
            )
        }
    }
}

@Composable
private fun ChatListPane(
    navController: NavHostController,
    selectedBufferId: Long? = null,
    replaceCurrentChat: Boolean = false,
    suppressOnboarding: Boolean = false,
    onDefaultBufferAvailable: (Long) -> Unit = {},
) {
    ChatListScreen(
        onOpenBuffer = {
            navController.openChat(ChatRoute(it), replaceCurrentChat)
        },
        onOpenAudioOrigin = { origin ->
            navController.openChat(
                ChatRoute(
                    bufferId = origin.bufferId,
                    jumpToMsgid = origin.msgid,
                    jumpToTime = origin.serverTime,
                    jumpToEventId = origin.eventId,
                ),
                replaceCurrentChat,
            )
        },
        onOpenSettings = { navController.navigate(SettingsRoute()) },
        onOpenSearch = { navController.navigate(SearchRoute()) },
        onOpenFeed = { navController.navigate(GlobalFeedRoute) },
        onOpenManageFolders = { navController.navigate(ManageFoldersRoute(it)) },
        onOpenFolderEditor = { navController.navigate(FolderEditorRoute(it)) },
        onOpenOnboarding = { navController.navigate(OnboardingRoute) },
        onOpenNetworkSettings = { navController.navigate(NetworkSettingsRoute(it)) },
        onOpenAddNetwork = { navController.navigate(AddNetworkRoute) },
        onCreateContactInvite = { navController.navigate(CreateContactInviteRoute(it)) },
        onScanInvite = { navController.navigate(QrInviteScannerRoute) },
        onOpenChannelList = { navController.navigate(ChannelListRoute(it)) },
        selectedBufferId = selectedBufferId,
        suppressOnboarding = suppressOnboarding,
        onDefaultBufferAvailable = onDefaultBufferAvailable,
    )
}

/**
 * Open [route], optionally replacing the chat already on the back stack.
 *
 * Internal rather than private because the gesture overlay performs its own chat jumps from outside
 * the graph, and a second copy of this would be a second set of rules for the same navigation.
 */
internal fun NavHostController.openChat(
    route: ChatRoute,
    replaceCurrentChat: Boolean,
) {
    navigate(route) {
        if (replaceCurrentChat) popUpTo<ChatRoute> { inclusive = true }
    }
}

internal fun NavHostController.completeShareNavigation(
    bufferId: Long,
    preserveSourceChat: Boolean,
) {
    popBackStack()
    val currentChat =
        currentBackStackEntry
            ?.takeIf { isChatRoutePattern(it.destination.route) }
            ?.toRoute<ChatRoute>()
    if (preserveSourceChat && currentChat?.bufferId == bufferId) return
    openChat(ChatRoute(bufferId), replaceCurrentChat = !preserveSourceChat)
}

private fun NavHostController.openSettingsResult(destination: SettingsSearchDestination) {
    when (destination) {
        is SettingsSearchDestination.Network -> {
            navigate(NetworkSettingsRoute(destination.networkId, destination.target))
        }

        is SettingsSearchDestination.Page -> {
            val target = destination.target
            when (destination.page) {
                SettingsSearchPage.ROOT -> navigate(SettingsRoute(target))
                SettingsSearchPage.APPEARANCE -> navigate(AppearanceSettingsRoute(target))
                SettingsSearchPage.CHAT -> navigate(ChatSettingsRoute(target))
                SettingsSearchPage.DELIVERY -> navigate(DeliverySettingsRoute(target))
                SettingsSearchPage.UPLOADS -> navigate(UploadsSettingsRoute(target))
                SettingsSearchPage.NETWORKS -> navigate(NetworksSettingsRoute(target))
                SettingsSearchPage.BACKUP -> navigate(BackupRestoreRoute(target))
                SettingsSearchPage.LABS -> navigate(LabsRoute(target))
                SettingsSearchPage.AI_LABS -> navigate(AiLabsRoute(target))
                SettingsSearchPage.ABOUT -> navigate(AboutRoute(target))
            }
        }
    }
}

private fun AnimatedContentTransitionScope<NavBackStackEntry>.isChatTarget(): Boolean = isChatRoutePattern(targetState.destination.route)

private fun AnimatedContentTransitionScope<NavBackStackEntry>.isChatInitial(): Boolean = isChatRoutePattern(initialState.destination.route)

internal fun isChatRoutePattern(route: String?): Boolean {
    val chatRouteName = ChatRoute::class.qualifiedName ?: return false
    return route == chatRouteName ||
        route?.startsWith("$chatRouteName/") == true ||
        route?.startsWith("$chatRouteName?") == true
}
