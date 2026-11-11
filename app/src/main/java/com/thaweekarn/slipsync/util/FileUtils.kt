package com.thaweekarn.slipsync.util

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import java.io.File
import java.io.FileInputStream
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
            val index0 = clean.indexOf("/0/")
            clean = if (index0 != -1) {
                clean.substring(index0 + 3)
            } else {
                val parts = clean.split("/")
                val lastDirs = parts.filter { it.isNotBlank() && !it.contains("storage") && !it.contains("emulated") }
                lastDirs.joinToString("/")
            }
        }
        // Remove trailing file name if a full path was passed
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
     * Checks if image folder matches user-selected monitored folder strictly.
     * Prevents "Pictures/KPLUS/" from matching "Pictures/KPLUS_BACKUP/".
     */
    fun isFolderMatched(imageFolder: String, monitoredFolder: String): Boolean {
        val normImg = normalizeFolderPath(imageFolder).lowercase()
        val normMon = normalizeFolderPath(monitoredFolder).lowercase()
        return normImg == normMon || normImg.startsWith(normMon)
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

                    val matchedFolder = selectedFolders.firstOrNull { folder ->
                        isFolderMatched(normalizedFolder, folder)
                    }

                    if (matchedFolder != null) {
                        val activationTimeMs = folderActivationTimestamps[matchedFolder] ?: 0L
                        val dateAddedMs = dateAddedSec * 1000L

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
     * Reads image content from a Content URI or local File, checks size limit, and encodes to Base64.
     */
    fun readImageAsBase64(context: Context, uri: Uri?, file: File?): Pair<String, Long>? {
        return try {
            val bytes = when {
                file != null && file.exists() -> {
                    if (file.length() > MAX_FILE_SIZE_BYTES) return null
                    FileInputStream(file).use { it.readBytes() }
                }
                uri != null -> {
                    context.contentResolver.openInputStream(uri)?.use { inputStream ->
                        val b = inputStream.readBytes()
                        if (b.size > MAX_FILE_SIZE_BYTES) return null
                        b
                    } ?: return null
                }
                else -> return null
            }

            if (bytes.size > MAX_FILE_SIZE_BYTES) return null
            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            Pair(base64, bytes.size.toLong())
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Extracts extension from fileName or MIME type cleanly.
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
     * Copies a shared stream from an external content URI into durable app-private storage (filesDir).
     * Unlike cacheDir, filesDir is NEVER purged by OS cache cleaners.
     */
    fun copyStreamToDurableStorage(context: Context, sourceUri: Uri): Pair<File, String>? {
        return try {
            val durableDir = File(context.filesDir, "shared_slips")
            if (!durableDir.exists()) durableDir.mkdirs()

            // Resolve real MIME type if available
            val mimeType = context.contentResolver.getType(sourceUri) ?: "image/jpeg"
            val ext = when (mimeType.lowercase()) {
                "image/png" -> "png"
                "image/webp" -> "webp"
                else -> "jpg"
            }

            val durableFile = File(durableDir, "shared_${System.currentTimeMillis()}_${(1000..9999).random()}.$ext")
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                FileOutputStream(durableFile).use { output ->
                    input.copyTo(output)
                }
            }

            if (durableFile.exists() && durableFile.length() > 0) {
                Pair(durableFile, mimeType)
            } else null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
