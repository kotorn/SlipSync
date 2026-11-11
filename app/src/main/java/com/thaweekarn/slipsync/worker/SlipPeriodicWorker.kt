package com.thaweekarn.slipsync.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class SlipPeriodicWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        // Enqueue unique upload work with ExistingWorkPolicy.KEEP
        WorkScheduler.enqueueSyncWork(appContext)
        return Result.success()
    }
}
