package io.github.trevarj.motd

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import io.github.trevarj.motd.ui.onboarding.AuthForm
import io.github.trevarj.motd.ui.onboarding.ConnectionChoice
import io.github.trevarj.motd.ui.onboarding.OnboardingContent
import io.github.trevarj.motd.ui.onboarding.OnboardingState
import io.github.trevarj.motd.ui.onboarding.OnboardingStep
import io.github.trevarj.motd.ui.onboarding.ServerForm
import io.github.trevarj.motd.ui.settings.NetworkForm
import io.github.trevarj.motd.ui.settings.addnetwork.AddNetworkContent
import io.github.trevarj.motd.ui.settings.addnetwork.AddNetworkUiState
import io.github.trevarj.motd.ui.settings.addnetwork.COMMON_NETWORK_PRESETS
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
        compose.onNodeWithTag("network_guidance_text").assertDoesNotExist()
        compose.onNodeWithTag("network_guidance_help")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
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
        compose.onNodeWithTag("network_guidance_help").performScrollTo().performClick()
        compose.onNodeWithTag("network_guidance_text").assertExists()
        compose.onNodeWithTag("network_guidance_help")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"))
            .performClick()
        compose.onNodeWithTag("network_guidance_text").assertDoesNotExist()
    }

    @Test
    fun selectedPresetLinksStayAvailableInAddNetworkWhileGuidanceIsCollapsed() {
        setAddNetwork(
            AddNetworkUiState(
                presetId = NetworkPresetId.IRCNET,
                server = ServerForm(host = "irc.ircnet.ca", nick = "me"),
            ),
        )

        compose.onNodeWithTag("network_guidance_card").assertExists()
        compose.onNodeWithTag("network_guidance_text").assertDoesNotExist()
        compose.onNodeWithTag("network_login_guide_link").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("https://www.ircnet.com/sasl"), openedUrls) }
    }

    @Test
    fun optionalSignInPathsExpandAndCustomHidesNetworkGuidance() {
        var presetId by mutableStateOf(NetworkPresetId.OFTC)
        compose.setContent {
            CompositionLocalProvider(LocalUriHandler provides uriHandler) {
                MotdTheme {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
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
        }

        compose.onNodeWithTag("network_guidance_text").assertDoesNotExist()
        compose.onNodeWithText("NickServ password (optional)").assertDoesNotExist()
        compose.onNodeWithTag("server_password_field").assertDoesNotExist()
        compose.onNodeWithTag("nickserv_options").performScrollTo().performClick()
        compose.onNodeWithText("NickServ password (optional)").assertExists()
        compose.onNodeWithTag("server_password_options").performScrollTo().performClick()
        compose.onNodeWithTag("server_password_field").assertExists()
        compose.onNodeWithTag("network_guidance_help").performScrollTo().performClick()
        compose.onNodeWithTag("network_guidance_text").assertExists()

        compose.runOnIdle { presetId = NetworkPresetId.EFNET }
        compose.onNodeWithTag("network_login_guide_link").assertDoesNotExist()
        compose.onNodeWithTag("network_registration_guide_link").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("https://www.efnet.org/"), openedUrls) }
        compose.runOnIdle { presetId = NetworkPresetId.CUSTOM }
        compose.onNodeWithTag("network_guidance_card").assertDoesNotExist()
    }

    @Test
    fun optionalIdentityDraftSurvivesCollapseAndInvalidCredentialsRemainReachable() {
        var server by mutableStateOf(ServerForm(host = "irc.example", nick = "me"))
        val auth = AuthForm(serverPassword = "invalid\npass", nickServPassword = "invalid pass")
        compose.setContent {
            MotdTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    NetworkForm(
                        server = server,
                        auth = auth,
                        onServerChange = { server = it },
                        onAuthChange = {},
                    )
                }
            }
        }
        compose.onNodeWithTag("network_host_field").assertExists()
        compose.onNodeWithTag("network_nick_field").assertExists()
        compose.onNodeWithText("Real name").assertDoesNotExist()
        compose.onNodeWithTag("network_identity_options").performScrollTo().performClick()
        compose.onNodeWithText("Real name").performScrollTo().performTextReplacement("My name")
        compose.onNodeWithTag("network_identity_options").performScrollTo().performClick()
        compose.onNodeWithText("Real name").assertDoesNotExist()
        compose.onNodeWithTag("network_identity_options").performClick()
        compose.onNodeWithText("Real name").assertTextContains("My name")
        compose.onNodeWithTag("server_password_field").assertExists()
        compose.onNodeWithText("NickServ password (optional)").assertExists()
        compose.onNodeWithText("Enter a password without whitespace and valid recovery commands.").assertExists()
    }

    @Test
    fun onboardingPresetsStartCompactAndKeepEverySelection() {
        val selected = mutableListOf<NetworkPresetId>()
        setOnboarding(
            OnboardingState(step = OnboardingStep.CHOICE, choice = ConnectionChoice.NETWORK),
            onSelectPreset = selected::add,
        )
        compose.onNodeWithText("Libera.Chat", substring = false).assertDoesNotExist()
        compose.onNodeWithTag("onboarding_network_presets").performScrollTo().performClick()
        compose.onNode(
            hasText("Custom") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton),
        ).performScrollTo().performClick()
        COMMON_NETWORK_PRESETS.forEach { preset ->
            compose.onNodeWithText(preset.displayName, substring = false).performScrollTo().performClick()
        }
        compose.runOnIdle {
            assertEquals(listOf(NetworkPresetId.CUSTOM) + COMMON_NETWORK_PRESETS.map { it.id }, selected)
        }
        compose.onNodeWithTag("onboarding_network_presets").performScrollTo().performClick()
        compose.onNodeWithText("Libera.Chat", substring = false).assertDoesNotExist()
    }

    @Test
    fun plaintextConsentRemainsVisibleWithoutOpeningPresetDetails() {
        setOnboarding(
            OnboardingState(
                step = OnboardingStep.CHOICE,
                choice = ConnectionChoice.NETWORK,
                presetId = NetworkPresetId.UNDERNET,
                showPlaintextWarning = true,
            ),
        )
        compose.onNodeWithTag("plaintext_network_warning").assertExists()
        compose.onNodeWithText("Libera.Chat", substring = false).assertDoesNotExist()
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

    private fun setOnboarding(
        state: OnboardingState,
        onSelectPreset: (NetworkPresetId) -> Unit = {},
    ) {
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
                        onSelectPreset = onSelectPreset,
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
