package com.thaweekarn.slipsync.util

data class ThaiQrAnalysis(
    val isValidThaiSlipQr: Boolean,
    val normalizedTransactionId: String?,
    val bankCode: String?,
    val rawPayload: String
) {
    val isValidBankQr: Boolean
        get() = isValidThaiSlipQr
}

object ThaiSlipQrParser {

    // Bank of Thailand / PromptPay National e-Payment AIDs and Tags
    private const val PROMPTPAY_AID = "A000000677010111"
    private const val PROMPTPAY_SLIP_AID = "A000000677010112"

    /**
     * Parses and validates whether a QR payload conforms to Thai Bank Slip QR standards
     * (e.g. PromptPay EMVCo transfer slip verification QR, or official bank slip verification URLs).
     */
    fun analyze(rawPayload: String?): ThaiQrAnalysis {
        if (rawPayload.isNullOrBlank()) {
            return ThaiQrAnalysis(
                isValidThaiSlipQr = false,
                normalizedTransactionId = null,
                bankCode = null,
                rawPayload = ""
            )
        }

        val trimmed = rawPayload.trim()

        // 1. EMVCo Thai QR Payment / Slip Verification Standard
        // Starts with Tag 00: "000201"
        if (trimmed.startsWith("000201")) {
            val emvTags = parseEmvTags(trimmed)
            val isPromptPay = trimmed.contains(PROMPTPAY_AID) || trimmed.contains(PROMPTPAY_SLIP_AID)
            
            // Tag 51 or Tag 30/31 often holds the transaction reference
            val ref = emvTags["51"] ?: emvTags["30"] ?: emvTags["31"] ?: emvTags["62"] ?: trimmed
            val cleanRef = ref.filter { it.isLetterOrDigit() }

            return ThaiQrAnalysis(
                isValidThaiSlipQr = isPromptPay || emvTags.isNotEmpty(),
                normalizedTransactionId = if (cleanRef.isNotBlank()) cleanRef else trimmed,
                bankCode = extractBankCode(trimmed),
                rawPayload = trimmed
            )
        }

        // 2. Official Thai Bank Verification URLs
        // (e.g. Kasikorn: kplus.kasikornbank.com, SCB: scbeasy.com, Krungthai: krungthai.com)
        val lower = trimmed.lowercase()
        val isBankUrl = lower.contains("kasikornbank.com") ||
                lower.contains("scb.co.th") || lower.contains("scbeasy") ||
                lower.contains("krungthai.com") || lower.contains("ktb.co.th") ||
                lower.contains("bangkokbank.com") || lower.contains("bbl.co.th") ||
                lower.contains("krungsri.com") || lower.contains("ttbbank.com") ||
                lower.contains("gsb.or.th") || lower.contains("baac.or.th") ||
                lower.contains("promptpay") || lower.contains("slip")

        if (isBankUrl && (lower.startsWith("http://") || lower.startsWith("https://"))) {
            // Extract query parameter value or path segment as transaction reference
            val refValue = try {
                val uri = android.net.Uri.parse(trimmed)
                val paramNames = uri.queryParameterNames
                if (!paramNames.isNullOrEmpty()) {
                    paramNames.firstNotNullOfOrNull { name -> uri.getQueryParameter(name) }
                } else {
                    uri.lastPathSegment
                }
            } catch (e: Exception) {
                null
            } ?: trimmed.substringAfterLast('=').substringAfterLast('/')

            val cleanRef = refValue?.filter { it.isLetterOrDigit() || it == '-' || it == '_' }

            return ThaiQrAnalysis(
                isValidThaiSlipQr = true,
                normalizedTransactionId = if (!cleanRef.isNullOrBlank()) cleanRef else trimmed,
                bankCode = extractBankCode(trimmed),
                rawPayload = trimmed
            )
        }

        // Generic non-bank QR code (URL, Wi-Fi, random text)
        return ThaiQrAnalysis(
            isValidThaiSlipQr = false,
            normalizedTransactionId = null,
            bankCode = null,
            rawPayload = trimmed
        )
    }

    private fun extractBankCode(payload: String): String? {
        val lower = payload.lowercase()
        return when {
            lower.contains("kplus") || lower.contains("004") || lower.contains("kasikorn") -> "KBANK"
            lower.contains("scb") || lower.contains("014") -> "SCB"
            lower.contains("ktb") || lower.contains("006") || lower.contains("krungthai") -> "KTB"
            lower.contains("bbl") || lower.contains("002") || lower.contains("bangkokbank") -> "BBL"
            lower.contains("bay") || lower.contains("025") || lower.contains("krungsri") -> "BAY"
            lower.contains("ttb") || lower.contains("011") -> "TTB"
            lower.contains("gsb") || lower.contains("030") -> "GSB"
            lower.contains("baac") || lower.contains("034") -> "BAAC"
            else -> null
        }
    }

    /**
     * Parses standard TLV (Tag-Length-Value) EMVCo formatted strings.
     */
    private fun parseEmvTags(payload: String): Map<String, String> {
        val tags = mutableMapOf<String, String>()
        var index = 0
        try {
            while (index + 4 <= payload.length) {
                val tag = payload.substring(index, index + 2)
                val lengthStr = payload.substring(index + 2, index + 4)
                val length = lengthStr.toIntOrNull() ?: break
                index += 4
                if (index + length <= payload.length) {
                    val value = payload.substring(index, index + length)
                    tags[tag] = value
                    index += length
                } else {
                    break
                }
            }
        } catch (e: Exception) {
            // Ignore parsing error on malformed strings
        }
        return tags
    }
}
