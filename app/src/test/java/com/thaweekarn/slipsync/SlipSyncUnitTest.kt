package com.thaweekarn.slipsync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.thaweekarn.slipsync.util.CryptoManager
import com.thaweekarn.slipsync.util.FileUtils
import com.thaweekarn.slipsync.util.HashUtils
import com.thaweekarn.slipsync.util.ThaiSlipQrParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    @Test
    fun testFolderPathNormalizationAndMatching() {
        val rawPath1 = "/storage/emulated/0/Pictures/KPLUS/slip.jpg"
        assertEquals("Pictures/KPLUS/", FileUtils.normalizeFolderPath(rawPath1))

        val rawPath2 = "DCIM/SCB EASY"
        assertEquals("DCIM/SCB EASY/", FileUtils.normalizeFolderPath(rawPath2))

        assertTrue(FileUtils.isFolderMatched("Pictures/KPLUS/", "Pictures/KPLUS/"))
        assertTrue(FileUtils.isFolderMatched("Pictures/KPLUS/sub/", "Pictures/KPLUS/"))
        assertFalse(FileUtils.isFolderMatched("Pictures/KPLUS_BACKUP/", "Pictures/KPLUS/"))
    }

    @Test
    fun testExtractExtension() {
        assertEquals("png", FileUtils.extractExtension("slip.png", "image/png"))
        assertEquals("jpeg", FileUtils.extractExtension("receipt.jpeg", "image/jpeg"))
        assertEquals("webp", FileUtils.extractExtension("receipt.webp", "image/webp"))
        assertEquals("jpg", FileUtils.extractExtension("unknown_file", "image/jpeg"))
    }

    @Test
    fun testThaiSlipQrParserPromptPayEmvCo() {
        // Standard Bank of Thailand / PromptPay slip QR payload
        val emvCoPayload = "0002010102120016A0000006770101110113006600000000102142023111112345653037645802TH6304ABCD"
        val result = ThaiSlipQrParser.analyze(emvCoPayload)

        assertTrue(result.isValidBankQr)
        assertNotNull(result.normalizedTransactionId)
        assertTrue(result.normalizedTransactionId!!.contains("20231111123456"))
    }

    @Test
    fun testThaiSlipQrParserBankUrl() {
        val bankUrl = "https://slip.kplus.com/verify?ref=20231111KP987654321"
        val result = ThaiSlipQrParser.analyze(bankUrl)

        assertTrue(result.isValidBankQr)
        assertEquals("20231111KP987654321", result.normalizedTransactionId)
    }

    @Test
    fun testThaiSlipQrParserNonBankQr() {
        val normalText = "https://example.com/some/article/123"
        val result = ThaiSlipQrParser.analyze(normalText)

        assertFalse(result.isValidBankQr)
        assertNull(result.normalizedTransactionId)
    }

    @Test
    fun testCryptoManagerEncryptionRoundtrip() {
        val secretToken = "super-secret-thai-bank-slip-token-2024"
        val encrypted = CryptoManager.encrypt(secretToken)
        assertNotNull(encrypted)
        assertTrue(encrypted!!.isNotBlank())
        assertFalse(encrypted == secretToken)

        val decrypted = CryptoManager.decrypt(encrypted)
        assertEquals(secretToken, decrypted)
    }
}
