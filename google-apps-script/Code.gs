/**
 * SlipSync Google Apps Script Receiver
 *
 * Receives bank transfer slips uploaded by the SlipSync Android app,
 * performs offline QR / SHA-256 deduplication via a Google Sheet ledger,
 * saves images to the Google Drive "Slips/Inbox" folder, tags file metadata,
 * and responds with JSON status for the Android client.
 *
 * Script Properties:
 *  - AUTH_TOKEN: Shared secret token for authenticating POST requests.
 *  - FOLDER_ID: Target Google Drive folder ID (e.g., "Slips/Inbox").
 *  - LEDGER_SHEET_ID: (Optional) Google Spreadsheet ID for the deduplication ledger.
 *                     If blank, a new spreadsheet named "SlipLedger" will be created.
 */

function doPost(e) {
  var lock = LockService.getScriptLock();
  var lockAcquired = false;

  try {
    if (!e || !e.postData || !e.postData.contents) {
      return createJsonResponse({ ok: false, error: "Missing POST request body" }, 400);
    }

    var body;
    try {
      body = JSON.parse(e.postData.contents);
    } catch (parseErr) {
      return createJsonResponse({ ok: false, error: "Invalid JSON body" }, 400);
    }

    // 1. Authenticate using body token
    var scriptProps = PropertiesService.getScriptProperties();
    var configuredToken = scriptProps.getProperty("AUTH_TOKEN");

    if (!configuredToken) {
      return createJsonResponse({
        ok: false,
        error: "Script not initialized: AUTH_TOKEN is not set in Script Properties."
      }, 500);
    }

    if (!body.token || body.token !== configuredToken) {
      return createJsonResponse({ ok: false, error: "Unauthorized: Invalid or missing token" }, 401);
    }

    // 2. Handle 'ping' health check
    if (body.action === "ping") {
      return createJsonResponse({ ok: true, message: "SlipSync Receiver is online and authenticated." });
    }

    // 3. Handle 'upload' action
    var fileName = body.fileName || ("slip_" + new Date().getTime() + ".jpg");
    var mimeType = body.mimeType || "image/jpeg";
    var sha256 = body.sha256;
    var slipQr = body.slipQr || null;
    var hasQr = Boolean(body.hasQr);
    var takenAt = body.takenAt || 0;
    var sourceFolder = body.sourceFolder || "Unknown";
    var imageBase64 = body.imageBase64;

    if (!imageBase64 || !sha256) {
      return createJsonResponse({ ok: false, error: "Missing required fields: imageBase64 or sha256" }, 400);
    }

    // Acquire lock (wait up to 30 seconds) to prevent concurrent deduplication race conditions
    lockAcquired = lock.tryLock(30000);
    if (!lockAcquired) {
      return createJsonResponse({ ok: false, error: "Server busy, could not acquire lock" }, 503);
    }

    // 4. Open or create Google Sheet ledger for deduplication & auditing
    var sheet = getOrCreateLedgerSheet(scriptProps);

    // 5. Deduplication check: Priority 1 = slipQr, Priority 2 = sha256
    var isDuplicate = checkDuplicate(sheet, slipQr, sha256);
    if (isDuplicate) {
      return createJsonResponse({
        ok: true,
        duplicate: true,
        message: "Duplicate slip detected. Already recorded in ledger."
      });
    }

    // 6. Get Drive destination folder
    var folderId = scriptProps.getProperty("FOLDER_ID");
    if (!folderId) {
      return createJsonResponse({
        ok: false,
        error: "Script not initialized: FOLDER_ID is not set in Script Properties."
      }, 500);
    }

    var folder;
    try {
      folder = DriveApp.getFolderById(folderId);
    } catch (fErr) {
      return createJsonResponse({
        ok: false,
        error: "Google Drive folder not found for ID: " + folderId
      }, 500);
    }

    // 7. Decode Base64 and save to Drive
    var decodedBytes = Utilities.base64Decode(imageBase64);
    var blob = Utilities.newBlob(decodedBytes, mimeType, fileName);
    var file = folder.createFile(blob);

    // Set file description with structured JSON metadata for Gemini Enterprise Agent
    var metadata = {
      sha256: sha256,
      slipQr: slipQr,
      hasQr: hasQr,
      takenAt: takenAt,
      sourceFolder: sourceFolder,
      uploadedAt: new Date().toISOString(),
      fileName: fileName
    };
    file.setDescription(JSON.stringify(metadata, null, 2));

    // 8. Record in Google Sheet Ledger
    // Columns: [slipQr, sha256, fileId, fileName, sourceFolder, hasQr, takenAt, receivedAt]
    sheet.appendRow([
      slipQr || "",
      sha256,
      file.getId(),
      fileName,
      sourceFolder,
      hasQr,
      new Date(takenAt).toISOString(),
      new Date().toISOString()
    ]);

    return createJsonResponse({
      ok: true,
      duplicate: false,
      fileId: file.getId(),
      fileName: fileName
    });

  } catch (err) {
    return createJsonResponse({ ok: false, error: "Server exception: " + err.toString() }, 500);
  } finally {
    if (lockAcquired) {
      try {
        lock.releaseLock();
      } catch (e) {}
    }
  }
}

function doGet(e) {
  return ContentService.createTextOutput(JSON.stringify({
    ok: false,
    error: "GET method not supported. Use POST with JSON body { action: 'ping', token: '...' } to test connection."
  })).setMimeType(ContentService.MimeType.JSON);
}

/**
 * Creates or retrieves the SlipLedger spreadsheet and returns the active sheet.
 */
function getOrCreateLedgerSheet(scriptProps) {
  var sheetId = scriptProps.getProperty("LEDGER_SHEET_ID");
  var ss;

  if (sheetId) {
    try {
      ss = SpreadsheetApp.openById(sheetId);
    } catch (e) {
      ss = null;
    }
  }

  if (!ss) {
    // Find if a spreadsheet named "SlipLedger" already exists in the same folder
    var folderId = scriptProps.getProperty("FOLDER_ID");
    var folder = folderId ? DriveApp.getFolderById(folderId) : null;
    
    ss = SpreadsheetApp.create("SlipLedger");
    scriptProps.setProperty("LEDGER_SHEET_ID", ss.getId());

    if (folder) {
      var file = DriveApp.getFileById(ss.getId());
      file.moveTo(folder);
    }

    var sheet = ss.getActiveSheet();
    sheet.setName("Ledger");
    // Initialize header row
    sheet.appendRow([
      "slipQr",
      "sha256",
      "fileId",
      "fileName",
      "sourceFolder",
      "hasQr",
      "takenAt",
      "receivedAt"
    ]);
    sheet.getRange(1, 1, 1, 8).setFontWeight("bold");
    sheet.setFrozenRows(1);
    return sheet;
  }

  var sheet = ss.getSheetByName("Ledger") || ss.getActiveSheet();
  if (sheet.getLastRow() === 0) {
    sheet.appendRow([
      "slipQr",
      "sha256",
      "fileId",
      "fileName",
      "sourceFolder",
      "hasQr",
      "takenAt",
      "receivedAt"
    ]);
    sheet.getRange(1, 1, 1, 8).setFontWeight("bold");
    sheet.setFrozenRows(1);
  }
  return sheet;
}

/**
 * Checks if the slip has already been recorded in the sheet.
 * Priority 1: slipQr
 * Priority 2: sha256
 */
function checkDuplicate(sheet, slipQr, sha256) {
  var lastRow = sheet.getLastRow();
  if (lastRow <= 1) {
    return false; // Only headers present
  }

  // Column A: slipQr, Column B: sha256
  var rangeValues = sheet.getRange(2, 1, lastRow - 1, 2).getValues();

  for (var i = 0; i < rangeValues.length; i++) {
    var existingQr = String(rangeValues[i][0]).trim();
    var existingSha = String(rangeValues[i][1]).trim();

    // Priority 1: check slipQr if present
    if (slipQr && existingQr && existingQr === String(slipQr).trim()) {
      return true;
    }

    // Priority 2: check sha256
    if (sha256 && existingSha && existingSha.toLowerCase() === String(sha256).toLowerCase().trim()) {
      return true;
    }
  }

  return false;
}

function createJsonResponse(data, statusCode) {
  var output = ContentService.createTextOutput(JSON.stringify(data));
  output.setMimeType(ContentService.MimeType.JSON);
  return output;
}

/**
 * Quick setup helper for initial configuration.
 * Run this function once from the Apps Script editor with your desired values.
 */
function setupScriptProperties() {
  var scriptProps = PropertiesService.getScriptProperties();
  
  // Set your desired authentication token and Google Drive Folder ID:
  // scriptProps.setProperty("AUTH_TOKEN", "YOUR_SUPER_SECRET_TOKEN");
  // scriptProps.setProperty("FOLDER_ID", "YOUR_GOOGLE_DRIVE_FOLDER_ID");
  
  Logger.log("Current AUTH_TOKEN: " + (scriptProps.getProperty("AUTH_TOKEN") ? "Configured" : "Not Set"));
  Logger.log("Current FOLDER_ID: " + (scriptProps.getProperty("FOLDER_ID") ? "Configured" : "Not Set"));
  Logger.log("Current LEDGER_SHEET_ID: " + (scriptProps.getProperty("LEDGER_SHEET_ID") || "Auto-create"));
}
