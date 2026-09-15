package io.github.trevarj.motd.service

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.trevarj.motd.di.AppClock
import io.github.trevarj.motd.diagnostics.DiagnosticLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationSettingsTest {
    @Test
    fun `start expires once`() =
        runTest {
            val expired = mutableListOf<Long>()
            val settings = NotificationSettingsImpl(backgroundScope, AppClock { testScheduler.currentTime }, onExpired = { expired += it })

            assertTrue(settings.startWatch(7, 1_000))
            assertTrue(settings.resolve(1, 7)?.watch != null)
            advanceTimeBy(999)
            runCurrent()
            assertTrue(expired.isEmpty())
            advanceTimeBy(1)
            runCurrent()

            assertNull(settings.resolve(1, 7)?.watch)
            assertEquals(listOf(7L), expired)
        }

    @Test
    fun startingSecondWatchKeepsFirstActive() =
        runTest {
            val expired = mutableListOf<Long>()
            val settings = NotificationSettingsImpl(backgroundScope, AppClock { testScheduler.currentTime }, onExpired = { expired += it })

            settings.startWatch(7, 1_000)
            settings.startWatch(8, 500)
            assertTrue(settings.resolve(1, 7)?.watch != null)
            assertTrue(settings.resolve(1, 8)?.watch != null)
            advanceTimeBy(500)
            runCurrent()
            assertEquals(listOf(8L), expired)
            assertTrue(settings.resolve(1, 7)?.watch != null)
            assertNull(settings.resolve(1, 8)?.watch)

            advanceTimeBy(500)
            runCurrent()
            assertEquals(listOf(8L, 7L), expired)
        }

    @Test
    fun `stopping one watch preserves its sibling and never expires the stopped channel`() =
        runTest {
            val expired = mutableListOf<Long>()
            val settings = NotificationSettingsImpl(backgroundScope, AppClock { testScheduler.currentTime }, onExpired = { expired += it })
            settings.startWatch(7, 500)
            settings.startWatch(8, 1_000)

            assertTrue(settings.stopWatch(7))
            assertTrue(settings.stopWatch(7))
            assertNull(settings.resolve(1, 7)?.watch)
            assertTrue(settings.resolve(1, 8)?.watch != null)
            advanceTimeBy(1_000)
            runCurrent()

            assertEquals(listOf(8L), expired)
        }

    @Test
    fun `renewal replaces only its own timer and expiry reveals the unchanged policy`() =
        runTest {
            val expired = mutableListOf<Long>()
            var persisted = NotificationConfig()
            val settings =
                NotificationSettingsImpl(
                    backgroundScope,
                    AppClock { testScheduler.currentTime },
                    onExpired = { expired += it },
                    save = { persisted = it },
                )
            settings.setGlobal(NotificationMode.ALL)
            settings.setServer(1, NotificationMode.OFF)
            settings.setChannel(7, NotificationMode.MENTIONS)
            settings.startWatch(7, 500)
            settings.startWatch(8, 800)
            advanceTimeBy(200)
            settings.startWatch(7, 1_000)
            advanceTimeBy(600)
            runCurrent()

            assertEquals(listOf(8L), expired)
            assertEquals(mapOf(7L to 1_200L), persisted.watches)
            assertTrue(settings.resolve(1, 7)?.watch != null)
            advanceTimeBy(400)
            runCurrent()

            assertEquals(listOf(8L, 7L), expired)
            assertEquals(ResolvedNotificationSettings(NotificationMode.MENTIONS, NotificationScope.CHANNEL, null), settings.resolve(1, 7))
            assertEquals(ResolvedNotificationSettings(NotificationMode.OFF, NotificationScope.SERVER, null), settings.resolve(1, 8))
            assertEquals(NotificationMode.ALL, persisted.global)
        }

    @Test
    fun `simultaneous expiries notify each valid channel once`() =
        runTest {
            val expired = mutableListOf<Long>()
            val settings = NotificationSettingsImpl(backgroundScope, AppClock { testScheduler.currentTime }, onExpired = { expired += it })
            settings.startWatch(7, 1_000)
            settings.startWatch(8, 1_000)

            advanceTimeBy(1_000)
            runCurrent()

            assertEquals(listOf(7L, 8L), expired.sorted())
            assertEquals(emptyMap<Long, Long>(), settings.config().watches)
        }

    @Test
    fun `forever watch stays active beside a finite watch and never gets an expiry`() =
        runTest {
            val expired = mutableListOf<Long>()
            val settings = NotificationSettingsImpl(backgroundScope, AppClock { testScheduler.currentTime }, onExpired = { expired += it })
            settings.startWatch(7, null)
            settings.startWatch(8, 1_000)

            advanceTimeBy(30L * 24 * 60 * 60 * 1000)
            runCurrent()

            assertEquals(ChannelWatchState(7, Long.MAX_VALUE), settings.resolve(1, 7)?.watch)
            assertEquals(listOf(8L), expired)
        }

    @Test
    fun `channel then exact server then global resolves without cascading between server rows`() =
        runTest {
            val settings = NotificationSettingsImpl(backgroundScope, AppClock { testScheduler.currentTime }, onExpired = {})
            settings.setGlobal(NotificationMode.OFF)
            settings.setServer(1, NotificationMode.ALL)
            settings.setChannel(7, NotificationMode.MENTIONS)
            settings.startWatch(7, null)

            assertEquals(NotificationScope.CHANNEL, settings.resolve(1, 7)?.source)
            assertEquals(NotificationMode.MENTIONS, settings.resolve(1, 7)?.mode)
            assertEquals(ResolvedNotificationSettings(NotificationMode.ALL, NotificationScope.SERVER, null), settings.resolve(1, 8))
            // A bouncer root and its child are just independent exact network IDs.
            assertEquals(ResolvedNotificationSettings(NotificationMode.OFF, NotificationScope.GLOBAL, null), settings.resolve(2, 9))
            settings.setServer(2, NotificationMode.MENTIONS)
            settings.setServer(1, null)
            assertEquals(ResolvedNotificationSettings(NotificationMode.MENTIONS, NotificationScope.SERVER, null), settings.resolve(2, 9))
            settings.setChannel(7, null)

            assertEquals(NotificationMode.OFF, settings.resolve(1, 7)?.mode)
            assertEquals(NotificationScope.GLOBAL, settings.resolve(1, 7)?.source)
            assertEquals(ChannelWatchState(7, Long.MAX_VALUE), settings.resolve(1, 7)?.watch)
        }

    @Test
    fun `resolve reads no persistence or room state after restoration`() =
        runTest {
            var readsAllowed = true
            val settings =
                NotificationSettingsImpl(
                    backgroundScope,
                    AppClock { testScheduler.currentTime },
                    onExpired = {},
                    load = {
                        check(readsAllowed)
                        preferences(NotificationConfig(channels = mapOf(7L to NotificationMode.OFF)))
                    },
                    resolveBufferId = {
                        check(readsAllowed)
                        it
                    },
                    serverExists = {
                        check(readsAllowed)
                        true
                    },
                )
            runCurrent()
            readsAllowed = false

            assertEquals(NotificationMode.OFF, settings.resolve(1, 7)?.mode)
            assertEquals(ResolvedNotificationSettings(NotificationMode.MENTIONS, NotificationScope.GLOBAL, null), settings.resolve(1, 8))
        }

    @Test
    fun `restore canonicalizes finite and forever watches while preserving absolute deadlines`() =
        runTest {
            val expired = mutableListOf<Long>()
            var persisted = NotificationConfig()
            advanceTimeBy(400)
            val settings =
                NotificationSettingsImpl(
                    backgroundScope,
                    AppClock { testScheduler.currentTime },
                    onExpired = { expired += it },
                    load = { preferences(NotificationConfig(watches = mapOf(7L to 1_000L, 9L to Long.MAX_VALUE))) },
                    save = { persisted = it },
                    resolveBufferId = { if (it == 7L) 8 else it },
                )
            runCurrent()

            assertEquals(mapOf(8L to 1_000L, 9L to Long.MAX_VALUE), persisted.watches)
            assertEquals(ChannelWatchState(8, 1_000), settings.resolve(1, 8)?.watch)
            advanceTimeBy(600)
            runCurrent()
            assertEquals(listOf(8L), expired)
            assertEquals(ChannelWatchState(9, Long.MAX_VALUE), settings.resolve(1, 9)?.watch)
        }

    @Test
    fun `restore expires each valid stale watch only after saving and prunes missing rooms silently`() =
        runTest {
            val expired = mutableListOf<Long>()
            var persisted: NotificationConfig? = null
            advanceTimeBy(5_000)
            val settings =
                NotificationSettingsImpl(
                    backgroundScope,
                    AppClock { testScheduler.currentTime },
                    onExpired = { id ->
                        assertFalse(checkNotNull(persisted).watches.containsKey(id))
                        expired += id
                    },
                    load = { preferences(NotificationConfig(watches = mapOf(7L to 1_000L, 8L to 2_000L, 9L to 3_000L))) },
                    save = { persisted = it },
                    resolveBufferId = { it.takeUnless { id -> id == 9L } },
                )
            runCurrent()

            assertEquals(listOf(7L, 8L), expired.sorted())
            assertEquals(emptyMap<Long, Long>(), settings.config().watches)
            assertEquals(NotificationConfig(), persisted)
        }

    @Test
    fun `legacy finite watch migrates once and a saved empty config blocks stale legacy keys`() =
        runTest {
            var saved = preferencesOf(BUFFER_ID to 7L, EXPIRES_AT to 1_000L)
            val settings =
                NotificationSettingsImpl(
                    backgroundScope,
                    AppClock { testScheduler.currentTime },
                    onExpired = {},
                    load = { saved },
                    save = { saved = preferences(it) },
                )
            runCurrent()
            assertEquals(ChannelWatchState(7, 1_000), settings.resolve(1, 7)?.watch)
            settings.stopWatch(7)
            // Presence of config_v1 is authoritative even if an old build left legacy values behind.
            saved =
                saved.toMutablePreferences().apply {
                    this[BUFFER_ID] = 7
                    this[EXPIRES_AT] = Long.MAX_VALUE
                }
            val restored =
                NotificationSettingsImpl(
                    backgroundScope,
                    AppClock { testScheduler.currentTime },
                    onExpired = {},
                    load = { saved },
                )
            runCurrent()

            assertEquals(NotificationConfig(), restored.config())
            assertNull(restored.resolve(1, 7)?.watch)
        }

    @Test
    fun `legacy forever watch imports without an expiry timer`() =
        runTest {
            val expired = mutableListOf<Long>()
            val settings =
                NotificationSettingsImpl(
                    backgroundScope,
                    AppClock { testScheduler.currentTime },
                    onExpired = { expired += it },
                    load = { preferencesOf(BUFFER_ID to 7L, EXPIRES_AT to Long.MAX_VALUE) },
                )
            runCurrent()
            advanceTimeBy(30L * 24 * 60 * 60 * 1000)
            runCurrent()

            assertEquals(ChannelWatchState(7, Long.MAX_VALUE), settings.resolve(1, 7)?.watch)
            assertTrue(expired.isEmpty())
        }

    @Test
    fun `malformed config is repaired instead of resurrecting legacy data`() =
        runTest {
            var persisted: NotificationConfig? = null
            val settings =
                NotificationSettingsImpl(
                    backgroundScope,
                    AppClock { testScheduler.currentTime },
                    onExpired = {},
                    load = { preferencesOf(CONFIG to "{broken", BUFFER_ID to 7L, EXPIRES_AT to Long.MAX_VALUE) },
                    save = { persisted = it },
                )
            runCurrent()

            assertEquals(NotificationConfig(), persisted)
            assertEquals(NotificationConfig(), settings.config())
            assertNull(settings.resolve(1, 7)?.watch)
        }

    @Test
    fun `empty or incomplete legacy data is still saved before ready`() =
        runTest {
            val writes = mutableListOf<NotificationConfig>()
            val settings =
                NotificationSettingsImpl(
                    backgroundScope,
                    AppClock { testScheduler.currentTime },
                    onExpired = {},
                    load = { preferencesOf(BUFFER_ID to 7L) },
                    save = { writes += it },
                )
            runCurrent()

            assertEquals(listOf(NotificationConfig()), writes)
            assertEquals(NotificationConfig(), settings.config())
        }

    @Test
    fun `read failure releases waiting callers as unavailable and retry restores settings`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            var failRead = true
            val failures = mutableListOf<Map<String, Any?>>()
            val logger =
                object : DiagnosticLogger by DiagnosticLogger.Noop {
                    override fun record(
                        component: String,
                        event: String,
                        fields: () -> Map<String, Any?>,
                    ) {
                        failures += fields()
                    }
                }
            val settings =
                NotificationSettingsImpl(
                    backgroundScope,
                    AppClock { testScheduler.currentTime },
                    onExpired = {},
                    load = {
                        gate.await()
                        if (failRead) throw IOException("do not log private preference contents")
                        preferences(NotificationConfig(global = NotificationMode.OFF))
                    },
                    diagnostics = logger,
                )
            val resolved = async { settings.resolve(1, 7) }
            val mutation = async { settings.startWatch(7, null) }
            runCurrent()
            assertEquals(NotificationSettingsState.Loading, settings.state.value)
            assertFalse(resolved.isCompleted)
            assertFalse(mutation.isCompleted)
            gate.complete(Unit)

            assertNull(resolved.await())
            assertFalse(mutation.await())
            assertEquals(NotificationSettingsState.Unavailable, settings.state.value)
            assertEquals(listOf(mapOf("error" to "IOException")), failures)
            assertFalse(settings.setGlobal(NotificationMode.ALL))
            assertFalse(settings.stopWatch(7))
            failRead = false
            assertTrue(settings.retryLoad())
            assertEquals(NotificationMode.OFF, settings.resolve(1, 7)?.mode)
            assertFalse(settings.retryLoad())
        }

    @Test
    fun `failed migration write leaves saved preferences untouched and retry arms the restored watch`() =
        runTest {
            val original = preferencesOf(BUFFER_ID to 7L, EXPIRES_AT to 1_000L)
            var saved: Preferences = original
            var failSave = true
            val expired = mutableListOf<Long>()
            val settings =
                NotificationSettingsImpl(
                    backgroundScope,
                    AppClock { testScheduler.currentTime },
                    onExpired = { expired += it },
                    load = { saved },
                    save = {
                        if (failSave) throw IOException("storage unavailable")
                        saved = preferences(it)
                    },
                )
            runCurrent()

            assertEquals(NotificationSettingsState.Unavailable, settings.state.value)
            assertSame(original, saved)
            assertNull(settings.resolve(1, 7))
            assertFalse(settings.setChannel(7, NotificationMode.ALL))
            failSave = false
            assertTrue(settings.retryLoad())
            assertEquals(ChannelWatchState(7, 1_000), settings.resolve(1, 7)?.watch)
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(listOf(7L), expired)
        }

    @Test
    fun `failed startup expiry cleanup never alerts on a later retry`() =
        runTest {
            advanceTimeBy(2_000)
            var failSave = true
            val expired = mutableListOf<Long>()
            val settings =
                NotificationSettingsImpl(
                    backgroundScope,
                    AppClock { testScheduler.currentTime },
                    onExpired = { expired += it },
                    load = { preferencesOf(BUFFER_ID to 7L, EXPIRES_AT to 1_000L) },
                    save = { if (failSave) throw IOException("failed cleanup") },
                )
            runCurrent()
            assertEquals(NotificationSettingsState.Unavailable, settings.state.value)
            failSave = false
            assertTrue(settings.retryLoad())
            runCurrent()

            assertEquals(emptyMap<Long, Long>(), settings.config().watches)
            assertTrue(expired.isEmpty())
        }

    @Test
    fun `later save failures preserve ready settings and the previous timer`() =
        runTest {
            var failSave = false
            var persisted = NotificationConfig()
            val expired = mutableListOf<Long>()
            val settings =
                NotificationSettingsImpl(
                    backgroundScope,
                    AppClock { testScheduler.currentTime },
                    onExpired = { expired += it },
                    save = {
                        if (failSave) throw IOException("read only")
                        persisted = it
                    },
                )
            settings.setChannel(7, NotificationMode.OFF)
            settings.startWatch(7, 1_000)
            settings.startWatch(8, null)
            val previous = settings.state.value
            failSave = true

            assertFalse(settings.startWatch(7, 5_000))
            assertFalse(settings.stopWatch(8))
            assertFalse(settings.setChannel(7, NotificationMode.ALL))
            assertEquals(previous, settings.state.value)
            assertEquals(settings.config(), persisted)
            failSave = false
            advanceTimeBy(1_000)
            runCurrent()

            assertEquals(listOf(7L), expired)
            assertNull(settings.resolve(1, 7)?.watch)
            assertEquals(ChannelWatchState(8, Long.MAX_VALUE), settings.resolve(1, 8)?.watch)
            assertEquals(NotificationMode.OFF, settings.resolve(1, 7)?.mode)
        }

    @Test
    fun `watch publication and expiry alerts wait for durable saves`() =
        runTest {
            var saveGate: CompletableDeferred<Unit>? = null
            var persisted = NotificationConfig()
            val expired = mutableListOf<Long>()
            val settings =
                NotificationSettingsImpl(
                    backgroundScope,
                    AppClock { testScheduler.currentTime },
                    onExpired = { id ->
                        assertFalse(persisted.watches.containsKey(id))
                        expired += id
                    },
                    save = {
                        saveGate?.await()
                        persisted = it
                    },
                )
            runCurrent()
            val started = CompletableDeferred<Unit>()
            saveGate = started
            val mutation = async { settings.startWatch(7, 1_000) }
            runCurrent()
            assertNull(settings.resolve(1, 7)?.watch)
            started.complete(Unit)
            assertTrue(mutation.await())
            assertEquals(ChannelWatchState(7, 1_000), settings.resolve(1, 7)?.watch)
            val ended = CompletableDeferred<Unit>()
            saveGate = ended
            advanceTimeBy(1_000)
            runCurrent()

            assertEquals(mapOf(7L to 1_000L), settings.config().watches)
            assertNull(settings.resolve(1, 7)?.watch)
            assertTrue(expired.isEmpty())
            ended.complete(Unit)
            runCurrent()
            assertEquals(emptyMap<Long, Long>(), settings.config().watches)
            assertEquals(listOf(7L), expired)
        }

    @Test
    fun `saves crossing existing and newly started watch deadlines still expire exactly once`() =
        runTest {
            var gate: CompletableDeferred<Unit>? = null
            var persisted = NotificationConfig()
            val expired = mutableListOf<Long>()
            val settings =
                NotificationSettingsImpl(
                    backgroundScope,
                    AppClock { testScheduler.currentTime },
                    onExpired = { id ->
                        assertFalse(persisted.watches.containsKey(id))
                        expired += id
                    },
                    save = {
                        gate?.await()
                        persisted = it
                    },
                )
            settings.startWatch(7, 1_000)
            runCurrent()
            advanceTimeBy(400)
            val unrelatedGate = CompletableDeferred<Unit>()
            gate = unrelatedGate
            val unrelatedSave = async { settings.setGlobal(NotificationMode.ALL) }
            runCurrent()
            advanceTimeBy(600)
            runCurrent()
            assertTrue(expired.isEmpty())
            unrelatedGate.complete(Unit)
            assertTrue(unrelatedSave.await())
            runCurrent()

            assertEquals(listOf(7L), expired)
            assertEquals(NotificationConfig(global = NotificationMode.ALL), persisted)

            val newWatchGate = CompletableDeferred<Unit>()
            gate = newWatchGate
            val newWatchSave = async { settings.startWatch(8, 500) }
            runCurrent()
            advanceTimeBy(500)
            runCurrent()
            newWatchGate.complete(Unit)
            assertTrue(newWatchSave.await())
            runCurrent()

            assertEquals(listOf(7L, 8L), expired)
            assertEquals(NotificationConfig(global = NotificationMode.ALL), persisted)
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(listOf(7L, 8L), expired)
        }

    @Test
    fun `failed expiry stops eligibility without spinning and later mutation silently cleans the stale entry`() =
        runTest {
            var failSave = false
            var writes = 0
            val expired = mutableListOf<Long>()
            val settings =
                NotificationSettingsImpl(
                    backgroundScope,
                    AppClock { testScheduler.currentTime },
                    onExpired = { expired += it },
                    save = {
                        writes++
                        if (failSave) throw IOException("disk full")
                    },
                )
            settings.startWatch(7, 1_000)
            settings.startWatch(8, null)
            failSave = true
            advanceTimeBy(1_000)
            runCurrent()
            val writesAfterFailure = writes
            assertNull(settings.resolve(1, 7)?.watch)
            assertTrue(expired.isEmpty())
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(writesAfterFailure, writes)
            failSave = false

            assertTrue(settings.setGlobal(NotificationMode.OFF))
            runCurrent()
            assertEquals(mapOf(8L to Long.MAX_VALUE), settings.config().watches)
            assertTrue(expired.isEmpty())
            settings.startWatch(7, 500)
            advanceTimeBy(500)
            runCurrent()
            assertEquals(listOf(7L), expired)
        }

    @Test
    fun `stale mutation targets use a live canonical channel and invalid targets never save`() =
        runTest {
            var writes = 0
            val settings =
                NotificationSettingsImpl(
                    backgroundScope,
                    AppClock { testScheduler.currentTime },
                    onExpired = {},
                    save = { writes++ },
                    resolveBufferId = { if (it == 7L || it == 8L) 8 else null },
                    serverExists = { it == 1L },
                )
            settings.setChannel(7, NotificationMode.OFF)
            settings.startWatch(7, 1_000)
            assertEquals(mapOf(8L to NotificationMode.OFF), settings.config().channels)
            assertEquals(mapOf(8L to 1_000L), settings.config().watches)
            val before = writes

            assertFalse(settings.startWatch(9, null))
            assertFalse(settings.stopWatch(9))
            assertFalse(settings.setChannel(9, NotificationMode.ALL))
            assertFalse(settings.setServer(2, NotificationMode.ALL))
            assertEquals(before, writes)
            assertTrue(settings.stopWatch(7))
            assertTrue(settings.stopWatch(8))
            assertEquals(emptyMap<Long, Long>(), settings.config().watches)
        }

    @Test
    fun `restoration prunes orphaned rules and deterministically coalesces channels and watches`() =
        runTest {
            val settings =
                NotificationSettingsImpl(
                    backgroundScope,
                    AppClock { testScheduler.currentTime },
                    onExpired = {},
                    load = {
                        preferences(
                            NotificationConfig(
                                servers = mapOf(1L to NotificationMode.ALL, 2L to NotificationMode.OFF),
                                channels = mapOf(8L to NotificationMode.OFF, 7L to NotificationMode.ALL, 9L to NotificationMode.OFF),
                                watches = mapOf(7L to 1_000L, 8L to 500L, 9L to Long.MAX_VALUE),
                            ),
                        )
                    },
                    resolveBufferId = { if (it == 7L || it == 8L) 8 else null },
                    serverExists = { it == 1L },
                )
            runCurrent()

            assertEquals(
                NotificationConfig(
                    servers = mapOf(1L to NotificationMode.ALL),
                    channels = mapOf(8L to NotificationMode.ALL),
                    watches = mapOf(8L to 1_000L),
                ),
                settings.config(),
            )
        }

    @Test
    fun `room observation coalesces lower ID policy and the forever watch then removes deleted entries silently`() =
        runTest {
            val canonical = mutableMapOf(7L to 7L, 8L to 8L, 9L to 9L)
            val observations = canonical.mapValues { MutableStateFlow<Long?>(it.value) }
            val expired = mutableListOf<Long>()
            val settings =
                NotificationSettingsImpl(
                    backgroundScope,
                    AppClock { testScheduler.currentTime },
                    onExpired = { expired += it },
                    resolveBufferId = { canonical[it] },
                    observeBufferId = { observations.getValue(it) },
                )
            settings.setChannel(8, NotificationMode.OFF)
            settings.setChannel(7, NotificationMode.ALL)
            settings.setChannel(9, NotificationMode.MENTIONS)
            settings.startWatch(7, 1_000)
            settings.startWatch(8, null)
            runCurrent()
            canonical[7] = 8
            observations.getValue(7).value = 8
            runCurrent()

            assertEquals(mapOf(8L to NotificationMode.ALL, 9L to NotificationMode.MENTIONS), settings.config().channels)
            assertEquals(mapOf(8L to Long.MAX_VALUE), settings.config().watches)
            canonical.remove(9)
            observations.getValue(9).value = null
            runCurrent()
            assertEquals(mapOf(8L to NotificationMode.ALL), settings.config().channels)
            advanceTimeBy(1_000)
            runCurrent()
            assertTrue(expired.isEmpty())
            canonical.remove(8)
            observations.getValue(8).value = null
            runCurrent()

            assertEquals(NotificationConfig(), settings.config())
            assertTrue(expired.isEmpty())
        }

    @Test
    fun `merge signal replaces an earlier inherited result before observation and before a failed rekey finishes`() =
        runTest {
            var canonicalId = 7L
            val observed = MutableStateFlow<Long?>(7)
            var saveGate: CompletableDeferred<Unit>? = null
            var failSave = false
            var readsAllowed = true
            val expired = mutableListOf<Long>()
            val settings =
                NotificationSettingsImpl(
                    backgroundScope,
                    AppClock { testScheduler.currentTime },
                    onExpired = { expired += it },
                    save = {
                        saveGate?.await()
                        if (failSave) throw IOException("rekey failed")
                    },
                    resolveBufferId = {
                        check(readsAllowed)
                        canonicalId
                    },
                    observeBufferId = { if (it == 7L) observed else flowOf(it) },
                )
            settings.setChannel(7, NotificationMode.OFF)
            settings.startWatch(7, 1_000)
            runCurrent()
            assertEquals(ResolvedNotificationSettings(NotificationMode.MENTIONS, NotificationScope.GLOBAL, null), settings.resolve(1, 8))
            advanceTimeBy(400)
            canonicalId = 8
            failSave = true
            val gate = CompletableDeferred<Unit>()
            saveGate = gate
            val merge = async { settings.onRoomsMerged(8, 7) }
            runCurrent()
            readsAllowed = false
            val expected = ResolvedNotificationSettings(NotificationMode.OFF, NotificationScope.CHANNEL, ChannelWatchState(8, 1_000))

            assertEquals(7L, observed.value)
            assertEquals(expected, settings.resolve(1, 8))
            assertEquals(mapOf(7L to NotificationMode.OFF), settings.config().channels)
            assertEquals(mapOf(7L to 1_000L), settings.config().watches)
            gate.complete(Unit)
            merge.await()
            assertEquals(expected, settings.resolve(1, 8))
            readsAllowed = true
            observed.value = 8
            runCurrent()
            assertEquals(expected, settings.resolve(1, 8))
            failSave = false

            assertTrue(settings.setGlobal(NotificationMode.ALL))
            assertEquals(mapOf(8L to NotificationMode.OFF), settings.config().channels)
            assertEquals(expected, settings.resolve(1, 8))
            advanceTimeBy(600)
            runCurrent()
            assertEquals(listOf(8L), expired)
        }

    @Test
    fun `observer redirect combines a direct finite winner with a forever loser when saving fails`() =
        runTest {
            val canonical = mutableMapOf(7L to 7L, 8L to 8L)
            val observations = canonical.mapValues { MutableStateFlow<Long?>(it.value) }
            var failSave = false
            val settings =
                NotificationSettingsImpl(
                    backgroundScope,
                    AppClock { testScheduler.currentTime },
                    onExpired = {},
                    resolveBufferId = { canonical[it] },
                    observeBufferId = { observations.getValue(it) },
                    save = { if (failSave) throw IOException("rekey failed") },
                )
            settings.setChannel(7, NotificationMode.ALL)
            settings.setChannel(8, NotificationMode.OFF)
            settings.startWatch(7, 1_000)
            settings.startWatch(8, null)
            runCurrent()
            assertEquals(ChannelWatchState(7, 1_000), settings.resolve(1, 7)?.watch)
            failSave = true
            canonical[8] = 7
            observations.getValue(8).value = 7
            runCurrent()

            assertEquals(mapOf(7L to 1_000L, 8L to Long.MAX_VALUE), settings.config().watches)
            assertEquals(
                ResolvedNotificationSettings(NotificationMode.ALL, NotificationScope.CHANNEL, ChannelWatchState(7, Long.MAX_VALUE)),
                settings.resolve(1, 7),
            )
        }

    @Test
    fun `committed merge aliases follow chains and disappear after a successful canonical mutation`() =
        runTest {
            val canonical = mutableMapOf(7L to 7L, 8L to 8L, 9L to 9L)
            var failSave = false
            val settings =
                NotificationSettingsImpl(
                    backgroundScope,
                    AppClock { testScheduler.currentTime },
                    onExpired = {},
                    resolveBufferId = { canonical[it] },
                    save = { if (failSave) throw IOException("rekey failed") },
                )
            settings.setChannel(8, NotificationMode.ALL)
            settings.setChannel(9, NotificationMode.OFF)
            settings.startWatch(8, 1_000)
            settings.startWatch(9, null)
            runCurrent()
            failSave = true
            canonical[9] = 8
            settings.onRoomsMerged(8, 9)
            assertEquals(
                ResolvedNotificationSettings(NotificationMode.ALL, NotificationScope.CHANNEL, ChannelWatchState(8, Long.MAX_VALUE)),
                settings.resolve(1, 8),
            )
            canonical[8] = 7
            canonical[9] = 7
            settings.onRoomsMerged(7, 8)

            assertEquals(mapOf(8L to 1_000L, 9L to Long.MAX_VALUE), settings.config().watches)
            assertEquals(
                ResolvedNotificationSettings(NotificationMode.ALL, NotificationScope.CHANNEL, ChannelWatchState(7, Long.MAX_VALUE)),
                settings.resolve(1, 7),
            )
            failSave = false
            assertTrue(settings.stopWatch(7))
            assertEquals(mapOf(7L to NotificationMode.ALL), settings.config().channels)
            assertEquals(emptyMap<Long, Long>(), settings.config().watches)
            assertEquals(ResolvedNotificationSettings(NotificationMode.ALL, NotificationScope.CHANNEL, null), settings.resolve(1, 7))
            assertEquals(ResolvedNotificationSettings(NotificationMode.MENTIONS, NotificationScope.GLOBAL, null), settings.resolve(1, 8))
        }

    @Test
    fun `network observation removes only orphaned server entries`() =
        runTest {
            val networks = MutableStateFlow(setOf(1L, 2L))
            val settings =
                NotificationSettingsImpl(
                    backgroundScope,
                    AppClock { testScheduler.currentTime },
                    onExpired = {},
                    serverExists = { it in networks.value },
                    observeServerIds = networks,
                )
            settings.setServer(1, NotificationMode.ALL)
            settings.setServer(2, NotificationMode.OFF)
            settings.setChannel(7, NotificationMode.MENTIONS)
            settings.startWatch(7, null)
            runCurrent()
            networks.value = setOf(2)
            runCurrent()

            assertEquals(mapOf(2L to NotificationMode.OFF), settings.config().servers)
            assertEquals(mapOf(7L to NotificationMode.MENTIONS), settings.config().channels)
            assertEquals(mapOf(7L to Long.MAX_VALUE), settings.config().watches)
            assertFalse(settings.setServer(1, NotificationMode.ALL))
        }

    @Test
    fun `mutation cancellation propagates without changing saved state`() =
        runTest {
            var cancelSave = false
            val cancellation = CancellationException("cancelled write")
            val settings =
                NotificationSettingsImpl(
                    backgroundScope,
                    AppClock { testScheduler.currentTime },
                    onExpired = {},
                    save = { if (cancelSave) throw cancellation },
                )
            settings.startWatch(7, null)
            val previous = settings.state.value
            cancelSave = true
            try {
                settings.stopWatch(7)
                fail("Cancellation must propagate")
            } catch (actual: CancellationException) {
                assertSame(cancellation, actual)
            }
            assertEquals(previous, settings.state.value)
        }

    @Test
    fun `cancelled retry propagates and leaves another retry available`() =
        runTest {
            var cancelled = false
            var readable = false
            val settings =
                NotificationSettingsImpl(
                    backgroundScope,
                    AppClock { testScheduler.currentTime },
                    onExpired = {},
                    load = {
                        if (cancelled) throw CancellationException("cancelled read")
                        if (!readable) throw IOException("unreadable")
                        preferences(NotificationConfig(global = NotificationMode.OFF))
                    },
                )
            runCurrent()
            cancelled = true
            try {
                settings.retryLoad()
                fail("Cancellation must propagate")
            } catch (_: CancellationException) {
                assertEquals(NotificationSettingsState.Unavailable, settings.state.value)
            }
            cancelled = false
            readable = true

            assertTrue(settings.retryLoad())
            assertEquals(NotificationMode.OFF, settings.resolve(1, 7)?.mode)
        }

    @Test
    fun `noop resolves mentions without a watch and rejects every write`() =
        runTest {
            val settings = NotificationSettings.Noop
            assertEquals(ResolvedNotificationSettings(NotificationMode.MENTIONS, NotificationScope.GLOBAL, null), settings.resolve(1, 7))
            assertFalse(settings.retryLoad())
            assertFalse(settings.setGlobal(NotificationMode.OFF))
            assertFalse(settings.setServer(1, NotificationMode.ALL))
            assertFalse(settings.setChannel(7, NotificationMode.ALL))
            assertFalse(settings.startWatch(7, null))
            assertFalse(settings.stopWatch(7))
        }

    private fun NotificationSettings.config(): NotificationConfig = (state.value as NotificationSettingsState.Ready).config

    private fun preferences(config: NotificationConfig): Preferences = preferencesOf(CONFIG to Json.encodeToString(config))

    private companion object {
        val CONFIG = stringPreferencesKey("config_v1")
        val BUFFER_ID = longPreferencesKey("buffer_id")
        val EXPIRES_AT = longPreferencesKey("expires_at")
    }
}
