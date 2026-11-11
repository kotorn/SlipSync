package com.thaweekarn.slipsync.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SlipDao {

    @Query("SELECT * FROM slips ORDER BY takenAt DESC")
    fun getAllSlips(): Flow<List<SlipEntity>>

    @Query("SELECT * FROM slips ORDER BY takenAt DESC LIMIT :limit")
    fun getRecentSlips(limit: Int = 50): Flow<List<SlipEntity>>

    @Query("SELECT * FROM slips WHERE sha256 = :sha256 LIMIT 1")
    suspend fun getSlipBySha256(sha256: String): SlipEntity?

    @Query("SELECT * FROM slips WHERE sha256 = :sha256 LIMIT 1")
    fun observeSlipBySha256(sha256: String): Flow<SlipEntity?>

    @Query("SELECT * FROM slips WHERE slipQr = :slipQr AND (status = 'UPLOADED' OR status = 'UPLOADED_NO_QR') LIMIT 1")
    suspend fun getUploadedSlipByQr(slipQr: String): SlipEntity?

    @Query("SELECT * FROM slips WHERE status = 'PENDING' OR status = 'FAILED' ORDER BY takenAt ASC")
    suspend fun getPendingOrFailedSlips(): List<SlipEntity>

    @Query("SELECT COUNT(*) FROM slips WHERE (status = 'UPLOADED' OR status = 'UPLOADED_NO_QR') AND uploadedAt >= :startOfDayMs")
    fun getUploadedCountToday(startOfDayMs: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM slips WHERE status = 'PENDING' OR status = 'UPLOADING'")
    fun getPendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM slips WHERE status = 'FAILED'")
    fun getFailedCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(slip: SlipEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(slip: SlipEntity): Long

    @Update
    suspend fun update(slip: SlipEntity)

    /**
     * Resets any lingering UPLOADING rows back to PENDING.
     * Invoked at the start of SlipUploadWorker to recover from sudden process death or crashes.
     */
    @Query("UPDATE slips SET status = 'PENDING' WHERE status = 'UPLOADING'")
    suspend fun resetUploadingToPending(): Int

    @Query("UPDATE slips SET status = :status, attempts = :attempts, lastError = :lastError, uploadedAt = :uploadedAt, driveFileId = :driveFileId WHERE sha256 = :sha256")
    suspend fun updateUploadResult(
        sha256: String,
        status: String,
        attempts: Int,
        lastError: String?,
        uploadedAt: Long?,
        driveFileId: String?
    )

    @Query("UPDATE slips SET status = 'SKIPPED_IGNORED' WHERE sha256 = :sha256")
    suspend fun ignoreSlip(sha256: String)

    @Query("UPDATE slips SET status = 'PENDING', attempts = 0, lastError = null WHERE sha256 = :sha256")
    suspend fun unignoreSlip(sha256: String)

    @Query("UPDATE slips SET status = 'PENDING', attempts = 0, lastError = null WHERE sha256 = :sha256")
    suspend fun retrySlip(sha256: String)
}
