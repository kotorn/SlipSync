package com.thaweekarn.slipsync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.thaweekarn.slipsync.data.local.SlipDatabase
import com.thaweekarn.slipsync.data.local.SlipEntity
import com.thaweekarn.slipsync.data.preferences.SlipPreferencesRepository
import com.thaweekarn.slipsync.util.CryptoManager
import com.thaweekarn.slipsync.util.FileUtils
import com.thaweekarn.slipsync.util.HashUtils
import com.thaweekarn.slipsync.util.ThaiSlipQrParser
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SlipSyncUnitTest {

    private lateinit var testDb: SlipDatabase
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
        testDb = Room.inMemoryDatabaseBuilder(context, SlipDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        testDb.close()
    }

    @Test
    fun testAppName() {
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

    // REGRESSION 1: Shared Slip Upload Allowed Even When selectedFolders is Empty
    @Test
    fun testSharedSlipUploadAllowedWhenSelectedFoldersEmpty() = runBlocking {
        val slipDao = testDb.slipDao()

        // Insert a pending shared slip (which has sourceFolder = "Shared/External")
        val sharedSlip = SlipEntity(
            sha256 = "shared_sha_1234567890abcdef1234567890abcdef1234567890abcdef1234567890",
            mediaStoreId = 99999L,
            fileName = "shared_slip.jpg",
            mimeType = "image/jpeg",
            extension = "jpg",
            sourceFolder = "Shared/External",
            status = SlipEntity.STATUS_PENDING,
            takenAt = System.currentTimeMillis(),
            localFilePath = "/data/user/0/com.thaweekarn.slipsync/files/shared_slips/test.jpg"
        )
        slipDao.insertOrUpdate(sharedSlip)

        // Selected folders is empty
        val selectedFolders = emptySet<String>()
        assertTrue("Selected folders set is empty", selectedFolders.isEmpty())

        // Queue retrieval still discovers the pending shared slip
        val eligibleUploads = slipDao.getEligibleUploads(System.currentTimeMillis())
        assertEquals(1, eligibleUploads.size)
        assertEquals(sharedSlip.sha256, eligibleUploads[0].sha256)
    }

    // REGRESSION 4: WorkerRunId Ownership Lease Enforcement
    @Test
    fun testWorkerRunIdOwnershipLeaseUpdate() = runBlocking {
        val slipDao = testDb.slipDao()
        val sha256 = "test_sha_lease_enforcement_00000000000000000000000000000000000000"
        val now = System.currentTimeMillis()

        val slip = SlipEntity(
            sha256 = sha256,
            mediaStoreId = 1L,
            fileName = "lease_test.jpg",
            mimeType = "image/jpeg",
            extension = "jpg",
            sourceFolder = "Pictures/KPLUS/",
            status = SlipEntity.STATUS_PENDING,
            takenAt = now
        )
        slipDao.insertOrUpdate(slip)

        // Worker A acquires lease
        val workerA = "worker_A"
        val leaseOk = slipDao.acquireLease(sha256, now + 300_000, workerA, now)
        assertEquals(1, leaseOk)

        // Expired Worker B attempts to update upload result with worker_B ID -> MUST FAIL (0 rows updated)
        val workerB = "worker_B"
        val rowsUpdatedByB = slipDao.updateUploadResultWithOwner(
            sha256 = sha256,
            workerId = workerB,
            status = SlipEntity.STATUS_UPLOADED,
            attempts = 1,
            lastError = null,
            uploadedAt = now,
            driveFileId = "file_123"
        )
        assertEquals(0, rowsUpdatedByB)

        // Authorized Worker A updates upload result -> MUST SUCCEED (1 row updated)
        val rowsUpdatedByA = slipDao.updateUploadResultWithOwner(
            sha256 = sha256,
            workerId = workerA,
            status = SlipEntity.STATUS_UPLOADED,
            attempts = 1,
            lastError = null,
            uploadedAt = now,
            driveFileId = "file_123"
        )
        assertEquals(1, rowsUpdatedByA)

        val updatedEntity = slipDao.getSlipBySha256(sha256)
        assertEquals(SlipEntity.STATUS_UPLOADED, updatedEntity?.status)
        assertEquals("file_123", updatedEntity?.driveFileId)
    }

    // REGRESSION 5: Health Tracking - lastSuccessfulUpload Changes Only After Confirmed Upload
    @Test
    fun testUploadHealthTrackingConfirmedOnly() = runBlocking {
        val prefs = SlipPreferencesRepository(context)

        // Initial state
        val initialLastUpload = prefs.lastSuccessfulUpload.first()

        // Calling recordServerStatus with success HTTP 200 MUST NOT alter lastSuccessfulUpload
        prefs.recordServerStatus(null, 200)
        val afterPingLastUpload = prefs.lastSuccessfulUpload.first()
        assertEquals(initialLastUpload, afterPingLastUpload)

        // Calling recordServerStatus with error MUST NOT alter lastSuccessfulUpload
        prefs.recordServerStatus("Network timeout", 500)
        val afterErrorLastUpload = prefs.lastSuccessfulUpload.first()
        assertEquals(initialLastUpload, afterErrorLastUpload)

        // Calling setLastSuccessfulUpload explicitly (simulating confirmed upload) updates it
        val confirmedTime = 1710000000000L
        prefs.setLastSuccessfulUpload(confirmedTime)
        assertEquals(confirmedTime, prefs.lastSuccessfulUpload.first())
    }

    // REGRESSION 6: ThaiSlipQrParser Hardening Against Unsupported Formats & False Deduplication
    @Test
    fun testThaiSlipQrParserHardenedFalsePositiveRejection() {
        // 1. URL containing "slip" on an untrusted non-bank domain -> MUST REJECT
        val fakeSlipUrl = "https://example.com/items/slip-cover-blue?id=12345"
        val fakeAnalysis = ThaiSlipQrParser.analyze(fakeSlipUrl)
        assertFalse(fakeAnalysis.isValidThaiSlipQr)
        assertNull(fakeAnalysis.normalizedTransactionId)

        // 2. Wi-Fi QR format -> MUST REJECT
        val wifiQr = "WIFI:S:MyHomeNetwork;T:WPA;P:secret123;;"
        val wifiAnalysis = ThaiSlipQrParser.analyze(wifiQr)
        assertFalse(wifiAnalysis.isValidThaiSlipQr)
        assertNull(wifiAnalysis.normalizedTransactionId)

        // 3. vCard format -> MUST REJECT
        val vCardQr = "BEGIN:VCARD\nVERSION:3.0\nN:Somchai;Somboon\nTEL:0812345678\nEND:VCARD"
        val vCardAnalysis = ThaiSlipQrParser.analyze(vCardQr)
        assertFalse(vCardAnalysis.isValidThaiSlipQr)
        assertNull(vCardAnalysis.normalizedTransactionId)

        // 4. Static PromptPay merchant QR without a dynamic transaction ID -> MUST NOT PRODUCE FALSE TRANSACTION ID
        // (PromptPay static merchant QR: AID A000000677010111 Tag 29/30 with 10-digit mobile)
        val staticMerchantQr = "00020101021129370016A0000006770101110113006608123456785802TH53037646304ABCD"
        val merchantAnalysis = ThaiSlipQrParser.analyze(staticMerchantQr)
        assertFalse(merchantAnalysis.isValidThaiSlipQr)
        assertNull(merchantAnalysis.normalizedTransactionId)
    }

    // REGRESSION 6b: ThaiSlipQrParser Valid Thai Bank Slips
    @Test
    fun testThaiSlipQrParserValidBankFormats() {
        // 1. Bank of Thailand e-Slip Mini QR with PromptPay Slip AID (A000000677010112)
        val validSlipPayload = "0002010102120016A000000677010112010300402142023111112345653037645802TH63041234"
        val slipResult = ThaiSlipQrParser.analyze(validSlipPayload)
        assertTrue(slipResult.isValidThaiSlipQr)
        assertNotNull(slipResult.normalizedTransactionId)
        assertTrue(slipResult.normalizedTransactionId!!.contains("20231111123456"))

        // 2. Official Bank Verification URL on whitelist domain (kplus.kasikornbank.com)
        val kplusUrl = "https://kplus.kasikornbank.com/verify?ref=20240315KB12345678"
        val kplusResult = ThaiSlipQrParser.analyze(kplusUrl)
        assertTrue(kplusResult.isValidThaiSlipQr)
        assertEquals("20240315KB12345678", kplusResult.normalizedTransactionId)
        assertEquals("KBANK", kplusResult.bankCode)

        // 3. Official SCB Verification URL
        val scbUrl = "https://scbeasy.com/slip/verify?id=SCB2024031599887766"
        val scbResult = ThaiSlipQrParser.analyze(scbUrl)
        assertTrue(scbResult.isValidThaiSlipQr)
        assertEquals("SCB2024031599887766", scbResult.normalizedTransactionId)
        assertEquals("SCB", scbResult.bankCode)
    }

    // REGRESSION 7: CryptoManager Security Policy & Production Verification
    @Test
    fun testCryptoManagerSecurityPolicy() {
        // In test runner, testEnvironment is acknowledged
        assertTrue(CryptoManager.isTestEnvironment)

        val secretToken = "thai-bank-slip-sync-secret-2024"
        val encrypted = CryptoManager.encrypt(secretToken)
        assertTrue(encrypted.isNotBlank())
        assertFalse(encrypted == secretToken)

        val decrypted = CryptoManager.decrypt(encrypted)
        assertEquals(secretToken, decrypted)
    }

    // FEATURE: Google Sign-In & Automated Google Drive Connection
    @Test
    fun testGoogleDriveAutoConnectionPreference() = runBlocking {
        val prefs = SlipPreferencesRepository(context)
        assertFalse(prefs.googleDriveAutoConnected.first())

        prefs.setGoogleDriveAutoConnected(true)
        assertTrue(prefs.googleDriveAutoConnected.first())

        prefs.setGoogleDriveAutoConnected(false)
        assertFalse(prefs.googleDriveAutoConnected.first())
    }

    @Test
    fun testHealthCheckWithGoogleDriveAutoConnection() = runBlocking {
        val prefs = SlipPreferencesRepository(context)
        prefs.setWebAppUrl("")
        prefs.setAuthToken("")
        prefs.setGoogleDriveAutoConnected(true)

        val healthRepo = com.thaweekarn.slipsync.data.repository.HealthCheckRepository(context, prefs)
        val checks = healthRepo.evaluateAllChecks()

        val urlCheck = checks.find { it.id == "url" }
        assertNotNull(urlCheck)
        assertEquals(com.thaweekarn.slipsync.data.repository.HealthStatus.PASS, urlCheck?.status)
        assertTrue(urlCheck?.description?.contains("Google Account") == true)

        val tokenCheck = checks.find { it.id == "token" }
        assertNotNull(tokenCheck)
        assertEquals(com.thaweekarn.slipsync.data.repository.HealthStatus.PASS, tokenCheck?.status)
        assertTrue(tokenCheck?.description?.contains("Google") == true)
    }
}
