# HyperOS / MIUI Optimization & Mitigation Guide

Xiaomi's Android skin (HyperOS / MIUI) includes some of the industry's most aggressive background process management systems, designed to preserve battery life at the expense of third-party background services.

This document details every known limitation on HyperOS and the robust mitigations built directly into **SlipSync**.

---

## 1. Known HyperOS Background Restrictions

| HyperOS Component | Behavior | Impact on Background Sync |
|---|---|---|
| **Security Center Autostart** | Blocks apps from launching background workers after boot or when closed unless "Autostart" is explicitly granted. | WorkManager jobs cannot execute if device reboots or app is closed. |
| **MIUI Battery Saver (PowerKeeper)** | Analyzes background CPU and network activity. After 10-15 minutes of screen-off, kills background jobs and blocks network access. | Background uploads fail or are deferred until user turns on the screen. |
| **MIUI Task Cleaner / Clear Cache** | When swiping apps away in Recent Apps or tapping the "Broom" memory cleaner, forcefully terminates the Linux process and cancels pending alarms. | MediaStore Content Observers are killed until the app is launched again. |
| **Deep Sleep / Doze Aggressiveness** | Puts non-whitelisted apps into deep Doze, restricting `JobScheduler` and `WorkManager` window executions. | 15-minute periodic sync may be delayed up to several hours. |

---

## 2. SlipSync Mitigations

### 2.1 OneTimeWork with ContentUriTrigger (`Constraints.addContentUriTrigger`)
- **How it works**: Instead of running a continuous, heavy background service (which HyperOS kills immediately), SlipSync registers an OS-level content trigger on `MediaStore.Images.Media.EXTERNAL_CONTENT_URI` with `descendants = true`.
- **HyperOS Resilience**: Content URI triggers are managed by Android's system `JobScheduler` daemon (`system_server`), not by SlipSync's process. When a banking app writes a file, Android OS wakes SlipSync's worker up just long enough to process the upload, consuming minimal battery.

### 2.2 Re-enqueuing Loop with `ExistingWorkPolicy.KEEP`
- On every run of `SlipContentTriggerWorker`, it immediately re-schedules itself using `ExistingWorkPolicy.REPLACE`, keeping the MediaStore listener permanently registered across days of operation.
- Every sync execution enqueues one single unique work name `"slip-sync"` with `ExistingWorkPolicy.KEEP`, preventing concurrent duplicate runs even if multiple bank slips are saved in quick succession.

### 2.3 15-Minute Periodic Fallback Net
- If a content URI event is dropped while the device is in deep Doze, `SlipPeriodicWorker` runs every 15 minutes as a fallback safety net to scan for any missed slips.

### 2.4 Worker Crash Recovery (`UPLOADING` -> `PENDING`)
- If HyperOS's memory manager kills the process halfway through a 10MB base64 upload, the row could remain stuck in `UPLOADING` state.
- At the very first line of `SlipUploadWorker.doWork()`, SlipSync runs:
  ```sql
  UPDATE slips SET status = 'PENDING' WHERE status = 'UPLOADING'
  ```
  This guarantees that interrupted uploads are automatically recovered and retried on the next run.

### 2.5 24-Hour Watchdog System
- If HyperOS somehow freezes the process for over 24 hours, `SlipWatchdogWorker` and `SlipApplication` evaluate `System.currentTimeMillis() - lastSuccessfulRun`.
- When elapsed time > 24 hours, SlipSync raises a **High Importance notification** alerting you:
  *"ระบบ SlipSync หยุดทำงาน: ไม่ได้ทำงานนานเกิน 24 ชั่วโมง กรุณาเปิดแอปเพื่อตรวจสอบสิทธิ์"*

### 2.6 Native Shortcut Buttons in App UI
- **Setup & Settings screens** include direct, 1-tap intent buttons that launch HyperOS internal setting screens:
  1. `com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity` (Autostart)
  2. `com.miui.powerkeeper/com.miui.powerkeeper.ui.HiddenAppsConfigActivity` (Battery No Restrictions)
  3. Dialog with graphic illustration showing how to long-press the SlipSync card in Recent Apps and tap the **Padlock icon** to lock it in memory.

---

## 3. Recommended HyperOS User Settings

To achieve **100% background sync reliability**, configure these 3 settings once:

1. **Autostart**:
   - Go to **Settings** > **Apps** > **Permissions** > **Autostart** > Find **SlipSync** > Toggle **ON**.
2. **Battery Saver**:
   - Long press the SlipSync app icon > Tap **App info** (ⓘ).
   - Tap **Battery saver** > Select **No restrictions** (instead of "Battery saver (recommended)").
3. **Lock in Recents**:
   - Open Recent Apps (swipe up and hold from the bottom of the screen).
   - Long press the SlipSync card.
   - Tap the **Padlock icon** (🔒).
   - A padlock will appear on the top right of the card, preventing HyperOS from clearing it when tapping the "X" broom icon.
