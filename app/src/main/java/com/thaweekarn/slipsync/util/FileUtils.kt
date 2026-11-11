package com.thaweekarn.slipsync.util

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class MediaImage(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val relativePath: String,
    val mimeType: String,
    val dateAddedSec: Long,
    val sizeBytes: Long
)

object FileUtils {

    const val MAX_FILE_SIZE_BYTES = 10L * 1024L * 1024L // 10 MB

    val KNOWN_BANK_FOLDERS = listOf(
        "Pictures/KPLUS/",
        "DCIM/SCB EASY/",
        "Pictures/SCB/",
        "Pictures/Krungthai NEXT/",
        "Pictures/BangkokBank/",
        "Pictures/Krungsri/",
        "Pictures/ttb touch/",
        "Pictures/CIMB THAI/",
        "Pictures/Government Savings Bank/",
        "Pictures/BAAC/"
    )

    /**
     * Queries MediaStore.Images to list all distinct RELATIVE_PATH directories.
     */
    fun getDistinctImageFolders(context: Context): List<String> {
        val folders = mutableSetOf<String>()
        val projection = arrayOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Images.Media.RELATIVE_PATH
            } else {
                MediaStore.Images.Media.DATA
            }
        )

        val sortOrder = "${MediaStore.Images.Media.DATE_MODIFIED} DESC"

        try {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                sortOrder
            )?.use { cursor ->
                val colIndex = cursor.getColumnIndexOrThrow(projection[0])
                while (cursor.moveToNext()) {
                    val rawPath = cursor.getString(colIndex)
                    if (!rawPath.isNullOrBlank()) {
                        val normalized = normalizeFolderPath(rawPath)
                        if (normalized.isNotBlank()) {
                            folders.add(normalized)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return folders.sorted()
    }

    /**
     * Normalizes a folder path to a clean format like "Pictures/KPLUS/".
     */
    fun normalizeFolderPath(path: String): String {
        var clean = path.replace("\\", "/").trim()
        if (clean.contains("/storage/") || clean.startsWith("/")) {
            // Extract from standard root (e.g., /storage/emulated/0/Pictures/KPLUS/ -> Pictures/KPLUS/)
            val index0 = clean.indexOf("/0/")
            if (index0 != -1) {
                clean = clean.substring(index0 + 3)
            } else {
                val parts = clean.split("/")
                val lastDirs = parts.filter { it.isNotBlank() && !it.contains("storage") && !it.contains("emulated") }
                clean = lastDirs.joinToString("/")
            }
        }
        // If it's a file path, remove file name
        if (clean.endsWith(".jpg") || clean.endsWith(".jpeg") || clean.endsWith(".png") || clean.endsWith(".webp")) {
            val lastSlash = clean.lastIndexOf('/')
            clean = if (lastSlash != -1) clean.substring(0, lastSlash + 1) else ""
        }
        if (clean.isNotBlank() && !clean.endsWith("/")) {
            clean += "/"
        }
        return clean
    }

    /**
     * Queries images inside selected folders that were added on or after the specified timestamp.
     * Skips images where IS_PENDING = 1.
     */
    fun queryCandidateImages(
        context: Context,
        selectedFolders: Set<String>,
        folderActivationTimestamps: Map<String, Long>
    ): List<MediaImage> {
        val results = mutableListOf<MediaImage>()
        if (selectedFolders.isEmpty()) return results

        val projection = mutableListOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.SIZE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            projection.add(MediaStore.Images.Media.RELATIVE_PATH)
            projection.add(MediaStore.Images.Media.IS_PENDING)
        } else {
            projection.add(MediaStore.Images.Media.DATA)
        }

        // Exclude pending images on API 29+
        val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.Images.Media.IS_PENDING} = 0"
        } else {
            null
        }

        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} ASC"

        try {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection.toTypedArray(),
                selection,
                null,
                sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                val pathCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
                } else {
                    cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                }

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val displayName = cursor.getString(nameCol) ?: "unknown"
                    val mimeType = cursor.getString(mimeCol) ?: "image/jpeg"
                    val dateAddedSec = cursor.getLong(dateCol) // DATE_ADDED is in SECONDS
                    val sizeBytes = cursor.getLong(sizeCol)
                    val rawPath = cursor.getString(pathCol) ?: ""
                    val normalizedFolder = normalizeFolderPath(rawPath)

                    // Check if this image belongs to one of the selected folders
                    val matchedFolder = selectedFolders.firstOrNull { folder ->
                        normalizedFolder.startsWith(folder, ignoreCase = true) ||
                                folder.startsWith(normalizedFolder, ignoreCase = true)
                    }

                    if (matchedFolder != null) {
                        val activationTimeMs = folderActivationTimestamps[matchedFolder] ?: 0L
                        val dateAddedMs = dateAddedSec * 1000L

                        // Only consider images added after folder activation / backfill cutoff
                        if (dateAddedMs >= activationTimeMs) {
                            val contentUri = ContentUris.withAppendedId(
                                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                id
                            )
                            results.add(
                                MediaImage(
                                    id = id,
                                    uri = contentUri,
                                    displayName = displayName,
                                    relativePath = matchedFolder,
                                    mimeType = mimeType,
                                    dateAddedSec = dateAddedSec,
                                    sizeBytes = sizeBytes
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return results
    }

    /**
     * Reads image content, verifies size <= 10MB, and encodes to Base64.
     */
    fun readImageAsBase64(context: Context, uri: Uri): Pair<String, Long>? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val bytes = inputStream.readBytes()
                if (bytes.size > MAX_FILE_SIZE_BYTES) {
                    return null // Exceeds 10 MB limit
                }
                val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                Pair(base64, bytes.size.toLong())
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Extracts the extension from the original file name or MIME type.
     */
    fun extractExtension(fileName: String, mimeType: String): String {
        val dotIndex = fileName.lastIndexOf('.')
        if (dotIndex != -1 && dotIndex < fileName.length - 1) {
            val ext = fileName.substring(dotIndex + 1).lowercase()
            if (ext in listOf("jpg", "jpeg", "png", "webp")) {
                return ext
            }
        }
        return when (mimeType.lowercase()) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            else -> "jpg"
        }
    }

    /**
     * Formats target cloud file name: yyyyMMdd_HHmmss_<cleanBankFolder>_<sha8>.<ext>
     */
    fun generateFileName(takenAtMs: Long, folderName: String, sha256: String, extension: String): String {
        val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val dateStr = dateFormat.format(Date(takenAtMs))
        val cleanFolder = folderName.trim('/')
            .replace('/', '_')
            .replace(" ", "")
            .replace("Pictures_", "")
            .replace("DCIM_", "")
            .ifBlank { "Slip" }
        val shortSha = if (sha256.length >= 8) sha256.substring(0, 8) else sha256
        return "${dateStr}_${cleanFolder}_${shortSha}.$extension"
    }

    /**
     * Copies a shared stream from an external content URI into private cache storage.
     * Prevents security exceptions or revoked permission issues.
     */
    fun copyStreamToPrivateCache(context: Context, sourceUri: Uri): File? {
        return try {
            val cacheDir = File(context.cacheDir, "shared_slips")
            if (!cacheDir.exists()) cacheDir.mkdirs()

            val tempFile = File(cacheDir, "shared_${System.currentTimeMillis()}_${(1000..9999).random()}.jpg")
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }
            if (tempFile.exists() && tempFile.length() > 0) tempFile else null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
