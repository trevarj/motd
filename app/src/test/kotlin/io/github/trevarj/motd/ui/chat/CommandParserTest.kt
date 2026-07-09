package io.github.trevarj.motd.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandParserTest {
    @Test fun blank_is_none() {
        assertEquals(ChatCommand.None, parseCommand(""))
        assertEquals(ChatCommand.None, parseCommand("   "))
    }

    @Test fun plain_text_is_message() {
        assertEquals(ChatCommand.Message("hello world"), parseCommand("hello world"))
        assertEquals(ChatCommand.Message("hello world"), parseCommand("  hello world  "))
    }

    @Test fun double_slash_escapes_to_literal_message() {
        assertEquals(ChatCommand.Message("/help"), parseCommand("//help"))
    }

    @Test fun me_maps_to_slash_me_message() {
        assertEquals(ChatCommand.Message("/me waves"), parseCommand("/me waves"))
    }

    @Test fun me_without_text_is_none() {
        assertEquals(ChatCommand.None, parseCommand("/me"))
        assertEquals(ChatCommand.None, parseCommand("/me   "))
    }

    @Test fun join_parses_channel() {
        assertEquals(ChatCommand.Join("#kotlin"), parseCommand("/join #kotlin"))
        assertEquals(ChatCommand.Join("#kotlin"), parseCommand("/join #kotlin extra"))
    }

    @Test fun join_without_channel_is_none() {
        assertEquals(ChatCommand.None, parseCommand("/join"))
    }

    @Test fun part_with_and_without_reason() {
        assertEquals(ChatCommand.Part(null), parseCommand("/part"))
        assertEquals(ChatCommand.Part("bye all"), parseCommand("/part bye all"))
    }

    @Test fun msg_needs_nick_and_text() {
        assertEquals(ChatCommand.Msg("alice", "hi there"), parseCommand("/msg alice hi there"))
        assertEquals(ChatCommand.None, parseCommand("/msg alice"))
        assertEquals(ChatCommand.None, parseCommand("/msg"))
    }

    @Test fun query_parses_nick_only() {
        assertEquals(ChatCommand.Query("bob"), parseCommand("/query bob"))
        assertEquals(ChatCommand.None, parseCommand("/query"))
    }

    @Test fun nick_parses_new_nick() {
        assertEquals(ChatCommand.Nick("newnick"), parseCommand("/nick newnick"))
        assertEquals(ChatCommand.None, parseCommand("/nick"))
    }

    @Test fun topic_keeps_full_text() {
        assertEquals(ChatCommand.Topic("welcome to the channel"), parseCommand("/topic welcome to the channel"))
        assertEquals(ChatCommand.None, parseCommand("/topic"))
    }

    @Test fun unknown_command_becomes_raw_line_without_slash() {
        assertEquals(ChatCommand.RawLine("names"), parseCommand("/names"))
        assertEquals(ChatCommand.RawLine("mode #c +m"), parseCommand("/mode #c +m"))
    }

    @Test fun command_case_is_insensitive() {
        assertEquals(ChatCommand.Join("#c"), parseCommand("/JOIN #c"))
    }

    @Test fun bare_slash_is_none() {
        assertEquals(ChatCommand.None, parseCommand("/"))
    }

    // --- Round 5 (plans/16 §5.9) ---

    @Test fun away_with_and_without_message() {
        assertEquals(ChatCommand.Away("brb lunch"), parseCommand("/away brb lunch"))
        assertEquals(ChatCommand.Away(null), parseCommand("/away"))
        assertEquals(ChatCommand.Away(null), parseCommand("/away   "))
    }

    @Test fun back_clears_away() {
        assertEquals(ChatCommand.Away(null), parseCommand("/back"))
    }

    @Test fun whois_parses_nick() {
        assertEquals(ChatCommand.Whois("alice"), parseCommand("/whois alice"))
        assertEquals(ChatCommand.Whois("alice"), parseCommand("/whois alice extra"))
        assertEquals(ChatCommand.None, parseCommand("/whois"))
    }

    @Test fun list_is_channel_list() {
        assertEquals(ChatCommand.ChannelList, parseCommand("/list"))
        // Trailing args are ignored — LIST opens the browser.
        assertEquals(ChatCommand.ChannelList, parseCommand("/list #foo"))
    }

    @Test fun kick_reason_optional() {
        assertEquals(ChatCommand.Kick("bob", null), parseCommand("/kick bob"))
        assertEquals(ChatCommand.Kick("bob", "spamming"), parseCommand("/kick bob spamming"))
        assertEquals(ChatCommand.None, parseCommand("/kick"))
    }

    @Test fun ban_parses_nick() {
        assertEquals(ChatCommand.Ban("bob"), parseCommand("/ban bob"))
        assertEquals(ChatCommand.None, parseCommand("/ban"))
    }

    @Test fun hint_list_includes_round5_commands() {
        listOf("/away", "/whois", "/list", "/kick", "/ban").forEach {
            assertTrue("$it should be a hint", it in COMMAND_HINTS)
        }
    }
}
