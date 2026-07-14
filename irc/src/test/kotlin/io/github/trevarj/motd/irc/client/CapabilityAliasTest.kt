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
}
