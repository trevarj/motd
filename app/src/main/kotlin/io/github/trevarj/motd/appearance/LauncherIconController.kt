package io.github.trevarj.motd.appearance

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.trevarj.motd.data.prefs.LauncherIcon
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Namespace-stable class name for the `activity-alias` that carries [icon]'s MAIN/LAUNCHER
 * filter in AndroidManifest.xml. Package-qualify at the call site with the running process's
 * `packageName` (never this literal namespace) so `.debug`/`.e2e` applicationIdSuffix builds
 * resolve their own alias rather than the release one.
 */
internal fun launcherAliasFqcn(icon: LauncherIcon): String {
    val simpleName =
        when (icon) {
            LauncherIcon.DEFAULT -> "LauncherDefault"
            LauncherIcon.MONO -> "LauncherMono"
            LauncherIcon.TERMINAL -> "LauncherTerminal"
            LauncherIcon.GRUVBOX -> "LauncherGruvbox"
            LauncherIcon.CATPPUCCIN -> "LauncherCatppuccin"
            LauncherIcon.NORD -> "LauncherNord"
            LauncherIcon.LIGHT -> "LauncherLight"
        }
    return "io.github.trevarj.motd.$simpleName"
}

/**
 * The alias to enable for [target] and every other alias in [all] that must end up disabled.
 * Pure and Context-free so the enable/disable set (and that [target] is excluded from its own
 * disable list) is unit-testable without Robolectric.
 */
internal fun launcherComponentPlan(
    target: LauncherIcon,
    all: List<LauncherIcon>,
): Pair<String, List<String>> {
    val toEnable = launcherAliasFqcn(target)
    val toDisable = all.filter { it != target }.map(::launcherAliasFqcn)
    return toEnable to toDisable
}

/**
 * Switches the enabled `activity-alias` to match [io.github.trevarj.motd.data.prefs.AppearanceConfig.launcherIcon]
 * so the home-screen icon and label reflect the user's chosen variant.
 *
 * Exactly one alias is ever left enabled. `.MainActivity` itself stays exported for the SEND
 * share target and explicit-component launches (notifications, E2E `am start`); none of that is
 * affected by which alias currently owns the MAIN/LAUNCHER filter.
 */
@Singleton
class LauncherIconController
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        fun apply(icon: LauncherIcon) {
            val packageManager = context.packageManager
            val (enableFqcn, disableFqcns) = launcherComponentPlan(icon, LauncherIcon.entries)

            setEnabledIfChanged(
                packageManager = packageManager,
                fqcn = enableFqcn,
                enabled = true,
            )
            disableFqcns.forEach { fqcn ->
                setEnabledIfChanged(
                    packageManager = packageManager,
                    fqcn = fqcn,
                    enabled = false,
                )
            }
        }

        /**
         * Applies the target state only when it differs from the component's current state, so a
         * repeat call (e.g. process restart re-collecting the same preference) never trips the
         * launcher-icon refresh some home-screen implementations perform on every
         * `setComponentEnabledSetting` call.
         *
         * The Mono alias ships `android:enabled="true"` in the manifest, so its unset
         * ([PackageManager.COMPONENT_ENABLED_STATE_DEFAULT]) state counts as enabled; every other
         * alias ships `android:enabled="false"`, so unset counts as disabled for them.
         */
        private fun setEnabledIfChanged(
            packageManager: PackageManager,
            fqcn: String,
            enabled: Boolean,
        ) {
            val component = ComponentName(context.packageName, fqcn)
            val manifestDefaultEnabled = fqcn == launcherAliasFqcn(LauncherIcon.MONO)
            val currentlyEnabled =
                when (packageManager.getComponentEnabledSetting(component)) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                    PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> manifestDefaultEnabled
                    else -> false
                }
            if (currentlyEnabled == enabled) return
            val state =
                if (enabled) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                } else {
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                }
            packageManager.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
        }
    }
