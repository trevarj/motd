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
}
