# SlipSync: Production Readiness & Architecture Audit

## 1. Executive Summary

SlipSync (`com.thaweekarn.slipsync`) is a specialized personal automation Android app and Google Apps Script receiver built to reliably bridge Thai banking apps (e.g., K PLUS, SCB EASY, Krungthai NEXT, Bangkok Bank, Krungsri, ttb touch, GSB, BAAC) with Google Drive and downstream Gemini Enterprise automation agents.

The core design philosophy is: **"Detect new slip -> Read QR -> Upload once -> Never lose or duplicate."**

This document details the hardening, security improvements, failure mode analysis, and production verification performed across the Android app, Google Apps Script receiver, and Xiaomi HyperOS background execution environment.

---

## 2. Architecture & Pipeline Overview

```
[ Thai Banking Apps ]
        │ (Saves image to storage / Share via intent)
        ▼
[ MediaStore Observer / ACTION_SEND ShareTarget ]
        │
        ▼
[ Unified Deduplication & Durable Queue (Room DB v2) ]
  • MediaStore candidates & shared streams copied to filesDir/shared_slips
  • Offline ML Kit Barcode Scanning -> ThaiPromptPay EMVCo / Bank URL parsing
  • Deduplication priority: 1. normalizedTransactionId -> 2. slipQr -> 3. SHA-256
        │
        ▼
[ Resilient Upload Queue & Concurrency Lease with Worker Ownership ]
  • Worker leases item (5-min expiration) with workerRunId
  • Update ownership enforcement: expired workers cannot overwrite newer state
  • WorkManager unique work "slip-sync" (ExistingWorkPolicy.KEEP)
        │
        ▼ (HTTPS POST JSON with redirect handling)
[ Google Apps Script Web App Receiver ]
  • Token authentication in POST body
  • Server-side SHA-256 integrity verification
  • Formula Injection Sanitization (=, +, -, @ escaped)
  • Google Sheet Ledger (`SlipLedger`) Deduplication & 8-to-9 Column Migration
  • Content-based Drive file idempotency with structured metadata
        │
        ▼
[ Google Drive / Gemini Enterprise Agent Trigger ]
```

---

## 3. P0/P1 Follow-Up Audit & Fixes Implemented

### 3.1 Shared Slip Upload Allowed When `selectedFolders` is Empty (P0)
- **Problem**: Previously, `SlipUploadWorker` performed an early return if `selectedFolders.isEmpty()`. Users who did not select MediaStore folders or who relied strictly on the Share Target (`ACTION_SEND`) to queue slips could never upload them.
- **Fix**: The worker now only exits early if core server credentials (`webAppUrl` or `authToken`) are missing. If `selectedFolders` is empty, Phase 1 (MediaStore scan) is bypassed, but Phase 2 (Upload Queue processing) proceeds normally, successfully uploading all pending shared slips.
- **Verification**: `testSharedSlipUploadAllowedWhenSelectedFoldersEmpty` (PASS).

### 3.2 Backward-Compatible Google Sheets Ledger Migration (P1)
- **Problem**: Early versions of `Code.gs` initialized an 8-column ledger: `[slipQr, sha256, fileId, fileName, sourceFolder, hasQr, takenAt, receivedAt]`. Upgrading to the 9-column schema (with `transactionId` in Column 1) risked misaligned data rows or breaking deduplication against historical records.
- **Fix**: `migrateLedgerSheetIfNeeded()` inspects Row 1. If Column 1 is `"slipQr"` and `"transactionId"` is missing, it dynamically inserts a new column at index 1 (`insertColumnBefore(1)`), labels it `"transactionId"`, and backfills transaction IDs from existing QR strings where possible. Furthermore, `checkDuplicate()` dynamically builds a header-to-column index map (`colMap`), ensuring accurate deduplication across both 8-column and 9-column sheets without data corruption.

### 3.3 Server-Side SHA-256 Verification & Content-Based Drive Idempotency (P0)
- **Problem**: The receiver previously trusted client-supplied SHA-256 values without server validation and checked Drive idempotency solely by filename (`folder.getFilesByName(fileName)`), which fails if filenames vary or collide.
- **Fix**:
  1. `Code.gs` computes `Utilities.computeDigest(Utilities.DigestAlgorithm.SHA_256, decodedBytes)` and asserts byte-level equivalence against client `sha256`. If mismatched, it returns HTTP 400 `CHECKSUM_MISMATCH`.
  2. Idempotency checks first query Drive using file description metadata: `folder.searchFiles("description contains '\"sha256\": \"" + sha256 + "\"' and trashed = false")`. If found, the existing file is returned, preventing redundant blob creation.

### 3.4 Worker Ownership Enforcement on Lease Updates (P0)
- **Problem**: `SlipDao.updateUploadResult` previously executed `WHERE sha256 = :sha256`. If Worker A's lease expired or lagged and Worker B picked up the item, Worker A could subsequently overwrite Worker B's newer result upon completion.
- **Fix**: Implemented `updateUploadResultWithOwner(sha256, workerId, ...)` with `WHERE sha256 = :sha256 AND workerRunId = :workerId`. Returns the number of affected rows. If 0 (indicating lease preemption or expiration), the result is discarded safely.
- **Verification**: `testWorkerRunIdOwnershipLeaseUpdate` (PASS).

### 3.5 Health Tracking: `lastSuccessfulUpload` Isolation (P1)
- **Problem**: `recordServerStatus(null, 200)` erroneously updated `lastSuccessfulUpload`, meaning routine health checks (ping) or duplicate responses falsely registered as new uploads.
- **Fix**: `recordServerStatus()` now strictly updates server status codes and error messages. `prefsRepo.setLastSuccessfulUpload()` is exclusively invoked in `SlipUploadWorker` after confirmed successful upload of a non-duplicate slip with active lease ownership (`updated > 0`).
- **Verification**: `testUploadHealthTrackingConfirmedOnly` (PASS).

### 3.6 Hardened Thai Slip QR Parser (P1)
- **Problem**: Generic URLs containing the substring `"slip"` or static merchant PromptPay QRs (without dynamic transaction references) could trigger false positive bank detection and cause erroneous deduplication.
- **Fix**:
  1. Bank URLs must strictly match authoritative host domains (`kasikornbank.com`, `scbeasy.com`, `scb.co.th`, `krungthai.com`, `bangkokbank.com`, `krungsri.com`, `ttbbank.com`, `gsb.or.th`, `baac.or.th`, `promptpay.co.th`).
  2. Extracted URL transaction reference must be between 6 and 64 alphanumeric characters.
  3. Static PromptPay merchant QRs (AID `A000000677010111` without dynamic reference) return `normalizedTransactionId = null` and `isValidThaiSlipQr = false`.
  4. Unsupported formats (Wi-Fi, vCard, mailto, tel) are rejected immediately.
- **Verification**: `testThaiSlipQrParserHardenedFalsePositiveRejection` (PASS) and `testThaiSlipQrParserValidBankFormats` (PASS).

### 3.7 Removal of Insecure/Nonpersistent Keystore Fallbacks in Production (P0)
- **Problem**: Previous fallback logic generated an in-memory ephemeral AES key when KeyStore initialization caught an error, which meant killing or restarting the app would destroy the key and render stored tokens un-decryptable.
- **Fix**: In production builds (`!isTestEnvironment`), `CryptoManager` strictly requires `AndroidKeyStore`. If hardware KeyStore services fail on a device, a `SecurityException` is thrown rather than silently generating an ephemeral throwaway key. The fallback is strictly isolated to JVM test runners (`isTestEnvironment == true`).
- **Verification**: `testCryptoManagerSecurityPolicy` (PASS).

### 3.8 Gradle Wrapper & Toolchain Repair
- **Problem**: `gradle-wrapper.jar` was absent from `gradle/wrapper/`.
- **Fix**: Executed `gradle wrapper`, generating valid `gradlew`, `gradlew.bat`, and `gradle-wrapper.jar` components.
- **Verification**: Clean build and successful execution of `:app:lintDebug` and `:app:testDebugUnitTest`.

### 3.9 Firebase Authentication & Automated Google Drive Connection (Feature)
- **Problem**: Manually deploying and configuring Google Apps Script with Web App URLs and secret tokens was tedious and error-prone for non-technical users.
- **Fix**:
  1. Integrated Firebase Authentication with Google Sign-In via Jetpack `CredentialManager` (`GetSignInWithGoogleOption`).
  2. Integrated Cloud Firestore with zero-trust security rules (`request.auth.uid == userId`) for storing user profile and Google Drive folder routing configuration.
  3. Added Google Account management in `SettingsScreen` (avatar, email, connection status, sign-in/sign-out buttons).
  4. Added 1-tap Google Sign-In option in `OnboardingScreen` Step 2 with automatic progression.
  5. Updated `HealthCheckRepository` and `HomeViewModel` to recognize Google account authentication as an active cloud connection.
- **Verification**: `testGoogleDriveAutoConnectionPreference` (PASS) and `testHealthCheckWithGoogleDriveAutoConnection` (PASS).

---

## 4. Test Verification Matrix & Evidence

| Test Case | Scope | Expected Behavior | Result |
|---|---|---|---|
| `testAppName` | App Branding | Matches "SlipSync" | **PASS** |
| `testHashCalculation` | Cryptography | 64-char lowercase hex SHA-256 | **PASS** |
| `testFileNameGeneration` | File Formatting | yyyyMMdd_HHmmss_<bank>_<sha8>.ext | **PASS** |
| `testFolderPathNormalizationAndMatching` | MediaStore | Strict folder match, no sub-name false matches | **PASS** |
| `testExtractExtension` | File Extension | Preserves jpg, jpeg, png, webp | **PASS** |
| `testSharedSlipUploadAllowedWhenSelectedFoldersEmpty` | Queue / Shared Slips | Discovers & uploads shared slips with empty folder set | **PASS** |
| `testWorkerRunIdOwnershipLeaseUpdate` | Concurrency | Unauthorized worker updates 0 rows; owner updates 1 row | **PASS** |
| `testUploadHealthTrackingConfirmedOnly` | Health Telemetry | `lastSuccessfulUpload` does not change on ping or server status | **PASS** |
| `testThaiSlipQrParserHardenedFalsePositiveRejection` | QR Parsing | Rejects non-bank URLs, Wi-Fi, vCard, static merchant QRs | **PASS** |
| `testThaiSlipQrParserValidBankFormats` | QR Parsing | Validates BOT slip mini-QR and official bank URLs | **PASS** |
| `testCryptoManagerSecurityPolicy` | Security | Persistent Keystore in prod; clean test runner isolation | **PASS** |
| `testGoogleDriveAutoConnectionPreference` | Cloud Auth | Persists and exposes Google Drive connection state | **PASS** |
| `testHealthCheckWithGoogleDriveAutoConnection` | System Health | Recognizes Google Sign-In as valid cloud connection | **PASS** |

### Build & Verification Evidence:
- **Gradle Compilation**: `compile_applet` PASSED cleanly (`Build succeeded - the applet is compiled`).
- **Unit & Robolectric Tests**: `gradle :app:testDebugUnitTest` PASSED (13 of 13 tests PASSED, 0 failures, 0 errors).
- **Android Lint**: PASSED (0 fatal errors).

---

## 5. Token Rotation & Operational Procedures

1. **Rotating Receiver Token**:
   - In Google Apps Script Project Settings > Script Properties > update `AUTH_TOKEN` with a new random 32+ character string.
   - In the Android SlipSync app > Settings > Web App Settings > paste the new token > tap **"Test connection"** > Save.
2. **Backfilling Previous Slips**:
   - In SlipSync Settings > tap **"Backfill last N days"** (choose 7, 30, or 90 days).
   - This adjusts folder activation timestamps backwards and queues historical slips for upload.
3. **Log Sanitization**:
   - The "Export Logs" button exports sync diagnostics with tokens, QR payloads, and image bytes completely stripped.
