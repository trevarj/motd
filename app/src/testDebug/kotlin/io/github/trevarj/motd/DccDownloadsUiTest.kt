package io.github.trevarj.motd

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import io.github.trevarj.motd.data.db.DccAddressKind
import io.github.trevarj.motd.data.db.DccDirection
import io.github.trevarj.motd.data.db.DccTransferEntity
import io.github.trevarj.motd.data.db.DccTransferProtocol
import io.github.trevarj.motd.data.db.DccTransferState
import io.github.trevarj.motd.ui.settings.DirectConnectionsContent
import io.github.trevarj.motd.ui.theme.MotdTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowToast
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class DccDownloadsUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun directConnections_exportsOnlyCompletedIncomingFilesAndReportsSuccessOrFailure() {
        val received = transfer()
        val calls = mutableListOf<Long>()
        var fail = false
        compose.setContent {
            MotdTheme {
                DirectConnectionsContent(
                    transfers =
                        listOf(
                            received,
                            received.copy(id = 2, direction = DccDirection.OUTGOING),
                            received.copy(id = 3, state = DccTransferState.FAILED),
                            received.copy(id = 4, state = DccTransferState.ACTIVE),
                            received.copy(id = 5, destinationUri = null),
                        ),
                    onBack = {},
                    onRemove = {},
                    onSaveToDownloads = { id ->
                        calls += id
                        if (fail) throw IOException("retained file unavailable")
                    },
                )
            }
        }
        (2..5).forEach { compose.onNodeWithTag("dcc_download_menu_$it").assertDoesNotExist() }
        compose.onNodeWithTag("dcc_download_menu_1").assertIsDisplayed().performClick()
        compose.onNodeWithTag("dcc_save_to_downloads_1").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(listOf(1L), calls)
            assertEquals(RuntimeEnvironment.getApplication().getString(R.string.dcc_saved_to_downloads), ShadowToast.getTextOfLatestToast())
            fail = true
        }
        compose.onNodeWithTag("dcc_download_menu_1").performClick()
        compose.onNodeWithTag("dcc_save_to_downloads_1").performClick()
        compose.runOnIdle {
            assertEquals(listOf(1L, 1L), calls)
            assertEquals(RuntimeEnvironment.getApplication().getString(R.string.dcc_save_to_downloads_failed), ShadowToast.getTextOfLatestToast())
        }
    }

    private fun transfer() =
        DccTransferEntity(
            id = 1,
            networkId = 1,
            timelineEventId = null,
            offerKey = "fixture",
            direction = DccDirection.INCOMING,
            protocol = DccTransferProtocol.SEND,
            peerNick = "books_bot",
            normalizedPeer = "books_bot",
            filename = "Search results.zip",
            displayFilename = "Search results.zip",
            address = "8.8.8.8",
            addressKind = DccAddressKind.IPV4_DOTTED,
            port = 5000,
            sizeBytes = 100,
            token = null,
            state = DccTransferState.COMPLETED,
            bytesTransferred = 100,
            destinationUri = "file:///private/ebooks-results/1.zip",
            createdAt = 1,
            expiresAt = null,
            completedAt = 2,
            updatedAt = 2,
        )
}
