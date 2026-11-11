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
        val AUTH_TOKEN = stringPreferencesKey("auth_token")
        val WIFI_ONLY = booleanPreferencesKey("wifi_only")
        val SELECTED_FOLDERS = stringSetPreferencesKey("selected_folders")
        val FOLDER_ACTIVATIONS_JSON = stringPreferencesKey("folder_activations_json")
        val BACKFILL_DAYS = intPreferencesKey("backfill_days")
        val LAST_SYNC_TIMESTAMP = longPreferencesKey("last_sync_timestamp")
        val LAST_SUCCESSFUL_RUN = longPreferencesKey("last_successful_run")
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

    val authToken: Flow<String> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[PreferencesKeys.AUTH_TOKEN] ?: "" }

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

    suspend fun setAuthToken(token: String) {
        dataStore.edit { it[PreferencesKeys.AUTH_TOKEN] = token.trim() }
    }

    suspend fun setWifiOnly(enabled: Boolean) {
        dataStore.edit { it[PreferencesKeys.WIFI_ONLY] = enabled }
    }

    suspend fun setSelectedFolders(folders: Set<String>) {
        val now = System.currentTimeMillis()
        dataStore.edit { prefs ->
            val oldFolders = prefs[PreferencesKeys.SELECTED_FOLDERS] ?: emptySet()
            prefs[PreferencesKeys.SELECTED_FOLDERS] = folders

            // Update activation timestamps for any newly added folders
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

    /**
     * Applies a backfill range for selected folders by moving their activation timestamp back in time.
     */
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
