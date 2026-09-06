package io.github.trevarj.motd.ui.settings

import io.github.trevarj.motd.R
import io.github.trevarj.motd.attachment.AttachmentBackend
import io.github.trevarj.motd.attachment.PasteBackendConfig
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.ui.nav.NetworkSettingsTarget
import io.github.trevarj.motd.ui.nav.SettingsTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSearchTest {
    @Test
    fun `search matches title summary and keywords case insensitively with title prefix first`() {
        val entries =
            listOf(
                entry("Media previews", "Download images", "photos"),
                entry("Preview links", "Metadata", "media"),
                entry("Images", "Media previews", "photos"),
            )

        assertEquals(listOf("Media previews", "Images", "Preview links"), searchSettings("MEDIA", entries).map { it.title })
        assertEquals(listOf("Images", "Media previews"), searchSettings("photos", entries).map { it.title })
    }

    @Test
    fun `static keywords map to exact typed targets`() {
        val entries =
            buildSettingsSearchEntries(emptyList(), ::resolve, ::networkTitle) +
                entry("Unrelated result", "", "bold encrypted tabs unassigned")

        listOf(
            "bold" to SettingsSearchDestination.Page(SettingsSearchPage.CHAT, SettingsTarget.COMPOSER_FORMATTING),
            "encrypted" to SettingsSearchDestination.Page(SettingsSearchPage.BACKUP, SettingsTarget.EXPORT_BACKUP),
            "tabs" to SettingsSearchDestination.Page(SettingsSearchPage.APPEARANCE, SettingsTarget.FOLDER_LAYOUT),
            "unassigned" to SettingsSearchDestination.Page(SettingsSearchPage.APPEARANCE, SettingsTarget.SHOW_FOLDER_CHATS_IN_ALL),
        ).forEach { (query, destination) ->
            assertEquals(destination, searchSettings(query, entries).single { it.destination == destination }.destination)
        }
    }

    @Test
    fun `voice search opens local transcription targets without retired features or model data`() {
        val entries = buildSettingsSearchEntries(emptyList(), ::resolve, ::networkTitle)

        listOf(
            "ggml" to SettingsSearchDestination.Page(SettingsSearchPage.AI_LABS, SettingsTarget.AI_MODELS),
            "speech audio" to SettingsSearchDestination.Page(SettingsSearchPage.AI_LABS, SettingsTarget.AI_TRANSCRIPTION),
            "local on device" to SettingsSearchDestination.Page(SettingsSearchPage.LABS, SettingsTarget.AI),
        ).forEach { (query, destination) ->
            assertEquals(destination, searchSettings(query, entries).single { it.destination == destination }.destination)
        }
        listOf("briefs", "semantic", "generation", "embeddings", "private-model.bin", "/storage/emulated/0/models").forEach { query ->
            assertTrue(searchSettings(query, entries).isEmpty())
        }
    }

    @Test
    fun `dynamic network entries use concrete network id and eligible sections without secrets`() {
        val direct = network(7, "Libera", NetworkRole.DIRECT)
        val child = network(9, "OFTC via soju", NetworkRole.BOUNCER_CHILD)
        val entries = buildSettingsSearchEntries(listOf(direct, child), ::resolve, ::networkTitle)
        val directRows = entries.filter { (it.destination as? SettingsSearchDestination.Network)?.networkId == 7L }
        val childRows = entries.filter { (it.destination as? SettingsSearchDestination.Network)?.networkId == 9L }

        listOf(
            "proxy" to SettingsSearchDestination.Network(7L, NetworkSettingsTarget.OBFUSCATION),
            "sasl" to SettingsSearchDestination.Network(7L, NetworkSettingsTarget.AUTHENTICATION),
        ).forEach { (query, destination) ->
            assertEquals(destination, searchSettings(query, directRows).single { it.destination == destination }.destination)
        }
        listOf(
            NetworkSettingsTarget.CONNECTION,
            NetworkSettingsTarget.AUTHENTICATION,
            NetworkSettingsTarget.OBFUSCATION,
            NetworkSettingsTarget.AVATAR,
            NetworkSettingsTarget.TOOLS,
        ).forEach { target ->
            val destination = SettingsSearchDestination.Network(7L, target)
            assertEquals(destination, directRows.single { it.destination == destination }.destination)
        }
        listOf(
            NetworkSettingsTarget.CONNECTION,
            NetworkSettingsTarget.AVATAR,
            NetworkSettingsTarget.TOOLS,
        ).forEach { target ->
            val destination = SettingsSearchDestination.Network(9L, target)
            assertEquals(destination, childRows.single { it.destination == destination }.destination)
        }
        listOf(
            "hidden-host.example",
            "private-nick",
            "private-user",
            "private-realname",
            "private-sasl-user",
            "sasl-secret-value",
            "server-secret-value",
            "nickserv-secret-value",
            "private-wss.example",
            "private-proxy.example",
            "private-vless-value",
        ).forEach { enteredValue ->
            assertTrue("entered value must not be indexed: $enteredValue", searchSettings(enteredValue, entries).isEmpty())
        }
        assertTrue(searchSettings("Libera", directRows).isNotEmpty())
        assertTrue(childRows.none { (it.destination as SettingsSearchDestination.Network).target == NetworkSettingsTarget.AUTHENTICATION })
        assertTrue(childRows.none { (it.destination as SettingsSearchDestination.Network).target == NetworkSettingsTarget.OBFUSCATION })
    }

    @Test
    fun `global index omits destructive targets and includes upload controls only when eligible`() {
        fun targets(backend: AttachmentBackend) =
            buildSettingsSearchEntries(
                networks = emptyList(),
                resolve = ::resolve,
                networkTitle = ::networkTitle,
                uploads = PasteBackendConfig().copy(backend = backend),
            ).mapNotNull { (it.destination as? SettingsSearchDestination.Page)?.target }

        val crafterBin = targets(AttachmentBackend.CRAFTERBIN)
        val custom = targets(AttachmentBackend.CUSTOM_0X0)
        val catbox = targets(AttachmentBackend.CATBOX)
        assertFalse(SettingsTarget.AUDIO_CACHE in crafterBin)
        assertFalse(SettingsTarget.UPLOAD_CONNECTION in crafterBin)
        assertTrue(SettingsTarget.UPLOAD_PRIVACY in crafterBin)
        assertTrue(SettingsTarget.UPLOAD_CONNECTION in custom)
        assertTrue(SettingsTarget.UPLOAD_PRIVACY in custom)
        assertFalse(SettingsTarget.UPLOAD_CONNECTION in catbox)
        assertFalse(SettingsTarget.UPLOAD_PRIVACY in catbox)
        assertTrue(SettingsTarget.UPLOAD_PROVIDER in catbox)
        assertTrue(SettingsTarget.UPLOAD_LIMIT in catbox)
    }

    private fun entry(
        title: String,
        summary: String,
        keywords: String,
    ) = SettingsSearchEntry(title, summary, keywords, SettingsSearchDestination.Page(SettingsSearchPage.ROOT, SettingsTarget.NETWORKS))

    private fun resolve(id: Int): String =
        when (id) {
            R.string.settings_composer_formatting_tools -> "Formatting tools"
            R.string.settings_composer_formatting_tools_desc -> "Show rich text controls"
            R.string.backup_export_title -> "Export configuration"
            R.string.backup_export_guidance -> "Save settings"
            R.string.network_settings_connection_section -> "Connection"
            R.string.network_settings_identity_section -> "Identity and authentication"
            R.string.network_settings_routing -> "Routing and obfuscation"
            R.string.network_settings_avatar_section -> "Avatar"
            R.string.network_settings_tools_section -> "Network tools"
            R.string.settings_search_network_summary -> "Open this network section"
            R.string.settings_folder_layout -> "Folder layout"
            R.string.settings_folder_layout_desc -> "Choose how folders appear in the chat list."
            R.string.settings_show_folder_chats_in_all -> "Show folder chats in All"
            R.string.settings_show_folder_chats_in_all_desc -> "Include chats assigned to folders in the All tab."
            R.string.labs_ai -> "Local voice"
            R.string.labs_ai_desc -> "Local voice transcription"
            R.string.ai_model_library -> "Model Library"
            R.string.ai_model_library_summary -> "Import local voice models"
            R.string.ai_transcription -> "Voice transcription"
            R.string.ai_transcription_summary -> "Transcribe voice messages locally"
            else -> "resource-$id"
        }

    private fun networkTitle(
        network: String,
        section: String,
    ) = "$network · $section"

    private fun network(
        id: Long,
        name: String,
        role: NetworkRole,
    ) = NetworkEntity(
        id = id,
        name = name,
        role = role,
        host = "hidden-host.example",
        port = 6697,
        nick = "private-nick",
        username = "private-user",
        realname = "private-realname",
        saslUser = "private-sasl-user",
        saslPassword = "sasl-secret-value",
        serverPassword = "server-secret-value",
        nickServPassword = "nickserv-secret-value",
        wsUrl = "wss://private-wss.example/socket",
        proxyHost = "private-proxy.example",
        obfsLink = "private-vless-value",
    )
}
