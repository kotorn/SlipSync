package com.thaweekarn.slipsync.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.thaweekarn.slipsync.data.local.SlipDatabase
import com.thaweekarn.slipsync.data.local.SlipEntity
import com.thaweekarn.slipsync.data.preferences.SlipPreferencesRepository
import com.thaweekarn.slipsync.data.repository.SlipRepository
import com.thaweekarn.slipsync.worker.WorkScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DetailViewModel(application: Application) : AndroidViewModel(application) {

    private val db = SlipDatabase.getInstance(application)
    private val prefs = SlipPreferencesRepository(application)
    private val repository = SlipRepository(application, db.slipDao(), prefs)

    private val _slipState = MutableStateFlow<SlipEntity?>(null)
    val slipState: StateFlow<SlipEntity?> = _slipState.asStateFlow()

    fun loadSlip(sha256: String) {
        viewModelScope.launch {
            repository.observeSlip(sha256).collect {
                _slipState.value = it
            }
        }
    }

    fun retry() {
        val current = _slipState.value ?: return
        viewModelScope.launch {
            repository.retrySlip(current.sha256)
            WorkScheduler.enqueueSyncWork(getApplication())
        }
    }

    fun ignore() {
        val current = _slipState.value ?: return
        viewModelScope.launch {
            repository.ignoreSlip(current.sha256)
        }
    }

    fun unignore() {
        val current = _slipState.value ?: return
        viewModelScope.launch {
            repository.unignoreSlip(current.sha256)
        }
    }
}
