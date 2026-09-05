package io.github.trevarj.motd.ui.settings

import androidx.annotation.StringRes
import io.github.trevarj.motd.R
import io.github.trevarj.motd.attachment.AttachmentBackend
import io.github.trevarj.motd.attachment.PasteBackendConfig
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.ui.nav.NetworkSettingsTarget
import io.github.trevarj.motd.ui.nav.SettingsTarget

enum class SettingsSearchPage { ROOT, APPEARANCE, CHAT, DELIVERY, UPLOADS, NETWORKS, BACKUP, LABS, AI_LABS, ABOUT }

sealed interface SettingsSearchDestination {
    data class Page(
        val page: SettingsSearchPage,
        val target: SettingsTarget,
    ) : SettingsSearchDestination

    data class Network(
        val networkId: Long,
        val target: NetworkSettingsTarget,
    ) : SettingsSearchDestination
}

data class SettingsSearchEntry(
    val title: String,
    val summary: String,
    val keywords: String,
    val destination: SettingsSearchDestination,
)

internal data class SettingsSearchSpec(
    @StringRes val title: Int,
    @StringRes val summary: Int,
    val keywords: String,
    val page: SettingsSearchPage,
    val target: SettingsTarget,
)

/** Case-insensitive substring search with title-prefix matches first. */
fun searchSettings(
    query: String,
    entries: List<SettingsSearchEntry>,
): List<SettingsSearchEntry> {
    val needle = query.trim().lowercase()
    if (needle.isEmpty()) return emptyList()
    return entries
        .asSequence()
        .filter {
            it.title.lowercase().contains(needle) ||
                it.summary.lowercase().contains(needle) ||
                it.keywords.lowercase().contains(needle)
        }.sortedWith(compareByDescending<SettingsSearchEntry> { it.title.lowercase().startsWith(needle) }.thenBy { it.title.lowercase() })
        .toList()
}

fun buildSettingsSearchEntries(
    networks: List<NetworkEntity>,
    resolve: (Int) -> String,
    networkTitle: (String, String) -> String,
    uploads: PasteBackendConfig = PasteBackendConfig(),
): List<SettingsSearchEntry> =
    (STATIC_SEARCH_SPECS + uploadSearchSpecs(uploads.backend)).map { spec ->
        SettingsSearchEntry(
            title = resolve(spec.title),
            summary = resolve(spec.summary),
            keywords = spec.keywords,
            destination = SettingsSearchDestination.Page(spec.page, spec.target),
        )
    } +
        networks.flatMap { network ->
            buildList {
                add(networkEntry(network, NetworkSettingsTarget.CONNECTION, R.string.network_settings_connection_section, "server host port tls websocket", resolve, networkTitle))
                if (network.role != NetworkRole.BOUNCER_CHILD) {
                    add(networkEntry(network, NetworkSettingsTarget.AUTHENTICATION, R.string.network_settings_identity_section, "nick username sasl password certificate", resolve, networkTitle))
                    add(networkEntry(network, NetworkSettingsTarget.OBFUSCATION, R.string.network_settings_routing, "proxy socks tor vless reality transport", resolve, networkTitle))
                }
                add(networkEntry(network, NetworkSettingsTarget.AVATAR, R.string.network_settings_avatar_section, "profile image", resolve, networkTitle))
                add(networkEntry(network, NetworkSettingsTarget.TOOLS, R.string.network_settings_tools_section, "server messages ignores mutes operator", resolve, networkTitle))
            }
        }

private fun networkEntry(
    network: NetworkEntity,
    target: NetworkSettingsTarget,
    @StringRes section: Int,
    keywords: String,
    resolve: (Int) -> String,
    networkTitle: (String, String) -> String,
) = SettingsSearchEntry(
    title = networkTitle(network.name, resolve(section)),
    summary = resolve(R.string.settings_search_network_summary),
    keywords = "${network.name} $keywords",
    destination = SettingsSearchDestination.Network(network.id, target),
)

private fun uploadSearchSpecs(backend: AttachmentBackend): List<SettingsSearchSpec> =
    buildList {
        if (backend == AttachmentBackend.CUSTOM_0X0) {
            add(
                spec(
                    R.string.settings_upload_endpoint,
                    R.string.settings_upload_custom_desc,
                    "url username password",
                    SettingsSearchPage.UPLOADS,
                    SettingsTarget.UPLOAD_CONNECTION,
                ),
            )
        }
        if (backend in setOf(AttachmentBackend.CRAFTERBIN, AttachmentBackend.ZERO_X_ZERO, AttachmentBackend.CUSTOM_0X0, AttachmentBackend.LITTERBOX)) {
            add(
                spec(
                    R.string.settings_upload_privacy,
                    R.string.settings_upload_secret_desc,
                    "secret expiry retention",
                    SettingsSearchPage.UPLOADS,
                    SettingsTarget.UPLOAD_PRIVACY,
                ),
            )
        }
    }

private fun spec(
    @StringRes title: Int,
    @StringRes summary: Int,
    keywords: String,
    page: SettingsSearchPage,
    target: SettingsTarget,
) = SettingsSearchSpec(title, summary, keywords, page, target)

private val STATIC_SEARCH_SPECS =
    listOf(
        spec(R.string.settings_networks, R.string.settings_networks_summary, "connections servers bouncers", SettingsSearchPage.NETWORKS, SettingsTarget.NETWORKS),
        spec(R.string.settings_appearance, R.string.settings_appearance_summary, "experience display", SettingsSearchPage.APPEARANCE, SettingsTarget.APPEARANCE),
        spec(R.string.settings_chat, R.string.settings_chat_summary, "conversation messages", SettingsSearchPage.CHAT, SettingsTarget.CHAT),
        spec(R.string.settings_delivery, R.string.settings_delivery_summary, "notifications push background", SettingsSearchPage.DELIVERY, SettingsTarget.DELIVERY),
        spec(R.string.settings_uploads, R.string.settings_uploads_summary, "attachments paste files", SettingsSearchPage.UPLOADS, SettingsTarget.UPLOADS),
        spec(R.string.settings_backup_restore, R.string.settings_backup_restore_summary, "data export import", SettingsSearchPage.BACKUP, SettingsTarget.BACKUP),
        spec(R.string.settings_labs, R.string.settings_labs_summary, "experimental", SettingsSearchPage.LABS, SettingsTarget.LABS),
        spec(R.string.labs_ai, R.string.labs_ai_desc, "local on device voice transcription", SettingsSearchPage.LABS, SettingsTarget.AI),
        spec(R.string.settings_about, R.string.settings_about_summary_search, "version help support", SettingsSearchPage.ABOUT, SettingsTarget.ABOUT),
        spec(R.string.settings_theme, R.string.settings_theme_section, "dark light color palette", SettingsSearchPage.APPEARANCE, SettingsTarget.THEME),
        spec(R.string.settings_follow_system, R.string.settings_follow_system_desc, "dark light", SettingsSearchPage.APPEARANCE, SettingsTarget.FOLLOW_SYSTEM),
        spec(R.string.settings_true_black, R.string.settings_true_black_desc, "oled amoled", SettingsSearchPage.APPEARANCE, SettingsTarget.TRUE_BLACK),
        spec(R.string.settings_dynamic_color, R.string.settings_dynamic_color_desc, "wallpaper material you", SettingsSearchPage.APPEARANCE, SettingsTarget.DYNAMIC_COLOR),
        spec(R.string.settings_nick_colors, R.string.settings_nick_colors_desc, "nickname sender", SettingsSearchPage.APPEARANCE, SettingsTarget.NICK_COLORS),
        spec(R.string.settings_nick_palette, R.string.settings_nick_palette_disabled, "nickname colors classic vivid", SettingsSearchPage.APPEARANCE, SettingsTarget.NICK_PALETTE),
        spec(R.string.settings_nick_color_overrides, R.string.settings_nick_colors_desc, "nickname hue", SettingsSearchPage.APPEARANCE, SettingsTarget.NICK_OVERRIDES),
        spec(R.string.settings_app_font, R.string.settings_layout_section, "typeface text", SettingsSearchPage.APPEARANCE, SettingsTarget.APP_FONT),
        spec(R.string.settings_ui_font_size, R.string.settings_ui_font_size_desc, "interface text scale", SettingsSearchPage.APPEARANCE, SettingsTarget.UI_FONT_SIZE),
        spec(R.string.settings_conversation_font_size, R.string.settings_conversation_font_size_desc, "message text scale", SettingsSearchPage.APPEARANCE, SettingsTarget.CONVERSATION_FONT_SIZE),
        spec(R.string.settings_folder_layout, R.string.settings_folder_layout_desc, "folders tabs inline chat list", SettingsSearchPage.APPEARANCE, SettingsTarget.FOLDER_LAYOUT),
        spec(R.string.settings_show_folder_chats_in_all, R.string.settings_show_folder_chats_in_all_desc, "folders all assigned unassigned chat list", SettingsSearchPage.APPEARANCE, SettingsTarget.SHOW_FOLDER_CHATS_IN_ALL),
        spec(R.string.settings_density, R.string.settings_density_comfortable_desc, "layout compact two line bubbles", SettingsSearchPage.APPEARANCE, SettingsTarget.MESSAGE_STYLE),
        spec(R.string.settings_avatar_style, R.string.settings_avatar_irc_sprite_desc, "monogram initials sprite", SettingsSearchPage.APPEARANCE, SettingsTarget.AVATAR_STYLE),
        spec(R.string.settings_show_timestamps, R.string.settings_show_timestamps_desc, "time", SettingsSearchPage.APPEARANCE, SettingsTarget.TIMESTAMPS),
        spec(R.string.settings_time_format, R.string.settings_time_format_custom_help, "12 24 clock", SettingsSearchPage.APPEARANCE, SettingsTarget.TIME_FORMAT),
        spec(R.string.settings_message_spacing, R.string.settings_appearance_messages_section, "compact relaxed", SettingsSearchPage.APPEARANCE, SettingsTarget.MESSAGE_SPACING),
        spec(R.string.settings_bubble_corners, R.string.settings_bubble_corners_desc, "rounded square", SettingsSearchPage.APPEARANCE, SettingsTarget.BUBBLE_CORNERS),
        spec(R.string.settings_wallpaper, R.string.settings_appearance_summary, "background image", SettingsSearchPage.APPEARANCE, SettingsTarget.WALLPAPER),
        spec(R.string.settings_app_icon_section, R.string.settings_appearance_summary, "launcher", SettingsSearchPage.APPEARANCE, SettingsTarget.LAUNCHER_ICON),
        spec(R.string.settings_presence_title, R.string.settings_presence_smart_desc, "join part quit away", SettingsSearchPage.CHAT, SettingsTarget.PRESENCE),
        spec(R.string.settings_show_redacted_messages, R.string.settings_show_redacted_messages_desc, "deleted accountability", SettingsSearchPage.CHAT, SettingsTarget.DELETED_MESSAGES),
        spec(R.string.settings_show_images, R.string.settings_show_images_desc, "media photos", SettingsSearchPage.CHAT, SettingsTarget.IMAGES),
        spec(R.string.settings_show_link_previews, R.string.settings_show_link_previews_desc, "url metadata", SettingsSearchPage.CHAT, SettingsTarget.LINK_PREVIEWS),
        spec(R.string.settings_auto_media_unmetered, R.string.settings_auto_media_unmetered_desc, "wifi download", SettingsSearchPage.CHAT, SettingsTarget.MEDIA_UNMETERED),
        spec(R.string.settings_auto_media_metered, R.string.settings_auto_media_metered_desc, "mobile data download", SettingsSearchPage.CHAT, SettingsTarget.MEDIA_METERED),
        spec(R.string.settings_direct_media_proxied, R.string.settings_direct_media_proxied_desc, "proxy privacy", SettingsSearchPage.CHAT, SettingsTarget.PROXIED_MEDIA),
        spec(R.string.settings_show_shared_avatars, R.string.settings_show_shared_avatars_desc, "profile images", SettingsSearchPage.CHAT, SettingsTarget.SHARED_AVATARS),
        spec(R.string.settings_auto_away, R.string.settings_auto_away_desc, "background idle", SettingsSearchPage.CHAT, SettingsTarget.AUTO_AWAY),
        spec(R.string.settings_auto_away_delay, R.string.settings_auto_away_desc, "minutes idle", SettingsSearchPage.CHAT, SettingsTarget.AUTO_AWAY_DELAY),
        spec(R.string.settings_auto_away_message_title, R.string.settings_auto_away_message_hint, "status", SettingsSearchPage.CHAT, SettingsTarget.AWAY_MESSAGE),
        spec(R.string.settings_chat_sounds, R.string.settings_chat_sounds_desc, "audio", SettingsSearchPage.CHAT, SettingsTarget.CHAT_SOUNDS),
        spec(R.string.settings_composer_emoji, R.string.settings_composer_emoji_desc, "input", SettingsSearchPage.CHAT, SettingsTarget.COMPOSER_EMOJI),
        spec(R.string.settings_composer_formatting_tools, R.string.settings_composer_formatting_tools_desc, "bold italic input", SettingsSearchPage.CHAT, SettingsTarget.COMPOSER_FORMATTING),
        spec(R.string.settings_reply_prefix, R.string.settings_reply_prefix_desc, "channel", SettingsSearchPage.CHAT, SettingsTarget.REPLY_PREFIX),
        spec(R.string.settings_direct_connections, R.string.settings_direct_connections_summary, "dcc files peer", SettingsSearchPage.CHAT, SettingsTarget.DIRECT_CONNECTIONS),
        spec(R.string.settings_voice_quality, R.string.settings_voice_section, "opus aac bitrate", SettingsSearchPage.CHAT, SettingsTarget.VOICE_QUALITY),
        spec(R.string.settings_voice_noise_reduction, R.string.settings_voice_noise_reduction_desc, "audio", SettingsSearchPage.CHAT, SettingsTarget.VOICE_NOISE_REDUCTION),
        spec(R.string.settings_voice_encryption, R.string.settings_voice_encryption_desc, "password secure", SettingsSearchPage.CHAT, SettingsTarget.VOICE_ENCRYPTION),
        spec(R.string.settings_friends, R.string.settings_people, "people nicks", SettingsSearchPage.CHAT, SettingsTarget.FRIENDS),
        spec(R.string.settings_fools, R.string.settings_people, "ignore people nicks", SettingsSearchPage.CHAT, SettingsTarget.FOOLS),
        spec(R.string.settings_fools_mode, R.string.settings_fools_hide_desc, "collapse hide", SettingsSearchPage.CHAT, SettingsTarget.FOOLS_MODE),
        spec(R.string.settings_delivery_socket, R.string.settings_delivery_socket_desc, "persistent connection", SettingsSearchPage.DELIVERY, SettingsTarget.PERSISTENT_DELIVERY),
        spec(R.string.settings_delivery_push, R.string.settings_delivery_push_desc, "unified push distributor", SettingsSearchPage.DELIVERY, SettingsTarget.UNIFIED_PUSH),
        spec(R.string.settings_start_on_boot, R.string.settings_start_on_boot_desc, "restart reconnect", SettingsSearchPage.DELIVERY, SettingsTarget.START_ON_BOOT),
        spec(R.string.settings_battery, R.string.settings_battery_desc, "optimization background", SettingsSearchPage.DELIVERY, SettingsTarget.BATTERY),
        spec(R.string.settings_upload_destination, R.string.settings_uploads_summary, "provider backend service", SettingsSearchPage.UPLOADS, SettingsTarget.UPLOAD_PROVIDER),
        spec(R.string.settings_upload_limits, R.string.settings_upload_limit_desc_search, "size mib", SettingsSearchPage.UPLOADS, SettingsTarget.UPLOAD_LIMIT),
        spec(R.string.backup_export_title, R.string.backup_export_guidance, "save configuration credentials encrypted", SettingsSearchPage.BACKUP, SettingsTarget.EXPORT_BACKUP),
        spec(R.string.backup_import_title, R.string.backup_import_guidance, "restore merge replace", SettingsSearchPage.BACKUP, SettingsTarget.IMPORT_BACKUP),
        spec(R.string.labs_gestures, R.string.labs_gestures_desc, "orb radial menu", SettingsSearchPage.LABS, SettingsTarget.GESTURES),
        spec(R.string.labs_agentwire, R.string.labs_agentwire_desc, "harness", SettingsSearchPage.LABS, SettingsTarget.AGENTWIRE),
        spec(R.string.labs_global_feed, R.string.labs_global_feed_desc, "merged stream", SettingsSearchPage.LABS, SettingsTarget.GLOBAL_FEED),
        spec(R.string.ai_model_library, R.string.ai_model_library_summary, "import whisper ggml speech model", SettingsSearchPage.AI_LABS, SettingsTarget.AI_MODELS),
        spec(R.string.ai_transcription, R.string.ai_transcription_summary, "voice speech audio text whisper", SettingsSearchPage.AI_LABS, SettingsTarget.AI_TRANSCRIPTION),
        spec(R.string.about_diagnostic_logging, R.string.about_diagnostic_logging_summary, "support logs export", SettingsSearchPage.ABOUT, SettingsTarget.DIAGNOSTICS),
        spec(R.string.about_license, R.string.about_license_gpl, "legal free software", SettingsSearchPage.ABOUT, SettingsTarget.LICENSE),
        spec(R.string.settings_github, R.string.settings_github_url, "source project repository", SettingsSearchPage.ABOUT, SettingsTarget.PROJECT),
    )
