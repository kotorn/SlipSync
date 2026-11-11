package com.thaweekarn.slipsync

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.thaweekarn.slipsync.data.local.SlipDatabase
import com.thaweekarn.slipsync.data.preferences.SlipPreferencesRepository
import com.thaweekarn.slipsync.data.repository.SlipRepository
import com.thaweekarn.slipsync.worker.WorkScheduler
import kotlinx.coroutines.launch

class ShareTargetActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val intent = intent
        val action = intent.action
        val type = intent.type

        if (Intent.ACTION_SEND == action && type != null && type.startsWith("image/")) {
            val imageUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM) as? Uri
            }

            if (imageUri != null) {
                processSharedSlip(imageUri)
                return
            }
        }

        Toast.makeText(this, "No image found in share payload", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun processSharedSlip(uri: Uri) {
        val db = SlipDatabase.getInstance(this)
        val prefs = SlipPreferencesRepository(this)
        val repository = SlipRepository(this, db.slipDao(), prefs)

        lifecycleScope.launch {
            val result = repository.queueSharedImage(uri)
            result.fold(
                onSuccess = {
                    WorkScheduler.enqueueSyncWork(this@ShareTargetActivity)
                    Toast.makeText(
                        this@ShareTargetActivity,
                        "บันทึกสลิปเข้าคิวแล้ว กำลังส่งขึ้นคลาวด์...",
                        Toast.LENGTH_SHORT
                    ).show()
                },
                onFailure = { error ->
                    Toast.makeText(
                        this@ShareTargetActivity,
                        "ไม่สามารถประมวลผลรูปภาพได้: ${error.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            )
            finish()
        }
    }
}
