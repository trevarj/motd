package io.github.trevarj.motd.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.onboardingDataStore by preferencesDataStore("onboarding")
private val COMPLETED = booleanPreferencesKey("completed_v1")
private val COMMUNITY_INVITATION_HANDLED = booleanPreferencesKey("community_invitation_handled")

interface OnboardingPrefs {
    val completed: Flow<Boolean>
    val communityInvitationHandled: Flow<Boolean>

    suspend fun markCompleted()

    suspend fun markCommunityInvitationHandled()
}

@Singleton
class OnboardingPrefsImpl
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : OnboardingPrefs {
        private val store = context.onboardingDataStore

        override val completed: Flow<Boolean> =
            store.data.map { prefs ->
                prefs[COMPLETED] ?: false
            }

        override val communityInvitationHandled: Flow<Boolean> =
            store.data.map { prefs ->
                prefs[COMMUNITY_INVITATION_HANDLED] ?: false
            }

        override suspend fun markCompleted() {
            store.edit { prefs -> prefs[COMPLETED] = true }
        }

        override suspend fun markCommunityInvitationHandled() {
            store.edit { prefs -> prefs[COMMUNITY_INVITATION_HANDLED] = true }
        }
    }
