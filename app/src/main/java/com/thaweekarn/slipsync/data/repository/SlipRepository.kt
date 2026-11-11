package com.thaweekarn.slipsync.data.repository

import android.content.Context
import android.net.Uri
import com.thaweekarn.slipsync.data.local.SlipDao
import com.thaweekarn.slipsync.data.local.SlipEntity
import com.thaweekarn.slipsync.data.preferences.SlipPreferencesRepository
import com.thaweekarn.slipsync.data.remote.SlipApiResponse
import com.thaweekarn.slipsync.data.remote.SlipUploadApi
import com.thaweekarn.slipsync.util.FileUtils
import com.thaweekarn.slipsync.util.HashUtils
import com.thaweekarn.slipsync.util.QrScannerHelper
import com.thaweekarn.slipsync.util.ThaiSlipQrParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.util.Calendar

class SlipRepository(
    private val context: Context,
    private val slipDao: SlipDao,
    private val preferencesRepository: SlipPreferencesRepository,
    private val uploadApi: SlipUploadApi = SlipUploadApi()
) {

    val recentSlips: Flow<List<SlipEntity>> = slipDao.getRecentSlips(50)

    val pendingCount: Flow<Int> = slipDao.getPendingCount()

    val failedCount: Flow<Int> = slipDao.getFailedCount()

    fun getTodayUploadedCount(): Flow<Int> {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return slipDao.getUploadedCountToday(calendar.timeInMillis)
    }

    fun observeSlip(sha256: String): Flow<SlipEntity?> = slipDao.observeSlipBySha256(sha256)

    suspend fun retrySlip(sha256: String) {
        slipDao.retrySlip(sha256)
    }

    suspend fun ignoreSlip(sha256: String) {
        slipDao.ignoreSlip(sha256)
    }

    suspend fun unignoreSlip(sha256: String) {
        slipDao.unignoreSlip(sha256)
    }

    suspend fun testConnection(url: String, token: String): Result<SlipApiResponse> {
        return uploadApi.ping(url, token)
    }

    /**
     * Queues an image shared to the app via ACTION_SEND.
     * Copies the stream to durable private storage (filesDir/shared_slips), computes SHA-256,
     * scans QR code, validates BOT / PromptPay format, and enqueues to Room as PENDING.
     */
    suspend fun queueSharedImage(sourceUri: Uri): Result<SlipEntity> = withContext(Dispatchers.IO) {
        try {
            val durablePair = FileUtils.copyStreamToDurableStorage(context, sourceUri)
                ?: return@withContext Result.failure(Exception("Could not read shared image stream"))

            val (durableFile, mimeType) = durablePair
            val sha256 = FileInputStream(durableFile).use { HashUtils.calculateSha256(it) }

            // Check if already recorded in Room
            val existing = slipDao.getSlipBySha256(sha256)
            if (existing != null) {
                if (existing.isSuccess) {
                    return@withContext Result.success(existing)
                }
            }

            val qrPayload = QrScannerHelper.scanQrFromFile(context, durableFile)
            val qrAnalysis = ThaiSlipQrParser.analyze(qrPayload)
            val now = System.currentTimeMillis()
            val ext = FileUtils.extractExtension(durableFile.name, mimeType)
            val fileName = FileUtils.generateFileName(now, "Shared", sha256, ext)

            val entity = SlipEntity(
                sha256 = sha256,
                mediaStoreId = now, // Identifier fallback for shared items
                slipQr = qrPayload,
                hasQr = qrAnalysis.isValidBankQr,
                fileName = fileName,
                mimeType = mimeType,
                extension = ext,
                sourceFolder = "Shared/External",
                status = SlipEntity.STATUS_PENDING,
                attempts = 0,
                lastError = null,
                takenAt = now,
                localFilePath = durableFile.absolutePath,
                contentUriString = sourceUri.toString(),
                normalizedTransactionId = qrAnalysis.normalizedTransactionId
            )

            slipDao.insertOrUpdate(entity)
            Result.success(entity)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
