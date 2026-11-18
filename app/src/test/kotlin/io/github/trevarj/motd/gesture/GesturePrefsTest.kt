package io.github.trevarj.motd.gesture

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GesturePrefsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs: GesturePrefs = GesturePrefsImpl(context)

    @Test fun defaultsToOff() =
        runTest {
            assertEquals(false, prefs.enabled.first())
        }

    @Test fun enabledFlag_roundTrips() =
        runTest {
            prefs.setEnabled(true)
            assertEquals(true, prefs.enabled.first())
            prefs.setEnabled(false)
            assertEquals(false, prefs.enabled.first())
        }

    @Test fun menuDefaultsToTheShippedTree() =
        runTest {
            assertEquals(GestureMenuConfig(), prefs.menu.first())
        }

    @Test fun menuRoundTripsIncludingNodesThisBuildCannotRead() =
        runTest {
            val unknown =
                GestureNode.Unknown(
                    JsonObject(mapOf("type" to JsonPrimitive("hologram"), "id" to JsonPrimitive("future"))),
                )
            val edited =
                GestureMenuConfig()
                    .updateNode("default-away") { (it as GestureNode.Leaf).copy(label = "Step out") }
                    .addChild("default-tools", unknown)

            prefs.setMenu(edited)

            assertEquals(edited, prefs.menu.first())
            prefs.setMenu(GestureMenuConfig())
        }

    /**
     * Storing the default clears the key instead of pinning it, so a user who never edited (or who
     * reset) keeps following the built-in tree as later releases change it.
     */
    @Test fun storingTheDefaultGoesBackToTheShippedTree() =
        runTest {
            prefs.setMenu(GestureMenuConfig().updateNode("default-away") { (it as GestureNode.Leaf).copy(label = "X") })
            prefs.setMenu(GestureMenuConfig())

            assertEquals(GestureMenuConfig(), prefs.menu.first())
        }

    @Test fun replaceMenuAppliesOnTopOfWhatIsStored() =
        runTest {
            prefs.setMenu(GestureMenuConfig().updateNode("default-away") { (it as GestureNode.Leaf).copy(label = "Out") })
            prefs.replaceMenu { it.removeNode("default-networks") }

            val stored = prefs.menu.first()
            assertEquals("Out", stored.findNode("default-away")?.label)
            assertEquals(null, stored.findNode("default-networks"))
            prefs.setMenu(GestureMenuConfig())
        }
}
