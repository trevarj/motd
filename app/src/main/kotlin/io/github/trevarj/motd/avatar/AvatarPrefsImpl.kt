package io.github.trevarj.motd.avatar

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.avatarPreferences by preferencesDataStore("avatar_preferences")
private val SHOW_SHARED = booleanPreferencesKey("show_shared_avatars")

@Singleton
class AvatarPrefsImpl @Inject constructor(
    @ApplicationContext context: Context,
) : AvatarPrefs {
    private val store = context.avatarPreferences

    override val config: Flow<AvatarConfig> = store.data.map { prefs ->
        AvatarConfig(showSharedAvatars = prefs[SHOW_SHARED] ?: true)
    }

    override fun selfSetting(networkId: Long): Flow<SelfAvatarSetting> = store.data.map { prefs ->
        decodeSelfSetting(prefs[selfKey(networkId)])
    }

    override suspend fun setShowSharedAvatars(show: Boolean) {
        store.edit { it[SHOW_SHARED] = show }
    }

    override suspend fun setSelfSetting(networkId: Long, setting: SelfAvatarSetting) {
        store.edit { it[selfKey(networkId)] = encodeSelfSetting(setting) }
    }
}

private fun selfKey(networkId: Long) = stringPreferencesKey("self_avatar_$networkId")

internal fun encodeSelfSetting(setting: SelfAvatarSetting): String = when (setting) {
    SelfAvatarSetting.Unmanaged -> "unmanaged"
    SelfAvatarSetting.ExplicitlyCleared -> "cleared"
    is SelfAvatarSetting.Set -> "set:${setting.url}"
}

internal fun decodeSelfSetting(value: String?): SelfAvatarSetting = when {
    value == "cleared" -> SelfAvatarSetting.ExplicitlyCleared
    value?.startsWith("set:") == true -> SelfAvatarSetting.Set(value.removePrefix("set:"))
    else -> SelfAvatarSetting.Unmanaged
}
