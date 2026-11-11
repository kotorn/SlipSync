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
[ Resilient Upload Queue & Concurrency Lease ]
  • Worker leases item (5-min expiration) with workerRunId
  • WorkManager unique work "slip-sync" (ExistingWorkPolicy.KEEP)
        │
        ▼ (HTTPS POST JSON with redirect handling)
[ Google Apps Script Web App Receiver ]
  • Token authentication in POST body
  • Formula Injection Sanitization (=, +, -, @ escaped)
  • Google Sheet Ledger (`SlipLedger`) Deduplication
  • Idempotent Google Drive file upload with structured metadata
        │
        ▼
[ Google Drive / Gemini Enterprise Agent Trigger ]
```

---

## 3. P0 Critical Fixes & Improvements

### 3.1 Unified Shared Image & MediaStore Pipeline
- **Previous Issue**: Images shared via Android's `ACTION_SEND` were saved into `cacheDir`, assigned arbitrary dummy `mediaStoreId`s, and ignored by the upload worker. Furthermore, UI thumbnail rendering crashed attempting to resolve a non-existent MediaStore URI.
- **Production Solution**:
  - `FileUtils.copyStreamToDurableStorage()` immediately copies the incoming stream to `context.filesDir/shared_slips/` (which OS cache-cleaners never purge).
  - Both `SlipItemRow` and `SlipDetailScreen` dynamically inspect `item.localFilePath` first; if present, Coil loads the durable file directly.
  - `SlipUploadWorker` processes both MediaStore-discovered and shared queue entries identically through `slipDao.getEligibleUploads()`.

### 3.2 Durable Queue with Concurrency Leases
- **Previous Issue**: Crashing or killed workers could leave rows in `UPLOADING` state indefinitely or cause race conditions if multiple triggers (WorkManager, tile, share target) fired at once.
- **Production Solution**:
  - `uploadLeaseUntil` (epoch timestamp) and `workerRunId` columns track active upload leases.
  - `slipDao.acquireLease()` atomically acquires a 5-minute lease.
  - On worker launch, `slipDao.resetExpiredLeases(now)` recovers abandoned uploads safely without re-uploading active ones.
  - All background triggers enforce `ExistingWorkPolicy.KEEP` under unique work tag `"slip-sync"`.

### 3.3 Hardware-Backed Credential Security
- **Previous Issue**: Authentication tokens were stored in plain text DataStore.
- **Production Solution**:
  - `CryptoManager` utilizes `AndroidKeyStore` with `AES-256-GCM` (`AES/GCM/NoPadding`).
  - Transparent migration: on first read, any legacy plaintext token is encrypted into `encrypted_auth_token` and the plaintext key is deleted.
  - Excluded from cloud backup in `backup_rules.xml` and `data_extraction_rules.xml`.
  - Exported log files redact both the token and raw QR payload.

### 3.4 Two-Tier Deduplication (PromptPay EMVCo + SHA-256)
- **Previous Issue**: Slips re-saved with different compression or timestamp could produce different SHA-256 hashes.
- **Production Solution**:
  - `ThaiSlipQrParser` parses Bank of Thailand (BOT) National e-Payment EMVCo payloads (`000201...`, AID `A000000677010111`) and official bank verification URLs, extracting `normalizedTransactionId`.
  - Deduplication checks `normalizedTransactionId` first, then full `slipQr`, and falls back to `sha256`.

---

## 4. Google Apps Script Receiver Hardening

### 4.1 Formula / CSV Injection Protection
User-controlled fields (`fileName`, `sourceFolder`, `slipQr`, `sha256`, `transactionId`) appended to Google Sheets could potentially start with `=`, `+`, `-`, or `@`. `sanitizeCell()` prepends `'` to neutralize formula execution risks.

### 4.2 Idempotency & Concurrency Locking
- `LockService.getScriptLock().tryLock(30000)` prevents concurrent race conditions across simultaneous uploads.
- Drive folder is checked by `fileName` before creating a new file blob.
- Returns structured JSON `{ ok: true, duplicate: ..., duplicateType: ..., fileId: ... }`.

---

## 5. Xiaomi HyperOS / MIUI Optimization

| Feature | HyperOS Behavior | SlipSync Mitigation |
|---|---|---|
| **Aggressive Process Termination** | Kills background services within 3–5 minutes | Enqueued as standard `OneTimeWorkRequest` with `Constraints.addContentUriTrigger` |
| **Doze Mode & App Standby** | Pauses network operations when device is idle | Periodic fallback (every 15 min) + `BootReceiver` re-enqueuing on boot |
| **Partial Photo Access (Android 14)** | `READ_MEDIA_VISUAL_USER_SELECTED` blocks background scans | Detects partial access and displays a blocking alert banner with deep link |
| **Watchdog Alert** | User unaware if background sync stopped for >24h | High-priority notification when `lastSuccessfulRun` > 24 hours |

---

## 6. Token Rotation & Operational Procedures

1. **Rotating Receiver Token**:
   - In Google Apps Script Project Settings > Script Properties > update `AUTH_TOKEN` with a new random 32+ character string.
   - In the Android SlipSync app > Settings > Web App Settings > paste the new token > tap **"Test connection"** > Save.
2. **Backfilling Previous Slips**:
   - In SlipSync Settings > tap **"Backfill last N days"** (choose 7, 30, or 90 days).
   - This adjusts folder activation timestamps backwards and queues historical slips for upload.
3. **Log Sanitization**:
   - The "Export Logs" button exports sync diagnostics with tokens, QR payloads, and image bytes completely stripped.
