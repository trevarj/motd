package io.github.trevarj.motd.appearance

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.prefs.LauncherIcon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

class LauncherIconControllerTest {
    @Test fun aliasFqcn_covers_all_seven_variants_under_the_stable_namespace() {
        assertEquals("io.github.trevarj.motd.LauncherDefault", launcherAliasFqcn(LauncherIcon.DEFAULT))
        assertEquals("io.github.trevarj.motd.LauncherMono", launcherAliasFqcn(LauncherIcon.MONO))
        assertEquals("io.github.trevarj.motd.LauncherTerminal", launcherAliasFqcn(LauncherIcon.TERMINAL))
        assertEquals("io.github.trevarj.motd.LauncherGruvbox", launcherAliasFqcn(LauncherIcon.GRUVBOX))
        assertEquals("io.github.trevarj.motd.LauncherCatppuccin", launcherAliasFqcn(LauncherIcon.CATPPUCCIN))
        assertEquals("io.github.trevarj.motd.LauncherNord", launcherAliasFqcn(LauncherIcon.NORD))
        assertEquals("io.github.trevarj.motd.LauncherLight", launcherAliasFqcn(LauncherIcon.LIGHT))
    }

    @Test fun aliasFqcn_is_distinct_per_variant() {
        val fqcns = LauncherIcon.entries.map(::launcherAliasFqcn)
        assertEquals(fqcns.size, fqcns.toSet().size)
    }

    @Test fun componentPlan_enables_the_target_and_disables_every_other_alias_exactly_once() {
        val (enable, disable) = launcherComponentPlan(LauncherIcon.NORD, LauncherIcon.entries)
        assertEquals(launcherAliasFqcn(LauncherIcon.NORD), enable)
        assertFalse(disable.contains(enable))
        assertEquals(LauncherIcon.entries.size - 1, disable.size)
        assertEquals(disable.size, disable.toSet().size) // no duplicates
        val expectedDisabled =
            LauncherIcon.entries
                .filter { it != LauncherIcon.NORD }
                .map(::launcherAliasFqcn)
                .toSet()
        assertEquals(expectedDisabled, disable.toSet())
    }

    @Test fun componentPlan_for_teal_disables_all_six_other_aliases() {
        val (enable, disable) = launcherComponentPlan(LauncherIcon.DEFAULT, LauncherIcon.entries)
        assertEquals(launcherAliasFqcn(LauncherIcon.DEFAULT), enable)
        assertEquals(6, disable.size)
        assertFalse(disable.contains(launcherAliasFqcn(LauncherIcon.DEFAULT)))
    }
}

@RunWith(RobolectricTestRunner::class)
class LauncherIconControllerApplyTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val packageManager: PackageManager get() = context.packageManager
    private val controller by lazy { LauncherIconController(context) }

    private fun componentFor(icon: LauncherIcon) = ComponentName(context.packageName, launcherAliasFqcn(icon))

    private fun launcherActivities() =
        packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName),
            0,
        )

    @Test fun fresh_install_uses_mono_for_the_launcher_and_application_icon() {
        val activity = launcherActivities().single().activityInfo
        assertEquals(launcherAliasFqcn(LauncherIcon.MONO), activity.name)
        val monoIcons = setOf(R.mipmap.ic_launcher_mono, R.mipmap.ic_launcher_mono_round)
        assertTrue(activity.icon in monoIcons)
        assertTrue(context.applicationInfo.icon in monoIcons)
    }

    @Test fun apply_enables_only_the_selected_alias() {
        controller.apply(LauncherIcon.GRUVBOX)

        assertEquals(launcherAliasFqcn(LauncherIcon.GRUVBOX), launcherActivities().single().activityInfo.name)
    }

    @Test fun apply_switching_variants_disables_the_previous_alias() {
        controller.apply(LauncherIcon.NORD)
        assertEquals(launcherAliasFqcn(LauncherIcon.NORD), launcherActivities().single().activityInfo.name)

        controller.apply(LauncherIcon.LIGHT)
        assertEquals(launcherAliasFqcn(LauncherIcon.LIGHT), launcherActivities().single().activityInfo.name)
    }

    @Test fun apply_mono_reconciles_legacy_component_overrides() {
        packageManager.setComponentEnabledSetting(
            componentFor(LauncherIcon.DEFAULT),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )
        packageManager.setComponentEnabledSetting(
            componentFor(LauncherIcon.MONO),
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )

        controller.apply(LauncherIcon.MONO)

        assertEquals(launcherAliasFqcn(LauncherIcon.MONO), launcherActivities().single().activityInfo.name)
    }

    @Test fun apply_teal_enables_the_legacy_default_alias_instead_of_mono() {
        controller.apply(LauncherIcon.DEFAULT)

        val activity = launcherActivities().single().activityInfo
        assertEquals(launcherAliasFqcn(LauncherIcon.DEFAULT), activity.name)
        assertTrue(activity.icon in setOf(R.mipmap.ic_launcher, R.mipmap.ic_launcher_round))
    }

    @Test fun apply_does_not_override_mono_manifest_default_when_already_selected() {
        controller.apply(LauncherIcon.MONO)

        assertEquals(
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
            packageManager.getComponentEnabledSetting(componentFor(LauncherIcon.MONO)),
        )
    }
}
