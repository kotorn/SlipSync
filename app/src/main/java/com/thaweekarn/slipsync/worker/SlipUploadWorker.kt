package com.thaweekarn.slipsync.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.thaweekarn.slipsync.data.local.SlipDatabase
import com.thaweekarn.slipsync.data.local.SlipEntity
import com.thaweekarn.slipsync.data.preferences.SlipPreferencesRepository
import com.thaweekarn.slipsync.data.remote.SlipUploadApi
import com.thaweekarn.slipsync.data.remote.SlipUploadRequest
import com.thaweekarn.slipsync.util.FileUtils
import com.thaweekarn.slipsync.util.HashUtils
import com.thaweekarn.slipsync.util.NotificationHelper
import com.thaweekarn.slipsync.util.QrScannerHelper
import kotlinx.coroutines.flow.first

class SlipUploadWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val db = SlipDatabase.getInstance(appContext)
    private val slipDao = db.slipDao()
    private val prefsRepo = SlipPreferencesRepository(appContext)
    private val uploadApi = SlipUploadApi()

    override suspend fun doWork(): Result {
        // 1. Reset any lingering UPLOADING rows back to PENDING (crash recovery)
        slipDao.resetUploadingToPending()

        // 2. Check if sync is paused
        val pauseUntil = prefsRepo.pauseUntilTimestamp.first()
        val now = System.currentTimeMillis()
        if (pauseUntil > now) {
            return Result.success()
        }

        // 3. Read configuration
        val webAppUrl = prefsRepo.webAppUrl.first()
        val authToken = prefsRepo.authToken.first()
        val selectedFolders = prefsRepo.selectedFolders.first()

        if (webAppUrl.isBlank() || authToken.isBlank() || selectedFolders.isEmpty()) {
            // Setup not completed yet
            return Result.success()
        }

        val folderActivationMap = mutableMapOf<String, Long>()
        for (folder in selectedFolders) {
            folderActivationMap[folder] = prefsRepo.getFolderActivationTimestamp(folder)
        }

        // 4. Query candidate images from selected folders
        val candidates = FileUtils.queryCandidateImages(
            appContext,
            selectedFolders,
            folderActivationMap
        )

        var successfulUploads = 0
        var hasTransientError = false

        for (candidate in candidates) {
            try {
                // Compute SHA-256 from input stream
                val sha256 = appContext.contentResolver.openInputStream(candidate.uri)?.use {
                    HashUtils.calculateSha256(it)
                } ?: continue

                // Check local Room database
                val existing = slipDao.getSlipBySha256(sha256)
                if (existing != null) {
                    if (existing.isSuccess || existing.status == SlipEntity.STATUS_SKIPPED_IGNORED) {
                        continue // Already uploaded or explicitly ignored
                    }
                    if (existing.status == SlipEntity.STATUS_SKIPPED_DUPLICATE) {
                        continue
                    }
                    if (existing.status == SlipEntity.STATUS_FAILED && existing.attempts >= 5) {
                        // Max retries reached
                        continue
                    }
                }

                // Scan for QR code offline using bundled ML Kit
                val qrPayload = QrScannerHelper.scanQrFromUri(appContext, candidate.uri)
                val hasQr = !qrPayload.isNullOrBlank()

                // Deduplication priority: 1. slipQr, 2. sha256
                if (hasQr && qrPayload != null) {
                    val duplicateByQr = slipDao.getUploadedSlipByQr(qrPayload)
                    if (duplicateByQr != null) {
                        val duplicateEntity = SlipEntity(
                            sha256 = sha256,
                            mediaStoreId = candidate.id,
                            slipQr = qrPayload,
                            hasQr = true,
                            fileName = candidate.displayName,
                            mimeType = candidate.mimeType,
                            extension = FileUtils.extractExtension(candidate.displayName, candidate.mimeType),
                            sourceFolder = candidate.relativePath,
                            status = SlipEntity.STATUS_SKIPPED_DUPLICATE,
                            attempts = 0,
                            lastError = "Duplicate QR payload already uploaded",
                            takenAt = candidate.dateAddedSec * 1000L
                        )
                        slipDao.insertOrUpdate(duplicateEntity)
                        continue
                    }
                }

                // Read image base64 (respecting 10MB limit)
                val base64Result = FileUtils.readImageAsBase64(appContext, candidate.uri)
                if (base64Result == null) {
                    // Exceeds 10 MB or cannot be read
                    val failedEntity = SlipEntity(
                        sha256 = sha256,
                        mediaStoreId = candidate.id,
                        slipQr = qrPayload,
                        hasQr = hasQr,
                        fileName = candidate.displayName,
                        mimeType = candidate.mimeType,
                        extension = FileUtils.extractExtension(candidate.displayName, candidate.mimeType),
                        sourceFolder = candidate.relativePath,
                        status = SlipEntity.STATUS_FAILED,
                        attempts = (existing?.attempts ?: 0) + 1,
                        lastError = "File exceeds 10 MB limit or cannot be read",
                        takenAt = candidate.dateAddedSec * 1000L
                    )
                    slipDao.insertOrUpdate(failedEntity)
                    continue
                }

                val (base64String, _) = base64Result
                val extension = FileUtils.extractExtension(candidate.displayName, candidate.mimeType)
                val takenAtMs = candidate.dateAddedSec * 1000L
                val targetFileName = FileUtils.generateFileName(takenAtMs, candidate.relativePath, sha256, extension)

                // Mark as UPLOADING in Room
                val currentAttempts = (existing?.attempts ?: 0) + 1
                val uploadingEntity = SlipEntity(
                    sha256 = sha256,
                    mediaStoreId = candidate.id,
                    slipQr = qrPayload,
                    hasQr = hasQr,
                    fileName = targetFileName,
                    mimeType = candidate.mimeType,
                    extension = extension,
                    sourceFolder = candidate.relativePath,
                    status = SlipEntity.STATUS_UPLOADING,
                    attempts = currentAttempts,
                    lastError = null,
                    takenAt = takenAtMs
                )
                slipDao.insertOrUpdate(uploadingEntity)

                // Transmit payload
                val requestDto = SlipUploadRequest(
                    action = "upload",
                    token = authToken,
                    fileName = targetFileName,
                    mimeType = candidate.mimeType,
                    sha256 = sha256,
                    hasQr = hasQr,
                    slipQr = qrPayload,
                    takenAt = takenAtMs,
                    sourceFolder = candidate.relativePath,
                    imageBase64 = base64String
                )

                val uploadResult = uploadApi.uploadSlip(webAppUrl, requestDto)
                val uploadSuccessTime = System.currentTimeMillis()

                uploadResult.fold(
                    onSuccess = { apiResp ->
                        if (apiResp.ok) {
                            val finalStatus = if (apiResp.duplicate) {
                                SlipEntity.STATUS_SKIPPED_DUPLICATE
                            } else if (hasQr) {
                                SlipEntity.STATUS_UPLOADED
                            } else {
                                SlipEntity.STATUS_UPLOADED_NO_QR
                            }

                            slipDao.updateUploadResult(
                                sha256 = sha256,
                                status = finalStatus,
                                attempts = currentAttempts,
                                lastError = if (apiResp.duplicate) "Server marked as duplicate" else null,
                                uploadedAt = uploadSuccessTime,
                                driveFileId = apiResp.fileId
                            )

                            if (!apiResp.duplicate) {
                                successfulUploads++
                            }
                        } else {
                            val errorMsg = apiResp.error ?: "Server rejected upload"
                            slipDao.updateUploadResult(
                                sha256 = sha256,
                                status = SlipEntity.STATUS_FAILED,
                                attempts = currentAttempts,
                                lastError = errorMsg,
                                uploadedAt = null,
                                driveFileId = null
                            )
                            if (currentAttempts >= 3) {
                                NotificationHelper.showFailureAlertNotification(appContext, targetFileName)
                            }
                        }
                    },
                    onFailure = { error ->
                        hasTransientError = true
                        val errorMsg = error.message ?: "Network communication error"
                        slipDao.updateUploadResult(
                            sha256 = sha256,
                            status = SlipEntity.STATUS_FAILED,
                            attempts = currentAttempts,
                            lastError = errorMsg,
                            uploadedAt = null,
                            driveFileId = null
                        )
                        if (currentAttempts >= 3) {
                            NotificationHelper.showFailureAlertNotification(appContext, targetFileName)
                        }
                    }
                )

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Record execution timestamps
        val finishTime = System.currentTimeMillis()
        prefsRepo.setLastSyncTimestamp(finishTime)
        if (!hasTransientError || successfulUploads > 0) {
            prefsRepo.setLastSuccessfulRun(finishTime)
        }

        // Post batch notification if any slips were uploaded
        if (successfulUploads > 0) {
            NotificationHelper.showSuccessBatchNotification(appContext, successfulUploads)
        }

        return if (hasTransientError && successfulUploads == 0) {
            Result.retry()
        } else {
            Result.success()
        }
    }
}
