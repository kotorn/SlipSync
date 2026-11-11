package com.thaweekarn.slipsync.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.thaweekarn.slipsync.util.CryptoManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject
import java.io.IOException

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "slip_preferences")

class SlipPreferencesRepository(private val context: Context) {

    private object PreferencesKeys {
        val WEB_APP_URL = stringPreferencesKey("web_app_url")
        val LEGACY_AUTH_TOKEN = stringPreferencesKey("auth_token")
        val ENCRYPTED_AUTH_TOKEN = stringPreferencesKey("encrypted_auth_token")
        val WIFI_ONLY = booleanPreferencesKey("wifi_only")
        val SELECTED_FOLDERS = stringSetPreferencesKey("selected_folders")
        val FOLDER_ACTIVATIONS_JSON = stringPreferencesKey("folder_activations_json")
        val BACKFILL_DAYS = intPreferencesKey("backfill_days")
        
        // Granular health & sync tracking
        val LAST_SYNC_TIMESTAMP = longPreferencesKey("last_sync_timestamp")
        val LAST_SUCCESSFUL_RUN = longPreferencesKey("last_successful_run")
        val LAST_SCAN_TIMESTAMP = longPreferencesKey("last_scan_timestamp")
        val LAST_SUCCESSFUL_UPLOAD = longPreferencesKey("last_successful_upload")
        val LAST_SERVER_ERROR = stringPreferencesKey("last_server_error")
        val LAST_SERVER_STATUS_CODE = intPreferencesKey("last_server_status_code")

        val PAUSE_UNTIL_TIMESTAMP = longPreferencesKey("pause_until_timestamp")
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val USER_CONFIRMED_AUTOSTART = booleanPreferencesKey("user_confirmed_autostart")
        val USER_CONFIRMED_BATTERY = booleanPreferencesKey("user_confirmed_battery")
        val APP_LANGUAGE = stringPreferencesKey("app_language")
    }

    private val dataStore = context.dataStore

    val webAppUrl: Flow<String> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[PreferencesKeys.WEB_APP_URL] ?: "" }

    /**
     * Retrieves decrypted token from Keystore storage with transparent migration from plain text.
     */
    val authToken: Flow<String> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs ->
            val encrypted = prefs[PreferencesKeys.ENCRYPTED_AUTH_TOKEN]
            if (!encrypted.isNullOrBlank()) {
                val decrypted = CryptoManager.decrypt(encrypted)
                if (decrypted.isNotBlank()) decrypted else ""
            } else {
                // Check legacy plain token for migration
                val legacy = prefs[PreferencesKeys.LEGACY_AUTH_TOKEN] ?: ""
                if (legacy.isNotBlank()) {
                    // Transparently trigger migration asynchronously
                    migrateLegacyToken(legacy)
                    legacy
                } else ""
            }
        }

    val wifiOnly: Flow<Boolean> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[PreferencesKeys.WIFI_ONLY] ?: false }

    val selectedFolders: Flow<Set<String>> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[PreferencesKeys.SELECTED_FOLDERS] ?: emptySet() }

    val backfillDays: Flow<Int> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[PreferencesKeys.BACKFILL_DAYS] ?: 7 }

    val lastSyncTimestamp: Flow<Long> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[PreferencesKeys.LAST_SYNC_TIMESTAMP] ?: 0L }

    val lastSuccessfulRun: Flow<Long> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[PreferencesKeys.LAST_SUCCESSFUL_RUN] ?: 0L }

    val lastScanTimestamp: Flow<Long> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[PreferencesKeys.LAST_SCAN_TIMESTAMP] ?: 0L }

    val lastSuccessfulUpload: Flow<Long> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[PreferencesKeys.LAST_SUCCESSFUL_UPLOAD] ?: 0L }

    val lastServerError: Flow<String> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[PreferencesKeys.LAST_SERVER_ERROR] ?: "" }

    val pauseUntilTimestamp: Flow<Long> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[PreferencesKeys.PAUSE_UNTIL_TIMESTAMP] ?: 0L }

    val onboardingCompleted: Flow<Boolean> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[PreferencesKeys.ONBOARDING_COMPLETED] ?: false }

    val userConfirmedAutostart: Flow<Boolean> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[PreferencesKeys.USER_CONFIRMED_AUTOSTART] ?: false }

    val userConfirmedBattery: Flow<Boolean> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[PreferencesKeys.USER_CONFIRMED_BATTERY] ?: false }

    val appLanguage: Flow<String> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[PreferencesKeys.APP_LANGUAGE] ?: "th" }

    suspend fun setWebAppUrl(url: String) {
        dataStore.edit { it[PreferencesKeys.WEB_APP_URL] = url.trim() }
    }

    /**
     * Encrypts and securely stores auth token via Keystore.
     */
    suspend fun setAuthToken(token: String) {
        val trimmed = token.trim()
        val encrypted = if (trimmed.isNotBlank()) CryptoManager.encrypt(trimmed) else ""
        dataStore.edit { prefs ->
            prefs[PreferencesKeys.ENCRYPTED_AUTH_TOKEN] = encrypted
            // Clean legacy plaintext token
            prefs.remove(PreferencesKeys.LEGACY_AUTH_TOKEN)
        }
    }

    private suspend fun migrateLegacyToken(legacyToken: String) {
        try {
            val encrypted = CryptoManager.encrypt(legacyToken)
            if (encrypted.isNotBlank()) {
                dataStore.edit { prefs ->
                    prefs[PreferencesKeys.ENCRYPTED_AUTH_TOKEN] = encrypted
                    prefs.remove(PreferencesKeys.LEGACY_AUTH_TOKEN)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun setWifiOnly(enabled: Boolean) {
        dataStore.edit { it[PreferencesKeys.WIFI_ONLY] = enabled }
    }

    suspend fun setSelectedFolders(folders: Set<String>) {
        val now = System.currentTimeMillis()
        dataStore.edit { prefs ->
            val oldFolders = prefs[PreferencesKeys.SELECTED_FOLDERS] ?: emptySet()
            prefs[PreferencesKeys.SELECTED_FOLDERS] = folders

            val currentMap = parseFolderActivations(prefs[PreferencesKeys.FOLDER_ACTIVATIONS_JSON])
            for (f in folders) {
                if (!oldFolders.contains(f) && !currentMap.containsKey(f)) {
                    currentMap[f] = now
                }
            }
            prefs[PreferencesKeys.FOLDER_ACTIVATIONS_JSON] = serializeFolderActivations(currentMap)
        }
    }

    suspend fun setBackfillDays(days: Int) {
        dataStore.edit { it[PreferencesKeys.BACKFILL_DAYS] = days }
    }

    suspend fun applyBackfillForFolders(days: Int) {
        val cutoffMs = System.currentTimeMillis() - (days * 24L * 60L * 60L * 1000L)
        dataStore.edit { prefs ->
            val folders = prefs[PreferencesKeys.SELECTED_FOLDERS] ?: emptySet()
            val currentMap = parseFolderActivations(prefs[PreferencesKeys.FOLDER_ACTIVATIONS_JSON])
            for (f in folders) {
                currentMap[f] = cutoffMs
            }
            prefs[PreferencesKeys.FOLDER_ACTIVATIONS_JSON] = serializeFolderActivations(currentMap)
            prefs[PreferencesKeys.BACKFILL_DAYS] = days
        }
    }

    suspend fun getFolderActivationTimestamp(folder: String): Long {
        val prefs = dataStore.data.first()
        val currentMap = parseFolderActivations(prefs[PreferencesKeys.FOLDER_ACTIVATIONS_JSON])
        return currentMap[folder] ?: 0L
    }

    suspend fun setLastSyncTimestamp(timestamp: Long) {
        dataStore.edit { it[PreferencesKeys.LAST_SYNC_TIMESTAMP] = timestamp }
    }

    suspend fun setLastSuccessfulRun(timestamp: Long) {
        dataStore.edit { it[PreferencesKeys.LAST_SUCCESSFUL_RUN] = timestamp }
    }

    suspend fun setLastScanTimestamp(timestamp: Long) {
        dataStore.edit { it[PreferencesKeys.LAST_SCAN_TIMESTAMP] = timestamp }
    }

    suspend fun setLastSuccessfulUpload(timestamp: Long) {
        dataStore.edit { it[PreferencesKeys.LAST_SUCCESSFUL_UPLOAD] = timestamp }
    }

    suspend fun setLastServerError(error: String, statusCode: Int = 0) {
        dataStore.edit {
            it[PreferencesKeys.LAST_SERVER_ERROR] = error
            it[PreferencesKeys.LAST_SERVER_STATUS_CODE] = statusCode
        }
    }

    suspend fun recordServerStatus(error: String?, statusCode: Int = 0) {
        if (error.isNullOrBlank()) {
            setLastSuccessfulUpload(System.currentTimeMillis())
            setLastServerError("", statusCode)
        } else {
            setLastServerError(error, statusCode)
        }
    }

    suspend fun setPauseUntil(timestamp: Long) {
        dataStore.edit { it[PreferencesKeys.PAUSE_UNTIL_TIMESTAMP] = timestamp }
    }

    suspend fun setOnboardingCompleted(completed: Boolean) {
        dataStore.edit { it[PreferencesKeys.ONBOARDING_COMPLETED] = completed }
    }

    suspend fun setUserConfirmedAutostart(confirmed: Boolean) {
        dataStore.edit { it[PreferencesKeys.USER_CONFIRMED_AUTOSTART] = confirmed }
    }

    suspend fun setUserConfirmedBattery(confirmed: Boolean) {
        dataStore.edit { it[PreferencesKeys.USER_CONFIRMED_BATTERY] = confirmed }
    }

    suspend fun setAppLanguage(lang: String) {
        dataStore.edit { it[PreferencesKeys.APP_LANGUAGE] = lang }
    }

    private fun parseFolderActivations(json: String?): MutableMap<String, Long> {
        val map = mutableMapOf<String, Long>()
        if (json.isNullOrBlank()) return map
        try {
            val obj = JSONObject(json)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                map[k] = obj.optLong(k, 0L)
            }
        } catch (e: Exception) {
            // ignore parse errors
        }
        return map
    }

    private fun serializeFolderActivations(map: Map<String, Long>): String {
        val obj = JSONObject()
        for ((k, v) in map) {
            obj.put(k, v)
        }
        return obj.toString()
    }
}
