package io.github.trevarj.motd.ui.nav

import android.content.Context
import android.os.Bundle
import android.os.Parcel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.composable
import androidx.navigation.createGraph
import androidx.navigation.toRoute
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.reflect.typeOf

@RunWith(RobolectricTestRunner::class)
class RoutesTest {
    @Test
    fun labsRestoresUnknownTargetsWithoutLosingKnownTargets() {
        fun controller() =
            NavHostController(ApplicationProvider.getApplicationContext<Context>()).apply {
                val owner =
                    object : LifecycleOwner {
                        override val lifecycle = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
                    }
                setLifecycleOwner(owner)
                setViewModelStore(ViewModelStore())
                navigatorProvider.addNavigator(ComposeNavigator())
            }

        fun NavHostController.installGraph() {
            graph =
                createGraph(startDestination = ChatListRoute) {
                    composable<ChatListRoute> {}
                    composable<LabsRoute>(typeMap = mapOf(typeOf<SettingsTarget?>() to SettingsTargetNavType)) {}
                }
        }

        for (target in listOf("AGENTWIRE", "unknown-future-target", "DICKORD", "null")) {
            val original = controller().apply { installGraph() }
            original.navigate("${LabsRoute::class.qualifiedName}?target=$target")
            val restored =
                controller().apply {
                    restoreState(original.saveState())
                    installGraph()
                }
            assertEquals(
                if (target == "DICKORD") SettingsTarget.DICKORD else null,
                restored.currentBackStackEntry!!.toRoute<LabsRoute>().target,
            )
            restored.navigate(LabsRoute(SettingsTarget.GESTURES))
            assertEquals(SettingsTarget.GESTURES, restored.currentBackStackEntry!!.toRoute<LabsRoute>().target)
        }
    }

    @Test
    fun savedTargetsAcceptLegacyEnumsAndStoreNames() {
        val saved = Bundle().apply { putSerializable("target", SettingsTarget.DICKORD) }
        assertEquals(SettingsTarget.DICKORD, SettingsTargetNavType[saved, "target"])
        SettingsTargetNavType.put(saved, "target", SettingsTarget.GESTURES)
        assertEquals("GESTURES", saved.getString("target"))
        SettingsTargetNavType.put(saved, "target", null)
        assertNull(SettingsTargetNavType[saved, "target"])
    }

    @Test
    fun removedSerializedEnumTargetFallsBackToNoAnchor() {
        val output = Parcel.obtain()
        val bytes =
            try {
                output.writeBundle(Bundle().apply { putSerializable("target", SettingsTarget.DICKORD) })
                output.marshall()
            } finally {
                output.recycle()
            }
        // Replace only the serialized constant, retaining a valid enum stream and bundle layout.
        val old = "DICKORD".encodeToByteArray()
        val offset = (0..bytes.size - old.size).single { start -> old.indices.all { bytes[start + it] == old[it] } }
        "REMOVED".encodeToByteArray().copyInto(bytes, offset)
        val input = Parcel.obtain()
        try {
            input.unmarshall(bytes, 0, bytes.size)
            input.setDataPosition(0)
            val saved = requireNotNull(input.readBundle(SettingsTarget::class.java.classLoader))
            assertNull(SettingsTargetNavType[saved, "target"])
        } finally {
            input.recycle()
        }
    }
}
