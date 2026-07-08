package io.github.trevarj.motd.ui.onboarding

import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.irc.event.IrcClientState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingReducerTest {

    private fun reduce(state: OnboardingState, vararg actions: OnboardingAction): OnboardingState =
        actions.fold(state) { s, a -> onboardingReducer(s, a) }

    @Test
    fun `welcome advances to choice`() {
        val s = onboardingReducer(OnboardingState(), OnboardingAction.Next)
        assertEquals(OnboardingStep.CHOICE, s.step)
    }

    @Test
    fun `choice does not advance until a choice is made`() {
        val s = onboardingReducer(
            OnboardingState(step = OnboardingStep.CHOICE),
            OnboardingAction.Next,
        )
        // No choice yet -> stays put.
        assertEquals(OnboardingStep.CHOICE, s.step)
    }

    @Test
    fun `choosing network then next advances to server`() {
        val s = reduce(
            OnboardingState(step = OnboardingStep.CHOICE),
            OnboardingAction.ChooseConnection(ConnectionChoice.NETWORK),
            OnboardingAction.Next,
        )
        assertEquals(OnboardingStep.SERVER, s.step)
        assertEquals(NetworkRole.DIRECT, s.role)
    }

    @Test
    fun `soju choice yields bouncer root role`() {
        val s = onboardingReducer(
            OnboardingState(step = OnboardingStep.CHOICE),
            OnboardingAction.ChooseConnection(ConnectionChoice.SOJU),
        )
        assertTrue(s.isSoju)
        assertEquals(NetworkRole.BOUNCER_ROOT, s.role)
    }

    @Test
    fun `libera preset fills host port tls and selects network path`() {
        val s = onboardingReducer(
            OnboardingState(step = OnboardingStep.CHOICE),
            OnboardingAction.ApplyLiberaPreset,
        )
        assertEquals(ConnectionChoice.NETWORK, s.choice)
        assertEquals("irc.libera.chat", s.server.host)
        assertEquals("6697", s.server.port)
        assertTrue(s.server.tls)
    }

    @Test
    fun `libera preset preserves already-typed nick`() {
        val s = reduce(
            OnboardingState(step = OnboardingStep.CHOICE),
            OnboardingAction.EditServer(ServerForm(nick = "trev")),
            OnboardingAction.ApplyLiberaPreset,
        )
        assertEquals("trev", s.server.nick)
        assertEquals("irc.libera.chat", s.server.host)
    }

    @Test
    fun `server invalid blocks advance, valid allows it`() {
        val invalid = OnboardingState(step = OnboardingStep.SERVER)
        assertFalse(invalid.canAdvance)
        assertEquals(OnboardingStep.SERVER, onboardingReducer(invalid, OnboardingAction.Next).step)

        val valid = invalid.copy(server = ServerForm(host = "irc.libera.chat", nick = "me"))
        assertTrue(valid.canAdvance)
        assertEquals(OnboardingStep.AUTH, onboardingReducer(valid, OnboardingAction.Next).step)
    }

    @Test
    fun `server invalid on bad port`() {
        val s = ServerForm(host = "h", nick = "n", port = "70000")
        assertFalse(s.isValid)
        assertTrue(s.copy(port = "6697").isValid)
    }

    @Test
    fun `effective username falls back to nick`() {
        assertEquals("me", ServerForm(nick = "me").effectiveUsername)
        assertEquals("bot", ServerForm(nick = "me", username = "bot").effectiveUsername)
    }

    @Test
    fun `auth plain requires user and password`() {
        assertTrue(AuthForm(AuthMode.NONE).isValid)
        assertFalse(AuthForm(AuthMode.PLAIN, saslUser = "u").isValid)
        assertTrue(AuthForm(AuthMode.PLAIN, saslUser = "u", saslPassword = "p").isValid)
        assertFalse(AuthForm(AuthMode.EXTERNAL).isValid)
        assertTrue(AuthForm(AuthMode.EXTERNAL, certAlias = "alias").isValid)
    }

    @Test
    fun `back moves to previous step and does not underflow`() {
        assertEquals(
            OnboardingStep.WELCOME,
            onboardingReducer(OnboardingState(step = OnboardingStep.CHOICE), OnboardingAction.Back).step,
        )
        assertEquals(
            OnboardingStep.WELCOME,
            onboardingReducer(OnboardingState(), OnboardingAction.Back).step,
        )
    }

    @Test
    fun `next does not overflow past finish`() {
        assertEquals(
            OnboardingStep.FINISH,
            onboardingReducer(OnboardingState(step = OnboardingStep.FINISH), OnboardingAction.Next).step,
        )
    }

    @Test
    fun `conn state changes accumulate a log and surface failure reason`() {
        val s = reduce(
            OnboardingState(step = OnboardingStep.CONNECT),
            OnboardingAction.ConnStateChanged(IrcClientState.Connecting),
            OnboardingAction.ConnStateChanged(IrcClientState.Registering),
            OnboardingAction.ConnStateChanged(IrcClientState.Failed("bad password", fatal = true)),
        )
        assertEquals(3, s.stateLog.size)
        assertEquals("bad password", s.error)
        assertFalse(s.isReady)
        assertFalse(s.canAdvance)
    }

    @Test
    fun `ready allows advance from connect`() {
        val s = onboardingReducer(
            OnboardingState(step = OnboardingStep.CONNECT),
            OnboardingAction.ConnStateChanged(
                IrcClientState.Ready("me", emptySet(), emptyMap()),
            ),
        )
        assertTrue(s.isReady)
        assertTrue(s.canAdvance)
        assertEquals(OnboardingStep.FINISH, onboardingReducer(s, OnboardingAction.Next).step)
    }

    @Test
    fun `bouncer list loads and toggles selection`() {
        val listed = onboardingReducer(
            OnboardingState(step = OnboardingStep.CONNECT),
            OnboardingAction.BouncerListed(
                listOf(
                    BouncerNetworkRow("1", "Libera", selected = false),
                    BouncerNetworkRow("2", "OFTC", selected = false),
                ),
            ),
        )
        assertTrue(listed.bouncerListLoaded)
        assertEquals(2, listed.bouncerNetworks.size)

        val toggled = onboardingReducer(listed, OnboardingAction.ToggleBouncerNetwork("1"))
        assertTrue(toggled.bouncerNetworks.first { it.netId == "1" }.selected)
        assertFalse(toggled.bouncerNetworks.first { it.netId == "2" }.selected)
    }

    @Test
    fun `bouncer add appends a row`() {
        val s = reduce(
            OnboardingState(step = OnboardingStep.CONNECT),
            OnboardingAction.BouncerListed(emptyList()),
            OnboardingAction.BouncerAdded(BouncerNetworkRow("9", "New", selected = true)),
        )
        assertEquals(1, s.bouncerNetworks.size)
        assertEquals("New", s.bouncerNetworks.first().name)
    }

    @Test
    fun `network created records id`() {
        val s = onboardingReducer(OnboardingState(), OnboardingAction.NetworkCreated(42L))
        assertEquals(42L, s.networkId)
    }

    @Test
    fun `error clears`() {
        val s = reduce(
            OnboardingState(error = "x"),
            OnboardingAction.Error(null),
        )
        assertNull(s.error)
    }

    @Test
    fun `goto jumps directly`() {
        assertEquals(
            OnboardingStep.CONNECT,
            onboardingReducer(OnboardingState(), OnboardingAction.GoTo(OnboardingStep.CONNECT)).step,
        )
    }
}
