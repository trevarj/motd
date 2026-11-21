package io.github.trevarj.motd.ui.chat

import io.github.trevarj.motd.R
import io.github.trevarj.motd.irc.format.IRC_BOLD
import io.github.trevarj.motd.irc.format.IRC_RESET
import io.github.trevarj.motd.irc.format.IrcTextStyle
import io.github.trevarj.motd.irc.format.parseIrcFormatting
import io.github.trevarj.motd.irc.proto.IrcMessage
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

    @Test fun ascii_trim_preserves_formatting_controls() {
        assertEquals(ChatCommand.Message("${IRC_BOLD}hello$IRC_BOLD"), parseCommand(" \t${IRC_BOLD}hello$IRC_BOLD\n"))
    }

    @Test fun formatting_is_retained_only_in_message_bearing_command_ranges() {
        assertEquals(
            ChatCommand.Msg("alice", "${IRC_BOLD}hello$IRC_BOLD"),
            parseCommand("/${IRC_BOLD}msg$IRC_BOLD ${IRC_BOLD}alice$IRC_BOLD ${IRC_BOLD}hello$IRC_BOLD"),
        )
        assertEquals(
            ChatCommand.Message("/me ${IRC_BOLD}waves$IRC_BOLD"),
            parseCommand("/${IRC_BOLD}me$IRC_BOLD ${IRC_BOLD}waves$IRC_BOLD"),
        )
        assertEquals(
            ChatCommand.RawLine("WHOIS ${IRC_BOLD}alice$IRC_RESET"),
            parseCommand("/raw ${IRC_BOLD}WHOIS alice$IRC_BOLD"),
        )
    }

    @Test fun unknown_command_preserves_formatted_text_on_the_wire() {
        val raw = (parseCommand("/foo ${IRC_BOLD}hello$IRC_BOLD") as ChatCommand.RawLine).line
        val sent = IrcMessage.parse(raw)
        val text = parseIrcFormatting(sent.params.single())

        assertEquals("FOO", sent.command)
        assertEquals("hello", text.visibleText)
        assertTrue(text.runs.all { it.state.enabled(IrcTextStyle.BOLD) })
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
        assertEquals(ChatCommand.Join("#kotlin"), parseCommand("/JOIN #kotlin"))
    }

    /** Regression: the key used to be dropped, so a keyed channel could never be joined. */
    @Test fun join_keeps_the_channel_key() {
        assertEquals(ChatCommand.Join("#kotlin", "hunter2"), parseCommand("/join #kotlin hunter2"))
    }

    @Test fun join_keeps_multi_channel_and_multi_key_pairing() {
        assertEquals(
            ChatCommand.Join("#a,#b", "key-a,key-b"),
            parseCommand("/join #a,#b key-a,key-b"),
        )
        assertEquals(ChatCommand.Join("#a,#b"), parseCommand("/join #a,#b"))
    }

    @Test fun join_alias() {
        assertEquals(ChatCommand.Join("#kotlin"), parseCommand("/j #kotlin"))
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
        assertEquals(ChatCommand.RawLine("links"), parseCommand("/links"))
    }

    @Test fun command_case_is_insensitive() {
        assertEquals(ChatCommand.Join("#c"), parseCommand("/JOIN #c"))
        assertEquals(ChatCommand.Mode(null, "+m"), parseCommand("/MODE +m"))
    }

    @Test fun bare_slash_is_none() {
        assertEquals(ChatCommand.None, parseCommand("/"))
    }

    // --- Round 5 ---

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

    // --- Halloy parity (#51): first-class commands beyond raw passthrough ---

    @Test fun mode_without_arguments_queries_the_current_buffer() {
        assertEquals(ChatCommand.Mode(null, null), parseCommand("/mode"))
    }

    @Test fun mode_treats_a_leading_sign_as_modes_for_the_current_buffer() {
        assertEquals(ChatCommand.Mode(null, "+m"), parseCommand("/mode +m"))
        assertEquals(ChatCommand.Mode(null, "+o alice"), parseCommand("/mode +o alice"))
        assertEquals(ChatCommand.Mode(null, "-o alice"), parseCommand("/mode -o alice"))
    }

    @Test fun mode_takes_an_explicit_target_when_one_is_given() {
        assertEquals(ChatCommand.Mode("#chan", "+o alice"), parseCommand("/mode #chan +o alice"))
        assertEquals(ChatCommand.Mode("#chan", null), parseCommand("/mode #chan"))
        assertEquals(ChatCommand.Mode("alice", "+i"), parseCommand("/m alice +i"))
    }

    @Test fun notice_needs_target_and_text() {
        assertEquals(ChatCommand.Notice("#chan", "heads up"), parseCommand("/notice #chan heads up"))
        assertEquals(ChatCommand.None, parseCommand("/notice #chan"))
        assertEquals(ChatCommand.None, parseCommand("/notice"))
    }

    @Test fun ctcp_keeps_the_request_and_its_arguments() {
        assertEquals(ChatCommand.Ctcp("alice", "PING 12345"), parseCommand("/ctcp alice PING 12345"))
        assertEquals(ChatCommand.Ctcp("alice", "VERSION"), parseCommand("/ctcp alice VERSION"))
        assertEquals(ChatCommand.None, parseCommand("/ctcp alice"))
    }

    @Test fun invite_defaults_the_channel_to_the_current_buffer() {
        assertEquals(ChatCommand.Invite("bob", null), parseCommand("/invite bob"))
        assertEquals(ChatCommand.Invite("bob", "#chan"), parseCommand("/invite bob #chan"))
        assertEquals(ChatCommand.None, parseCommand("/invite"))
    }

    @Test fun knock_reason_optional() {
        assertEquals(ChatCommand.Knock("#secret", null), parseCommand("/knock #secret"))
        assertEquals(ChatCommand.Knock("#secret", "let me in"), parseCommand("/knock #secret let me in"))
        assertEquals(ChatCommand.None, parseCommand("/knock"))
    }

    @Test fun setname_keeps_the_whole_realname() {
        assertEquals(ChatCommand.SetName("Ada Lovelace"), parseCommand("/setname Ada Lovelace"))
        assertEquals(ChatCommand.None, parseCommand("/setname"))
    }

    @Test fun motd_server_optional() {
        assertEquals(ChatCommand.Motd(null), parseCommand("/motd"))
        assertEquals(ChatCommand.Motd("irc.example"), parseCommand("/motd irc.example"))
    }

    @Test fun hop_reason_optional() {
        assertEquals(ChatCommand.Hop(null), parseCommand("/hop"))
        assertEquals(ChatCommand.Hop("brb"), parseCommand("/rejoin brb"))
    }

    /** `/raw` reaches the server even when the line's first word collides with a handled command. */
    @Test fun raw_sends_the_rest_verbatim() {
        assertEquals(ChatCommand.RawLine("JOIN #chan key"), parseCommand("/raw JOIN #chan key"))
        assertEquals(ChatCommand.RawLine("PRIVMSG a :hi"), parseCommand("/quote PRIVMSG a :hi"))
        assertEquals(ChatCommand.None, parseCommand("/raw"))
    }

    @Test fun aliases_resolve_to_their_command() {
        assertEquals(ChatCommand.Message("/me waves"), parseCommand("/describe waves"))
        assertEquals(ChatCommand.Part("bye"), parseCommand("/leave bye"))
        assertEquals(ChatCommand.Topic("hello"), parseCommand("/t hello"))
    }

    @Test fun hint_list_includes_the_new_commands() {
        listOf("/mode", "/notice", "/ctcp", "/invite", "/knock", "/setname", "/motd", "/hop", "/raw")
            .forEach { assertTrue("$it should be a hint", it in COMMAND_HINTS) }
    }

    @Test fun channel_only_hints_are_withheld_outside_a_channel() {
        val queryHints = commandHintsFor(isChannel = false)
        listOf("/topic", "/kick", "/ban", "/invite", "/hop", "/part", "/op", "/deop").forEach {
            assertTrue("$it should be channel-only", it !in queryHints)
        }
        listOf("/msg", "/join", "/whois", "/mode", "/raw").forEach {
            assertTrue("$it should always be offered", it in queryHints)
        }
        assertEquals(COMMAND_HINTS, commandHintsFor(isChannel = true))
    }

    @Test fun remaining_parameters_follow_aliases_optional_arguments_and_implicit_targets() {
        val nick = CommandParameter(R.string.chat_parameter_nick)
        val reason = CommandParameter(R.string.chat_parameter_reason, optional = true)
        val modeArgs = CommandParameter(R.string.chat_parameter_arguments, optional = true)
        assertEquals(listOf(nick, reason), remainingCommandParameters("/kick", isChannel = true))
        assertEquals(listOf(reason), remainingCommandParameters("/KICK  alice  ", isChannel = true))
        assertEquals(emptyList<CommandParameter>(), remainingCommandParameters("/kick alice off topic", isChannel = true))
        assertEquals(listOf(modeArgs), remainingCommandParameters("/m +o", isChannel = true))
        assertEquals(listOf(modeArgs), remainingCommandParameters("/mode #room +o", isChannel = true))
        assertEquals(listOf(reason), remainingCommandParameters("/leave", isChannel = true))
        assertEquals(listOf(nick.copy(optional = true)), remainingCommandParameters("/deop", isChannel = true))
        assertEquals(emptyList<CommandParameter>(), remainingCommandParameters("/deop", isChannel = false))
        assertEquals(emptyList<CommandParameter>(), remainingCommandParameters("//kick", isChannel = true))
        assertEquals(emptyList<CommandParameter>(), remainingCommandParameters("/ki", isChannel = true))
    }
}
