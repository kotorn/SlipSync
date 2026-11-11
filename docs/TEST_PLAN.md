# SlipSync Test Plan (แผนการทดสอบระบบ)

This test plan validates all critical user journeys, background resilience on Xiaomi / HyperOS, and zero-data-loss guarantees.

---

## Test Matrix

| ID | Scenario | Pre-condition | Test Steps | Expected Result | Pass Criteria |
|---|---|---|---|---|---|
| **TC-01** | **New Slip Detection** | App setup complete, K PLUS folder enabled | 1. Perform transfer in K PLUS or save slip image to `Pictures/KPLUS/`.<br>2. Keep app closed in background. | MediaStore triggers `SlipContentTriggerWorker` -> Reads QR -> Uploads to Drive -> Appends to Google Sheet -> Silent notification displayed. | Image appears in `Slips/Inbox`, status in Room = `UPLOADED`, row in `SlipLedger` sheet. |
| **TC-02** | **Offline then Online** | Device in Airplane Mode | 1. Save new slip image.<br>2. Open SlipSync.<br>3. Turn Airplane Mode OFF. | Slip added to Room with `PENDING` status. WorkManager waits for active network constraint. Once online, worker triggers automatically and uploads slip. | Status transitions `PENDING` -> `UPLOADING` -> `UPLOADED` with zero data loss. |
| **TC-03** | **Duplicate Slip Prevention** | Slip already uploaded | 1. Copy an existing slip image with same content/QR into a monitored folder.<br>2. Tap "Sync now". | On-device: Room checks `slipQr` and `sha256`; flags as `SKIPPED_DUPLICATE`. Server: If transmitted, Apps Script checks Sheet ledger and returns `{ ok: true, duplicate: true }` without saving a duplicate file. | Zero duplicate files created in `Slips/Inbox`. Only 1 row in `SlipLedger` sheet. |
| **TC-04** | **Phone Reboot** | Pending slips exist in Room | 1. Reboot Xiaomi device.<br>2. Do NOT open the app manually. | `BootReceiver` receives `ACTION_BOOT_COMPLETED` -> Restores `SlipContentTriggerWorker`, 15-minute `SlipPeriodicWorker`, and evaluates Watchdog. | Slip detection and uploads resume seamlessly in background without launching app. |
| **TC-05** | **App Killed by Battery Cleaner** | SlipSync killed from MIUI Recents (without lock) | 1. Swipe app away from Recent Apps.<br>2. Trigger a 15-minute periodic window or save a new slip. | Android WorkManager wakes up via system JobScheduler / AlarmManager; `SlipUploadWorker` resets any stale `UPLOADING` status to `PENDING` and executes upload. | Upload completes; Watchdog alerts if worker has been blocked for >24 hours. |
| **TC-06** | **Android 14 Partial Access** | Android 14+ device | 1. During onboarding or in Settings, select "Select photos and videos" instead of "Allow all". | App detects `READ_MEDIA_VISUAL_USER_SELECTED`. Home Health Card turns RED and displays warning banner with "Grant Full Access" button. | User taps Fix -> System App Settings opens -> User grants full access -> Banner clears to Green. |
| **TC-07** | **Share Target (ACTION_SEND)** | Banking or Gallery app open | 1. Tap Share on a slip image.<br>2. Choose "SlipSync Queue". | `ShareTargetActivity` copies image stream immediately to app-private cache (`shared_slips/`), computes hash, scans QR, queues to Room, and enqueues `slip-sync`. | Toast confirms queueing. File uploads to Drive immediately. |

---

## Detailed Step-by-Step Test Procedures

### Test 1: Real-time Slip Trigger
1. Ensure `Pictures/KPLUS/` is toggled ON in SlipSync Settings.
2. Put the app in background or lock the screen.
3. Save an image to `Pictures/KPLUS/slip_test.jpg` (or execute a transfer in K PLUS).
4. Watch the notification shade:
   - Within 5-15 seconds, a silent notification appears: *"อัปโหลดสลิป 1 ใบไปยัง Google Drive เรียบร้อยแล้ว"*.
5. Open Google Drive `Slips/Inbox`: verify `yyyyMMdd_HHmmss_KPLUS_xxxxxxxx.jpg` is present.
6. Open Google Drive file details: verify description has metadata JSON with `sha256`, `slipQr`, and `takenAt`.

### Test 2: Airplane Mode Disconnection
1. Turn on Airplane Mode.
2. Add a new slip image to `Pictures/KPLUS/`.
3. Open SlipSync: Home screen shows Amber status *"มีสลิป 1 ใบ กำลังรอการซิงค์"*.
4. Turn off Airplane Mode.
5. Within 30 seconds of Wi-Fi / cellular reconnection, WorkManager executes automatically.
6. Home status returns to Green *"ระบบทำงานปกติ"*.

### Test 3: Deduplication Stress Test
1. Re-upload the exact same slip via "Test slip upload" or "Sync now".
2. In the recent slips list, verify the status pill is `SKIPPED_DUPLICATE` or `UPLOADED`.
3. Open the `SlipLedger` Google Sheet: verify the number of rows did not increase.
4. Verify Drive folder still has exactly one copy of the file.
