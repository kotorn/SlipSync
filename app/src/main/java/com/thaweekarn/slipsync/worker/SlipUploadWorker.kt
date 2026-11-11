package com.thaweekarn.slipsync.worker

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
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
import com.thaweekarn.slipsync.util.ThaiSlipQrParser
import kotlinx.coroutines.flow.first
import java.io.File
import java.util.UUID

class SlipUploadWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val db = SlipDatabase.getInstance(appContext)
    private val slipDao = db.slipDao()
    private val prefsRepo = SlipPreferencesRepository(appContext)
    private val uploadApi = SlipUploadApi()

    override suspend fun doWork(): Result {
        val now = System.currentTimeMillis()

        // 1. Reset expired UPLOADING leases back to PENDING (survives app kill / worker timeout)
        slipDao.resetExpiredLeases(now)

        // 2. Check if sync is currently paused by user
        val pauseUntil = prefsRepo.pauseUntilTimestamp.first()
        if (pauseUntil > now) {
            return Result.success()
        }

        // 3. Read and validate core configuration
        val webAppUrl = prefsRepo.webAppUrl.first()
        val authToken = prefsRepo.authToken.first()
        val selectedFolders = prefsRepo.selectedFolders.first()

        // Only exit if webAppUrl or authToken is missing.
        // Even if selectedFolders is empty, pending Shared Slips in Room MUST still be uploaded.
        if (webAppUrl.isBlank() || authToken.isBlank()) {
            return Result.success()
        }

        // Phase 1: Scan MediaStore for newly added bank slip candidates (only if folders selected)
        if (selectedFolders.isNotEmpty()) {
            try {
                val folderActivationMap = mutableMapOf<String, Long>()
                for (folder in selectedFolders) {
                    folderActivationMap[folder] = prefsRepo.getFolderActivationTimestamp(folder)
                }

                val candidates = FileUtils.queryCandidateImages(
                    appContext,
                    selectedFolders,
                    folderActivationMap
                )

                for (candidate in candidates) {
                    try {
                        val sha256 = appContext.contentResolver.openInputStream(candidate.uri)?.use {
                            HashUtils.calculateSha256(it)
                        } ?: continue

                        // Check if already in Room
                        val existing = slipDao.getSlipBySha256(sha256)
                        if (existing != null) {
                            continue
                        }

                        // Scan QR code offline using bundled ML Kit
                        val qrPayload = QrScannerHelper.scanQrFromUri(appContext, candidate.uri)
                        val qrAnalysis = ThaiSlipQrParser.analyze(qrPayload)
                        val takenAtMs = candidate.dateAddedSec * 1000L
                        val extension = FileUtils.extractExtension(candidate.displayName, candidate.mimeType)
                        val targetFileName = FileUtils.generateFileName(takenAtMs, candidate.relativePath, sha256, extension)

                        // Two-tier deduplication check:
                        // 1. Normalized transaction ID
                        // 2. Exact QR payload
                        var isDuplicate = false
                        if (qrAnalysis.normalizedTransactionId != null) {
                            val dupByTx = slipDao.getUploadedSlipByTransactionId(qrAnalysis.normalizedTransactionId)
                            if (dupByTx != null) {
                                isDuplicate = true
                            }
                        }
                        if (!isDuplicate && qrPayload != null) {
                            val dupByQr = slipDao.getUploadedSlipByQr(qrPayload)
                            if (dupByQr != null) {
                                isDuplicate = true
                            }
                        }

                        val initialStatus = if (isDuplicate) {
                            SlipEntity.STATUS_SKIPPED_DUPLICATE
                        } else {
                            SlipEntity.STATUS_PENDING
                        }

                        val newEntity = SlipEntity(
                            sha256 = sha256,
                            mediaStoreId = candidate.id,
                            slipQr = qrPayload,
                            hasQr = qrAnalysis.isValidBankQr,
                            fileName = targetFileName,
                            mimeType = candidate.mimeType,
                            extension = extension,
                            sourceFolder = candidate.relativePath,
                            status = initialStatus,
                            attempts = 0,
                            lastError = if (isDuplicate) "Duplicate slip already uploaded" else null,
                            takenAt = takenAtMs,
                            localFilePath = null,
                            contentUriString = candidate.uri.toString(),
                            normalizedTransactionId = qrAnalysis.normalizedTransactionId
                        )
                        slipDao.insertOrIgnore(newEntity)
                    } catch (candidateEx: Exception) {
                        candidateEx.printStackTrace()
                    }
                }
                prefsRepo.setLastScanTimestamp(System.currentTimeMillis())
            } catch (scanEx: Exception) {
                scanEx.printStackTrace()
            }
        }

        // Phase 2: Process upload queue (Durable queue with lease recovery & workerRunId ownership)
        val workerRunId = "worker_${UUID.randomUUID().toString().take(8)}"
        val leaseDurationMs = 5 * 60 * 1000L // 5 minutes lease per item
        val eligibleUploads = slipDao.getEligibleUploads(now = System.currentTimeMillis(), limit = 25)

        var successfulUploads = 0
        var hasTransientError = false

        for (slip in eligibleUploads) {
            val leaseNow = System.currentTimeMillis()
            val leaseSuccess = slipDao.acquireLease(
                sha256 = slip.sha256,
                leaseUntil = leaseNow + leaseDurationMs,
                workerId = workerRunId,
                now = leaseNow
            )

            if (leaseSuccess == 0) {
                // Another thread or worker acquired this item
                continue
            }

            try {
                // Determine source for reading bytes: Local file or MediaStore URI
                val localFile = slip.localFilePath?.let { File(it) }?.takeIf { it.exists() }
                val mediaUri = if (localFile == null) {
                    ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, slip.mediaStoreId)
                } else null

                val base64Result = FileUtils.readImageAsBase64(appContext, mediaUri, localFile)
                if (base64Result == null) {
                    val currentAttempts = slip.attempts + 1
                    slipDao.updateUploadResultWithOwner(
                        sha256 = slip.sha256,
                        workerId = workerRunId,
                        status = SlipEntity.STATUS_FAILED,
                        attempts = currentAttempts,
                        lastError = "File exceeds 10 MB limit or cannot be read",
                        uploadedAt = null,
                        driveFileId = null
                    )
                    continue
                }

                val (base64String, _) = base64Result

                // If QR was not scanned or normalized transaction was missing, run scan now
                var qrPayload = slip.slipQr
                var normalizedTx = slip.normalizedTransactionId
                var hasQr = slip.hasQr

                if (qrPayload == null) {
                    qrPayload = if (localFile != null) {
                        QrScannerHelper.scanQrFromFile(appContext, localFile)
                    } else if (mediaUri != null) {
                        QrScannerHelper.scanQrFromUri(appContext, mediaUri)
                    } else null

                    if (qrPayload != null) {
                        val analysis = ThaiSlipQrParser.analyze(qrPayload)
                        normalizedTx = analysis.normalizedTransactionId
                        hasQr = analysis.isValidBankQr
                    }
                }

                // Check local duplicate before sending over network
                var localDup = false
                if (normalizedTx != null) {
                    val dupByTx = slipDao.getUploadedSlipByTransactionId(normalizedTx)
                    if (dupByTx != null && dupByTx.sha256 != slip.sha256) {
                        localDup = true
                    }
                }
                if (!localDup && qrPayload != null) {
                    val dupByQr = slipDao.getUploadedSlipByQr(qrPayload)
                    if (dupByQr != null && dupByQr.sha256 != slip.sha256) {
                        localDup = true
                    }
                }

                if (localDup) {
                    slipDao.updateUploadResultWithOwner(
                        sha256 = slip.sha256,
                        workerId = workerRunId,
                        status = SlipEntity.STATUS_SKIPPED_DUPLICATE,
                        attempts = slip.attempts,
                        lastError = "Duplicate QR/Transaction already uploaded",
                        uploadedAt = null,
                        driveFileId = null
                    )
                    continue
                }

                // Build upload request
                val requestDto = SlipUploadRequest(
                    action = "upload",
                    token = authToken,
                    fileName = slip.fileName,
                    mimeType = slip.mimeType,
                    sha256 = slip.sha256,
                    hasQr = hasQr,
                    slipQr = qrPayload,
                    transactionId = normalizedTx,
                    takenAt = slip.takenAt,
                    sourceFolder = slip.sourceFolder,
                    imageBase64 = base64String
                )

                val uploadResult = uploadApi.uploadSlip(webAppUrl, requestDto)
                val uploadSuccessTime = System.currentTimeMillis()
                val currentAttempts = slip.attempts + 1

                uploadResult.fold(
                    onSuccess = { apiResp ->
                        prefsRepo.recordServerStatus(null, 200)

                        if (apiResp.ok) {
                            val finalStatus = if (apiResp.duplicate) {
                                SlipEntity.STATUS_SKIPPED_DUPLICATE
                            } else if (hasQr) {
                                SlipEntity.STATUS_UPLOADED
                            } else {
                                SlipEntity.STATUS_UPLOADED_NO_QR
                            }

                            val updated = slipDao.updateUploadResultWithOwner(
                                sha256 = slip.sha256,
                                workerId = workerRunId,
                                status = finalStatus,
                                attempts = currentAttempts,
                                lastError = if (apiResp.duplicate) "Server marked as duplicate" else null,
                                uploadedAt = uploadSuccessTime,
                                driveFileId = apiResp.fileId
                            )

                            // Health tracking: only update lastSuccessfulUpload after confirmed successful upload with lease ownership
                            if (updated > 0 && !apiResp.duplicate) {
                                prefsRepo.setLastSuccessfulUpload(uploadSuccessTime)
                                successfulUploads++
                            }
                        } else {
                            val errorMsg = apiResp.error ?: "Server rejected upload"
                            slipDao.updateUploadResultWithOwner(
                                sha256 = slip.sha256,
                                workerId = workerRunId,
                                status = SlipEntity.STATUS_FAILED,
                                attempts = currentAttempts,
                                lastError = errorMsg,
                                uploadedAt = null,
                                driveFileId = null
                            )
                            if (currentAttempts >= 3) {
                                NotificationHelper.showFailureAlertNotification(appContext, slip.fileName)
                            }
                        }
                    },
                    onFailure = { error ->
                        hasTransientError = true
                        val errorMsg = error.message ?: "Network communication error"
                        prefsRepo.recordServerStatus(errorMsg, 0)

                        slipDao.updateUploadResultWithOwner(
                            sha256 = slip.sha256,
                            workerId = workerRunId,
                            status = SlipEntity.STATUS_FAILED,
                            attempts = currentAttempts,
                            lastError = errorMsg,
                            uploadedAt = null,
                            driveFileId = null
                        )
                        if (currentAttempts >= 3) {
                            NotificationHelper.showFailureAlertNotification(appContext, slip.fileName)
                        }
                    }
                )
            } catch (itemEx: Exception) {
                hasTransientError = true
                itemEx.printStackTrace()
                slipDao.updateUploadResultWithOwner(
                    sha256 = slip.sha256,
                    workerId = workerRunId,
                    status = SlipEntity.STATUS_FAILED,
                    attempts = slip.attempts + 1,
                    lastError = itemEx.message ?: "Unexpected processing error",
                    uploadedAt = null,
                    driveFileId = null
                )
            }
        }

        // Phase 3: Finalize and notify
        val finishTime = System.currentTimeMillis()
        prefsRepo.setLastSyncTimestamp(finishTime)
        if (!hasTransientError || successfulUploads > 0) {
            prefsRepo.setLastSuccessfulRun(finishTime)
        }

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
