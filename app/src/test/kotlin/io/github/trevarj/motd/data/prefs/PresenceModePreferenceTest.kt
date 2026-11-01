package io.github.trevarj.motd.data.prefs

import io.github.trevarj.motd.data.db.MessageKind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Upgrade contract for the presence preference. Installations predating it stored only the former
 * `show_join_part_quit` boolean, and an explicit choice there has to survive the upgrade.
 */
class PresenceModePreferenceTest {
    @Test fun `no stored choice adopts the smart default`() {
        assertEquals(
            PresenceMode.SMART,
            presenceModeFromPreference(saved = null, legacyShowJoinPartQuit = null),
        )
    }

    @Test fun `a legacy hide stays hidden`() {
        assertEquals(
            PresenceMode.HIDDEN,
            presenceModeFromPreference(saved = null, legacyShowJoinPartQuit = "false"),
        )
    }

    @Test fun `a legacy show stays fully shown rather than becoming smart`() {
        assertEquals(
            PresenceMode.ALL,
            presenceModeFromPreference(saved = null, legacyShowJoinPartQuit = "true"),
        )
    }

    @Test fun `an explicit mode wins over any legacy value`() {
        assertEquals(
            PresenceMode.SMART,
            presenceModeFromPreference(saved = "SMART", legacyShowJoinPartQuit = "false"),
        )
        assertEquals(
            PresenceMode.HIDDEN,
            presenceModeFromPreference(saved = "HIDDEN", legacyShowJoinPartQuit = "true"),
        )
    }

    @Test fun `an unreadable stored value falls back instead of throwing`() {
        assertEquals(
            PresenceMode.ALL,
            presenceModeFromPreference(saved = "NONSENSE", legacyShowJoinPartQuit = "true"),
        )
        assertEquals(
            PresenceMode.SMART,
            presenceModeFromPreference(saved = "NONSENSE", legacyShowJoinPartQuit = null),
        )
    }

    @Test fun `custom kinds retain only known presence events`() {
        assertEquals(
            setOf(MessageKind.JOIN, MessageKind.NETJOIN),
            customPresenceKindsFromPreference("JOIN,PRIVMSG,UNKNOWN,NETJOIN"),
        )
        assertEquals("JOIN,NETJOIN", encodeCustomPresenceKinds(setOf(MessageKind.NETJOIN, MessageKind.JOIN, MessageKind.PRIVMSG)))
    }
}
