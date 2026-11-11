package com.thaweekarn.slipsync.ui.viewmodel

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.thaweekarn.slipsync.data.local.SlipDatabase
import com.thaweekarn.slipsync.data.preferences.SlipPreferencesRepository
import com.thaweekarn.slipsync.data.repository.HealthCheckItem
import com.thaweekarn.slipsync.data.repository.HealthCheckRepository
import com.thaweekarn.slipsync.util.FileUtils
import com.thaweekarn.slipsync.util.LogExporter
import com.thaweekarn.slipsync.worker.WorkScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

import com.google.firebase.auth.FirebaseUser
import com.thaweekarn.slipsync.data.auth.AuthRepository

data class SettingsUiState(
    val webAppUrl: String = "",
    val authToken: String = "",
    val wifiOnly: Boolean = false,
    val selectedFolders: Set<String> = emptySet(),
    val discoveredFolders: List<String> = emptyList(),
    val suggestedFolders: List<String> = emptyList(),
    val pauseUntil: Long = 0L,
    val isPaused: Boolean = false,
    val backfillDays: Int = 7,
    val appLanguage: String = "th",
    val healthChecks: List<HealthCheckItem> = emptyList(),
    val isGoogleDriveConnected: Boolean = false,
    val googleUser: FirebaseUser? = null,
    val isSigningInGoogle: Boolean = false,
    val authMessage: String? = null
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val db = SlipDatabase.getInstance(application)
    private val prefsRepo = SlipPreferencesRepository(application)
    private val healthRepo = HealthCheckRepository(application, prefsRepo)
    private val authRepo = AuthRepository(application, prefsRepo)

    private val _discoveredFolders = MutableStateFlow<List<String>>(emptyList())
    private val _suggestedFolders = MutableStateFlow<List<String>>(emptyList())
    private val _healthChecks = MutableStateFlow<List<HealthCheckItem>>(emptyList())
    private val _isSigningInGoogle = MutableStateFlow(false)
    private val _authMessage = MutableStateFlow<String?>(null)
    val healthChecks: StateFlow<List<HealthCheckItem>> = _healthChecks.asStateFlow()

    val uiState: StateFlow<SettingsUiState> = combine(
        prefsRepo.webAppUrl,
        prefsRepo.authToken,
        prefsRepo.wifiOnly,
        prefsRepo.selectedFolders,
        prefsRepo.pauseUntilTimestamp,
        prefsRepo.backfillDays,
        prefsRepo.appLanguage,
        _discoveredFolders,
        _suggestedFolders,
        authRepo.currentUser,
        _isSigningInGoogle,
        _authMessage
    ) { values ->
        val url = values[0] as String
        val token = values[1] as String
        val wifi = values[2] as Boolean
        @Suppress("UNCHECKED_CAST")
        val folders = values[3] as Set<String>
        val pause = values[4] as Long
        val backfill = values[5] as Int
        val lang = values[6] as String
        @Suppress("UNCHECKED_CAST")
        val discovered = values[7] as List<String>
        @Suppress("UNCHECKED_CAST")
        val suggested = values[8] as List<String>
        val currentUser = values[9] as? FirebaseUser
        val isSigningIn = values[10] as Boolean
        val authMsg = values[11] as? String

        val now = System.currentTimeMillis()
        val isPaused = pause > now

        SettingsUiState(
            webAppUrl = url,
            authToken = token,
            wifiOnly = wifi,
            selectedFolders = folders,
            discoveredFolders = discovered,
            suggestedFolders = suggested,
            pauseUntil = pause,
            isPaused = isPaused,
            backfillDays = backfill,
            appLanguage = lang,
            healthChecks = _healthChecks.value,
            isGoogleDriveConnected = currentUser != null,
            googleUser = currentUser,
            isSigningInGoogle = isSigningIn,
            authMessage = authMsg
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SettingsUiState()
    )

    init {
        loadFolders()
        refreshHealthChecks()
        viewModelScope.launch {
            authRepo.currentUser.collect {
                refreshHealthChecks()
            }
        }
    }

    private fun loadFolders() {
        viewModelScope.launch {
            val discovered = FileUtils.getDistinctImageFolders(getApplication())
            val suggested = discovered.filter { f ->
                FileUtils.KNOWN_BANK_FOLDERS.any { bank -> f.contains(bank, ignoreCase = true) || bank.contains(f, ignoreCase = true) }
            }
            _discoveredFolders.value = discovered
            _suggestedFolders.value = suggested
        }
    }

    fun refreshHealthChecks() {
        viewModelScope.launch {
            _healthChecks.value = healthRepo.evaluateAllChecks()
        }
    }

    fun setWebAppUrl(url: String) {
        viewModelScope.launch {
            prefsRepo.setWebAppUrl(url)
            refreshHealthChecks()
        }
    }

    fun setAuthToken(token: String) {
        viewModelScope.launch {
            prefsRepo.setAuthToken(token)
            refreshHealthChecks()
        }
    }

    fun setWifiOnly(enabled: Boolean) {
        viewModelScope.launch {
            prefsRepo.setWifiOnly(enabled)
            WorkScheduler.enqueueSyncWork(getApplication())
        }
    }

    fun toggleFolder(folder: String) {
        viewModelScope.launch {
            val current = uiState.value.selectedFolders.toMutableSet()
            if (current.contains(folder)) {
                current.remove(folder)
            } else {
                current.add(folder)
            }
            prefsRepo.setSelectedFolders(current)
            WorkScheduler.enqueueSyncWork(getApplication())
        }
    }

    fun applyBackfill(days: Int) {
        viewModelScope.launch {
            prefsRepo.applyBackfillForFolders(days)
            WorkScheduler.enqueueSyncWork(getApplication())
        }
    }

    fun togglePauseSync(forOneHour: Boolean = true) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            if (uiState.value.isPaused) {
                // Resume
                prefsRepo.setPauseUntil(0L)
                WorkScheduler.enqueueSyncWork(getApplication())
            } else {
                // Pause for 1 hour or indefinitely (30 days)
                val duration = if (forOneHour) 60L * 60L * 1000L else 30L * 24L * 60L * 60L * 1000L
                prefsRepo.setPauseUntil(now + duration)
            }
        }
    }

    fun setLanguage(lang: String) {
        viewModelScope.launch {
            prefsRepo.setAppLanguage(lang)
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(lang))
        }
    }

    fun exportSanitizedLogs() {
        viewModelScope.launch {
            val logText = LogExporter.generateSanitizedLog(getApplication(), prefsRepo, db)
            LogExporter.shareLog(getApplication(), logText)
        }
    }

    fun signInWithGoogle(context: android.content.Context) {
        viewModelScope.launch {
            _isSigningInGoogle.value = true
            _authMessage.value = null
            val result = authRepo.signInWithGoogle(context)
            _isSigningInGoogle.value = false
            result.fold(
                onSuccess = { user ->
                    _authMessage.value = "เชื่อมต่อ Google Drive สำเร็จ (${user.email})"
                    refreshHealthChecks()
                    WorkScheduler.enqueueSyncWork(getApplication())
                },
                onFailure = { error ->
                    if (error !is androidx.credentials.exceptions.GetCredentialCancellationException) {
                        _authMessage.value = error.message ?: "Sign-in failed"
                    }
                }
            )
        }
    }

    fun signOut() {
        viewModelScope.launch {
            authRepo.signOut()
            _authMessage.value = "ออกจากระบบเรียบร้อยแล้ว"
            refreshHealthChecks()
        }
    }

    fun clearAuthMessage() {
        _authMessage.value = null
    }
}
