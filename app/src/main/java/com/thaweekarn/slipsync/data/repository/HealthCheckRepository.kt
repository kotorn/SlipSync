package com.thaweekarn.slipsync.data.repository

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.content.ContextCompat
import com.thaweekarn.slipsync.data.preferences.SlipPreferencesRepository
import com.thaweekarn.slipsync.util.XiaomiIntentHelper
import kotlinx.coroutines.flow.first

enum class HealthStatus {
    PASS,
    WARNING,
    FAIL
}

data class HealthCheckItem(
    val id: String,
    val titleRes: Int,
    val status: HealthStatus,
    val description: String,
    val actionType: HealthActionType
)

enum class HealthActionType {
    PERMISSION_STORAGE,
    SETTINGS_WEB_APP,
    SETTINGS_TOKEN,
    SYSTEM_NETWORK,
    XIAOMI_BATTERY,
    SYNC_NOW
}

class HealthCheckRepository(
    private val context: Context,
    private val preferencesRepository: SlipPreferencesRepository
) {

    suspend fun evaluateAllChecks(): List<HealthCheckItem> {
        val list = mutableListOf<HealthCheckItem>()

        // 1. Storage Permission
        val storageStatus = checkStoragePermission()
        list.add(storageStatus)

        // 2. Web App URL / Google Drive Auto Connection
        val isDriveAutoConnected = preferencesRepository.googleDriveAutoConnected.first()
        val url = preferencesRepository.webAppUrl.first()
        val urlStatus = if (isDriveAutoConnected) {
            HealthCheckItem(
                id = "url",
                titleRes = com.thaweekarn.slipsync.R.string.check_url_title,
                status = HealthStatus.PASS,
                description = "Connected via Google Account (Auto Drive)",
                actionType = HealthActionType.SETTINGS_WEB_APP
            )
        } else if (url.isNotBlank() && url.startsWith("https://script.google.com/")) {
            HealthCheckItem(
                id = "url",
                titleRes = com.thaweekarn.slipsync.R.string.check_url_title,
                status = HealthStatus.PASS,
                description = "Valid Web App URL configured",
                actionType = HealthActionType.SETTINGS_WEB_APP
            )
        } else {
            HealthCheckItem(
                id = "url",
                titleRes = com.thaweekarn.slipsync.R.string.check_url_title,
                status = HealthStatus.FAIL,
                description = "Web App URL is missing or invalid",
                actionType = HealthActionType.SETTINGS_WEB_APP
            )
        }
        list.add(urlStatus)

        // 3. Security Token
        val token = preferencesRepository.authToken.first()
        val tokenStatus = if (isDriveAutoConnected) {
            HealthCheckItem(
                id = "token",
                titleRes = com.thaweekarn.slipsync.R.string.check_token_title,
                status = HealthStatus.PASS,
                description = "Authenticated via Google Sign-In",
                actionType = HealthActionType.SETTINGS_TOKEN
            )
        } else if (token.isNotBlank()) {
            HealthCheckItem(
                id = "token",
                titleRes = com.thaweekarn.slipsync.R.string.check_token_title,
                status = HealthStatus.PASS,
                description = "Security Token configured",
                actionType = HealthActionType.SETTINGS_TOKEN
            )
        } else {
            HealthCheckItem(
                id = "token",
                titleRes = com.thaweekarn.slipsync.R.string.check_token_title,
                status = HealthStatus.FAIL,
                description = "Security Token is not set",
                actionType = HealthActionType.SETTINGS_TOKEN
            )
        }
        list.add(tokenStatus)

        // 4. Network Connectivity
        val hasNetwork = isNetworkAvailable()
        list.add(
            HealthCheckItem(
                id = "network",
                titleRes = com.thaweekarn.slipsync.R.string.check_network_title,
                status = if (hasNetwork) HealthStatus.PASS else HealthStatus.FAIL,
                description = if (hasNetwork) "Internet connection active" else "No internet connection",
                actionType = HealthActionType.SYSTEM_NETWORK
            )
        )

        // 5. Battery Optimization
        val batteryIgnored = XiaomiIntentHelper.isBatteryOptimizationIgnored(context) ||
                preferencesRepository.userConfirmedBattery.first()
        list.add(
            HealthCheckItem(
                id = "battery",
                titleRes = com.thaweekarn.slipsync.R.string.check_battery_title,
                status = if (batteryIgnored) HealthStatus.PASS else HealthStatus.WARNING,
                description = if (batteryIgnored) "Battery optimization exempted" else "HyperOS battery saver may terminate background sync",
                actionType = HealthActionType.XIAOMI_BATTERY
            )
        )

        // 6. WorkManager Background Trigger Health
        val lastRun = preferencesRepository.lastSuccessfulRun.first()
        val now = System.currentTimeMillis()
        val hoursSinceLastRun = if (lastRun > 0) (now - lastRun) / (1000 * 60 * 60) else 999
        val workerStatus = when {
            lastRun == 0L -> HealthCheckItem(
                id = "worker",
                titleRes = com.thaweekarn.slipsync.R.string.check_worker_title,
                status = HealthStatus.WARNING,
                description = "Worker has not run yet. Tap to trigger first sync.",
                actionType = HealthActionType.SYNC_NOW
            )
            hoursSinceLastRun > 24 -> HealthCheckItem(
                id = "worker",
                titleRes = com.thaweekarn.slipsync.R.string.check_worker_title,
                status = HealthStatus.FAIL,
                description = "No worker run in over 24 hours ($hoursSinceLastRun hrs ago). System may have frozen background process.",
                actionType = HealthActionType.SYNC_NOW
            )
            else -> HealthCheckItem(
                id = "worker",
                titleRes = com.thaweekarn.slipsync.R.string.check_worker_title,
                status = HealthStatus.PASS,
                description = "Worker ran successfully $hoursSinceLastRun hour(s) ago",
                actionType = HealthActionType.SYNC_NOW
            )
        }
        list.add(workerStatus)

        return list
    }

    private fun checkStoragePermission(): HealthCheckItem {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { // Android 14+ (API 34)
            val fullGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_MEDIA_IMAGES
            ) == PackageManager.PERMISSION_GRANTED

            val partialGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            ) == PackageManager.PERMISSION_GRANTED

            when {
                fullGranted -> HealthCheckItem(
                    id = "storage",
                    titleRes = com.thaweekarn.slipsync.R.string.check_storage_title,
                    status = HealthStatus.PASS,
                    description = "Full photo access granted",
                    actionType = HealthActionType.PERMISSION_STORAGE
                )
                partialGranted -> HealthCheckItem(
                    id = "storage",
                    titleRes = com.thaweekarn.slipsync.R.string.check_storage_title,
                    status = HealthStatus.FAIL,
                    description = "Partial photo access detected. Background detection requires full access.",
                    actionType = HealthActionType.PERMISSION_STORAGE
                )
                else -> HealthCheckItem(
                    id = "storage",
                    titleRes = com.thaweekarn.slipsync.R.string.check_storage_title,
                    status = HealthStatus.FAIL,
                    description = "Photo access permission denied",
                    actionType = HealthActionType.PERMISSION_STORAGE
                )
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // API 33
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_MEDIA_IMAGES
            ) == PackageManager.PERMISSION_GRANTED
            HealthCheckItem(
                id = "storage",
                titleRes = com.thaweekarn.slipsync.R.string.check_storage_title,
                status = if (granted) HealthStatus.PASS else HealthStatus.FAIL,
                description = if (granted) "Media images access granted" else "Media images access denied",
                actionType = HealthActionType.PERMISSION_STORAGE
            )
        } else {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
            HealthCheckItem(
                id = "storage",
                titleRes = com.thaweekarn.slipsync.R.string.check_storage_title,
                status = if (granted) HealthStatus.PASS else HealthStatus.FAIL,
                description = if (granted) "Storage permission granted" else "Storage permission denied",
                actionType = HealthActionType.PERMISSION_STORAGE
            )
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val activeNetwork = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
