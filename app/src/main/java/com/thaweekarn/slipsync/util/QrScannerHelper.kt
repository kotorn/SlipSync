package com.thaweekarn.slipsync.util

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

object QrScannerHelper {

    private val options = BarcodeScannerOptions.Builder()
        .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
        .build()

    private val scanner by lazy { BarcodeScanning.getClient(options) }

    /**
     * Scans an image for QR codes on-device using bundled ML Kit model.
     * Returns the raw payload string if found, or null if no QR code exists.
     */
    suspend fun scanQrFromUri(context: Context, uri: Uri): String? {
        return suspendCancellableCoroutine { continuation ->
            try {
                val image = InputImage.fromFilePath(context, uri)
                scanner.process(image)
                    .addOnSuccessListener { barcodes ->
                        val qrBarcode = barcodes.firstOrNull { it.format == Barcode.FORMAT_QR_CODE }
                        continuation.resume(qrBarcode?.rawValue)
                    }
                    .addOnFailureListener {
                        continuation.resume(null)
                    }
            } catch (e: Exception) {
                continuation.resume(null)
            }
        }
    }

    suspend fun scanQrFromFile(context: Context, file: File): String? {
        return suspendCancellableCoroutine { continuation ->
            try {
                val uri = Uri.fromFile(file)
                val image = InputImage.fromFilePath(context, uri)
                scanner.process(image)
                    .addOnSuccessListener { barcodes ->
                        val qrBarcode = barcodes.firstOrNull { it.format == Barcode.FORMAT_QR_CODE }
                        continuation.resume(qrBarcode?.rawValue)
                    }
                    .addOnFailureListener {
                        continuation.resume(null)
                    }
            } catch (e: Exception) {
                continuation.resume(null)
            }
        }
    }
}
