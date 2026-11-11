package com.thaweekarn.slipsync.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.thaweekarn.slipsync.data.preferences.SlipPreferencesRepository
import com.thaweekarn.slipsync.util.NotificationHelper
import kotlinx.coroutines.flow.first

class SlipWatchdogWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val prefsRepo = SlipPreferencesRepository(appContext)

    override suspend fun doWork(): Result {
        val lastRun = prefsRepo.lastSuccessfulRun.first()
        val now = System.currentTimeMillis()

        // If configured and last successful run > 24 hours, fire high-importance watchdog notification
        val isConfigured = prefsRepo.webAppUrl.first().isNotBlank() && prefsRepo.authToken.first().isNotBlank()
        if (isConfigured && lastRun > 0 && (now - lastRun) > (24L * 60L * 60L * 1000L)) {
            NotificationHelper.showWatchdogAlertNotification(appContext)
        }

        return Result.success()
    }
}
