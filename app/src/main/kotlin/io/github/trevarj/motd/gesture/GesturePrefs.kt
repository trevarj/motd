package io.github.trevarj.motd.gesture

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.trevarj.motd.gesture.radial.OrbPlacement
import io.github.trevarj.motd.gesture.radial.decodeOrbPlacement
import io.github.trevarj.motd.gesture.radial.encodeOrbPlacement
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Preferences for the experimental gesture lab (radial orb menu).
 *
 * The enabled flag lives in its own store and is deliberately kept out of configuration backups, so
 * restoring normal settings on another device can never switch the lab on. The [menu] is the
 * opposite case: it is authored work, it does nothing while the lab is off, and it *is* carried in
 * backups.
 */
interface GesturePrefs {
    /** Whether the gesture orb lab is switched on. Defaults to false. */
    val enabled: Flow<Boolean>

    suspend fun setEnabled(enabled: Boolean)

    /** The user's menu graph, or the built-in default when nothing readable is stored. */
    val menu: Flow<GestureMenuConfig>

    /**
     * Store [config]. A config equal to the default clears the stored menu instead of pinning it, so
     * a user who never edited (or who reset) keeps following the built-in tree as it evolves.
     */
    suspend fun setMenu(config: GestureMenuConfig)

    /** Read-modify-write of the stored menu in one edit, so concurrent saves cannot lose each other. */
    suspend fun replaceMenu(transform: (GestureMenuConfig) -> GestureMenuConfig)

    /** Where the resting orb tab sits. Device-local layout, so it stays out of backups too. */
    val orb: Flow<OrbPlacement>

    suspend fun setOrb(placement: OrbPlacement)
}

// Own store, isolated from Settings exports so restoring configuration cannot enable this lab.
private val Context.gestureDataStore by preferencesDataStore("gesture_labs")
private val ENABLED = booleanPreferencesKey("enabled_v1")
private val MENU = stringPreferencesKey("menu_v1")
private val ORB = stringPreferencesKey("orb_v1")

@Singleton
class GesturePrefsImpl
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : GesturePrefs {
        private val store = context.gestureDataStore

        override val enabled: Flow<Boolean> = store.data.map { it[ENABLED] ?: false }

        override suspend fun setEnabled(enabled: Boolean) {
            store.edit { it[ENABLED] = enabled }
        }

        // A menu that cannot be decoded falls back to the default rather than leaving the orb dead.
        override val menu: Flow<GestureMenuConfig> = store.data.map { decodeGestureMenu(it[MENU]) }

        override suspend fun setMenu(config: GestureMenuConfig) {
            store.edit { prefs ->
                if (config == GestureMenuConfig()) prefs.remove(MENU) else prefs[MENU] = encodeGestureMenu(config)
            }
        }

        override suspend fun replaceMenu(transform: (GestureMenuConfig) -> GestureMenuConfig) {
            store.edit { prefs ->
                val next = transform(decodeGestureMenu(prefs[MENU]))
                if (next == GestureMenuConfig()) prefs.remove(MENU) else prefs[MENU] = encodeGestureMenu(next)
            }
        }

        override val orb: Flow<OrbPlacement> = store.data.map { decodeOrbPlacement(it[ORB]) }

        override suspend fun setOrb(placement: OrbPlacement) {
            store.edit { it[ORB] = encodeOrbPlacement(placement) }
        }
    }
