package io.github.trevarj.motd.dickord

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.data.db.BufferType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DickordLabsTest {
    @Test fun defaultsOffAndRoundTrips() =
        runTest {
            val prefs = DickordLabsPrefs(ApplicationProvider.getApplicationContext<Context>())

            assertFalse(prefs.enabled.first())
            prefs.setEnabled(true)
            assertTrue(prefs.enabled.first())
            prefs.setEnabled(false)
            assertFalse(prefs.enabled.first())
        }

    @Test fun enabledLabelsOnlySimplifyExactDickordNames() {
        assertEquals("#guild.general", dickordChannelLabel("#discord.guild.general", enabled = true))
        assertEquals("#guild.general", dickordChannelLabel("#Discord.guild.general", enabled = true))
        assertEquals("Alice", dickordNickLabel("Alice/discord", enabled = true))

        listOf("discord.guild.general", "#discord", "#discorded.guild.general").forEach {
            assertEquals(it, dickordChannelLabel(it, enabled = true))
        }
        listOf("Alice/Discord", "Alice/discord/device", "Alice-discord").forEach {
            assertEquals(it, dickordNickLabel(it, enabled = true))
        }
    }

    @Test fun portalClassifierKeepsControlAndLookalikesOrdinary() {
        assertTrue(isDickordPortalConversation(BufferType.CHANNEL, "#discord.guild.general"))
        assertTrue(isDickordPortalConversation(BufferType.CHANNEL, "#Discord.DM.alice"))
        assertFalse(isDickordPortalConversation(BufferType.CHANNEL, "#discord.control"))
        assertFalse(isDickordPortalConversation(BufferType.CHANNEL, "#Discord.Control"))
        assertFalse(isDickordPortalConversation(BufferType.QUERY, "#discord.guild.general"))
        assertFalse(isDickordPortalConversation(BufferType.CHANNEL, "#discord"))
        assertFalse(isDickordPortalConversation(BufferType.CHANNEL, "#discorded.guild.general"))
    }

    @Test fun disabledLabelsRemainByteForByteUnchanged() {
        assertEquals("#discord.guild._2Fgeneral", dickordChannelLabel("#discord.guild._2Fgeneral", enabled = false))
        assertEquals("Alice/discord", dickordNickLabel("Alice/discord", enabled = false))
    }

    @Test fun decodesOnlyStrictValidVersionOneDescriptors() {
        assertEquals(
            DickordChannelDescriptor(
                v = 1,
                guildId = "123",
                guildName = "Motd",
                channelId = "456",
                channelType = 11,
                parentId = "789",
            ),
            decodeDickordChannelDescriptor(
                """{"v":1,"guild_id":"123","guild_name":"Motd","channel_id":"456","channel_type":11,"parent_id":"789"}""",
            ),
        )
        assertEquals(
            DickordChannelDescriptor(1, null, null, "456", 18, null),
            decodeDickordChannelDescriptor(
                """{"v":1,"guild_id":null,"guild_name":null,"channel_id":"456","channel_type":18,"parent_id":null}""",
            ),
        )
    }

    @Test fun decodesRichVersionOneDescriptorsWithoutChangingNames() {
        assertEquals(
            descriptor.copy(
                guildName = "Motd & friends",
                channelName = "release.notes_20 \uD83D\uDE80",
            ),
            decodeDickordChannelDescriptor(
                """{"v":1,"guild_id":"123","guild_name":"Motd & friends","channel_id":"456","channel_type":0,"parent_id":null,"channel_name":"release.notes_20 🚀","guild_icon_url":"https://cdn.discordapp.com/icons/123/icon.png"}""",
            ),
        )
        assertEquals(
            DickordChannelDescriptor(1, null, null, "456", 3, null, "Alice Smith, Bob"),
            decodeDickordChannelDescriptor(
                """{"v":1,"guild_id":null,"guild_name":null,"channel_id":"456","channel_type":3,"parent_id":null,"channel_name":"Alice Smith, Bob","guild_icon_url":null}""",
            ),
        )
        val direct =
            descriptor.copy(
                guildId = null,
                guildName = null,
                channelType = 1,
                channelName = "Alice Smith",
                guildIconUrl = null,
                channelIconUrl = "https://cdn.discordapp.com/avatars/789/avatar.png",
            )
        assertEquals(
            direct,
            decodeDickordChannelDescriptor(
                """{"v":1,"guild_id":null,"guild_name":null,"channel_id":"456","channel_type":1,"parent_id":null,"channel_name":"Alice Smith","guild_icon_url":null,"channel_icon_url":"https://cdn.discordapp.com/avatars/789/avatar.png"}""",
            ),
        )
    }

    @Test fun validatesNamesByUnicodeCodePointsAndRejectsControls() {
        val longestName = "\uD83D\uDE80".repeat(100)
        val longest = descriptor.copy(guildName = longestName, channelName = longestName)
        assertEquals(longest, decodeDickordChannelDescriptor(Json.encodeToString(longest)))

        listOf(longestName + "x", "", " \u3000", "release\nnotes", "release\u0000notes", "release\u007fnotes", "release\u0085notes")
            .forEach { name ->
                assertNull(decodeDickordChannelDescriptor(Json.encodeToString(descriptor.copy(channelName = name))))
                assertNull(decodeDickordChannelDescriptor(Json.encodeToString(descriptor.copy(guildName = name))))
            }
    }

    @Test fun validatesDescriptorIconsAndTheirUtf8ByteLimit() {
        val prefix = "https://example.com/"
        val boundedUrl = prefix + "é".repeat(246)
        assertEquals(512, boundedUrl.toByteArray(Charsets.UTF_8).size)
        val boundedGuild = descriptor.copy(guildIconUrl = boundedUrl)
        assertEquals(boundedGuild, decodeDickordChannelDescriptor(Json.encodeToString(boundedGuild)))
        val boundedDirect =
            descriptor.copy(
                guildId = null,
                guildName = null,
                channelType = 1,
                guildIconUrl = null,
                channelIconUrl = boundedUrl,
            )
        assertEquals(boundedDirect, decodeDickordChannelDescriptor(Json.encodeToString(boundedDirect)))

        listOf(boundedUrl + "x", "", "not a URL", "http://example.com/a.png", "https://user:pass@example.com/a.png")
            .forEach { url ->
                assertNull(decodeDickordChannelDescriptor(Json.encodeToString(descriptor.copy(guildIconUrl = url))))
                assertNull(decodeDickordChannelDescriptor(Json.encodeToString(boundedDirect.copy(channelIconUrl = url))))
            }

        assertNull(decodeDickordChannelDescriptor(Json.encodeToString(descriptor.copy(channelIconUrl = boundedUrl))))
        listOf(3, 18).forEach { type ->
            assertNull(decodeDickordChannelDescriptor(Json.encodeToString(boundedDirect.copy(channelType = type))))
        }
        listOf(1, 3, 18).forEach { type ->
            val directMessage = descriptor.copy(guildId = null, guildName = null, channelType = type)
            assertNull(decodeDickordChannelDescriptor(Json.encodeToString(directMessage)))
            val cleared = directMessage.copy(guildIconUrl = null)
            assertEquals(cleared, decodeDickordChannelDescriptor(Json.encodeToString(cleared)))
        }
    }

    @Test fun boundsOriginalDescriptorJsonAt2048Utf8Bytes() {
        val rich = descriptor.copy(guildName = "\uD83D\uDE80".repeat(100), channelName = "\uD83D\uDE80".repeat(100))
        val raw = Json.encodeToString(rich)
        val bounded = raw + " ".repeat(2048 - raw.toByteArray(Charsets.UTF_8).size)
        assertTrue(bounded.length < 2048)
        assertEquals(rich, decodeDickordChannelDescriptor(bounded))
        assertNull(decodeDickordChannelDescriptor(bounded + " "))
    }

    @Test fun rejectsMalformedUnknownOrInconsistentDescriptors() {
        listOf(
            null,
            "",
            "{",
            """{"v":2,"guild_id":"123","guild_name":"Motd","channel_id":"456","channel_type":0,"parent_id":null}""",
            """{"v":1,"guild_id":"123","guild_name":"Motd","channel_id":"0456","channel_type":0,"parent_id":null}""",
            """{"v":1,"guild_id":"123","guild_name":"Motd","channel_id":"18446744073709551616","channel_type":0,"parent_id":null}""",
            """{"v":1,"guild_id":null,"guild_name":null,"channel_id":"456","channel_type":0,"parent_id":null}""",
            """{"v":1,"guild_id":"123","guild_name":"Motd","channel_id":"456","channel_type":1,"parent_id":null}""",
            """{"v":1,"guild_id":"123","guild_name":"Motd","channel_id":"456","channel_type":4,"parent_id":null}""",
            """{"v":1,"guild_id":"123","guild_name":"Motd","channel_id":"456","channel_type":0,"parent_id":"789"}""",
            """{"v":1,"guild_id":"123","guild_name":" ","channel_id":"456","channel_type":0,"parent_id":null}""",
            """{"v":1,"guild_id":"123","guild_name":"Motd","channel_id":"456","channel_type":0,"parent_id":null,"extra":true}""",
        ).forEach { assertNull(it, decodeDickordChannelDescriptor(it)) }
        assertNull(
            decodeDickordChannelDescriptor(
                """{"v":1,"guild_id":"123","guild_name":"${"x".repeat(101)}","channel_id":"456","channel_type":0,"parent_id":null}""",
            ),
        )
    }

    private val descriptor =
        DickordChannelDescriptor(
            v = 1,
            guildId = "123",
            guildName = "Motd",
            channelId = "456",
            channelType = 0,
            parentId = null,
            channelName = "release.notes_20",
            guildIconUrl = "https://cdn.discordapp.com/icons/123/icon.png",
        )
}
