package io.github.trevarj.motd.ui.invite

import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.prefs.AccountEnrollmentDraft
import io.github.trevarj.motd.data.prefs.AccountEnrollmentPhase
import io.github.trevarj.motd.data.prefs.AccountEnrollmentProvider
import io.github.trevarj.motd.data.prefs.InviteEnrollmentStore
import io.github.trevarj.motd.data.repo.NetworkRepository
import io.github.trevarj.motd.irc.client.IrcClient
import io.github.trevarj.motd.irc.client.IrcClientConfig
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.irc.transport.IrcTransport
import io.github.trevarj.motd.irc.transport.TransportFactory
import io.github.trevarj.motd.testing.NoopConnectionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AccountSetupViewModelTest {
    private lateinit var mainDispatcher: ExecutorCoroutineDispatcher

    @Before
    fun setUp() {
        mainDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        mainDispatcher.close()
    }

    @Test
    fun chosenPasswordSurvivesRegistrationVerificationAndActivationForEveryProvider() =
        runTest {
            withContext(Dispatchers.Default) {
                AccountEnrollmentProvider.entries.forEach { provider ->
                    withFixture(provider) {
                        assertEquals("", vm.state.value.password)
                        vm.editEmail(EMAIL)
                        vm.editPassword(CHOSEN_PASSWORD)
                        vm.submit()

                        assertRegistration(CHOSEN_PASSWORD)
                        acceptRegistration()
                        awaitPhase(AccountSetupPhase.VERIFY)
                        assertEquals(CHOSEN_PASSWORD, enrollment.accountDraft(NETWORK_ID)?.password)
                        assertEquals(AccountEnrollmentPhase.AWAITING_VERIFICATION, enrollment.accountDraft(NETWORK_ID)?.phase)
                        completeVerification(CHOSEN_PASSWORD)
                        assertActivated(CHOSEN_PASSWORD)
                    }
                }
            }
        }

    @Test
    fun invalidPasswordsHaveNoSideEffectsEvenWithEditedNick() =
        runTest {
            withContext(Dispatchers.Default) {
                AccountEnrollmentProvider.entries.forEach { provider ->
                    listOf(null, AccountEnrollmentPhase.PREPARED).forEach { draftPhase ->
                        withFixture(provider, draftPhase) {
                            val originalDraft = enrollment.accountDraft(NETWORK_ID)
                            val originalNetwork = networks.rows.value.single()
                            val originalLines = transport.sent.toList()
                            val invalid =
                                listOf("", "bad\u0000password", "bad\npassword", "bad\tpassword", "a".repeat(301)) +
                                    if (provider == AccountEnrollmentProvider.IRCV3) listOf("abc", "é".repeat(33)) else listOf("two words", "two\u2003words")
                            vm.editAccount("another-nick")
                            vm.editEmail(EMAIL)
                            invalid.forEach { password ->
                                vm.editPassword(password)
                                assertNull(vm.state.value.error)
                                assertEquals(password, vm.state.value.password)
                                vm.submit()

                                assertEquals(AccountSetupPhase.FAILED, vm.state.value.phase)
                                assertNotNull(vm.state.value.error)
                                assertEquals(originalDraft, enrollment.accountDraft(NETWORK_ID))
                                assertEquals(originalNetwork, networks.rows.value.single())
                                assertTrue(networks.updates.isEmpty())
                                assertEquals(0, connections.connectCalls.get())
                                assertEquals(originalLines, transport.sent.toList())
                            }
                        }
                    }
                }
            }
        }

    @Test
    fun preparedDraftResumesUnchangedCredentialsForEveryProvider() =
        runTest {
            withContext(Dispatchers.Default) {
                AccountEnrollmentProvider.entries.forEach { provider ->
                    withFixture(provider, AccountEnrollmentPhase.PREPARED) {
                        assertEquals(OLD_GENERATED_PASSWORD, vm.state.value.password)
                        vm.submit()
                        assertRegistration(OLD_GENERATED_PASSWORD)
                        rejectAccountExists()
                        if (provider != AccountEnrollmentProvider.IRCV3) {
                            awaitPhase(AccountSetupPhase.VERIFY)
                            completeVerification(OLD_GENERATED_PASSWORD)
                        }
                        assertActivated(OLD_GENERATED_PASSWORD)
                    }
                }
            }
        }

    @Test
    fun editedPreparedPasswordDoesNotRecoverAccountExistsForEveryProvider() =
        runTest {
            withContext(Dispatchers.Default) {
                AccountEnrollmentProvider.entries.forEach { provider ->
                    withFixture(provider, AccountEnrollmentPhase.PREPARED) {
                        vm.editPassword(CHOSEN_PASSWORD)
                        vm.submit()
                        assertRegistration(CHOSEN_PASSWORD)
                        rejectAccountExists()
                        awaitPhase(AccountSetupPhase.FAILED)

                        assertEquals(CHOSEN_PASSWORD, enrollment.accountDraft(NETWORK_ID)?.password)
                        assertEquals(AccountEnrollmentPhase.PREPARED, enrollment.accountDraft(NETWORK_ID)?.phase)
                        assertTrue(networks.updates.isEmpty())
                        assertEquals(0, connections.connectCalls.get())
                        assertEquals(
                            "NONE",
                            networks.rows.value
                                .single()
                                .saslMechanism,
                        )
                        assertNull(
                            networks.rows.value
                                .single()
                                .nickServPassword,
                        )
                        assertEquals(1, registrationLines().size)
                    }
                }
            }
        }

    @Test
    fun awaitingVerificationRestoresGeneratedCredentialsWithoutRegisteringForEveryProvider() =
        runTest {
            withContext(Dispatchers.Default) {
                AccountEnrollmentProvider.entries.forEach { provider ->
                    withFixture(provider, AccountEnrollmentPhase.AWAITING_VERIFICATION) {
                        assertEquals(AccountSetupPhase.VERIFY, vm.state.value.phase)
                        assertEquals("alice", vm.state.value.account)
                        assertEquals(EMAIL, vm.state.value.email)
                        assertEquals(OLD_GENERATED_PASSWORD, vm.state.value.password)
                        assertTrue(registrationLines().isEmpty())
                        assertTrue(networks.updates.isEmpty())
                        assertEquals(0, connections.connectCalls.get())

                        completeVerification(OLD_GENERATED_PASSWORD)
                        assertActivated(OLD_GENERATED_PASSWORD)
                        assertTrue(registrationLines().isEmpty())
                    }
                }
            }
        }

    @Test
    fun activatingDraftRestoresSavedCredentialsWithoutRegisteringForEveryProvider() =
        runTest {
            withContext(Dispatchers.Default) {
                AccountEnrollmentProvider.entries.forEach { provider ->
                    withFixture(provider, AccountEnrollmentPhase.ACTIVATING) {
                        if (provider == AccountEnrollmentProvider.OFTC) confirmOftcVerification(OLD_GENERATED_PASSWORD)
                        assertActivated(OLD_GENERATED_PASSWORD)
                        assertEquals(OLD_GENERATED_PASSWORD, vm.state.value.password)
                        assertTrue(registrationLines().isEmpty())
                    }
                }
            }
        }

    @Test
    fun ircv3SubmissionPreservesLeadingAndTrailingPasswordSpaces() =
        runTest {
            withContext(Dispatchers.Default) {
                withFixture(AccountEnrollmentProvider.IRCV3) {
                    val password = "  $CHOSEN_PASSWORD  "
                    vm.editEmail(EMAIL)
                    vm.editPassword(password)
                    vm.submit()
                    assertRegistration(password)
                    acceptRegistration()
                    awaitPhase(AccountSetupPhase.VERIFY)
                    completeVerification(password)
                    assertActivated(password)
                }
            }
        }

    private suspend fun withFixture(
        provider: AccountEnrollmentProvider,
        draftPhase: AccountEnrollmentPhase? = null,
        block: suspend Fixture.() -> Unit,
    ) {
        val fixture = Fixture(provider, draftPhase)
        try {
            fixture.start()
            fixture.block()
        } finally {
            fixture.close()
        }
    }

    private class Fixture(
        val provider: AccountEnrollmentProvider,
        private val draftPhase: AccountEnrollmentPhase?,
    ) {
        val enrollment = InviteEnrollmentStore(ApplicationProvider.getApplicationContext())
        val transport = RecordingTransport()
        private val clientScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val connectionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        private val host =
            when (provider) {
                AccountEnrollmentProvider.IRCV3 -> "irc.example"
                AccountEnrollmentProvider.LIBERA -> "irc.libera.chat"
                AccountEnrollmentProvider.OFTC -> "irc.oftc.net"
            }
        val networks =
            FakeNetworks(
                NetworkEntity(
                    id = NETWORK_ID,
                    name = "Test network",
                    role = NetworkRole.DIRECT,
                    host = host,
                    port = 6697,
                    tls = true,
                    nick = "alice",
                    username = "alice",
                    realname = "Alice",
                    saslMechanism = if (draftPhase == AccountEnrollmentPhase.ACTIVATING && provider != AccountEnrollmentProvider.OFTC) "PLAIN" else "NONE",
                    saslUser = if (draftPhase == AccountEnrollmentPhase.ACTIVATING && provider != AccountEnrollmentProvider.OFTC) "alice" else null,
                    saslPassword = if (draftPhase == AccountEnrollmentPhase.ACTIVATING && provider != AccountEnrollmentProvider.OFTC) OLD_GENERATED_PASSWORD else null,
                ),
            )
        private val client =
            IrcClient(
                IrcClientConfig(host, 6697, true, "alice", "alice", "Alice"),
                TransportFactory { _, _, _, _, _ -> transport },
                clientScope,
            )
        val connections = FakeConnections(client, networks, connectionScope)
        val vm = AccountSetupViewModel(networks, connections, enrollment)

        suspend fun start() {
            enrollment.clearNetwork(NETWORK_ID)
            if (draftPhase != null) {
                enrollment.putAccountDraft(AccountEnrollmentDraft(NETWORK_ID, provider, "alice", EMAIL, OLD_GENERATED_PASSWORD, draftPhase))
            }
            client.start()
            transport.awaitCommand("CAP LS")
            val caps =
                "batch message-tags server-time" +
                    if (provider == AccountEnrollmentProvider.IRCV3) {
                        " draft/account-registration=email-required,min-password-length=4,max-password-length=64 standard-replies"
                    } else {
                        ""
                    }
            transport.feed(":srv CAP * LS :$caps")
            transport.awaitCommand("CAP REQ")
            transport.feed(":srv CAP alice ACK :$caps")
            transport.feed(":srv 005 alice CHANTYPES=# CASEMAPPING=rfc1459 :supported")
            transport.feed(":srv 001 alice :Welcome")
            val ready = withTimeout(5_000) { client.state.first { it is IrcClientState.Ready } }
            connections.states.value = mapOf(NETWORK_ID to ready)
            vm.init(NETWORK_ID)
            withTimeout(5_000) { vm.state.first { it.provider != null } }
        }

        suspend fun assertRegistration(password: String) {
            val expected =
                if (provider == AccountEnrollmentProvider.IRCV3) {
                    "REGISTER * $EMAIL ${if (' ' in password) ":" else ""}$password"
                } else {
                    "PRIVMSG NickServ :REGISTER $password $EMAIL"
                }
            assertEquals(expected, transport.awaitCommand(if (provider == AccountEnrollmentProvider.IRCV3) "REGISTER" else "PRIVMSG NickServ :REGISTER"))
            assertEquals(AccountEnrollmentDraft(NETWORK_ID, provider, "alice", EMAIL, password), enrollment.accountDraft(NETWORK_ID))
        }

        suspend fun acceptRegistration() {
            when (provider) {
                AccountEnrollmentProvider.IRCV3 -> transport.feed(":srv REGISTER VERIFICATION_REQUIRED alice :check email")
                AccountEnrollmentProvider.LIBERA -> nickServReply("An email containing nickname activation instructions has been sent.")
                AccountEnrollmentProvider.OFTC -> nickServReply("Visit https://verify.oftc.net/account/token to continue.")
            }
        }

        suspend fun rejectAccountExists() {
            if (provider == AccountEnrollmentProvider.IRCV3) {
                transport.feed(":srv FAIL REGISTER ACCOUNT_EXISTS alice :already exists")
            } else {
                nickServReply("The nickname alice is already registered.")
            }
        }

        suspend fun completeVerification(password: String) {
            vm.editVerification("test-code")
            vm.verify()
            when (provider) {
                AccountEnrollmentProvider.IRCV3 -> {
                    assertEquals("VERIFY alice test-code", transport.awaitCommand("VERIFY"))
                    transport.feed(":srv VERIFY SUCCESS alice :done")
                }

                AccountEnrollmentProvider.LIBERA -> {
                    assertEquals("PRIVMSG NickServ :VERIFY REGISTER alice test-code", transport.awaitCommand("PRIVMSG NickServ :VERIFY"))
                    nickServReply("alice has now been verified.")
                }

                AccountEnrollmentProvider.OFTC -> {
                    confirmOftcVerification(password)
                }
            }
        }

        suspend fun confirmOftcVerification(password: String) {
            assertEquals("PRIVMSG NickServ :IDENTIFY $password alice", transport.awaitCommand("PRIVMSG NickServ :IDENTIFY"))
            nickServReply("You are now identified for alice.")
            assertEquals("PRIVMSG NickServ :INFO alice", transport.awaitCommand("PRIVMSG NickServ :INFO"))
            nickServReply("Email address: $EMAIL (verified)")
        }

        suspend fun assertActivated(password: String) {
            awaitPhase(AccountSetupPhase.SUCCESS)
            val network = networks.rows.value.single()
            assertEquals("alice", network.nick)
            if (provider == AccountEnrollmentProvider.OFTC) {
                assertEquals(password, network.nickServPassword)
                assertEquals("PASSWORD_NICK", network.nickServIdentifySyntax)
                assertEquals("NONE", network.saslMechanism)
                assertNull(network.saslPassword)
                assertEquals(0, connections.connectCalls.get())
            } else {
                assertEquals(password, network.saslPassword)
                assertEquals("alice", network.saslUser)
                assertEquals("PLAIN", network.saslMechanism)
                assertNull(network.nickServPassword)
                assertEquals(1, connections.connectCalls.get())
            }
            assertNull(enrollment.accountDraft(NETWORK_ID))
        }

        fun registrationLines(): List<String> = transport.sent.filter { it.startsWith("REGISTER ") || it.startsWith("PRIVMSG NickServ :REGISTER ") }

        suspend fun awaitPhase(phase: AccountSetupPhase) {
            withTimeout(5_000) { vm.state.first { it.phase == phase } }
        }

        private suspend fun nickServReply(text: String) {
            transport.feed(":NickServ!service@localhost NOTICE alice :$text")
        }

        suspend fun close() {
            vm.viewModelScope.cancel()
            connectionScope.cancel()
            client.stop()
            transport.close()
            clientScope.cancel()
            enrollment.clearNetwork(NETWORK_ID)
        }
    }

    private class FakeConnections(
        private val client: IrcClient,
        private val networks: FakeNetworks,
        private val scope: CoroutineScope,
    ) : NoopConnectionManager() {
        val connectCalls = AtomicInteger()

        override fun clientFor(networkId: Long): IrcClient = client

        override suspend fun connect(networkId: Long) {
            connectCalls.incrementAndGet()
            states.value = mapOf(networkId to IrcClientState.Connecting)
            // Queue Ready on the same dispatcher after activation begins waiting on Connecting.
            scope.launch {
                states.value =
                    mapOf(
                        networkId to
                            IrcClientState.Ready(
                                networks.rows.value
                                    .single()
                                    .nick,
                                emptySet(),
                                emptyMap(),
                            ),
                    )
            }
        }
    }

    private class FakeNetworks(
        initial: NetworkEntity,
    ) : NetworkRepository {
        val rows = MutableStateFlow(listOf(initial))
        val updates = CopyOnWriteArrayList<NetworkEntity>()

        override fun observeNetworks(): Flow<List<NetworkEntity>> = rows

        override suspend fun addNetwork(n: NetworkEntity): Long = error("Unexpected network creation")

        override suspend fun updateNetwork(n: NetworkEntity) {
            updates += n
            rows.value = listOf(n)
        }

        override suspend fun deleteNetwork(id: Long) = error("Unexpected network deletion")

        override suspend fun reorderNetworks(orderedIds: List<Long>) = Unit

        override suspend fun networkById(id: Long): NetworkEntity? = rows.value.singleOrNull { it.id == id }

        override suspend fun childrenOf(rootId: Long): List<NetworkEntity> = emptyList()
    }

    private class RecordingTransport : IrcTransport {
        private val inbound = Channel<String>(Channel.UNLIMITED)
        private val writes = Channel<String>(Channel.UNLIMITED)
        val sent = CopyOnWriteArrayList<String>()

        override suspend fun connect() = Unit

        override val incoming = inbound.consumeAsFlow()

        override suspend fun send(line: String) {
            sent += line
            writes.send(line)
        }

        override suspend fun close() {
            inbound.close()
            writes.close()
        }

        suspend fun feed(line: String) {
            inbound.send(line)
        }

        suspend fun awaitCommand(command: String): String =
            withTimeout(5_000) {
                writes.receiveAsFlow().first { it == command || it.startsWith("$command ") }
            }
    }

    private companion object {
        const val NETWORK_ID = 42L
        const val EMAIL = "a@example.org"

        // Fake credentials used only with the local recording transport.
        const val CHOSEN_PASSWORD = "Chosen-Secret-42!"
        const val OLD_GENERATED_PASSWORD = "TZXQEkCWp2wy4K2hnVxMu7XDbIV9LWca"
    }
}
