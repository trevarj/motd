package io.github.trevarj.motd

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.AnnotatedString
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.ui.onboarding.BouncerAddDraft
import io.github.trevarj.motd.ui.onboarding.BouncerAddState
import io.github.trevarj.motd.ui.onboarding.BouncerDiscoveryState
import io.github.trevarj.motd.ui.onboarding.BouncerNetworkRow
import io.github.trevarj.motd.ui.onboarding.BouncerOperationError
import io.github.trevarj.motd.ui.onboarding.ConnectionChoice
import io.github.trevarj.motd.ui.onboarding.OnboardingContent
import io.github.trevarj.motd.ui.onboarding.OnboardingState
import io.github.trevarj.motd.ui.onboarding.OnboardingStep
import io.github.trevarj.motd.ui.theme.MotdTheme
import org.junit.Assert.assertEquals
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
class OnboardingBouncerUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule val compose = createComposeRule()

    @Test
    fun loadingDiscovery_isVisible() {
        content(discovery = BouncerDiscoveryState.Loading())

        compose.onAllNodesWithTag("onboarding_bouncer_discovery_loading").assertCountEquals(1)
        compose.onAllNodesWithTag("onboarding_bouncer_discovery_progress").assertCountEquals(1)
    }

    @Test
    fun failedDiscovery_showsRetainedRowsAndRetry() {
        var retried = false
        content(
            discovery =
                BouncerDiscoveryState.Failed(
                    BouncerOperationError.ConnectionLost,
                    listOf(BouncerNetworkRow("libera", "Libera", selected = true)),
                ),
            onRetryDiscovery = { retried = true },
        )

        compose.onAllNodesWithTag("onboarding_bouncer_row_libera").assertCountEquals(1)
        compose.onAllNodesWithTag("onboarding_bouncer_discovery_error").assertCountEquals(1)
        compose.onNodeWithTag("onboarding_bouncer_discovery_retry").performClick()
        compose.runOnIdle { assertTrue(retried) }
    }

    @Test
    fun selectAll_togglesEveryUnselectedNetwork() {
        val toggled = mutableListOf<String>()
        content(
            discovery =
                BouncerDiscoveryState.Loaded(
                    listOf(
                        BouncerNetworkRow("libera", "Libera", selected = false),
                        BouncerNetworkRow("oftc", "OFTC", selected = false),
                    ),
                ),
            onToggleBouncer = toggled::add,
        )

        compose.onNodeWithTag("onboarding_bouncer_toggle_all").assertTextContains("Select all").performClick()
        compose.runOnIdle { assertEquals(listOf("libera", "oftc"), toggled) }
    }

    @Test
    fun unselectAll_togglesEverySelectedNetwork() {
        val toggled = mutableListOf<String>()
        content(
            discovery =
                BouncerDiscoveryState.Loaded(
                    listOf(
                        BouncerNetworkRow("libera", "Libera", selected = true),
                        BouncerNetworkRow("oftc", "OFTC", selected = true),
                    ),
                ),
            onToggleBouncer = toggled::add,
        )

        compose.onNodeWithTag("onboarding_bouncer_toggle_all").assertTextContains("Unselect all").performClick()
        compose.runOnIdle { assertEquals(listOf("libera", "oftc"), toggled) }
    }

    @Test
    fun pendingAdd_disablesDuplicateSubmissionAndRetainsDraft() {
        content(
            discovery = BouncerDiscoveryState.Loaded(emptyList()),
            draft = BouncerAddDraft("New", "irc.new.example"),
            add = BouncerAddState.Submitting,
        )

        compose.onNodeWithTag("onboarding_bouncer_add_name").assertTextContains("New")
        compose.onNodeWithTag("onboarding_bouncer_add_host").assertTextContains("irc.new.example")
        compose.onAllNodesWithTag("onboarding_bouncer_add_progress").assertCountEquals(1)
        compose.onNodeWithTag("onboarding_bouncer_add_submit").assertIsNotEnabled()
    }

    @Test
    fun failedAdd_retainsDraftUntilSuccessClearsIt() {
        var state by mutableStateOf(
            baseState(
                discovery = BouncerDiscoveryState.Loaded(emptyList()),
                draft = BouncerAddDraft("New", "irc.new.example"),
                add = BouncerAddState.Failed(BouncerOperationError.ServerRejected("not allowed")),
            ),
        )
        compose.setContent {
            MotdTheme {
                OnboardingContent(
                    state = state,
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

        compose.onAllNodesWithTag("onboarding_bouncer_add_error").assertCountEquals(1)
        compose.onNodeWithTag("onboarding_bouncer_add_name").assertTextContains("New")
        compose.runOnIdle { state = state.copy(bouncerAdd = BouncerAddState.Success, bouncerAddDraft = BouncerAddDraft()) }
        val emptyEditableText =
            SemanticsMatcher.expectValue(
                SemanticsProperties.EditableText,
                AnnotatedString(""),
            )
        compose.onNodeWithTag("onboarding_bouncer_add_name").assert(emptyEditableText)
        compose.onNodeWithTag("onboarding_bouncer_add_host").assert(emptyEditableText)
    }

    private fun content(
        discovery: BouncerDiscoveryState,
        draft: BouncerAddDraft = BouncerAddDraft(),
        add: BouncerAddState = BouncerAddState.Idle,
        onRetryDiscovery: () -> Unit = {},
        onToggleBouncer: (String) -> Unit = {},
    ) {
        compose.setContent {
            MotdTheme {
                OnboardingContent(
                    state = baseState(discovery, draft, add),
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
                    onRetryBouncerDiscovery = onRetryDiscovery,
                    onToggleBouncer = onToggleBouncer,
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

    private fun baseState(
        discovery: BouncerDiscoveryState,
        draft: BouncerAddDraft = BouncerAddDraft(),
        add: BouncerAddState = BouncerAddState.Idle,
    ) = OnboardingState(
        step = OnboardingStep.CONNECT,
        choice = ConnectionChoice.BOUNCER,
        networkId = 1L,
        connState = IrcClientState.Ready("motd", emptySet(), emptyMap()),
        bouncerDiscovery = discovery,
        bouncerSessionGeneration = 1L,
        bouncerAddDraft = draft,
        bouncerAdd = add,
    )
}
