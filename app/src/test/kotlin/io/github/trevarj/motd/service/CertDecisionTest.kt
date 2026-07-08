package io.github.trevarj.motd.service

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure TOFU pin-decision logic (plans/12): pinned/mismatch/first-use → Trust/Prompt/Changed. */
class CertDecisionTest {

    private val fp = "a1b2c3"

    @Test
    fun noPin_caValid_trusts() {
        assertEquals(CertDecision.TRUST, certDecision(pinned = null, presentedSha256 = fp, caValid = true))
    }

    @Test
    fun noPin_notCaValid_prompts() {
        assertEquals(CertDecision.PROMPT, certDecision(pinned = null, presentedSha256 = fp, caValid = false))
    }

    @Test
    fun pinMatches_trusts_evenWhenNotCaValid() {
        assertEquals(CertDecision.TRUST, certDecision(pinned = fp, presentedSha256 = fp, caValid = false))
    }

    @Test
    fun pinMatches_caseInsensitive() {
        assertEquals(
            CertDecision.TRUST,
            certDecision(pinned = "A1B2C3", presentedSha256 = "a1b2c3", caValid = false),
        )
    }

    @Test
    fun pinDiffers_reportsChanged() {
        assertEquals(
            CertDecision.CHANGED,
            certDecision(pinned = "deadbeef", presentedSha256 = fp, caValid = true),
        )
    }
}
