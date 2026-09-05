package io.github.trevarj.motd

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.trevarj.motd.ui.onboarding.AuthForm
import io.github.trevarj.motd.ui.onboarding.ConnectionChoice
import io.github.trevarj.motd.ui.onboarding.OnboardingContent
import io.github.trevarj.motd.ui.onboarding.OnboardingState
import io.github.trevarj.motd.ui.onboarding.OnboardingStep
import io.github.trevarj.motd.ui.onboarding.ServerForm
import io.github.trevarj.motd.ui.settings.NetworkForm
import io.github.trevarj.motd.ui.settings.addnetwork.AddNetworkContent
import io.github.trevarj.motd.ui.settings.addnetwork.AddNetworkUiState
import io.github.trevarj.motd.ui.settings.addnetwork.NetworkPresetId
import io.github.trevarj.motd.ui.settings.addnetwork.networkPreset
import io.github.trevarj.motd.ui.theme.MotdTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class NetworkGuidanceUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule val compose = createComposeRule()

    private val openedUrls = mutableListOf<String>()
    private val uriHandler =
        object : UriHandler {
            override fun openUri(uri: String) {
                openedUrls += uri
            }
        }

    @Test
    fun selectedPresetGuidanceAppearsInOnboardingAndOpensOfficialLinks() {
        setOnboarding(
            OnboardingState(
                step = OnboardingStep.AUTH,
                choice = ConnectionChoice.NETWORK,
                presetId = NetworkPresetId.LIBERA,
                server = ServerForm(host = "irc.libera.chat", nick = "me"),
            ),
        )

        compose.onNodeWithTag("network_guidance_card").assertExists()
        compose.onNodeWithText("SASL username is your account name", substring = true).assertExists()
        compose.onNodeWithTag("network_registration_guide_link").performScrollTo().performClick()
        compose.onNodeWithTag("network_login_guide_link").performScrollTo().performClick()

        compose.runOnIdle {
            assertEquals(
                listOf(
                    "https://libera.chat/guides/registration",
                    "https://libera.chat/guides/sasl",
                ),
                openedUrls,
            )
        }
    }

    @Test
    fun selectedPresetGuidanceAppearsInAddNetworkAndUsesLoginIdCopy() {
        setAddNetwork(
            AddNetworkUiState(
                presetId = NetworkPresetId.IRCNET,
                server = ServerForm(host = "irc.ircnet.ca", nick = "me"),
            ),
        )

        compose.onNodeWithTag("network_guidance_card").assertExists()
        compose.onNodeWithText("web login ID, not your nickname", substring = true).assertExists()
        compose.onNodeWithTag("network_login_guide_link").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("https://www.ircnet.com/sasl"), openedUrls) }
    }

    @Test
    fun guidanceCoversSpecialAuthModelsAndCustomHidesIt() {
        var presetId by mutableStateOf(NetworkPresetId.OFTC)
        compose.setContent {
            CompositionLocalProvider(LocalUriHandler provides uriHandler) {
                MotdTheme {
                    NetworkForm(
                        server = ServerForm(host = "irc.example", nick = "me"),
                        auth = AuthForm(),
                        onServerChange = {},
                        onAuthChange = {},
                        showServer = false,
                        preset = networkPreset(presetId),
                    )
                }
            }
        }

        compose.onNodeWithText("OFTC does not support SASL", substring = true).assertExists()
        compose.onNodeWithText("NickServ password (optional)").assertExists()

        compose.runOnIdle { presetId = NetworkPresetId.EFNET }
        compose.onNodeWithText("no network account or nickname registration", substring = true).assertExists()

        compose.runOnIdle { presetId = NetworkPresetId.IRCHIGHWAY }
        compose.onNodeWithText("Password only", substring = true).assertExists()

        compose.runOnIdle { presetId = NetworkPresetId.QUAKENET }
        compose.onNodeWithText("official /auth flow", substring = true).assertExists()

        compose.runOnIdle { presetId = NetworkPresetId.UNDERNET }
        compose.onNodeWithText("Server password (optional)").assertExists()
        compose.onNodeWithText("+x! username password", substring = true).assertExists()

        compose.runOnIdle { presetId = NetworkPresetId.CUSTOM }
        compose.onNodeWithTag("network_guidance_card").assertDoesNotExist()
    }

    @Test
    fun onboardingBouncerFlowOmitsNetworkGuidance() {
        setOnboarding(
            OnboardingState(
                step = OnboardingStep.AUTH,
                choice = ConnectionChoice.BOUNCER,
                presetId = NetworkPresetId.LIBERA,
            ),
        )

        compose.onNodeWithTag("network_guidance_card").assertDoesNotExist()
    }

    @Test
    fun addNetworkBouncerFlowOmitsNetworkGuidance() {
        setAddNetwork(
            AddNetworkUiState(
                kind = ConnectionChoice.BOUNCER,
                presetId = NetworkPresetId.LIBERA,
            ),
        )

        compose.onNodeWithTag("network_guidance_card").assertDoesNotExist()
    }

    private fun setOnboarding(state: OnboardingState) {
        compose.setContent {
            CompositionLocalProvider(LocalUriHandler provides uriHandler) {
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
        }
    }

    private fun setAddNetwork(state: AddNetworkUiState) {
        compose.setContent {
            CompositionLocalProvider(LocalUriHandler provides uriHandler) {
                MotdTheme {
                    AddNetworkContent(
                        state = state,
                        onBack = {},
                        onSetKind = {},
                        onSetBouncerKind = {},
                        onSelectPreset = {},
                        onDisplayNameChange = {},
                        onServerChange = {},
                        onAuthChange = {},
                        onSojuLoginChange = {},
                        onZncLoginChange = {},
                        onSubmit = {},
                        onRetry = {},
                        onSaveAnyway = {},
                        onEditForm = {},
                        onAbandon = {},
                        onConfirmPlaintext = {},
                        onDismissPlaintext = {},
                    )
                }
            }
        }
    }
}
