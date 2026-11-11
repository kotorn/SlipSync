package com.thaweekarn.slipsync.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.thaweekarn.slipsync.data.local.SlipDatabase
import com.thaweekarn.slipsync.data.preferences.SlipPreferencesRepository
import com.thaweekarn.slipsync.data.repository.SlipRepository
import com.thaweekarn.slipsync.util.FileUtils
import com.thaweekarn.slipsync.worker.WorkScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class OnboardingUiState(
    val currentStep: Int = 1,
    val webAppUrl: String = "",
    val authToken: String = "",
    val isTestingConnection: Boolean = false,
    val connectionSuccess: Boolean? = null,
    val connectionMessage: String = "",
    val discoveredFolders: List<String> = emptyList(),
    val suggestedBankFolders: List<String> = emptyList(),
    val selectedFolders: Set<String> = emptySet(),
    val backfillDays: Int = 7,
    val testImageUri: Uri? = null,
    val isTestingUpload: Boolean = false,
    val testUploadSuccess: Boolean? = null,
    val testUploadMessage: String = ""
)

class OnboardingViewModel(application: Application) : AndroidViewModel(application) {

    private val db = SlipDatabase.getInstance(application)
    private val prefsRepo = SlipPreferencesRepository(application)
    private val slipRepo = SlipRepository(application, db.slipDao(), prefsRepo)

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    init {
        loadInitialData()
    }

    private fun loadInitialData() {
        viewModelScope.launch {
            val url = prefsRepo.webAppUrl.first()
            val token = prefsRepo.authToken.first()
            val folders = prefsRepo.selectedFolders.first()
            val backfill = prefsRepo.backfillDays.first()

            val discovered = FileUtils.getDistinctImageFolders(getApplication())
            val suggested = discovered.filter { f ->
                FileUtils.KNOWN_BANK_FOLDERS.any { bank -> f.contains(bank, ignoreCase = true) || bank.contains(f, ignoreCase = true) }
            }

            _uiState.value = _uiState.value.copy(
                webAppUrl = url,
                authToken = token,
                discoveredFolders = discovered,
                suggestedBankFolders = suggested,
                selectedFolders = if (folders.isNotEmpty()) folders else emptySet(), // suggestions only, user confirms
                backfillDays = backfill
            )
        }
    }

    fun nextStep() {
        if (_uiState.value.currentStep < 5) {
            _uiState.value = _uiState.value.copy(currentStep = _uiState.value.currentStep + 1)
        }
    }

    fun prevStep() {
        if (_uiState.value.currentStep > 1) {
            _uiState.value = _uiState.value.copy(currentStep = _uiState.value.currentStep - 1)
        }
    }

    fun setWebAppUrl(url: String) {
        _uiState.value = _uiState.value.copy(webAppUrl = url, connectionSuccess = null)
    }

    fun setAuthToken(token: String) {
        _uiState.value = _uiState.value.copy(authToken = token, connectionSuccess = null)
    }

    fun toggleFolder(folder: String) {
        val current = _uiState.value.selectedFolders.toMutableSet()
        if (current.contains(folder)) {
            current.remove(folder)
        } else {
            current.add(folder)
        }
        _uiState.value = _uiState.value.copy(selectedFolders = current)
    }

    fun setBackfillDays(days: Int) {
        _uiState.value = _uiState.value.copy(backfillDays = days)
    }

    fun testConnection() {
        val url = _uiState.value.webAppUrl.trim()
        val token = _uiState.value.authToken.trim()
        if (url.isBlank() || token.isBlank()) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isTestingConnection = true, connectionSuccess = null)
            val result = slipRepo.testConnection(url, token)
            result.fold(
                onSuccess = { resp ->
                    _uiState.value = _uiState.value.copy(
                        isTestingConnection = false,
                        connectionSuccess = resp.ok,
                        connectionMessage = if (resp.ok) "เชื่อมต่อสำเร็จ! (Connection verified)" else (resp.error ?: "Connection failed")
                    )
                },
                onFailure = { error ->
                    _uiState.value = _uiState.value.copy(
                        isTestingConnection = false,
                        connectionSuccess = false,
                        connectionMessage = error.message ?: "Failed to connect"
                    )
                }
            )
        }
    }

    fun setTestImageUri(uri: Uri?) {
        _uiState.value = _uiState.value.copy(testImageUri = uri, testUploadSuccess = null)
    }

    fun uploadTestImage() {
        val uri = _uiState.value.testImageUri ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isTestingUpload = true, testUploadSuccess = null)
            val queueResult = slipRepo.queueSharedImage(uri)
            queueResult.fold(
                onSuccess = {
                    WorkScheduler.enqueueSyncWork(getApplication())
                    _uiState.value = _uiState.value.copy(
                        isTestingUpload = false,
                        testUploadSuccess = true,
                        testUploadMessage = "เพิ่มภาพทดสอบเข้าคิวส่งแล้ว! (Queued for sync)"
                    )
                },
                onFailure = { error ->
                    _uiState.value = _uiState.value.copy(
                        isTestingUpload = false,
                        testUploadSuccess = false,
                        testUploadMessage = error.message ?: "Failed to queue test image"
                    )
                }
            )
        }
    }

    fun completeOnboarding(onFinish: () -> Unit) {
        viewModelScope.launch {
            prefsRepo.setWebAppUrl(_uiState.value.webAppUrl)
            prefsRepo.setAuthToken(_uiState.value.authToken)
            prefsRepo.setSelectedFolders(_uiState.value.selectedFolders)
            prefsRepo.applyBackfillForFolders(_uiState.value.backfillDays)
            prefsRepo.setOnboardingCompleted(true)

            // Trigger initial sync and schedule workers
            WorkScheduler.scheduleAll(getApplication())
            WorkScheduler.enqueueSyncWork(getApplication())

            onFinish()
        }
    }
}
