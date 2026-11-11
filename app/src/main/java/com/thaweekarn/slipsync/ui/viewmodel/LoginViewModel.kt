package com.thaweekarn.slipsync.ui.viewmodel

import android.app.Application
import android.content.Context
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseUser
import com.thaweekarn.slipsync.data.auth.AuthRepository
import com.thaweekarn.slipsync.data.preferences.SlipPreferencesRepository
import com.thaweekarn.slipsync.worker.WorkScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class LoginUiState(
    val isSigningIn: Boolean = false,
    val currentUser: FirebaseUser? = null,
    val isGoogleDriveConnected: Boolean = false,
    val driveFolderName: String = "Slips/Inbox",
    val isSavingFolder: Boolean = false,
    val authErrorMessage: String? = null,
    val successMessage: String? = null
)

class LoginViewModel(application: Application) : AndroidViewModel(application) {

    private val prefsRepo = SlipPreferencesRepository(application)
    private val authRepo = AuthRepository(application, prefsRepo)

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    init {
        observeCurrentUser()
    }

    private fun observeCurrentUser() {
        viewModelScope.launch {
            authRepo.currentUser.collect { user ->
                val isConnected = user != null || prefsRepo.googleDriveAutoConnected.first()
                _uiState.value = _uiState.value.copy(
                    currentUser = user,
                    isGoogleDriveConnected = isConnected
                )
                if (user != null) {
                    loadCloudDriveSettings(user.uid)
                }
            }
        }
    }

    private fun loadCloudDriveSettings(userId: String) {
        viewModelScope.launch {
            val profile = authRepo.getUserProfile(userId)
            val folder = profile?.get("driveFolderName") as? String ?: "Slips/Inbox"
            _uiState.value = _uiState.value.copy(driveFolderName = folder)
        }
    }

    fun signInWithGoogle(context: Context) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSigningIn = true, authErrorMessage = null, successMessage = null)
            val result = authRepo.signInWithGoogle(context)
            _uiState.value = _uiState.value.copy(isSigningIn = false)

            result.fold(
                onSuccess = { user ->
                    _uiState.value = _uiState.value.copy(
                        currentUser = user,
                        isGoogleDriveConnected = true,
                        successMessage = "เชื่อมต่อบัญชี Google Drive สำเร็จ (${user.email})"
                    )
                    WorkScheduler.enqueueSyncWork(getApplication())
                },
                onFailure = { error ->
                    if (error !is GetCredentialCancellationException) {
                        _uiState.value = _uiState.value.copy(
                            authErrorMessage = error.message ?: "Sign-in failed"
                        )
                    }
                }
            )
        }
    }

    fun signOut() {
        viewModelScope.launch {
            authRepo.signOut()
            _uiState.value = _uiState.value.copy(
                currentUser = null,
                isGoogleDriveConnected = false,
                successMessage = "ออกจากระบบเรียบร้อยแล้ว"
            )
        }
    }

    fun updateDriveFolderName(folderName: String) {
        val user = _uiState.value.currentUser ?: return
        val trimmed = folderName.trim()
        if (trimmed.isBlank()) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSavingFolder = true)
            val ok = authRepo.updateDriveFolderName(user.uid, trimmed)
            _uiState.value = _uiState.value.copy(
                isSavingFolder = false,
                driveFolderName = if (ok) trimmed else _uiState.value.driveFolderName,
                successMessage = if (ok) "บันทึกโฟลเดอร์ Google Drive เรียบร้อย" else null,
                authErrorMessage = if (!ok) "ไม่สามารถบันทึกโฟลเดอร์ได้" else null
            )
        }
    }

    fun clearMessages() {
        _uiState.value = _uiState.value.copy(authErrorMessage = null, successMessage = null)
    }
}
