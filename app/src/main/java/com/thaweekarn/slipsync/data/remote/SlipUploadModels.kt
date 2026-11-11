package com.thaweekarn.slipsync.data.remote

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class SlipUploadRequest(
    @field:Json(name = "action") val action: String = "upload",
    @field:Json(name = "token") val token: String,
    @field:Json(name = "fileName") val fileName: String,
    @field:Json(name = "mimeType") val mimeType: String,
    @field:Json(name = "sha256") val sha256: String,
    @field:Json(name = "hasQr") val hasQr: Boolean,
    @field:Json(name = "slipQr") val slipQr: String?,
    @field:Json(name = "transactionId") val transactionId: String? = null,
    @field:Json(name = "takenAt") val takenAt: Long,
    @field:Json(name = "sourceFolder") val sourceFolder: String,
    @field:Json(name = "imageBase64") val imageBase64: String
)

@JsonClass(generateAdapter = true)
data class SlipPingRequest(
    @field:Json(name = "action") val action: String = "ping",
    @field:Json(name = "token") val token: String
)

@JsonClass(generateAdapter = true)
data class SlipApiResponse(
    @field:Json(name = "ok") val ok: Boolean = false,
    @field:Json(name = "duplicate") val duplicate: Boolean = false,
    @field:Json(name = "duplicateType") val duplicateType: String? = null, // "HASH" or "TRANSACTION"
    @field:Json(name = "fileId") val fileId: String? = null,
    @field:Json(name = "fileName") val fileName: String? = null,
    @field:Json(name = "error") val error: String? = null,
    @field:Json(name = "errorCode") val errorCode: String? = null, // "UNAUTHORIZED", "INVALID_PAYLOAD", "SERVER_ERROR", etc.
    @field:Json(name = "message") val message: String? = null
)
