package com.thaweekarn.slipsync.worker

import android.content.Context
import android.os.Build
import android.provider.MediaStore
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.thaweekarn.slipsync.data.preferences.SlipPreferencesRepository
import com.thaweekarn.slipsync.util.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

object WorkScheduler {

    const val WORK_NAME_SYNC = "slip-sync"
    private const val WORK_NAME_CONTENT_TRIGGER = "slip-content-trigger"
    private const val WORK_NAME_PERIODIC = "slip-periodic-check"
    private const val WORK_NAME_WATCHDOG = "slip-watchdog"

    /**
     * Enqueues a single upload job with ExistingWorkPolicy.KEEP to prevent concurrent executions.
     * Respects user's network mode (Wi-Fi only vs Any Network).
     */
    fun enqueueSyncWork(context: Context) {
        val workManager = WorkManager.getInstance(context)
        val prefsRepo = SlipPreferencesRepository(context)

        CoroutineScope(Dispatchers.IO).launch {
            val wifiOnly = prefsRepo.wifiOnly.first()

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .build()

            val uploadRequest = OneTimeWorkRequestBuilder<SlipUploadWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

            workManager.enqueueUniqueWork(
                WORK_NAME_SYNC,
                ExistingWorkPolicy.KEEP,
                uploadRequest
            )
        }
    }

    /**
     * Schedules MediaStore content URI observer work request.
     */
    fun scheduleContentTrigger(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return

        val workManager = WorkManager.getInstance(context)

        val constraints = Constraints.Builder()
            .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
            .build()

        val triggerRequest = OneTimeWorkRequestBuilder<SlipContentTriggerWorker>()
            .setConstraints(constraints)
            .build()

        workManager.enqueueUniqueWork(
            WORK_NAME_CONTENT_TRIGGER,
            ExistingWorkPolicy.REPLACE,
            triggerRequest
        )
    }

    /**
     * Schedules 15-minute fallback periodic worker.
     */
    fun schedulePeriodicFallback(context: Context) {
        val workManager = WorkManager.getInstance(context)

        val periodicRequest = PeriodicWorkRequestBuilder<SlipPeriodicWorker>(
            15, TimeUnit.MINUTES,
            5, TimeUnit.MINUTES
        ).build()

        workManager.enqueueUniquePeriodicWork(
            WORK_NAME_PERIODIC,
            ExistingPeriodicWorkPolicy.UPDATE,
            periodicRequest
        )
    }

    /**
     * Schedules 6-hour watchdog periodic worker.
     */
    fun scheduleWatchdog(context: Context) {
        val workManager = WorkManager.getInstance(context)

        val watchdogRequest = PeriodicWorkRequestBuilder<SlipWatchdogWorker>(
            6, TimeUnit.HOURS,
            1, TimeUnit.HOURS
        ).build()

        workManager.enqueueUniquePeriodicWork(
            WORK_NAME_WATCHDOG,
            ExistingPeriodicWorkPolicy.UPDATE,
            watchdogRequest
        )
    }

    /**
     * Initializes all background workers.
     */
    fun scheduleAll(context: Context) {
        scheduleContentTrigger(context)
        schedulePeriodicFallback(context)
        scheduleWatchdog(context)
    }

    /**
     * Evaluates watchdog status on app launch or boot.
     */
    fun evaluateWatchdogNow(context: Context) {
        val prefsRepo = SlipPreferencesRepository(context)
        CoroutineScope(Dispatchers.IO).launch {
            val lastRun = prefsRepo.lastSuccessfulRun.first()
            val now = System.currentTimeMillis()
            val isConfigured = prefsRepo.webAppUrl.first().isNotBlank() && prefsRepo.authToken.first().isNotBlank()
            if (isConfigured && lastRun > 0 && (now - lastRun) > (24L * 60L * 60L * 1000L)) {
                NotificationHelper.showWatchdogAlertNotification(context)
            }
        }
    }
}
