package io.github.trevarj.motd.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieDynamicProperties
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import com.airbnb.lottie.model.KeyPath
import io.github.trevarj.motd.R
import io.github.trevarj.motd.bouncer.BouncerKind
import io.github.trevarj.motd.bouncer.SojuLoginForm
import io.github.trevarj.motd.bouncer.ZncLoginForm
import io.github.trevarj.motd.data.prefs.HistorySyncDepth
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.ui.settings.BouncerLoginFields
import io.github.trevarj.motd.ui.settings.NetworkForm
import io.github.trevarj.motd.ui.settings.addnetwork.NetworkPresetId
import io.github.trevarj.motd.ui.settings.addnetwork.NetworkPresetPicker
import io.github.trevarj.motd.ui.settings.addnetwork.networkPreset
import io.github.trevarj.motd.ui.theme.LocalLottieMotionEnabled
import io.github.trevarj.motd.ui.theme.MotdMotion
import io.github.trevarj.motd.ui.theme.MotdTheme
import io.github.trevarj.motd.ui.theme.lottieStrokeColor

/** Stateful entry: wires the ViewModel; the wizard is a single route with an internal pager. */
@Composable
fun OnboardingScreen(
    onDone: () -> Unit = {},
    onScanInvite: () -> Unit = {},
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    OnboardingContent(
        state = state,
        onNext = viewModel::next,
        onBack = viewModel::back,
        onSkip = { viewModel.skip(onDone) },
        onChoose = viewModel::chooseConnection,
        onChooseBouncerKind = viewModel::chooseBouncerKind,
        onSelectPreset = viewModel::selectPreset,
        onServerChange = viewModel::editServer,
        onAuthChange = viewModel::editAuth,
        onSojuLoginChange = viewModel::editSojuLogin,
        onZncLoginChange = viewModel::editZncLogin,
        onRetry = viewModel::retryConnect,
        onRetryBouncerDiscovery = viewModel::retryBouncerDiscovery,
        onToggleBouncer = viewModel::toggleBouncerNetwork,
        onBouncerAddDraftChange = viewModel::editBouncerAddDraft,
        onAddBouncer = viewModel::addBouncerNetwork,
        onSelectHistoryDepth = viewModel::selectHistorySyncDepth,
        onFinish = { viewModel.finish(onDone) },
        onConfirmPlaintext = viewModel::confirmPlaintext,
        onDismissPlaintext = viewModel::dismissPlaintextWarning,
        onScanInvite = onScanInvite,
    )
}

@Composable
fun OnboardingContent(
    state: OnboardingState,
    onNext: () -> Unit,
    onBack: () -> Unit,
    onSkip: () -> Unit,
    onChoose: (ConnectionChoice) -> Unit,
    onChooseBouncerKind: (BouncerKind) -> Unit,
    onSelectPreset: (NetworkPresetId) -> Unit,
    onServerChange: (ServerForm) -> Unit,
    onAuthChange: (AuthForm) -> Unit,
    onSojuLoginChange: (SojuLoginForm) -> Unit,
    onZncLoginChange: (ZncLoginForm) -> Unit,
    onRetry: () -> Unit,
    onRetryBouncerDiscovery: () -> Unit,
    onToggleBouncer: (String) -> Unit,
    onBouncerAddDraftChange: (BouncerAddDraft) -> Unit,
    onAddBouncer: () -> Unit,
    onSelectHistoryDepth: (HistorySyncDepth) -> Unit,
    onFinish: () -> Unit,
    onConfirmPlaintext: () -> Unit,
    onDismissPlaintext: () -> Unit,
    onScanInvite: () -> Unit = {},
) {
    val steps = OnboardingStep.entries
    val pagerState = rememberPagerState(pageCount = { steps.size })

    // Keep the pager synced to the reducer-driven step. Drop field focus first: a focused field
    // keeps issuing cursor bring-into-view requests, and those target the pager's own scrollable,
    // so they can cancel this page animation and snap the wizard back to the step being left.
    val focusManager = LocalFocusManager.current
    LaunchedEffect(state.step) {
        focusManager.clearFocus(force = true)
        pagerState.animateScrollToPage(steps.indexOf(state.step))
    }

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f),
            userScrollEnabled = false,
        ) { page ->
            when (steps[page]) {
                OnboardingStep.WELCOME -> {
                    WelcomePage()
                }

                OnboardingStep.CHOICE -> {
                    ChoicePage(state, onChoose, onChooseBouncerKind, onSelectPreset)
                }

                OnboardingStep.SERVER -> {
                    ServerPage(state, onServerChange, onAuthChange, authOnly = false)
                }

                OnboardingStep.AUTH -> {
                    if (state.isBouncer) {
                        BouncerAuthPage(state, onSojuLoginChange, onZncLoginChange)
                    } else {
                        // Direct path AUTH step: mechanism picker only (server fields live on step 3).
                        ServerPage(state, onServerChange, onAuthChange, authOnly = true)
                    }
                }

                OnboardingStep.CONNECT -> {
                    ConnectPage(
                        state,
                        onRetry,
                        onRetryBouncerDiscovery,
                        onToggleBouncer,
                        onBouncerAddDraftChange,
                        onAddBouncer,
                        onSelectHistoryDepth,
                    )
                }

                OnboardingStep.FINISH -> {
                    FinishPage()
                }
            }
        }
        WizardBar(
            state = state,
            onNext = onNext,
            onBack = onBack,
            onSkip = onSkip,
            onFinish = onFinish,
            onScanInvite = onScanInvite,
        )
    }

    if (state.showPlaintextWarning) {
        AlertDialog(
            modifier = Modifier.testTag("plaintext_network_warning"),
            onDismissRequest = onDismissPlaintext,
            title = { Text(stringResource(R.string.add_network_plaintext_title)) },
            text = { Text(stringResource(R.string.add_network_plaintext_message)) },
            confirmButton = {
                Button(onClick = onConfirmPlaintext) {
                    Text(stringResource(R.string.add_network_plaintext_continue))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissPlaintext) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            },
        )
    }
}

@Composable
private fun WizardBar(
    state: OnboardingState,
    onNext: () -> Unit,
    onBack: () -> Unit,
    onSkip: () -> Unit,
    onFinish: () -> Unit,
    onScanInvite: () -> Unit,
) {
    Column(
        // The activity draws edge-to-edge, so keep the actual touch targets above gesture and
        // three-button navigation. Semantics clicks do not reveal this class of overlap.
        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.step != OnboardingStep.WELCOME) {
                // Stable handle: Back is icon-agnostic across steps.
                TextButton(onClick = onBack, modifier = Modifier.testTag("onboarding_back_button")) {
                    Text(stringResource(R.string.onboarding_back))
                }
            } else {
                TextButton(onClick = onSkip, modifier = Modifier.testTag("onboarding_skip_button")) {
                    Text(stringResource(R.string.onboarding_skip))
                }
            }
            // Single stable handle for the forward button whose label varies (Get started/Next/Finish).
            val forwardTag = Modifier.testTag("onboarding_forward_button")
            when (state.step) {
                OnboardingStep.WELCOME -> {
                    Button(onClick = onNext, modifier = forwardTag) {
                        Text(stringResource(R.string.onboarding_get_started))
                    }
                }

                OnboardingStep.FINISH -> {
                    Button(onClick = onFinish, modifier = forwardTag) {
                        Text(stringResource(R.string.onboarding_finish))
                    }
                }

                else -> {
                    Button(onClick = onNext, enabled = state.canAdvance, modifier = forwardTag) {
                        Text(stringResource(R.string.onboarding_next))
                    }
                }
            }
        }
        if (state.step == OnboardingStep.WELCOME) {
            OutlinedButton(
                onClick = onScanInvite,
                modifier = Modifier.fillMaxWidth().testTag("onboarding_scan_invite"),
            ) {
                Text(stringResource(R.string.invite_scan_title))
            }
        }
    }
}

@Composable
private fun WelcomePage() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Brand hero above the welcome copy: the mark draws itself on, the wordmark under it is
        // the same static lockup geometry the stacked hero vector used.
        WelcomeHeroMark()
        Spacer(Modifier.height(16.dp))
        Image(
            painter = painterResource(R.drawable.motd_onboarding_wordmark),
            contentDescription = null,
            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface),
            modifier = Modifier.width(91.dp).height(31.dp),
        )
        Spacer(Modifier.height(24.dp))
        Text(
            stringResource(R.string.onboarding_welcome_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Text(
            stringResource(R.string.onboarding_welcome_tagline),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

/**
 * The welcome hero's one-shot entrance: the bubble mark strokes itself on, then its message rays
 * stagger in. Plays once per entry into the screen and never loops; a recreation (rotation, process
 * death) restores the settled last frame rather than replaying the entrance under the user, and the
 * platform animator scale can suppress it entirely.
 */
@Composable
private fun WelcomeHeroMark() {
    val composition by rememberLottieComposition(
        LottieCompositionSpec.RawRes(R.raw.onboarding_hero),
    )
    var played by rememberSaveable { mutableStateOf(false) }
    val motionEnabled = LocalLottieMotionEnabled.current
    // animateLottieCompositionAsState rather than a bare LottieAnimatable: it divides the speed by
    // the system animator scale, so a device set to half speed slows the entrance instead of
    // ignoring the magnitude and only honouring the off switch.
    val progress by animateLottieCompositionAsState(
        composition = composition,
        isPlaying = motionEnabled && !played,
        iterations = 1,
    )
    // Keyed on the boolean, not the frame value, so the effect is not relaunched every frame.
    LaunchedEffect(progress >= 1f) { if (progress >= 1f) played = true }
    val strokeColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val dynamicProperties =
        remember(strokeColor) {
            // Every stroke is the same ink the static lockup was tinted with. Built directly rather
            // than through rememberLottieDynamicProperty, which keys on the vararg array's identity.
            LottieDynamicProperties(
                listOf(
                    lottieStrokeColor(strokeColor, KeyPath("**")),
                ),
            )
        }
    LottieAnimation(
        composition = composition,
        progress = { if (played || !motionEnabled) 1f else progress },
        dynamicProperties = dynamicProperties,
        modifier = Modifier.width(68.dp).height(66.dp),
    )
}

@Composable
private fun ChoicePage(
    state: OnboardingState,
    onChoose: (ConnectionChoice) -> Unit,
    onChooseBouncerKind: (BouncerKind) -> Unit,
    onSelectPreset: (NetworkPresetId) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()).selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            stringResource(R.string.onboarding_choice_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Column {
            ChoiceCard(
                title = stringResource(R.string.onboarding_choice_network_title),
                desc = stringResource(R.string.onboarding_choice_network_desc),
                selected = state.choice == ConnectionChoice.NETWORK,
                onClick = { onChoose(ConnectionChoice.NETWORK) },
                modifier = Modifier.testTag("onboarding_choice_network"),
            )
            AnimatedVisibility(
                visible = state.choice == ConnectionChoice.NETWORK,
                enter =
                    fadeIn(MotdMotion.fadeIn) +
                        expandVertically(animationSpec = MotdMotion.contentSize),
                exit =
                    fadeOut(MotdMotion.microFadeOut) +
                        shrinkVertically(animationSpec = MotdMotion.contentSize),
            ) {
                OnboardingDisclosure(
                    title = stringResource(R.string.add_network_preset_section),
                    summary = networkPreset(state.presetId)?.displayName ?: stringResource(R.string.add_network_preset_custom),
                    tag = "onboarding_network_presets",
                ) {
                    NetworkPresetPicker(selected = state.presetId, onSelect = onSelectPreset)
                }
            }
        }
        Column {
            ChoiceCard(
                title = stringResource(R.string.onboarding_choice_bouncer_title),
                desc = stringResource(R.string.onboarding_choice_bouncer_desc),
                selected = state.choice == ConnectionChoice.BOUNCER,
                onClick = { onChoose(ConnectionChoice.BOUNCER) },
                modifier = Modifier.testTag("onboarding_choice_bouncer"),
            )
            AnimatedVisibility(
                visible = state.isBouncer,
                enter =
                    fadeIn(MotdMotion.fadeIn) +
                        expandVertically(animationSpec = MotdMotion.contentSize),
                exit =
                    fadeOut(MotdMotion.microFadeOut) +
                        shrinkVertically(animationSpec = MotdMotion.contentSize),
            ) {
                Column(
                    modifier = Modifier.padding(top = 16.dp).selectableGroup(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ChoiceCard(
                        title = stringResource(R.string.bouncer_kind_soju),
                        desc = stringResource(R.string.bouncer_kind_soju_desc),
                        selected = state.bouncerKind == BouncerKind.SOJU,
                        onClick = { onChooseBouncerKind(BouncerKind.SOJU) },
                        modifier = Modifier.testTag("onboarding_choice_soju"),
                    )
                    ChoiceCard(
                        title = stringResource(R.string.bouncer_kind_znc),
                        desc = stringResource(R.string.bouncer_kind_znc_desc),
                        selected = state.bouncerKind == BouncerKind.ZNC,
                        onClick = { onChooseBouncerKind(BouncerKind.ZNC) },
                        modifier = Modifier.testTag("onboarding_choice_znc"),
                    )
                }
            }
        }
    }
}

@Composable
private fun ChoiceCard(
    title: String,
    desc: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier =
            modifier
                .fillMaxWidth()
                .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
                .then(
                    if (selected) {
                        Modifier.border(
                            2.dp,
                            MaterialTheme.colorScheme.primary,
                            RoundedCornerShape(12.dp),
                        )
                    } else {
                        Modifier
                    },
                ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                desc,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/**
 * Steps 3 (server) and 4 (auth) share the [NetworkForm], but render disjoint sections: the SERVER
 * step shows server fields only, the direct-path AUTH step shows the mechanism picker only.
 */
@Composable
private fun ServerPage(
    state: OnboardingState,
    onServerChange: (ServerForm) -> Unit,
    onAuthChange: (AuthForm) -> Unit,
    authOnly: Boolean,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
    ) {
        Text(
            stringResource(if (authOnly) R.string.onboarding_auth_title else R.string.onboarding_server_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        Text(
            stringResource(if (authOnly) R.string.onboarding_auth_hint else R.string.onboarding_server_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        NetworkForm(
            modifier = Modifier.padding(horizontal = 8.dp),
            server = state.server,
            auth = state.auth,
            onServerChange = onServerChange,
            onAuthChange = onAuthChange,
            showServer = !authOnly,
            showAuth = authOnly,
            // Optional identity details use the same disclosure as Add Network.
            showIdentity = !state.isBouncer,
            showNick = state.isBouncer,
            preset = networkPreset(state.presetId),
        )
    }
}

/**
 * Simplified AUTH page for the soju bouncer path: only username + password, always SASL PLAIN.
 * No mechanism picker (NONE/EXTERNAL are meaningless for soju login).
 */
@Composable
private fun BouncerAuthPage(
    state: OnboardingState,
    onSojuLoginChange: (SojuLoginForm) -> Unit,
    onZncLoginChange: (ZncLoginForm) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.onboarding_auth_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        BouncerLoginFields(
            kind = state.bouncerKind,
            server = state.server,
            sojuLogin = state.sojuLogin,
            zncLogin = state.zncLogin,
            onServerChange = {},
            onSojuLoginChange = onSojuLoginChange,
            onZncLoginChange = onZncLoginChange,
            showEndpoint = false,
        )
    }
}

@Composable
private fun ConnectPage(
    state: OnboardingState,
    onRetry: () -> Unit,
    onRetryBouncerDiscovery: () -> Unit,
    onToggleBouncer: (String) -> Unit,
    onBouncerAddDraftChange: (BouncerAddDraft) -> Unit,
    onAddBouncer: () -> Unit,
    onSelectHistoryDepth: (HistorySyncDepth) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.onboarding_connect_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        StateIndicator(state.connState)
        if (state.stateLog.isNotEmpty()) {
            OnboardingDisclosure(
                title = stringResource(R.string.onboarding_connection_details),
                tag = "onboarding_connection_details",
            ) {
                state.stateLog.forEach { entry ->
                    val label = if (entry is IrcClientState.Failed) "Failed: ${entry.reason}" else entry::class.simpleName.orEmpty()
                    Text(label, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        // The connection retries, so `connState` may already be back to Connecting after a failure;
        // surface the latest captured failure reason (reducer's `error`) so it stays visible (#43).
        val failureReason = (state.connState as? IrcClientState.Failed)?.reason ?: state.error
        if (failureReason != null) {
            Text(
                failureReason,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.onboarding_connect_retry)) }
        }

        // Retain discovery/add recovery controls after the root loses its connection. A failed
        // refresh is actionable only if its retry affordance stays visible while reconnecting.
        if (state.isSoju && state.bouncerDiscovery != null) {
            BouncerNetworksSection(
                state,
                onRetryBouncerDiscovery,
                onToggleBouncer,
                onBouncerAddDraftChange,
                onAddBouncer,
                onSelectHistoryDepth,
            )
        }
    }
}

/** Icon-relevant collapse of [IrcClientState] so busy states never crossfade into each other. */
private enum class StateIndicatorKind { READY, FAILED, BUSY }

@Composable
private fun StateIndicator(connState: IrcClientState?) {
    Row(
        // Stable handle for the connect-step status line (label varies: Connecting…/Connected as …).
        modifier = Modifier.testTag("onboarding_state_indicator"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // House fade so the icon swaps at the same tempo as the sibling label below.
        AnimatedContent(
            targetState =
                when (connState) {
                    is IrcClientState.Ready -> StateIndicatorKind.READY
                    is IrcClientState.Failed -> StateIndicatorKind.FAILED
                    else -> StateIndicatorKind.BUSY
                },
            transitionSpec = {
                fadeIn(MotdMotion.microFadeIn) togetherWith fadeOut(MotdMotion.microFadeOut)
            },
            label = "connState",
        ) { kind ->
            when (kind) {
                StateIndicatorKind.READY -> {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }

                StateIndicatorKind.FAILED -> {
                    Icon(Icons.Filled.Close, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                }

                StateIndicatorKind.BUSY -> {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
        }
        AnimatedContent(
            targetState =
                when (connState) {
                    is IrcClientState.Ready -> "Connected as ${connState.nick}"
                    is IrcClientState.Failed -> "Failed"
                    IrcClientState.Registering -> "Registering…"
                    IrcClientState.Connecting -> "Connecting…"
                    else -> "Starting…"
                },
            transitionSpec = {
                fadeIn(MotdMotion.microFadeIn) togetherWith fadeOut(MotdMotion.microFadeOut)
            },
            label = "onboarding_connection_label",
        ) { label -> Text(label) }
    }
}

@Composable
private fun BouncerNetworksSection(
    state: OnboardingState,
    onRetryDiscovery: () -> Unit,
    onToggleBouncer: (String) -> Unit,
    onDraftChange: (BouncerAddDraft) -> Unit,
    onAddBouncer: () -> Unit,
    onSelectHistoryDepth: (HistorySyncDepth) -> Unit,
) {
    Text(
        stringResource(R.string.onboarding_connect_networks_title),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 12.dp),
    )
    Text(
        stringResource(R.string.onboarding_connect_import_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    when (val discovery = state.bouncerDiscovery) {
        is BouncerDiscoveryState.Loading -> {
            Row(
                modifier = Modifier.testTag("onboarding_bouncer_discovery_loading"),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp).testTag("onboarding_bouncer_discovery_progress"),
                    strokeWidth = 2.dp,
                )
                Text(stringResource(R.string.onboarding_bouncer_discovery_loading), style = MaterialTheme.typography.bodySmall)
            }
        }

        is BouncerDiscoveryState.Failed -> {
            Text(
                bouncerErrorMessage(discovery.error),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("onboarding_bouncer_discovery_error"),
            )
            OutlinedButton(
                onClick = onRetryDiscovery,
                modifier = Modifier.testTag("onboarding_bouncer_discovery_retry"),
            ) { Text(stringResource(R.string.onboarding_bouncer_discovery_retry)) }
        }

        is BouncerDiscoveryState.Loaded -> {
            OutlinedButton(
                onClick = onRetryDiscovery,
                modifier = Modifier.testTag("onboarding_bouncer_discovery_refresh"),
            ) { Text(stringResource(R.string.onboarding_bouncer_discovery_refresh)) }
        }

        null -> {}
    }
    if (state.bouncerNetworks.isNotEmpty()) {
        val allSelected = state.bouncerNetworks.all { it.selected }
        TextButton(
            onClick = {
                state.bouncerNetworks.filter { it.selected == allSelected }.forEach { onToggleBouncer(it.netId) }
            },
            modifier = Modifier.testTag("onboarding_bouncer_toggle_all"),
        ) {
            Text(stringResource(if (allSelected) R.string.action_unselect_all else R.string.action_select_all))
        }
    }
    Column(Modifier.selectableGroup()) {
        state.bouncerNetworks.forEach { row ->
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .testTag("onboarding_bouncer_row_${row.netId}")
                        .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(row.name, modifier = Modifier.weight(1f))
                Switch(
                    checked = row.selected,
                    onCheckedChange = { onToggleBouncer(row.netId) },
                    modifier = Modifier.testTag("onboarding_bouncer_switch_${row.netId}"),
                )
            }
        }
    }

    val adding = state.bouncerAdd is BouncerAddState.Submitting
    if (state.bouncerAdd is BouncerAddState.Failed) {
        Text(
            bouncerErrorMessage(state.bouncerAdd.error),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("onboarding_bouncer_add_error"),
        )
    }
    OnboardingDisclosure(
        title = stringResource(R.string.onboarding_connect_add_network),
        tag = "onboarding_bouncer_add_options",
        initiallyExpanded =
            state.bouncerAddDraft.name.isNotEmpty() || state.bouncerAddDraft.host.isNotEmpty() ||
                adding || state.bouncerAdd is BouncerAddState.Failed,
    ) {
        OutlinedTextField(
            value = state.bouncerAddDraft.name,
            onValueChange = { onDraftChange(state.bouncerAddDraft.copy(name = it)) },
            label = { Text(stringResource(R.string.onboarding_connect_add_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("onboarding_bouncer_add_name"),
        )
        OutlinedTextField(
            value = state.bouncerAddDraft.host,
            onValueChange = { onDraftChange(state.bouncerAddDraft.copy(host = it)) },
            label = { Text(stringResource(R.string.onboarding_connect_add_host)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("onboarding_bouncer_add_host"),
        )
        OutlinedButton(
            onClick = onAddBouncer,
            enabled = state.bouncerAddDraft.isValid && !adding,
            modifier = Modifier.testTag("onboarding_bouncer_add_submit"),
        ) {
            if (adding) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp).testTag("onboarding_bouncer_add_progress"),
                    strokeWidth = 2.dp,
                )
            } else {
                Text(stringResource(R.string.onboarding_connect_add_network))
            }
        }
    }

    HistorySyncDepthSection(selected = state.historySyncDepth, onSelect = onSelectHistoryDepth)
}

/**
 * First-sync window for the imported networks. A shorter window keeps the initial catch-up quick on
 * a bouncer with years of backlog; "Everything" enumerates the whole account in one pass.
 */
@Composable
private fun HistorySyncDepthSection(
    selected: HistorySyncDepth,
    onSelect: (HistorySyncDepth) -> Unit,
) {
    OnboardingDisclosure(
        title = stringResource(R.string.onboarding_history_depth_title),
        summary = historySyncDepthLabel(selected),
        tag = "onboarding_history_options",
    ) {
        Text(
            stringResource(R.string.onboarding_history_depth_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.selectableGroup()) {
            HistorySyncDepth.entries.forEach { depth ->
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(
                                selected = depth == selected,
                                onClick = { onSelect(depth) },
                                role = Role.RadioButton,
                            ).testTag("onboarding_history_depth_${depth.name.lowercase()}")
                            .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RadioButton(selected = depth == selected, onClick = null)
                    Text(historySyncDepthLabel(depth))
                }
            }
        }
    }
}

@Composable
private fun historySyncDepthLabel(depth: HistorySyncDepth): String =
    when (depth) {
        HistorySyncDepth.WEEK -> stringResource(R.string.onboarding_history_depth_week)
        HistorySyncDepth.MONTH -> stringResource(R.string.onboarding_history_depth_month)
        HistorySyncDepth.QUARTER -> stringResource(R.string.onboarding_history_depth_quarter)
        HistorySyncDepth.EVERYTHING -> stringResource(R.string.onboarding_history_depth_everything)
    }

@Composable
private fun OnboardingDisclosure(
    title: String,
    tag: String,
    summary: String? = null,
    initiallyExpanded: Boolean = false,
    content: @Composable () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    LaunchedEffect(initiallyExpanded) {
        if (initiallyExpanded) expanded = true
    }
    val description = stringResource(if (expanded) R.string.onboarding_disclosure_expanded else R.string.onboarding_disclosure_collapsed)
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(role = Role.Button) { expanded = !expanded }
                    .semantics { stateDescription = description }
                    .testTag(tag)
                    .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                summary?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
        }
        if (expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
        }
    }
}

@Composable
private fun bouncerErrorMessage(error: BouncerOperationError): String =
    when (error) {
        BouncerOperationError.ConnectionLost -> {
            stringResource(R.string.onboarding_bouncer_connection_lost)
        }

        is BouncerOperationError.ServerRejected -> {
            if (error.detail.isBlank()) {
                stringResource(R.string.onboarding_bouncer_server_rejected_generic)
            } else {
                stringResource(R.string.onboarding_bouncer_server_rejected, error.detail)
            }
        }

        is BouncerOperationError.Unexpected -> {
            if (error.detail.isBlank()) {
                stringResource(R.string.onboarding_bouncer_request_failed)
            } else {
                error.detail
            }
        }
    }

@Composable
private fun FinishPage() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier.size(72.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(64.dp),
            )
        }
        Text(
            stringResource(R.string.onboarding_finish),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

@Preview
@Composable
private fun OnboardingChoicePreview() {
    MotdTheme {
        // Surface so the preview reflects the runtime themed background under the wizard.
        Surface {
            OnboardingContent(
                state = OnboardingState(step = OnboardingStep.CHOICE, choice = ConnectionChoice.NETWORK),
                onNext = {},
                onBack = {},
                onSkip = {},
                onChoose = {},
                onChooseBouncerKind = {},
                onSelectPreset = {},
                onServerChange = {},
                onAuthChange = {},
                onSojuLoginChange = {},
                onZncLoginChange = {},
                onRetry = {},
                onRetryBouncerDiscovery = {},
                onToggleBouncer = {},
                onBouncerAddDraftChange = {},
                onAddBouncer = {},
                onSelectHistoryDepth = {},
                onFinish = {},
                onConfirmPlaintext = {},
                onDismissPlaintext = {},
            )
        }
    }
}

@Preview
@Composable
private fun OnboardingConnectPreview() {
    MotdTheme {
        Surface {
            OnboardingContent(
                state =
                    OnboardingState(
                        step = OnboardingStep.CONNECT,
                        choice = ConnectionChoice.BOUNCER,
                        connState = IrcClientState.Ready("me", emptySet(), emptyMap()),
                        stateLog = listOf(IrcClientState.Connecting, IrcClientState.Registering),
                        bouncerDiscovery =
                            BouncerDiscoveryState.Loaded(
                                listOf(
                                    BouncerNetworkRow("1", "Libera", selected = true),
                                    BouncerNetworkRow("2", "OFTC", selected = false),
                                ),
                            ),
                    ),
                onNext = {},
                onBack = {},
                onSkip = {},
                onChoose = {},
                onChooseBouncerKind = {},
                onSelectPreset = {},
                onServerChange = {},
                onAuthChange = {},
                onSojuLoginChange = {},
                onZncLoginChange = {},
                onRetry = {},
                onRetryBouncerDiscovery = {},
                onToggleBouncer = {},
                onBouncerAddDraftChange = {},
                onAddBouncer = {},
                onSelectHistoryDepth = {},
                onFinish = {},
                onConfirmPlaintext = {},
                onDismissPlaintext = {},
            )
        }
    }
}
