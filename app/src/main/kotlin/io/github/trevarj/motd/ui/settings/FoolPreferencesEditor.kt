package io.github.trevarj.motd.ui.settings

import io.github.trevarj.motd.data.prefs.FoolPreferences
import io.github.trevarj.motd.data.prefs.FoolsMode
import io.github.trevarj.motd.data.prefs.SettingsRepository
import io.github.trevarj.motd.data.prefs.merge
import io.github.trevarj.motd.data.prefs.normalizeNick
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Field edits read after the preceding write commits, never from a stale sheet composition. */
internal class FoolPreferencesEditor(
    private val settingsRepository: SettingsRepository,
) {
    private val mutex = Mutex()

    suspend fun update(
        nick: String,
        identityRules: IrcIdentityRules? = null,
        transform: (FoolPreferences) -> FoolPreferences,
    ) = mutex.withLock {
        val settings = settingsRepository.settings.first()
        val current =
            if (identityRules == null) {
                val key = normalizeNick(nick)
                if (key in settings.fools) settings.foolPreferences[key] ?: FoolPreferences() else null
            } else {
                foolEditorPreferences(nick, settings.foolsMode, settings.fools, settings.foolPreferences, identityRules)
            } ?: return@withLock
        settingsRepository.setFoolPreferences(nick, transform(current), identityRules)
    }
}

/** Profiles edit the configured equivalents together, with the same conservative merge as policy. */
internal fun foolEditorPreferences(
    nick: String,
    globalMode: FoolsMode,
    fools: Set<String>,
    preferences: Map<String, FoolPreferences>,
    identityRules: IrcIdentityRules,
): FoolPreferences? {
    val actor = identityRules.normalize(nick.trim())
    var result: FoolPreferences? = null
    var inherited = true
    for (configured in fools) {
        if (identityRules.normalize(configured.trim()) != actor) continue
        val saved = preferences[configured] ?: FoolPreferences()
        inherited = inherited && saved.visibility == null
        result = result?.merge(saved, globalMode) ?: saved
    }
    return result?.let { if (inherited) it.copy(visibility = null) else it }
}
