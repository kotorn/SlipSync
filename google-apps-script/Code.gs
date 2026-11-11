/**
 * SlipSync Google Apps Script Receiver
 *
 * Receives bank transfer slip images uploaded by the SlipSync Android app,
 * verifies SHA-256 integrity on the server, performs content-based Drive idempotency,
 * conducts two-tier deduplication (Transaction ID / QR -> SHA-256) via a Google Sheet ledger,
 * includes backward-compatible migration for 8-column historical ledgers,
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

    // Server-side SHA-256 integrity verification
    var decodedBytes;
    try {
      decodedBytes = Utilities.base64Decode(imageBase64);
    } catch (decodeErr) {
      return createJsonResponse({
        ok: false,
        error: "Malformed base64 image data",
        errorCode: "INVALID_BASE64"
      });
    }

    var digestBytes = Utilities.computeDigest(Utilities.DigestAlgorithm.SHA_256, decodedBytes);
    var computedSha256 = digestBytes.map(function(byte) {
      var hex = (byte < 0 ? byte + 256 : byte).toString(16);
      return hex.length === 1 ? "0" + hex : hex;
    }).join("").toLowerCase();

    if (computedSha256 !== sha256) {
      return createJsonResponse({
        ok: false,
        error: "SHA-256 integrity verification failed on server. Expected: " + sha256 + ", Computed: " + computedSha256,
        errorCode: "CHECKSUM_MISMATCH"
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

    // 4. Open or create Google Sheet ledger (with automatic backward-compatible schema migration)
    var sheet = getOrCreateLedgerSheet(scriptProps);

    // 5. Deduplication check:
    // Priority 1: transactionId
    // Priority 1b: slipQr
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

    // Content-based Drive Idempotency Check
    var targetFile = null;

    // Check if duplicate search returned a fileId
    if (dupResult.fileId) {
      try {
        var existingFromLedger = DriveApp.getFileById(dupResult.fileId);
        if (existingFromLedger && !existingFromLedger.isTrashed()) {
          targetFile = existingFromLedger;
        }
      } catch (e) {}
    }

    // Search Drive folder for any file whose description metadata contains this sha256
    if (!targetFile) {
      try {
        var searchResults = folder.searchFiles("description contains '\"sha256\": \"" + sha256 + "\"' and trashed = false");
        if (searchResults.hasNext()) {
          targetFile = searchResults.next();
        }
      } catch (searchErr) {
        Logger.log("Notice: Drive content search by description failed: " + searchErr);
      }
    }

    // Fallback: Check if file with exact target name already exists
    if (!targetFile) {
      var nameMatches = folder.getFilesByName(fileName);
      if (nameMatches.hasNext()) {
        targetFile = nameMatches.next();
      }
    }

    // Create file blob in Drive only if not found by content
    if (!targetFile) {
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
 * Includes automatic backward-compatible schema migration from 8-column format to 9-column format.
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
    return sheet;
  }

  // Check and perform backward-compatible schema migration if sheet is in legacy 8-column format
  migrateLedgerSheetIfNeeded(sheet);

  return sheet;
}

/**
 * Migrates a legacy 8-column sheet to 9-column format by inserting 'transactionId' as Column 1.
 * Preserves all historical records.
 */
function migrateLedgerSheetIfNeeded(sheet) {
  var lastCol = sheet.getLastColumn();
  if (lastCol < 8) return;

  var headers = sheet.getRange(1, 1, 1, lastCol).getValues()[0];
  var col1 = String(headers[0] || "").trim();

  // Legacy 8-column layout: [slipQr, sha256, fileId, fileName, sourceFolder, hasQr, takenAt, receivedAt]
  if (col1 === "slipQr" && headers.indexOf("transactionId") === -1) {
    Logger.log("Migrating ledger from 8-column to 9-column format (inserting transactionId column)...");
    sheet.insertColumnBefore(1);
    sheet.getRange(1, 1).setValue("transactionId");
    sheet.getRange(1, 1, 1, 9).setFontWeight("bold");

    // Optional backfill: Parse transaction ID from existing slipQr column (now Column 2)
    var lastRow = sheet.getLastRow();
    if (lastRow > 1) {
      var qrValues = sheet.getRange(2, 2, lastRow - 1, 1).getValues();
      var txUpdates = [];
      for (var r = 0; r < qrValues.length; r++) {
        var rawQr = String(qrValues[r][0] || "").replace(/^'/, "").trim();
        var extractedTx = extractTxFromQrString(rawQr);
        txUpdates.push([extractedTx ? sanitizeCell(extractedTx) : ""]);
      }
      sheet.getRange(2, 1, lastRow - 1, 1).setValues(txUpdates);
    }
  }
}

/**
 * Helper to extract transaction ID from legacy QR strings during migration.
 */
function extractTxFromQrString(rawQr) {
  if (!rawQr) return "";
  if (rawQr.indexOf("http") === 0) {
    var match = rawQr.match(/[?&](?:ref|tx|id|reference)=([A-Za-z0-9_-]+)/i);
    if (match && match[1]) return match[1];
    var lastSegment = rawQr.substring(rawQr.lastIndexOf("/") + 1).replace(/[^A-Za-z0-9_-]/g, "");
    if (lastSegment.length >= 6) return lastSegment;
  }
  // EMVCo PromptPay slip AID check
  if (rawQr.indexOf("000201") === 0 && rawQr.indexOf("A000000677010112") !== -1) {
    var matchEmv = rawQr.match(/A000000677010112\d{2}([A-Za-z0-9]+)/);
    if (matchEmv && matchEmv[1] && matchEmv[1].length >= 8) return matchEmv[1];
  }
  return "";
}

/**
 * Checks if the slip has already been recorded in the sheet ledger.
 * Dynamically resolves column positions to support both 8-column and 9-column sheets.
 * Returns { isDuplicate: bool, duplicateType: string, fileId: string, fileName: string }
 */
function checkDuplicate(sheet, transactionId, slipQr, sha256) {
  var lastRow = sheet.getLastRow();
  if (lastRow <= 1) {
    return { isDuplicate: false };
  }

  var lastCol = Math.max(sheet.getLastColumn(), 1);
  var headers = sheet.getRange(1, 1, 1, lastCol).getValues()[0];
  var colMap = {};
  for (var c = 0; c < headers.length; c++) {
    colMap[String(headers[c] || "").trim()] = c;
  }

  var txCol = colMap.hasOwnProperty("transactionId") ? colMap["transactionId"] : -1;
  var qrCol = colMap.hasOwnProperty("slipQr") ? colMap["slipQr"] : (colMap.hasOwnProperty("transactionId") ? 1 : 0);
  var shaCol = colMap.hasOwnProperty("sha256") ? colMap["sha256"] : (colMap.hasOwnProperty("transactionId") ? 2 : 1);
  var fileIdCol = colMap.hasOwnProperty("fileId") ? colMap["fileId"] : (colMap.hasOwnProperty("transactionId") ? 3 : 2);
  var fileNameCol = colMap.hasOwnProperty("fileName") ? colMap["fileName"] : (colMap.hasOwnProperty("transactionId") ? 4 : 3);

  var rangeValues = sheet.getRange(2, 1, lastRow - 1, lastCol).getValues();

  for (var i = 0; i < rangeValues.length; i++) {
    var row = rangeValues[i];
    var existingTx = txCol >= 0 ? String(row[txCol] || "").replace(/^'/, "").trim() : "";
    var existingQr = qrCol >= 0 ? String(row[qrCol] || "").replace(/^'/, "").trim() : "";
    var existingSha = shaCol >= 0 ? String(row[shaCol] || "").replace(/^'/, "").trim().toLowerCase() : "";
    var fileId = fileIdCol >= 0 ? String(row[fileIdCol] || "").replace(/^'/, "").trim() : "";
    var fileName = fileNameCol >= 0 ? String(row[fileNameCol] || "").replace(/^'/, "").trim() : "";

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
