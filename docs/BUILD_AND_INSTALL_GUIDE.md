# SlipSync: Build & Installation Guide (คู่มือการติดตั้งและใช้งาน)

Complete step-by-step guide to building the SlipSync Android app in Android Studio, deploying the Google Apps Script receiver to Google Drive, and installing the APK on your Xiaomi / HyperOS device.

---

## Part 1: Deploy Google Apps Script Receiver (Web App)

Before running the Android app, deploy the Apps Script receiver so you have the **Web App URL** and **Security Token**.

### 1.1 Create Folder in Google Drive
1. Open [Google Drive](https://drive.google.com).
2. Create a new folder named `Slips` and inside it create a subfolder `Inbox` (Path: `Slips/Inbox`).
3. Open the `Inbox` folder and copy its **Folder ID** from your browser address bar:
   - Example URL: `https://drive.google.com/drive/folders/1a2B3c4D5e6F7g8H9i0J`
   - Folder ID: `1a2B3c4D5e6F7g8H9i0J`

### 1.2 Create Google Apps Script Project
1. Open [Google Apps Script](https://script.google.com/home/start).
2. Click **New Project** (+).
3. Name the project `SlipSync Receiver`.
4. Copy the entire contents of `google-apps-script/Code.gs` and paste it into the editor, replacing all default code.
5. In Project Settings (gear icon on the left menu) > Check **"Show 'appsscript.json' manifest file in editor"**.
6. Switch back to the Editor, open `appsscript.json`, and replace with the contents of `google-apps-script/appsscript.json`.

### 1.3 Configure Script Properties
1. Go to **Project Settings** (gear icon) > Scroll down to **Script Properties** > Click **Add script property**.
2. Add the following key-value pairs:
   - **`AUTH_TOKEN`**: Generate a strong secret string (e.g. `MySlipSecureToken2026_xYz987`).
   - **`FOLDER_ID`**: Paste the Folder ID copied in Step 1.1.
   - **`LEDGER_SHEET_ID`**: *(Optional)* Leave blank; the script will automatically create a dedicated `SlipLedger` spreadsheet in the same Drive folder with column headers `[slipQr, sha256, fileId, fileName, sourceFolder, hasQr, takenAt, receivedAt]`.
3. Click **Save script properties**.

### 1.4 Deploy as Web App
1. Click the blue **Deploy** button (top right) > **New deployment**.
2. Click the gear icon next to "Select type" > Choose **Web app**.
3. Set the following deployment configurations:
   - **Description**: `SlipSync v1 Production`
   - **Execute as**: `Me (your_email@gmail.com)` *(CRITICAL)*
   - **Who has access**: `Anyone` *(CRITICAL - authentication is verified via token in POST body)*
4. Click **Deploy**.
5. Grant permissions if prompted by Google (Click *Advanced* > *Go to SlipSync Receiver (unsafe)* > *Allow*).
6. Copy the generated **Web app URL**:
   - Format: `https://script.google.com/macros/s/AKfycbx.../exec`
   - Keep this URL and your `AUTH_TOKEN` ready for the Android app.

---

## Part 2: Build APK in Android Studio

### 2.1 Prerequisites
- Android Studio Hedgehog (2023.1.1) or newer / Ladybug (2024.2+).
- JDK 17 or JDK 21 configured as Gradle JDK.
- Android SDK Platform 34 or 35.

### 2.2 Open and Build
1. Open Android Studio > **File** > **Open** > Select the project directory.
2. Wait for Gradle Sync to complete.
3. Build the debug APK:
   - Menu bar: **Build** > **Build Bundle(s) / APK(s)** > **Build APK(s)**.
   - Or run from terminal: `gradle assembleDebug`
4. When the build finishes, click **locate** in the popup notification to find `app-debug.apk` (usually located at `app/build/outputs/apk/debug/app-debug.apk`).

---

## Part 3: Install on Xiaomi / HyperOS Device

### 3.1 Enable Unknown Apps & USB Debugging
1. On your Xiaomi phone, open **Settings** > **About phone**.
2. Tap **OS version** (or MIUI version) 7 times until you see *"You are now a developer!"*.
3. Go back to **Settings** > **Additional settings** > **Developer options**.
4. Enable:
   - **USB debugging**
   - **Install via USB**
   - *(Optional if installing via ADB)* Disable "Verify apps over USB" if prompted.
5. If sideloading the APK via Line, Telegram, or Google Drive directly on the phone:
   - Open the APK from File Manager.
   - Xiaomi Package Installer will prompt *"Allow installation from unknown sources"* > Toggle **Allow** > Check the 10-second warning checkbox > Tap **OK**.

### 3.2 Complete Onboarding in SlipSync
1. Launch **SlipSync**.
2. **Step 1 (Permissions)**: Tap *Grant Permission* > Select **"Allow all"** (Do NOT choose "Select photos and videos" on Android 14).
3. **Step 2 (Web App)**: Paste your `Web app URL` and `AUTH_TOKEN` > Tap **Test connection** (You should see green checkmark *"เชื่อมต่อสำเร็จ"*).
4. **Step 3 (Folders)**: Check the detected bank folders (e.g. `Pictures/KPLUS/`, `DCIM/SCB EASY/`) > Choose your backfill days (e.g. 7 days).
5. **Step 4 (HyperOS Optimization)**:
   - Tap **Autostart settings** > Toggle SlipSync to **ON**.
   - Tap **Battery (No restrictions)** > Select **No restrictions**.
   - Review the **Lock in Recents** guide.
6. **Step 5 (Verify)**: Test with a sample slip or tap **เสร็จสิ้น (Finish)**.
