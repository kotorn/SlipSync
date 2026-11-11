package com.thaweekarn.slipsync.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity representing an individual bank transfer receipt slip.
 * Primary key is the SHA-256 hash of the image content.
 */
@Entity(
    tableName = "slips",
    indices = [
        Index(value = ["slipQr"]),
        Index(value = ["mediaStoreId"]),
        Index(value = ["status"]),
        Index(value = ["takenAt"]),
        Index(value = ["normalizedTransactionId"])
    ]
)
data class SlipEntity(
    @PrimaryKey
    val sha256: String,
    val mediaStoreId: Long,
    val slipQr: String? = null,
    val hasQr: Boolean = false,
    val fileName: String,
    val mimeType: String,
    val extension: String,
    val sourceFolder: String,
    val status: String, // PENDING, UPLOADING, UPLOADED, UPLOADED_NO_QR, FAILED, SKIPPED_DUPLICATE, SKIPPED_IGNORED
    val attempts: Int = 0,
    val lastError: String? = null,
    val takenAt: Long,
    val uploadedAt: Long? = null,
    val driveFileId: String? = null,
    val userNote: String? = null, // V2 readiness
    val localFilePath: String? = null, // Durable file path for shared slips
    val contentUriString: String? = null, // Optional URI string
    val normalizedTransactionId: String? = null, // Extracted BOT / PromptPay transaction ref
    val uploadLeaseUntil: Long = 0L, // Concurrency lease expiration timestamp
    val workerRunId: String? = null // Worker identifier holding the lease
) {
    companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_UPLOADING = "UPLOADING"
        const val STATUS_UPLOADED = "UPLOADED"
        const val STATUS_UPLOADED_NO_QR = "UPLOADED_NO_QR"
        const val STATUS_FAILED = "FAILED"
        const val STATUS_SKIPPED_DUPLICATE = "SKIPPED_DUPLICATE"
        const val STATUS_SKIPPED_IGNORED = "SKIPPED_IGNORED"
    }

    val isSuccess: Boolean
        get() = status == STATUS_UPLOADED || status == STATUS_UPLOADED_NO_QR
}
