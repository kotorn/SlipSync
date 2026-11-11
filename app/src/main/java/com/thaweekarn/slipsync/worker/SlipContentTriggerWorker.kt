package com.thaweekarn.slipsync.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class SlipContentTriggerWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        // Enqueue unique upload work with ExistingWorkPolicy.KEEP
        WorkScheduler.enqueueSyncWork(appContext)

        // Reschedule self to continue monitoring MediaStore content URI changes
        WorkScheduler.scheduleContentTrigger(appContext)

        return Result.success()
    }
}
