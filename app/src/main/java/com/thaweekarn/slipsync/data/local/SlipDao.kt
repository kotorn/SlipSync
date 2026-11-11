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

    @Query("SELECT * FROM slips WHERE normalizedTransactionId = :txId AND (status = 'UPLOADED' OR status = 'UPLOADED_NO_QR') LIMIT 1")
    suspend fun getUploadedSlipByTransactionId(txId: String): SlipEntity?

    @Query("SELECT * FROM slips WHERE status = 'PENDING' OR status = 'FAILED' ORDER BY takenAt ASC")
    suspend fun getPendingOrFailedSlips(): List<SlipEntity>

    /**
     * Retrieves queue items eligible for upload:
     * - PENDING items
     * - Expired UPLOADING items where uploadLeaseUntil < now (worker crash or timeout)
     * - FAILED items where attempts < 5
     */
    @Query("""
        SELECT * FROM slips 
        WHERE status = 'PENDING' 
           OR (status = 'UPLOADING' AND uploadLeaseUntil < :now)
           OR (status = 'FAILED' AND attempts < 5)
        ORDER BY takenAt ASC 
        LIMIT :limit
    """)
    suspend fun getEligibleUploads(now: Long, limit: Int = 25): List<SlipEntity>

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
     * Acquires a lease on an item for a specific worker run.
     * Returns 1 if successfully leased, 0 otherwise.
     */
    @Query("""
        UPDATE slips 
        SET status = 'UPLOADING', uploadLeaseUntil = :leaseUntil, workerRunId = :workerId 
        WHERE sha256 = :sha256 
          AND (status = 'PENDING' OR (status = 'UPLOADING' AND uploadLeaseUntil < :now) OR status = 'FAILED')
    """)
    suspend fun acquireLease(sha256: String, leaseUntil: Long, workerId: String, now: Long): Int

    /**
     * Resets expired UPLOADING leases back to PENDING.
     */
    @Query("UPDATE slips SET status = 'PENDING', uploadLeaseUntil = 0, workerRunId = null WHERE status = 'UPLOADING' AND uploadLeaseUntil < :now")
    suspend fun resetExpiredLeases(now: Long): Int

    @Query("""
        UPDATE slips 
        SET status = :status, 
            attempts = :attempts, 
            lastError = :lastError, 
            uploadedAt = :uploadedAt, 
            driveFileId = :driveFileId,
            uploadLeaseUntil = 0,
            workerRunId = null
        WHERE sha256 = :sha256
    """)
    suspend fun updateUploadResult(
        sha256: String,
        status: String,
        attempts: Int,
        lastError: String?,
        uploadedAt: Long?,
        driveFileId: String?
    )

    /**
     * Atomically updates the upload result ONLY if the workerRunId matches the leased worker.
     * Prevents an expired, timed-out, or preempted worker from overwriting newer upload state.
     * Returns the number of rows updated (1 if ownership held, 0 if lease was lost).
     */
    @Query("""
        UPDATE slips 
        SET status = :status, 
            attempts = :attempts, 
            lastError = :lastError, 
            uploadedAt = :uploadedAt, 
            driveFileId = :driveFileId,
            uploadLeaseUntil = 0,
            workerRunId = null
        WHERE sha256 = :sha256 AND workerRunId = :workerId
    """)
    suspend fun updateUploadResultWithOwner(
        sha256: String,
        workerId: String,
        status: String,
        attempts: Int,
        lastError: String?,
        uploadedAt: Long?,
        driveFileId: String?
    ): Int

    @Query("UPDATE slips SET status = 'SKIPPED_IGNORED', uploadLeaseUntil = 0, workerRunId = null WHERE sha256 = :sha256")
    suspend fun ignoreSlip(sha256: String)

    @Query("UPDATE slips SET status = 'PENDING', attempts = 0, lastError = null, uploadLeaseUntil = 0, workerRunId = null WHERE sha256 = :sha256")
    suspend fun unignoreSlip(sha256: String)

    @Query("UPDATE slips SET status = 'PENDING', attempts = 0, lastError = null, uploadLeaseUntil = 0, workerRunId = null WHERE sha256 = :sha256")
    suspend fun retrySlip(sha256: String)
}
