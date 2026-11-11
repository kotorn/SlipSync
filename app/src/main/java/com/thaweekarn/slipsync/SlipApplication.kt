package com.thaweekarn.slipsync

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.thaweekarn.slipsync.data.preferences.SlipPreferencesRepository
import com.thaweekarn.slipsync.util.NotificationHelper
import com.thaweekarn.slipsync.worker.WorkScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SlipApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // 1. Create notification channels
        NotificationHelper.createNotificationChannels(this)

        // 2. Set per-app language (Thai default)
        val prefs = SlipPreferencesRepository(this)
        CoroutineScope(Dispatchers.Main).launch {
            val savedLang = prefs.appLanguage.first()
            val currentLocales = AppCompatDelegate.getApplicationLocales()
            if (currentLocales.isEmpty) {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(savedLang))
            }
        }

        // 3. Initialize background sync triggers
        try {
            WorkScheduler.scheduleAll(this)
            // 4. Evaluate watchdog for stale background sync
            WorkScheduler.evaluateWatchdogNow(this)
        } catch (e: Exception) {
            // Graceful fallback for testing or constrained environments where WorkManager is not initialized
        }
    }
}
