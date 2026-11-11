package com.thaweekarn.slipsync.ui.viewmodel

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.thaweekarn.slipsync.R
import com.thaweekarn.slipsync.data.local.SlipDatabase
import com.thaweekarn.slipsync.data.local.SlipEntity
import com.thaweekarn.slipsync.data.preferences.SlipPreferencesRepository
import com.thaweekarn.slipsync.data.repository.HealthActionType
import com.thaweekarn.slipsync.data.repository.SlipRepository
import com.thaweekarn.slipsync.ui.components.FilterStatus
import com.thaweekarn.slipsync.ui.components.HealthCardState
import com.thaweekarn.slipsync.worker.WorkScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HomeUiState(
    val recentSlips: List<SlipEntity> = emptyList(),
    val filteredSlips: List<SlipEntity> = emptyList(),
    val todayUploadedCount: Int = 0,
    val pendingCount: Int = 0,
    val failedCount: Int = 0,
    val currentFilter: FilterStatus = FilterStatus.ALL,
    val healthState: HealthCardState = HealthCardState.GREEN_OK,
    val healthTitle: String = "",
    val healthDesc: String = "",
    val healthActionType: HealthActionType? = null,
    val isSyncing: Boolean = false,
    val hasPartialAccess: Boolean = false,
    val lastSyncTimestamp: Long = 0L,
    val webAppConfigured: Boolean = false
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val db = SlipDatabase.getInstance(application)
    private val prefsRepo = SlipPreferencesRepository(application)
    private val slipRepo = SlipRepository(application, db.slipDao(), prefsRepo)

    private val _currentFilter = MutableStateFlow(FilterStatus.ALL)
    val currentFilter: StateFlow<FilterStatus> = _currentFilter.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private data class Counts(val today: Int, val pending: Int, val failed: Int)
    private data class Prefs(val lastSync: Long, val url: String)

    private val countsFlow = combine(
        slipRepo.getTodayUploadedCount(),
        slipRepo.pendingCount,
        slipRepo.failedCount
    ) { today, pending, failed -> Counts(today, pending, failed) }

    private val prefsFlow = combine(
        prefsRepo.lastSyncTimestamp,
        prefsRepo.webAppUrl
    ) { lastSync, url -> Prefs(lastSync, url) }

    val uiState: StateFlow<HomeUiState> = combine(
        slipRepo.recentSlips,
        countsFlow,
        _currentFilter,
        prefsFlow,
        _isSyncing
    ) { allSlips, counts, filter, prefs, syncing ->

        val filtered = when (filter) {
            FilterStatus.ALL -> allSlips
            FilterStatus.UPLOADED -> allSlips.filter { it.isSuccess }
            FilterStatus.PENDING -> allSlips.filter { it.status == SlipEntity.STATUS_PENDING || it.status == SlipEntity.STATUS_UPLOADING }
            FilterStatus.FAILED -> allSlips.filter { it.status == SlipEntity.STATUS_FAILED }
        }

        val context = getApplication<Application>()
        val partialAccess = checkPartialAccess(context)
        val hasFullAccess = checkFullAccess(context)

        val (hState, hTitle, hDesc, hAction) = evaluateHealth(
            context = context,
            hasFullAccess = hasFullAccess,
            hasPartialAccess = partialAccess,
            urlConfigured = prefs.url.isNotBlank(),
            pendingCount = counts.pending,
            failedCount = counts.failed,
            lastSync = prefs.lastSync
        )

        HomeUiState(
            recentSlips = allSlips,
            filteredSlips = filtered,
            todayUploadedCount = counts.today,
            pendingCount = counts.pending,
            failedCount = counts.failed,
            currentFilter = filter,
            healthState = hState,
            healthTitle = hTitle,
            healthDesc = hDesc,
            healthActionType = hAction,
            isSyncing = syncing,
            hasPartialAccess = partialAccess,
            lastSyncTimestamp = prefs.lastSync,
            webAppConfigured = prefs.url.isNotBlank()
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = HomeUiState()
    )

    fun setFilter(filter: FilterStatus) {
        _currentFilter.value = filter
    }

    fun triggerSyncNow() {
        viewModelScope.launch {
            _isSyncing.value = true
            WorkScheduler.enqueueSyncWork(getApplication())
            // Brief visual feedback animation
            kotlinx.coroutines.delay(1200)
            _isSyncing.value = false
        }
    }

    fun retrySlip(slip: SlipEntity) {
        viewModelScope.launch {
            slipRepo.retrySlip(slip.sha256)
            WorkScheduler.enqueueSyncWork(getApplication())
        }
    }

    fun ignoreSlip(slip: SlipEntity) {
        viewModelScope.launch {
            slipRepo.ignoreSlip(slip.sha256)
        }
    }

    fun unignoreSlip(sha256: String) {
        viewModelScope.launch {
            slipRepo.unignoreSlip(sha256)
        }
    }

    private fun checkPartialAccess(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val partial = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            ) == PackageManager.PERMISSION_GRANTED
            val full = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_MEDIA_IMAGES
            ) == PackageManager.PERMISSION_GRANTED
            partial && !full
        } else false
    }

    private fun checkFullAccess(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_MEDIA_IMAGES
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun evaluateHealth(
        context: Context,
        hasFullAccess: Boolean,
        hasPartialAccess: Boolean,
        urlConfigured: Boolean,
        pendingCount: Int,
        failedCount: Int,
        lastSync: Long
    ): Quadruple<HealthCardState, String, String, HealthActionType?> {
        return when {
            hasPartialAccess || !hasFullAccess -> Quadruple(
                HealthCardState.RED_ACTION_NEEDED,
                context.getString(R.string.health_action_needed_title),
                if (hasPartialAccess) context.getString(R.string.partial_access_title) else "Photo permission required",
                HealthActionType.PERMISSION_STORAGE
            )
            !urlConfigured -> Quadruple(
                HealthCardState.RED_ACTION_NEEDED,
                context.getString(R.string.health_action_needed_title),
                "Google Apps Script Web App URL is not set",
                HealthActionType.SETTINGS_WEB_APP
            )
            failedCount > 0 -> Quadruple(
                HealthCardState.AMBER_PENDING,
                context.getString(R.string.health_pending_title),
                "$failedCount slip(s) failed upload. Tap to review.",
                null
            )
            pendingCount > 0 -> Quadruple(
                HealthCardState.AMBER_PENDING,
                context.getString(R.string.health_pending_title),
                context.getString(R.string.health_pending_desc, pendingCount),
                null
            )
            else -> {
                val syncDesc = if (lastSync > 0) {
                    val diffMin = (System.currentTimeMillis() - lastSync) / (1000 * 60)
                    if (diffMin <= 1) context.getString(R.string.health_just_now) else context.getString(R.string.health_minutes_ago, diffMin)
                } else context.getString(R.string.health_never_synced)

                Quadruple(
                    HealthCardState.GREEN_OK,
                    context.getString(R.string.health_ok_title),
                    context.getString(R.string.health_ok_desc, syncDesc),
                    null
                )
            }
        }
    }
}

private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
