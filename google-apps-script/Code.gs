/**
 * SlipSync Google Apps Script Receiver
 *
 * Receives bank transfer slip images uploaded by the SlipSync Android app,
 * performs two-tier deduplication (Transaction ID / QR -> SHA-256) via a Google Sheet ledger,
 * protects against Formula Injection (CSV / Spreadsheet command injection),
 * stores images to the designated Google Drive folder ("Slips/Inbox"),
 * tags file descriptions with structured metadata for Gemini Enterprise Agent processing,
 * and returns JSON responses for the Android client.
 *
 * Script Properties:
 *  - AUTH_TOKEN: Shared secret token for authenticating POST requests.
 *  - FOLDER_ID: Target Google Drive folder ID (e.g., "Slips/Inbox").
 *  - LEDGER_SHEET_ID: (Optional) Google Spreadsheet ID for the deduplication ledger.
 *                     If not set, a new spreadsheet named "SlipLedger" will be created.
 */

function doPost(e) {
  var lock = LockService.getScriptLock();
  var lockAcquired = false;

  try {
    if (!e || !e.postData || !e.postData.contents) {
      return createJsonResponse({ ok: false, error: "Missing POST request body", errorCode: "INVALID_PAYLOAD" });
    }

    var body;
    try {
      body = JSON.parse(e.postData.contents);
    } catch (parseErr) {
      return createJsonResponse({ ok: false, error: "Invalid JSON body", errorCode: "INVALID_PAYLOAD" });
    }

    // 1. Authenticate using body token (Apps Script Web Apps do not reliably expose custom headers)
    var scriptProps = PropertiesService.getScriptProperties();
    var configuredToken = scriptProps.getProperty("AUTH_TOKEN");

    if (!configuredToken) {
      return createJsonResponse({
        ok: false,
        error: "Script not initialized: AUTH_TOKEN is not set in Script Properties.",
        errorCode: "SERVER_NOT_INITIALIZED"
      });
    }

    if (!body.token || body.token !== configuredToken) {
      return createJsonResponse({
        ok: false,
        error: "Unauthorized: Invalid or missing authentication token",
        errorCode: "UNAUTHORIZED"
      });
    }

    // 2. Handle 'ping' health check
    if (body.action === "ping") {
      return createJsonResponse({
        ok: true,
        message: "SlipSync Receiver is online, authenticated, and ready."
      });
    }

    // 3. Handle 'upload' action
    var fileName = body.fileName || ("slip_" + new Date().getTime() + ".jpg");
    var mimeType = body.mimeType || "image/jpeg";
    var sha256 = (body.sha256 || "").trim().toLowerCase();
    var slipQr = body.slipQr ? String(body.slipQr).trim() : null;
    var transactionId = body.transactionId ? String(body.transactionId).trim() : null;
    var hasQr = Boolean(body.hasQr);
    var takenAt = Number(body.takenAt) || new Date().getTime();
    var sourceFolder = body.sourceFolder || "Unknown";
    var imageBase64 = body.imageBase64;

    if (!imageBase64 || !sha256) {
      return createJsonResponse({
        ok: false,
        error: "Missing required fields: imageBase64 or sha256",
        errorCode: "INVALID_PAYLOAD"
      });
    }

    // Acquire lock (wait up to 30 seconds) to prevent concurrent deduplication race conditions
    lockAcquired = lock.tryLock(30000);
    if (!lockAcquired) {
      return createJsonResponse({
        ok: false,
        error: "Server busy, could not acquire execution lock",
        errorCode: "SERVER_BUSY"
      });
    }

    // 4. Open or create Google Sheet ledger for deduplication & auditing
    var sheet = getOrCreateLedgerSheet(scriptProps);

    // 5. Deduplication check:
    // Priority 1: transactionId / slipQr
    // Priority 2: sha256
    var dupResult = checkDuplicate(sheet, transactionId, slipQr, sha256);
    if (dupResult.isDuplicate) {
      return createJsonResponse({
        ok: true,
        duplicate: true,
        duplicateType: dupResult.duplicateType,
        fileId: dupResult.fileId,
        fileName: dupResult.fileName,
        message: "Duplicate slip detected. Already recorded in ledger."
      });
    }

    // 6. Get Drive destination folder
    var folderId = scriptProps.getProperty("FOLDER_ID");
    if (!folderId) {
      return createJsonResponse({
        ok: false,
        error: "Script not initialized: FOLDER_ID is not set in Script Properties.",
        errorCode: "SERVER_NOT_INITIALIZED"
      });
    }

    var folder;
    try {
      folder = DriveApp.getFolderById(folderId);
    } catch (fErr) {
      return createJsonResponse({
        ok: false,
        error: "Google Drive folder not found for ID: " + folderId,
        errorCode: "FOLDER_NOT_FOUND"
      });
    }

    // Idempotency check: Search Drive folder for matching file before creating
    var existingFiles = folder.getFilesByName(fileName);
    var targetFile;
    if (existingFiles.hasNext()) {
      targetFile = existingFiles.next();
    } else {
      // Decode Base64 and create file in Drive
      var decodedBytes = Utilities.base64Decode(imageBase64);
      var blob = Utilities.newBlob(decodedBytes, mimeType, fileName);
      targetFile = folder.createFile(blob);
    }

    // 7. Set file description with structured JSON metadata for Gemini Enterprise Agent
    var metadata = {
      sha256: sha256,
      transactionId: transactionId,
      slipQr: slipQr,
      hasQr: hasQr,
      takenAt: takenAt,
      sourceFolder: sourceFolder,
      uploadedAt: new Date().toISOString(),
      fileName: fileName
    };
    try {
      targetFile.setDescription(JSON.stringify(metadata, null, 2));
    } catch (descErr) {
      Logger.log("Notice: Could not set file description: " + descErr);
    }

    // 8. Record in Google Sheet Ledger with Formula Injection sanitization
    // Columns: [transactionId, slipQr, sha256, fileId, fileName, sourceFolder, hasQr, takenAt, receivedAt]
    sheet.appendRow([
      sanitizeCell(transactionId || ""),
      sanitizeCell(slipQr || ""),
      sanitizeCell(sha256),
      sanitizeCell(targetFile.getId()),
      sanitizeCell(fileName),
      sanitizeCell(sourceFolder),
      hasQr,
      sanitizeCell(new Date(takenAt).toISOString()),
      sanitizeCell(new Date().toISOString())
    ]);

    return createJsonResponse({
      ok: true,
      duplicate: false,
      fileId: targetFile.getId(),
      fileName: fileName
    });

  } catch (err) {
    return createJsonResponse({
      ok: false,
      error: "Server exception: " + err.toString(),
      errorCode: "SERVER_EXCEPTION"
    });
  } finally {
    if (lockAcquired) {
      try {
        lock.releaseLock();
      } catch (e) {}
    }
  }
}

function doGet(e) {
  return createJsonResponse({
    ok: false,
    error: "GET method not supported. Use POST with JSON body { action: 'ping', token: '...' } to test connection.",
    errorCode: "METHOD_NOT_ALLOWED"
  });
}

/**
 * Sanitizes input strings before appending to Google Sheets cells
 * to prevent Formula / CSV Injection (=, +, -, @, tab, return).
 */
function sanitizeCell(val) {
  if (val === null || val === undefined) return "";
  var s = String(val);
  if (/^[=+\-@\t\r]/.test(s)) {
    return "'" + s;
  }
  return s;
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
    var folderId = scriptProps.getProperty("FOLDER_ID");
    var folder = folderId ? DriveApp.getFolderById(folderId) : null;

    ss = SpreadsheetApp.create("SlipLedger");
    scriptProps.setProperty("LEDGER_SHEET_ID", ss.getId());

    if (folder) {
      try {
        var file = DriveApp.getFileById(ss.getId());
        file.moveTo(folder);
      } catch (moveErr) {}
    }

    var sheet = ss.getActiveSheet();
    sheet.setName("Ledger");
    sheet.appendRow([
      "transactionId",
      "slipQr",
      "sha256",
      "fileId",
      "fileName",
      "sourceFolder",
      "hasQr",
      "takenAt",
      "receivedAt"
    ]);
    sheet.getRange(1, 1, 1, 9).setFontWeight("bold");
    sheet.setFrozenRows(1);
    return sheet;
  }

  var sheet = ss.getSheetByName("Ledger") || ss.getActiveSheet();
  if (sheet.getLastRow() === 0) {
    sheet.appendRow([
      "transactionId",
      "slipQr",
      "sha256",
      "fileId",
      "fileName",
      "sourceFolder",
      "hasQr",
      "takenAt",
      "receivedAt"
    ]);
    sheet.getRange(1, 1, 1, 9).setFontWeight("bold");
    sheet.setFrozenRows(1);
  }
  return sheet;
}

/**
 * Checks if the slip has already been recorded in the sheet ledger.
 * Returns { isDuplicate: bool, duplicateType: string, fileId: string, fileName: string }
 */
function checkDuplicate(sheet, transactionId, slipQr, sha256) {
  var lastRow = sheet.getLastRow();
  if (lastRow <= 1) {
    return { isDuplicate: false };
  }

  // Read columns:
  // Col 1: transactionId, Col 2: slipQr, Col 3: sha256, Col 4: fileId, Col 5: fileName
  var numCols = Math.min(sheet.getLastColumn(), 5);
  var rangeValues = sheet.getRange(2, 1, lastRow - 1, numCols).getValues();

  for (var i = 0; i < rangeValues.length; i++) {
    var row = rangeValues[i];
    var existingTx = String(row[0] || "").replace(/^'/, "").trim();
    var existingQr = String(row[1] || "").replace(/^'/, "").trim();
    var existingSha = String(row[2] || "").replace(/^'/, "").trim().toLowerCase();
    var fileId = String(row[3] || "");
    var fileName = String(row[4] || "");

    // Priority 1: Normalized Transaction ID
    if (transactionId && existingTx && existingTx === transactionId) {
      return { isDuplicate: true, duplicateType: "TRANSACTION", fileId: fileId, fileName: fileName };
    }

    // Priority 1b: Exact QR payload
    if (slipQr && existingQr && existingQr === slipQr) {
      return { isDuplicate: true, duplicateType: "QR", fileId: fileId, fileName: fileName };
    }

    // Priority 2: SHA-256 hash
    if (sha256 && existingSha && existingSha === sha256) {
      return { isDuplicate: true, duplicateType: "HASH", fileId: fileId, fileName: fileName };
    }
  }

  return { isDuplicate: false };
}

function createJsonResponse(data) {
  var output = ContentService.createTextOutput(JSON.stringify(data));
  output.setMimeType(ContentService.MimeType.JSON);
  return output;
}

/**
 * Helper to view or configure Script Properties from Apps Script editor.
 */
function setupScriptProperties() {
  var scriptProps = PropertiesService.getScriptProperties();
  Logger.log("Current AUTH_TOKEN: " + (scriptProps.getProperty("AUTH_TOKEN") ? "Configured" : "Not Set"));
  Logger.log("Current FOLDER_ID: " + (scriptProps.getProperty("FOLDER_ID") ? "Configured" : "Not Set"));
  Logger.log("Current LEDGER_SHEET_ID: " + (scriptProps.getProperty("LEDGER_SHEET_ID") || "Auto-create"));
}
