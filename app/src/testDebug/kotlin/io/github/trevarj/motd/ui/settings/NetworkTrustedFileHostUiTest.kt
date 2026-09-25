package io.github.trevarj.motd.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.ui.theme.MotdTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class NetworkTrustedFileHostUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule val compose = createComposeRule()

    @Test
    fun trustedFileHostField_isVisibleForDirectNetwork() {
        setContent(NetworkRole.DIRECT)
        compose.onNodeWithTag("network_trusted_filehost").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun trustedFileHostField_isVisibleForBouncerChildNetwork() {
        setContent(NetworkRole.BOUNCER_CHILD)
        compose.onNodeWithTag("network_trusted_filehost").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun trustedFileHostField_reportsInvalidEntryThroughItsCallback() {
        compose.setContent {
            var trustedHost by remember { mutableStateOf("") }
            MotdTheme(dynamicColor = false) {
                NetworkSettingsContent(
                    state = stateFor(NetworkRole.DIRECT).copy(trustedFileHost = trustedHost),
                    onBack = {},
                    onTrustedFileHostChange = { trustedHost = it },
                    onServerChange = {},
                    onAuthChange = {},
                    onSave = {},
                    onDelete = {},
                )
            }
        }

        compose.onNodeWithTag("network_trusted_filehost").performTextInput("https://files.example")

        compose.onNodeWithText("Enter a hostname only, without a scheme, path, port, or IP address.").assertIsDisplayed()
    }

    private fun setContent(role: NetworkRole) {
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                NetworkSettingsContent(
                    state = stateFor(role),
                    onBack = {},
                    onServerChange = {},
                    onAuthChange = {},
                    onSave = {},
                    onDelete = {},
                )
            }
        }
    }

    private fun stateFor(role: NetworkRole) =
        NetworkSettingsUiState(
            loaded = true,
            entity =
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
                ),
            parentName = "Bouncer",
        )
}
