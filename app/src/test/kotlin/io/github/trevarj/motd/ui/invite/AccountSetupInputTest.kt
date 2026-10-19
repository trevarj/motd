package io.github.trevarj.motd.ui.invite

import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.prefs.AccountEnrollmentDraft
import io.github.trevarj.motd.data.prefs.AccountEnrollmentProvider
import io.github.trevarj.motd.irc.client.AccountRegistrationPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountSetupInputTest {
    private val draft = AccountEnrollmentDraft(1, AccountEnrollmentProvider.LIBERA, "alice", "a@example.org", "secret")

    @Test
    fun `Libera code accepts token or exact command only`() {
        assertEquals("code", parseVerification("code", draft))
        assertEquals("code", parseVerification("/msg NickServ VERIFY REGISTER alice code", draft))
        assertEquals("code", parseVerification("VERIFY REGISTER alice code", draft))
        assertNull(parseVerification("/msg #channel hello everyone", draft))
        assertNull(parseVerification("/msg NickServ VERIFY REGISTER bob code", draft))
    }

    @Test
    fun `Libera advances only after explicit registration and verification success`() {
        assertTrue(liberaRegistrationAccepted("An email containing nickname activation instructions has been sent to a@example.org."))
        assertEquals(false, liberaRegistrationAccepted("The nickname alice cannot be registered."))
        assertTrue(liberaVerificationSucceeded("alice has now been verified."))
        assertTrue(liberaVerificationSucceeded("alice is already verified."))
        assertEquals(false, liberaVerificationSucceeded("Invalid key for registration."))
    }

    @Test
    fun `canonical OFTC uses NickServ instead of unsupported SASL`() {
        val network =
            NetworkEntity(
                id = 2,
                name = "OFTC",
                role = NetworkRole.DIRECT,
                host = "irc.oftc.net",
                port = 6697,
                nick = "alice",
                username = "alice",
                realname = "Alice",
            )
        val oftcDraft = AccountEnrollmentDraft(2, AccountEnrollmentProvider.OFTC, "alice", "a@example.org", "generated-password")

        assertEquals(AccountEnrollmentProvider.OFTC, accountEnrollmentProvider(network, hasIrcv3Registration = false))
        val activated = activateOftcNetwork(network.copy(saslMechanism = "PLAIN", saslUser = "old", saslPassword = "old"), oftcDraft)
        assertEquals("NONE", activated.saslMechanism)
        assertNull(activated.saslPassword)
        assertEquals("generated-password", activated.nickServPassword)
        assertEquals("PASSWORD_NICK", activated.nickServIdentifySyntax)
    }

    @Test
    fun `OFTC verification accepts only OFTC HTTPS links and confirmed info`() {
        val url = "https://verify.oftc.net/account/token"
        assertEquals(url, parseOftcVerificationUrl("Visit $url to continue."))
        assertNull(parseOftcVerificationUrl("https://oftc.net.evil.example/token"))
        assertTrue(oftcAccountVerified("Email address: a@example.org (verified)"))
        assertEquals(false, oftcAccountVerified("Email address: a@example.org (unverified)"))
    }

    @Test
    fun `passwords are required and reject control characters for every provider`() {
        AccountEnrollmentProvider.entries.forEach { provider ->
            listOf("", "bad\u0000password", "bad\rpassword", "bad\npassword", "bad\tpassword", "bad\u007fpassword").forEach { password ->
                assertNotNull(accountPasswordError(password, provider, null))
            }
            assertNull(accountPasswordError("Chosen-Secret-42!", provider, null))
            assertNull(accountPasswordError("short", provider, null))
        }
    }

    @Test
    fun `NickServ rejects whitespace while IRCv3 preserves ordinary spaces`() {
        listOf(AccountEnrollmentProvider.LIBERA, AccountEnrollmentProvider.OFTC).forEach { provider ->
            listOf("two words", " leading", "trailing ", "two\twords", "two\u2003words").forEach { password ->
                assertNotNull(accountPasswordError(password, provider, null))
            }
        }
        assertNull(accountPasswordError(" chosen password ", AccountEnrollmentProvider.IRCV3, null))
    }

    @Test
    fun `password ceiling counts UTF-8 bytes for every provider`() {
        AccountEnrollmentProvider.entries.forEach { provider ->
            assertNull(accountPasswordError("a".repeat(300), provider, null))
            assertNotNull(accountPasswordError("a".repeat(301), provider, null))
            assertNull(accountPasswordError("é".repeat(150), provider, null))
            assertNotNull(accountPasswordError("é".repeat(150) + "a", provider, null))
        }
    }

    @Test
    fun `IRCv3 advertised bounds count UTF-8 bytes without a generated password minimum`() {
        val policy = AccountRegistrationPolicy(minPasswordLength = 4, maxPasswordLength = 6)
        assertNotNull(accountPasswordError("é", AccountEnrollmentProvider.IRCV3, policy))
        assertNull(accountPasswordError("éé", AccountEnrollmentProvider.IRCV3, policy))
        assertNull(accountPasswordError("ééé", AccountEnrollmentProvider.IRCV3, policy))
        assertNotNull(accountPasswordError("éééa", AccountEnrollmentProvider.IRCV3, policy))
        assertNull(accountPasswordError("a", AccountEnrollmentProvider.IRCV3, null))
        assertNull(accountPasswordError("a", AccountEnrollmentProvider.LIBERA, policy))
        assertNull(accountPasswordError("a".repeat(7), AccountEnrollmentProvider.OFTC, policy))
    }

    @Test
    fun `email validation rejects control and malformed values`() {
        assertTrue(validEmail("alice@example.org"))
        assertEquals(false, validEmail("not-an-email"))
        assertEquals(false, validEmail("a@example.org\nJOIN #evil"))
    }
}
