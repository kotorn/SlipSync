package com.thaweekarn.slipsync.util

import android.content.Context
import android.content.Intent
import android.os.Build
import com.thaweekarn.slipsync.data.local.SlipDatabase
import com.thaweekarn.slipsync.data.preferences.SlipPreferencesRepository
import kotlinx.coroutines.flow.first
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogExporter {

    suspend fun generateSanitizedLog(
        context: Context,
        prefsRepo: SlipPreferencesRepository,
        db: SlipDatabase
    ): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val nowStr = dateFormat.format(Date())

        val webAppUrl = prefsRepo.webAppUrl.first()
        val maskedUrl = if (webAppUrl.isNotBlank()) {
            val length = webAppUrl.length
            if (length > 30) webAppUrl.take(20) + "..." + webAppUrl.takeLast(8) else webAppUrl
        } else "Not Configured"

        val hasToken = prefsRepo.authToken.first().isNotBlank()
        val wifiOnly = prefsRepo.wifiOnly.first()
        val folders = prefsRepo.selectedFolders.first()
        val lastSync = prefsRepo.lastSyncTimestamp.first()
        val lastSyncStr = if (lastSync > 0) dateFormat.format(Date(lastSync)) else "Never"
        val lastRun = prefsRepo.lastSuccessfulRun.first()
        val lastRunStr = if (lastRun > 0) dateFormat.format(Date(lastRun)) else "Never"

        val allSlips = db.slipDao().getAllSlips().first()
        val uploadedCount = allSlips.count { it.isSuccess }
        val pendingCount = allSlips.count { it.status == "PENDING" || it.status == "UPLOADING" }
        val failedCount = allSlips.count { it.status == "FAILED" }
        val ignoredCount = allSlips.count { it.status == "SKIPPED_IGNORED" }

        val sb = StringBuilder()
        sb.appendLine("=== SlipSync Diagnostic Log ===")
        sb.appendLine("Generated: $nowStr")
        sb.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        sb.appendLine("Android OS: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("")
        sb.appendLine("--- Configuration (Sanitized) ---")
        sb.appendLine("Web App URL: $maskedUrl")
        sb.appendLine("Auth Token Configured: $hasToken (token withheld)")
        sb.appendLine("Network Mode: ${if (wifiOnly) "Wi-Fi Only" else "Any Network"}")
        sb.appendLine("Monitored Folders (${folders.size}):")
        for (f in folders) {
            sb.appendLine("  - $f")
        }
        sb.appendLine("Last Sync Attempt: $lastSyncStr")
        sb.appendLine("Last Successful Run: $lastRunStr")
        sb.appendLine("")
        sb.appendLine("--- Slip Statistics ---")
        sb.appendLine("Total Tracked: ${allSlips.size}")
        sb.appendLine("Uploaded: $uploadedCount")
        sb.appendLine("Pending: $pendingCount")
        sb.appendLine("Failed: $failedCount")
        sb.appendLine("Ignored: $ignoredCount")
        sb.appendLine("")
        sb.appendLine("--- Recent Activity (Last 25 Slips - Sanitized) ---")
        val recentSlips = allSlips.take(25)
        if (recentSlips.isEmpty()) {
            sb.appendLine("No slip records.")
        } else {
            for (slip in recentSlips) {
                val shortSha = if (slip.sha256.length >= 8) slip.sha256.take(8) else slip.sha256
                val takenStr = dateFormat.format(Date(slip.takenAt))
                val qrStatus = if (slip.hasQr) "QR_PRESENT(payload_withheld)" else "NO_QR"
                sb.appendLine("[${slip.status}] $shortSha... | $takenStr | ${slip.sourceFolder} | QR: $qrStatus | Attempts: ${slip.attempts}")
                if (!slip.lastError.isNullOrBlank()) {
                    sb.appendLine("   Error: ${slip.lastError}")
                }
            }
        }
        sb.appendLine("")
        sb.appendLine("=== End of Log ===")
        return sb.toString()
    }

    fun shareLog(context: Context, logText: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "SlipSync Diagnostic Log")
            putExtra(Intent.EXTRA_TEXT, logText)
        }
        val chooser = Intent.createChooser(intent, "Share SlipSync Log")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }
}
