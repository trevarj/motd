package io.github.trevarj.motd.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
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
class NetworkOnConnectUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule val compose = createComposeRule()

    @Test fun directNetwork_editsScriptAndEnablesSave() = checkRole(NetworkRole.DIRECT, null)

    @Test fun rootNetwork_editsScriptAndEnablesSave() = checkRole(NetworkRole.BOUNCER_ROOT, "Root bouncer session only")

    @Test fun childNetwork_editsScriptAndEnablesSave() = checkRole(NetworkRole.BOUNCER_CHILD, "This bouncer network only.")

    private fun checkRole(
        role: NetworkRole,
        scope: String?,
    ) {
        val script = "/mode +i\n/msg Gatekeeper hello"
        var edited = ""
        var saved = false
        val network =
            NetworkEntity(
                id = 1,
                name = "Example",
                role = role,
                parentId = if (role == NetworkRole.BOUNCER_CHILD) 2 else null,
                bouncerNetId = if (role == NetworkRole.BOUNCER_CHILD) "example" else null,
                host = "irc.example",
                port = 6697,
                nick = "me",
                username = "me",
                realname = "Me",
            )
        compose.setContent {
            var commands by remember { mutableStateOf("") }
            MotdTheme(dynamicColor = false) {
                NetworkSettingsContent(
                    state =
                        NetworkSettingsUiState(
                            loaded = true,
                            entity = network,
                            displayName = network.name,
                            server = network.toServerForm(),
                            auth = network.toAuthForm(),
                            onConnectCommands = commands,
                            parentName = "Bouncer",
                        ),
                    onBack = {},
                    onOnConnectCommandsChange = {
                        commands = it
                        edited = it
                    },
                    onServerChange = {},
                    onAuthChange = {},
                    onSave = { saved = true },
                    onDelete = {},
                )
            }
        }
        compose.onNodeWithTag("network_on_connect_commands").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Commands after connecting").assertExists()
        if (scope != null) compose.onNodeWithText(scope, substring = true).assertExists()
        compose.onNodeWithTag("network_on_connect_commands").performTextInput(script)
        compose.runOnIdle { assertEquals(script, edited) }
        compose.onNodeWithTag("network_settings_save").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(true, saved) }
    }
}
