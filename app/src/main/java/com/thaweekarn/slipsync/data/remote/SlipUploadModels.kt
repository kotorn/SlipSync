package com.thaweekarn.slipsync.data.remote

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class SlipUploadRequest(
    @Json(name = "action") val action: String = "upload",
    @Json(name = "token") val token: String,
    @Json(name = "fileName") val fileName: String,
    @Json(name = "mimeType") val mimeType: String,
    @Json(name = "sha256") val sha256: String,
    @Json(name = "hasQr") val hasQr: Boolean,
    @Json(name = "slipQr") val slipQr: String?,
    @Json(name = "transactionId") val transactionId: String? = null,
    @Json(name = "takenAt") val takenAt: Long,
    @Json(name = "sourceFolder") val sourceFolder: String,
    @Json(name = "imageBase64") val imageBase64: String
)

@JsonClass(generateAdapter = true)
data class SlipPingRequest(
    @Json(name = "action") val action: String = "ping",
    @Json(name = "token") val token: String
)

@JsonClass(generateAdapter = true)
data class SlipApiResponse(
    @Json(name = "ok") val ok: Boolean = false,
    @Json(name = "duplicate") val duplicate: Boolean = false,
    @Json(name = "duplicateType") val duplicateType: String? = null, // "HASH" or "TRANSACTION"
    @Json(name = "fileId") val fileId: String? = null,
    @Json(name = "fileName") val fileName: String? = null,
    @Json(name = "error") val error: String? = null,
    @Json(name = "errorCode") val errorCode: String? = null, // "UNAUTHORIZED", "INVALID_PAYLOAD", "SERVER_ERROR", etc.
    @Json(name = "message") val message: String? = null
)
