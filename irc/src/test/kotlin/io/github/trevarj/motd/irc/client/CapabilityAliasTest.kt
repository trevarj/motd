package io.github.trevarj.motd.irc.client

import org.junit.Assert.assertEquals
import org.junit.Test

class CapabilityAliasTest {
    @Test fun `no implicit names requests exactly one preferred alias`() {
        val all = NO_IMPLICIT_NAMES_ALIASES.toSet()
        assertEquals(
            setOf("no-implicit-names"),
            CapNegotiator.requestSet(all, emptySet()),
        )
        assertEquals(
            setOf("draft/no-implicit-names"),
            CapNegotiator.requestSet(all - "no-implicit-names", emptySet()),
        )
        assertEquals(
            setOf("soju.im/no-implicit-names"),
            CapNegotiator.requestSet(setOf("soju.im/no-implicit-names"), emptySet()),
        )
        assertEquals(null, preferredNoImplicitNames(emptySet()))
    }

    @Test fun `runtime capability discovery does not switch selected names alias`() {
        assertEquals(
            emptySet<String>(),
            CapNegotiator.runtimeRequestSet(
                newCaps = setOf("no-implicit-names"),
                ackedCaps = setOf("draft/no-implicit-names"),
                extraCaps = emptySet(),
            ),
        )
        assertEquals(
            setOf("no-implicit-names"),
            CapNegotiator.runtimeRequestSet(
                newCaps = setOf("no-implicit-names", "draft/no-implicit-names"),
                ackedCaps = emptySet(),
                extraCaps = emptySet(),
            ),
        )
    }

    @Test fun `extended monitor requests one stable preferred alias`() {
        assertEquals(
            setOf("extended-monitor"),
            CapNegotiator.requestSet(EXTENDED_MONITOR_ALIASES.toSet(), emptySet()),
        )
        assertEquals(
            emptySet<String>(),
            CapNegotiator.runtimeRequestSet(
                newCaps = setOf("extended-monitor"),
                ackedCaps = setOf("draft/extended-monitor"),
                extraCaps = emptySet(),
            ),
        )
    }

    @Test fun `read marker requests draft standard when both offered, soju fallback when only it is offered`() {
        // Both advertised: IRCv3 draft wins; no redundant soju.im/read request.
        assertEquals(
            setOf("draft/read-marker"),
            CapNegotiator.requestSet(READ_MARKER_ALIASES.toSet(), emptySet()),
        )
        // Only the soju fallback is offered: request it.
        assertEquals(
            setOf("soju.im/read"),
            CapNegotiator.requestSet(setOf("soju.im/read"), emptySet()),
        )
        // Neither offered: request nothing.
        assertEquals(
            emptySet<String>(),
            CapNegotiator.requestSet(emptySet(), emptySet()),
        )
        assertEquals(null, preferredReadMarker(emptySet()))
    }

    @Test fun `runtime capability discovery holds the already-acked read marker alias`() {
        // Already on soju.im/read; a later CAP NEW advertising the draft must not switch aliases.
        assertEquals(
            emptySet<String>(),
            CapNegotiator.runtimeRequestSet(
                newCaps = setOf("draft/read-marker"),
                ackedCaps = setOf("soju.im/read"),
                extraCaps = emptySet(),
            ),
        )
        // Fresh connection with both advertised still prefers the draft.
        assertEquals(
            setOf("draft/read-marker"),
            CapNegotiator.runtimeRequestSet(
                newCaps = READ_MARKER_ALIASES.toSet(),
                ackedCaps = emptySet(),
                extraCaps = emptySet(),
            ),
        )
    }

    @Test fun `server time requests modern name before ZNC legacy alias`() {
        assertEquals(
            setOf("server-time"),
            CapNegotiator.requestSet(SERVER_TIME_ALIASES.toSet(), emptySet()),
        )
        assertEquals(
            setOf("znc.in/server-time-iso"),
            CapNegotiator.requestSet(setOf("znc.in/server-time-iso"), emptySet()),
        )
        assertEquals(null, preferredServerTime(emptySet()))
    }

    @Test fun `issue 32 capability set requests only real CAP names`() {
        val advertised =
            setOf(
                "standard-replies",
                "draft/relaymsg",
                "draft/pre-away",
                "draft/channel-rename",
                "message-ids",
                "utf8only",
                "znc.in/playback",
            )
        assertEquals(
            setOf("standard-replies", "draft/relaymsg", "draft/pre-away", "draft/channel-rename"),
            CapNegotiator.requestSet(advertised, emptySet()),
        )
    }

    @Test fun `multiline is requested with batch and rejected without it`() {
        assertEquals(
            setOf("batch", "draft/multiline"),
            CapNegotiator.requestSet(
                setOf("batch", "draft/multiline"),
                emptySet(),
            ),
        )
        assertEquals(
            emptySet<String>(),
            CapNegotiator.requestSet(setOf("draft/multiline"), emptySet()),
        )
    }
}
