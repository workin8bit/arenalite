package dev.arenalite.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "arenalite-settings")

/**
 * User-facing settings.
 *
 * Deliberately no "API key" field for the end user: the app talks to an operator
 * gateway (your `server/` deployment), which holds the upstream keys. The
 * gateway URL/key below are operator config, set once at install time or by an
 * admin profile — not something a normal user has to obtain.
 */
class SettingsStore(private val context: Context) {

    suspend fun gatewayUrl(): String = read(KEY_GATEWAY_URL, DEFAULT_GATEWAY)
    suspend fun gatewayKey(): String = read(KEY_GATEWAY_KEY, "")
    suspend fun providerKind(): String = read(KEY_PROVIDER_KIND, "anthropic")
    suspend fun model(): String = read(KEY_MODEL, "arena-agent-1")
    suspend fun remoteRoot(): String? = read(KEY_REMOTE_ROOT, "").ifBlank { null }
    suspend fun autoSync(): Boolean = read(KEY_AUTO_SYNC, "true") == "true"

    suspend fun setGatewayUrl(value: String) = write(KEY_GATEWAY_URL, value)
    suspend fun setGatewayKey(value: String) = write(KEY_GATEWAY_KEY, value)
    suspend fun setProviderKind(value: String) = write(KEY_PROVIDER_KIND, value)
    suspend fun setModel(value: String) = write(KEY_MODEL, value)
    suspend fun setRemoteRoot(value: String) = write(KEY_REMOTE_ROOT, value)
    suspend fun setAutoSync(value: Boolean) = write(KEY_AUTO_SYNC, value.toString())

    fun observeModel() = context.dataStore.data.map { it[KEY_MODEL] ?: "arena-agent-1" }

    private suspend fun read(key: Preferences.Key<String>, default: String): String =
        context.dataStore.data.map { it[key] ?: default }.first()

    private suspend fun write(key: Preferences.Key<String>, value: String) {
        context.dataStore.edit { it[key] = value }
    }

    companion object {
        private val KEY_GATEWAY_URL = stringPreferencesKey("gateway_url")
        private val KEY_GATEWAY_KEY = stringPreferencesKey("gateway_key")
        private val KEY_PROVIDER_KIND = stringPreferencesKey("provider_kind")
        private val KEY_MODEL = stringPreferencesKey("model")
        private val KEY_REMOTE_ROOT = stringPreferencesKey("remote_root")
        private val KEY_AUTO_SYNC = stringPreferencesKey("auto_sync")

        /** Blank by default → the app runs on the offline MockProvider. */
        const val DEFAULT_GATEWAY = ""

        val MODELS = listOf(
            "arena-agent-1" to "Arena Agent 1",
            "arena-agent-1-mini" to "Arena Agent 1 mini",
            "arena-fast" to "Arena Fast",
        )
    }
}
