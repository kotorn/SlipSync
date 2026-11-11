package com.thaweekarn.slipsync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.thaweekarn.slipsync.util.FileUtils
import com.thaweekarn.slipsync.util.HashUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SlipSyncUnitTest {

    @Test
    fun testAppName() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("SlipSync", appName)
    }

    @Test
    fun testHashCalculation() {
        val testBytes = "Hello SlipSync".toByteArray(Charsets.UTF_8)
        val hash = HashUtils.calculateSha256(ByteArrayInputStream(testBytes))
        assertTrue(hash.isNotBlank())
        assertEquals(64, hash.length)
    }

    @Test
    fun testFileNameGeneration() {
        val takenAtMs = 1700000000000L
        val folder = "Pictures/KPLUS/"
        val sha256 = "1234567890abcdef"
        val ext = "jpg"
        val fileName = FileUtils.generateFileName(takenAtMs, folder, sha256, ext)
        assertTrue(fileName.contains("KPLUS"))
        assertTrue(fileName.contains("12345678"))
        assertTrue(fileName.endsWith(".jpg"))
    }
}
