# Thai Bank Slip Auto-Uploader & Drive Receiver (SlipSync) - Approved Plan

ระบบอัปโหลดสลิปธนาคารไทยอัตโนมัติ (K PLUS, SCB EASY, Krungthai NEXT ฯลฯ) จากโทรศัพท์ Xiaomi/HyperOS ไปยัง Google Drive ("Slips/Inbox") พร้อมสแกน QR Code ในตัวแบบออฟไลน์ (ML Kit Barcode Scanning) ตรวจสอบความซ้ำซ้อน บันทึกลง Google Sheet Ledger และระบบบริหารจัดการระดับโปรดักชัน

---

### Approved Architecture & Final Refinements

1. **Original Extension & MimeType Preservation**: Always preserve the exact original file extension (`.jpg`, `.jpeg`, `.png`, `.webp`) and MIME type (`image/jpeg`, `image/png`, etc.). Never force `.jpg`.
2. **MediaStore Safe Query**: Exclude incomplete or writing files (`IS_PENDING = 0`); treat `DATE_ADDED` as seconds (multiply by 1000 for millisecond timestamps).
3. **24h Watchdog**: Alerts whenever `last_successful_run > 24 hours` regardless of pending count; also evaluated on application launch and after device boot.
4. **Share Target Immediate Stream Copy**: When an image is shared to SlipSync via `ACTION_SEND`, immediately copy the stream into app-private cache storage (`context.cacheDir/shared_slips/`) and resolve its hash before enqueuing, never retaining foreign ephemeral content URIs.
5. **Unified WorkManager Concurrency**: All triggers (ContentUriTrigger, 15m Periodic, Quick Settings Tile, "Sync Now" button, and Share Target) enqueue one single unique work name `"slip-sync"` using `ExistingWorkPolicy.KEEP` to eliminate race conditions and concurrent upload collisions.
6. **Thai Default & Language Switch**: Thai (`th`) is the default locale set via `AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("th"))` on first run, with an in-app language switch in Settings.
7. **Xiaomi Health Check**: Autostart and Battery "No restrictions" are flagged as user-confirmed state checks; the primary objective health signal is the timestamp of the last successful worker run.
8. **Undo Snackbar on Ignore**: Swiping right or pressing "Ignore" updates status to `SKIPPED_IGNORED` and immediately shows an Undo snackbar allowing instantaneous restoration to `PENDING`.
9. **Slip Timeline "ไม่พบ QR"**: When `hasQr = false`, the detail timeline explicitly displays "ไม่พบ QR" (No QR found) with neutral status, confirming the slip was still safely uploaded using SHA-256 deduplication.
10. **Sanitized Log Exporter**: Excludes all authorization tokens, image base64, raw `slipQr` payloads, and full SHA-256 hashes (showing only first 8 chars for diagnostic identification).

---

## 1. Execution Stages

- **Stage 2**:
  - Update `metadata.json` (App Name: `SlipSync`) and `settings.gradle.kts`.
  - Update `libs.versions.toml` and `app/build.gradle.kts` (WorkManager, ML Kit Barcode Scanning, Coil, DataStore, AppCompat).
  - Update `AndroidManifest.xml` (Permissions, `SlipSyncTileService`, `ShareTargetActivity`, `BootReceiver`).
  - Create string resources (`res/values/strings.xml` and `res/values-th/strings.xml`).
  - Google Apps Script receiver (`google-apps-script/Code.gs` and `appsscript.json`) with Sheet ledger, `slipQr` & `sha256` dedup, LockService, and exact deployment guide.
- **Stage 3**:
  - Local database: `SlipEntity` (PK = sha256, indices on slipQr and mediaStoreId, userNote ready), `SlipDao`, `SlipDatabase`.
  - DataStore: `SlipPreferencesRepository` (URLs, token, folders, language, sync timestamps).
  - Remote: `SlipUploadApi`, OkHttp client with 302 redirect support, request/response models.
  - Utilities: `HashUtils`, `FileUtils`, `QrScannerHelper` (ML Kit offline QR).
- **Stage 4**:
  - Worker: `SlipUploadWorker` (resets UPLOADING to PENDING, scans MediaStore, reads QR, checks Room, uploads, sends notifications), `SlipContentTriggerWorker`, `SlipPeriodicWorker`, `SlipWatchdogWorker`.
  - System components: `WorkScheduler` (enqueues `"slip-sync"` with `ExistingWorkPolicy.KEEP`), `SlipSyncTileService`, `BootReceiver`, `NotificationHelper`.
- **Stage 5**:
  - UI: `OnboardingScreen` (5 steps), `HomeScreen` (Health Card, counters, swipe-to-action list, Undo snackbar), `SlipDetailScreen` (preview, timeline with "ไม่พบ QR", Drive link), `SettingsScreen`, `HealthCheckScreen` (6 checks), `BackfillDialog`.
  - ViewModels & Theme (M3 Dynamic Color, Dark/Light mode).
  - `ShareTargetActivity` (immediate private cache copy).
- **Stage 6**:
  - Documentation: Android Studio build guide, Xiaomi APK sideloading, comprehensive test plan, HyperOS mitigation guide, and build compilation verification (`compile_applet`).
