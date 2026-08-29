package io.github.trevarj.motd.sidecar

import android.content.ComponentName
import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

private val Context.sidecarDataStore by preferencesDataStore("sidecar_labs")
private val ENABLED = booleanPreferencesKey("enabled_v1")

interface SidecarPrefs {
    val enabled: Flow<Boolean>

    suspend fun setEnabled(enabled: Boolean)
}

@Singleton
class SidecarPrefsImpl
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : SidecarPrefs {
        private val store = context.sidecarDataStore

        override val enabled: Flow<Boolean> = store.data.map { it[ENABLED] ?: false }

        override suspend fun setEnabled(enabled: Boolean) {
            store.edit { it[ENABLED] = enabled }
        }
    }

object DisabledSidecarPrefs : SidecarPrefs {
    override val enabled: Flow<Boolean> = kotlinx.coroutines.flow.flowOf(false)

    override suspend fun setEnabled(enabled: Boolean) = Unit
}

interface SidecarTrustStore {
    suspend fun pinnedSigner(component: ComponentName): String?

    suspend fun trust(
        component: ComponentName,
        signerSha256: String,
    )

    suspend fun revoke(component: ComponentName)
}

@Singleton
class SidecarTrustStoreImpl
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : SidecarTrustStore {
        private val store = context.sidecarDataStore

        override suspend fun pinnedSigner(component: ComponentName): String? = store.data.first()[key(component)]

        override suspend fun trust(
            component: ComponentName,
            signerSha256: String,
        ) {
            require(signerSha256.matches(Regex("[0-9a-f]{64}"))) { "invalid signer digest" }
            store.edit { it[key(component)] = signerSha256 }
        }

        override suspend fun revoke(component: ComponentName) {
            store.edit { it.remove(key(component)) }
        }

        private fun key(component: ComponentName) = stringPreferencesKey("trust_${component.flattenToString().sha256()}")

        private fun String.sha256(): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest(toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
