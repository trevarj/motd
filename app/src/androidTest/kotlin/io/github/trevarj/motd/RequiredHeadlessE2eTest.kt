package io.github.trevarj.motd

import android.Manifest
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.e2e.BootstrappedNetwork
import io.github.trevarj.motd.e2e.BufferProbe
import io.github.trevarj.motd.e2e.ConnectionProbe
import io.github.trevarj.motd.e2e.E2eBootstrap
import io.github.trevarj.motd.e2e.E2eFailureArtifactRule
import io.github.trevarj.motd.e2e.E2eMilestoneRecorder
import io.github.trevarj.motd.e2e.MessageLifecycleProbe
import io.github.trevarj.motd.e2e.ScenarioHolder
import io.github.trevarj.motd.e2e.robots.BouncerRobot
import io.github.trevarj.motd.e2e.robots.ChatListRobot
import io.github.trevarj.motd.e2e.robots.ChatRobot
import io.github.trevarj.motd.e2e.robots.OnboardingRobot
import io.github.trevarj.motd.e2e.robots.SettingsRobot
import io.github.trevarj.motd.e2e.robots.ThemeSheetRobot
import io.github.trevarj.motd.e2e.robots.TimelineRobot
import io.github.trevarj.motd.e2e.robots.NetworksRobot
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** Marks the real-stack, isolated journeys required by the headless API34 gate. */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class FastHeadlessE2e

@RunWith(AndroidJUnit4::class)
@FastHeadlessE2e
class RequiredHeadlessE2eTest {
    private val milestones = E2eMilestoneRecorder()
    private val scenario = ScenarioHolder()
    private val artifacts = E2eFailureArtifactRule(scenario, milestones)
    private val compose = createEmptyComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain
        .outerRule(GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS))
        .around(compose)
        .around(artifacts)

    private fun launchBootstrapped(requiredCaps: Set<String> = emptySet()): Pair<E2eBootstrap, BootstrappedNetwork> {
        val bootstrap = E2eBootstrap.fromApplication(InstrumentationRegistry.getInstrumentation().targetContext)
        val network = runBlocking { bootstrap.connectedSojuNetwork() }
        val probe = ConnectionProbe(bootstrap.seams.connections(), milestones)
        runBlocking {
            probe.awaitReady(network.rootId, emptySet())
            probe.awaitReady(network.childId, requiredCaps)
        }
        scenario.launch()
        return bootstrap to network
    }

    @Test
    fun onboardingTrustsEphemeralTlsAndImportsNetwork() {
        val bootstrap = E2eBootstrap.fromApplication(InstrumentationRegistry.getInstrumentation().targetContext)
        scenario.launch()
        OnboardingRobot(compose).importSoju(bootstrap.args)
        val rows = runBlocking { bootstrap.seams.networks().observeNetworks().first() }
        val root = rows.single { it.role == NetworkRole.BOUNCER_ROOT }
        val child = rows.single {
            it.role == NetworkRole.BOUNCER_CHILD && it.parentId == root.id &&
                it.name == "libera" && !it.bouncerNetId.isNullOrBlank()
        }
        runBlocking { ConnectionProbe(bootstrap.seams.connections(), milestones).awaitReady(child.id, emptySet()) }
        assertTrue(runBlocking { bootstrap.seams.certTrust().isPinned(bootstrap.args.host, bootstrap.args.port, bootstrap.args.fingerprint) })
        compose.onAllNodesWithTag("cert_trust_dialog", useUnmergedTree = true).assertCountEquals(0)
        milestones.record("onboarding_imported", "root=${root.id} child=${child.id}")
    }

    @Test
    fun sendEchoPersistsVisibleRowAndReconnects() {
        val (bootstrap, network) = launchBootstrapped(setOf("echo-message", "message-tags"))
        val bufferId = runBlocking { BufferProbe(bootstrap.seams.buffers(), milestones).awaitJoinedChannel(network.childId, bootstrap.args.channel) }
        ChatListRobot(compose).open(bufferId)
        val token = "required${bootstrap.args.runId.filter(Char::isLetterOrDigit).takeLast(16)}"
        val probe = MessageLifecycleProbe(bootstrap.seams.search(), milestones)
        val canonical = runBlocking {
            coroutineScope {
                val observed = async(start = CoroutineStart.UNDISPATCHED) { probe.awaitCanonical(token, bufferId) }
                ChatRobot(compose).send(token)
                observed.await()
            }
        }
        TimelineRobot(compose).assertMessage(token)
        runBlocking {
            bootstrap.seams.connections().disconnect(network.childId)
            bootstrap.seams.connections().connect(network.childId)
            ConnectionProbe(bootstrap.seams.connections(), milestones).awaitReady(network.childId, setOf("echo-message", "message-tags"))
        }
        val after = runBlocking { probe.awaitCanonical(token, bufferId) }
        assertEquals(canonical.id, after.id)
        TimelineRobot(compose).assertMessage(token)
    }

    @Test
    fun bootstrappedNavigationSettingsAndBouncerSmoke() {
        val (bootstrap, network) = launchBootstrapped()
        SettingsRobot(compose).apply {
            open()
            appearance()
        }
        ThemeSheetRobot(compose).selectAyuDarkAndTrueBlack()
        scenario.scenario?.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onAllNodesWithTag("settings_theme_sheet", useUnmergedTree = true).assertCountEquals(0)
        // Return from Appearance to Settings, then exercise the category and bouncer routes.
        SettingsRobot(compose).apply {
            returnToRoot()
            chat()
            assertDisplayed("settings_switch_show_jpq")
            returnToRoot()
            networks()
        }
        NetworksRobot(compose).openRoot(network.rootId)
        BouncerRobot(compose).assertPanels()
        milestones.record("settings_bouncer_smoke", "root=${network.rootId}")
    }
}
