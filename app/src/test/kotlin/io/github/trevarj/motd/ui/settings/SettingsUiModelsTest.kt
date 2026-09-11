package io.github.trevarj.motd.ui.settings

import io.github.trevarj.motd.attachment.AttachmentBackend
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.prefs.ColorThemePreset
import io.github.trevarj.motd.ui.onboarding.AuthForm
import io.github.trevarj.motd.ui.onboarding.AuthMode
import io.github.trevarj.motd.ui.onboarding.ServerForm
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsUiModelsTest {
    @Test
    fun `audio cache feedback follows completion and reports failures`() =
        runTest {
            var completed = false

            val cleared =
                audioCacheClearEvent {
                    completed = true
                }
            val failed =
                audioCacheClearEvent {
                    throw IllegalStateException("disk error")
                }

            assertTrue(completed)
            assertEquals(AudioCacheClearEvent.CLEARED, cleared)
            assertEquals(AudioCacheClearEvent.FAILED, failed)
        }

    @Test
    fun `audio cache feedback preserves cancellation`() =
        runTest {
            var cancellation: CancellationException? = null

            try {
                audioCacheClearEvent { throw CancellationException("cancelled") }
            } catch (caught: CancellationException) {
                cancellation = caught
            }

            assertEquals("cancelled", cancellation?.message)
        }

    @Test
    fun `history size planner accepts both decimal separators`() {
        assertEquals(12.5, parseMegabytes("12.5")!!, 0.0)
        assertEquals(12.5, parseMegabytes("12,5")!!, 0.0)
    }

    @Test
    fun `theme groups expose every exact light and dark selection once`() {
        val selectableThemes = ColorThemePreset.entries.filter { it != ColorThemePreset.AMOLED }.toSet()
        val grouped = listOf(ColorThemePreset.SYSTEM) + LIGHT_THEME_PRESETS + DARK_THEME_PRESETS

        assertEquals(selectableThemes, grouped.toSet())
        assertEquals(grouped.size, grouped.distinct().size)
        assertEquals(
            LIGHT_THEME_PRESETS.map(::themePresetLabelText).sorted(),
            LIGHT_THEME_PRESETS.map(::themePresetLabelText),
        )
        assertEquals(
            DARK_THEME_PRESETS.map(::themePresetLabelText).sorted(),
            DARK_THEME_PRESETS.map(::themePresetLabelText),
        )
    }

    @Test
    fun `upload credentials stay bound to one endpoint authority`() {
        assertTrue(sameUploadAuthority("https://share.example/upload", "https://share.example/other"))
        assertTrue(sameUploadAuthority("https://share.example/upload", "https://SHARE.example:443/other"))
        assertFalse(sameUploadAuthority("https://share.example/upload", "https://other.example/upload"))
        assertFalse(sameUploadAuthority("https://share.example/upload", "https://share.example:8443/upload"))
    }

    @Test
    fun `upload endpoint classification controls the visible size maximum`() {
        assertEquals(25L, uploadLimitMaximumMiB(AttachmentBackend.CRAFTERBIN))
        assertEquals(25L, uploadLimitMaximumMiB(AttachmentBackend.LITTERBOX))
        assertEquals(512L, uploadLimitMaximumMiB(AttachmentBackend.CUSTOM_0X0))
        assertEquals(1024L, uploadLimitMaximumMiB(AttachmentBackend.X0_AT))
    }

    @Test
    fun `upload expiry displays whole days while preserving wire hours`() {
        assertEquals(7, uploadExpiryWholeDays("168"))
        assertEquals(null, uploadExpiryWholeDays("12"))
        assertEquals("168", uploadExpiryHours("7 days"))
        assertEquals("24", uploadExpiryHours("1 day"))
        assertEquals(null, uploadExpiryHours("  "))
    }

    @Test
    fun `networks are grouped with children directly under alphabetized roots`() {
        val rootB = network(2, "Zulu", NetworkRole.BOUNCER_ROOT)
        val rootA = network(1, "Alpha", NetworkRole.BOUNCER_ROOT)
        val childB = network(4, "beta", NetworkRole.BOUNCER_CHILD, parentId = 1)
        val childA = network(3, "AlphaNet", NetworkRole.BOUNCER_CHILD, parentId = 1)
        val direct = network(5, "Libera", NetworkRole.DIRECT)

        val organized = organizeNetworks(listOf(rootB, childB, direct, childA, rootA))

        assertEquals(listOf(rootA, rootB), organized.bouncerRoots)
        assertEquals(listOf(childA, childB), organized.childrenByRoot[1])
        assertEquals(listOf(direct), organized.direct)
    }

    @Test
    fun `ZNC direct rows appear with bouncers instead of direct networks`() {
        val soju = network(1, "Soju", NetworkRole.BOUNCER_ROOT)
        val znc = network(2, "ZNC Libera", NetworkRole.DIRECT)
        val direct = network(3, "Libera", NetworkRole.DIRECT)

        val organized = organizeNetworks(listOf(direct, znc, soju), zncNetworkIds = setOf(2))

        assertEquals(listOf(soju, znc), organized.bouncerRoots)
        assertEquals(listOf(direct), organized.direct)
    }

    @Test
    fun `network save requires a valid dirty form and includes auto connect`() {
        val entity =
            network(1, "Libera", NetworkRole.DIRECT).copy(
                nick = "me",
                username = "me",
                realname = "Me",
                autoConnect = true,
            )
        val clean =
            NetworkSettingsUiState(
                entity = entity,
                displayName = entity.name,
                server = entity.toServerForm(),
                auth = entity.toAuthForm(),
                autoConnect = true,
            )
        assertFalse(clean.hasUnsavedChanges)
        assertFalse(clean.canSave)
        assertTrue(clean.copy(autoConnect = false).canSave)
        assertFalse(clean.copy(server = ServerForm()).canSave)
    }

    private fun network(
        id: Long,
        name: String,
        role: NetworkRole,
        parentId: Long? = null,
    ) = NetworkEntity(
        id = id,
        name = name,
        role = role,
        parentId = parentId,
        host = "irc.example",
        port = 6697,
        tls = true,
        nick = "me",
        username = "me",
        realname = "Me",
        saslMechanism = AuthMode.NONE.name,
    )
}
