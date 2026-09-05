package io.github.trevarj.motd.ui.channelinfo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.MemberEntity
import io.github.trevarj.motd.ui.theme.MotdTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Drives the stateless Channel Info content and the shared ban picker with fake callbacks. The
 * fixture viewer is an op, so the controls section renders.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class ChannelControlsUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule val compose = createComposeRule()

    private val members = listOf(MemberEntity(1, "me", "@"), MemberEntity(1, "bob", ""))

    @Test
    fun inviteRowOpensSharedPickerRoute() {
        var opened = 0
        compose.setContent {
            Controls(
                catalog = ModeCatalog.DEFAULT,
                onInvite = { opened++ },
            )
        }

        compose.onNodeWithTag("channelinfo_invite_row").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Test
    fun curatedRowsRender_andExceptionRowsAreAbsentWhenNotAdvertised() {
        compose.setContent { Controls(catalog = ModeCatalog.from(mapOf("CHANMODES" to "b,k,l,imnst"))) }

        compose.onNodeWithTag("channelinfo_controls_section").assertExists()
        listOf('i', 'm', 'n', 't', 's').forEach { letter ->
            compose.onNodeWithTag("channelinfo_mode_row_$letter").performScrollTo().assertExists()
        }
        compose.onNodeWithTag("channelinfo_key_row").performScrollTo().assertExists()
        compose.onNodeWithTag("channelinfo_limit_row").performScrollTo().assertExists()
        compose.onNodeWithTag("channelinfo_bans_row").performScrollTo().assertExists()
        // No EXCEPTS/INVEX token: those rows must be absent entirely, not present but disabled.
        compose.onAllNodesWithTag("channelinfo_excepts_row").assertCountEquals(0)
        compose.onAllNodesWithTag("channelinfo_invex_row").assertCountEquals(0)
    }

    @Test
    fun exceptionRowsAppearWhenTheCatalogAdvertisesThem() {
        compose.setContent {
            Controls(
                catalog =
                    ModeCatalog.from(
                        mapOf("CHANMODES" to "beI,k,l,imnst", "EXCEPTS" to "", "INVEX" to ""),
                    ),
            )
        }

        compose.onNodeWithTag("channelinfo_excepts_row").performScrollTo().assertExists()
        compose.onNodeWithTag("channelinfo_invex_row").performScrollTo().assertExists()
    }

    @Test
    fun banPicker_disablesAddressScopeUntilAHostIsKnown_andThePreviewFollowsIt() {
        var host by mutableStateOf<String?>(null)
        compose.setContent {
            MotdTheme {
                BanTargetPicker(
                    state = rememberBanTargetState("bob"),
                    members = listOf("bob"),
                    resolvedHost = host,
                    hostLoading = true,
                    onNickSelected = {},
                    tagPrefix = "channelinfo_ban",
                )
            }
        }

        compose.onNodeWithTag("channelinfo_ban_preview").assertTextEquals("bob!*@*")
        compose.onNodeWithTag("channelinfo_ban_scope_host").assertIsNotEnabled()

        host = "host.example.net"
        compose.onNodeWithTag("channelinfo_ban_scope_host").performClick()

        compose.onNodeWithTag("channelinfo_ban_preview").assertTextEquals("*!*@host.example.net")
    }

    @Test
    fun limitDialog_presetsIncludeCommonRoomSizes() {
        assertEquals(listOf(25, 50, 100, 500), LIMIT_PRESETS)
    }

    @Composable
    private fun Controls(
        catalog: ModeCatalog?,
        onSetLimit: (Int?) -> Unit = {},
        onInvite: () -> Unit = {},
    ) {
        MotdTheme {
            ChannelInfoContent(
                state =
                    ChannelInfoUiState(
                        buffer = BufferEntity(1, 1, "#room", "#room", BufferType.CHANNEL),
                        sections = sectionMembers(members),
                        canModerate = true,
                        connected = true,
                        modeCatalog = catalog,
                    ),
                onBack = {},
                onSetPinned = {},
                onSetMuted = {},
                onLeave = {},
                onSetLimit = onSetLimit,
                onInvite = onInvite,
            )
        }
    }
}
