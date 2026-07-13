package io.github.trevarj.motd.irc.client

import org.junit.Assert.assertEquals
import org.junit.Test

class MetadataCapabilityTest {
    @Test fun requests_draft_metadata_only_when_advertised() {
        assertEquals(
            setOf("batch", "draft/metadata-2"),
            CapNegotiator.requestSet(setOf("batch", "draft/metadata-2"), emptySet()),
        )
        assertEquals(setOf("batch"), CapNegotiator.requestSet(setOf("batch"), emptySet()))
        assertEquals(emptySet<String>(), CapNegotiator.requestSet(setOf("draft/metadata-2"), emptySet()))
    }
}
