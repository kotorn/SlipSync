package com.thaweekarn.slipsync.util

import android.net.Uri

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

    // Bank of Thailand / PromptPay National e-Payment Slip Verification AID
    private const val PROMPTPAY_SLIP_AID = "A000000677010112"
    private const val PROMPTPAY_PAYMENT_AID = "A000000677010111"

    // Whitelist of authoritative Thai bank slip verification host domains
    private val TRUSTED_BANK_DOMAINS = listOf(
        "kasikornbank.com",
        "scb.co.th",
        "scbeasy.com",
        "krungthai.com",
        "ktb.co.th",
        "bangkokbank.com",
        "bbl.co.th",
        "krungsri.com",
        "krungsrionline.com",
        "ttbbank.com",
        "tmbbank.com",
        "gsb.or.th",
        "baac.or.th",
        "promptpay.co.th",
        "promptpay.or.th"
    )

    /**
     * Parses and validates whether a QR payload conforms to Thai Bank Slip QR standards
     * (e.g. PromptPay EMVCo transfer slip verification QR, or official bank slip verification URLs).
     * Rejects generic merchant QRs, non-bank URLs, and unsupported QR formats.
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

        // Reject non-slip common formats immediately
        val upper = trimmed.uppercase()
        if (upper.startsWith("WIFI:") ||
            upper.startsWith("BEGIN:VCARD") ||
            upper.startsWith("SMSTO:") ||
            upper.startsWith("MAILTO:") ||
            upper.startsWith("TEL:") ||
            upper.startsWith("GEO:")
        ) {
            return ThaiQrAnalysis(
                isValidThaiSlipQr = false,
                normalizedTransactionId = null,
                bankCode = null,
                rawPayload = trimmed
            )
        }

        // 1. EMVCo Thai QR Slip Verification Standard (Starts with "000201")
        if (trimmed.startsWith("000201")) {
            val emvTags = parseEmvTags(trimmed)
            val isSlipVerificationAid = trimmed.contains(PROMPTPAY_SLIP_AID)
            val isPromptPayPayment = trimmed.contains(PROMPTPAY_PAYMENT_AID)

            // Look for transaction reference in slip tags (e.g., Tag 51, Tag 30, Tag 31, Tag 62)
            // or sub-tag inside Tag 00/01 of PromptPay slip template
            var candidateRef = extractSlipReferenceFromEmv(trimmed, emvTags)

            if (!candidateRef.isNullOrBlank()) {
                val cleanRef = candidateRef.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
                // Thai bank transaction IDs are typically 8 to 40 characters
                if (cleanRef.length in 8..40 && cleanRef != trimmed) {
                    val bankCode = extractBankCode(trimmed)
                    return ThaiQrAnalysis(
                        isValidThaiSlipQr = isSlipVerificationAid || emvTags.isNotEmpty(),
                        normalizedTransactionId = cleanRef,
                        bankCode = bankCode,
                        rawPayload = trimmed
                    )
                }
            }

            // If it's a generic PromptPay payment QR (merchant static QR) without a slip transaction ID,
            // DO NOT set normalizedTransactionId to prevent false deduplication across transfers.
            return ThaiQrAnalysis(
                isValidThaiSlipQr = false,
                normalizedTransactionId = null,
                bankCode = extractBankCode(trimmed),
                rawPayload = trimmed
            )
        }

        // 2. Official Thai Bank Verification URLs
        if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            val uri = try {
                Uri.parse(trimmed)
            } catch (e: Exception) {
                null
            }

            val host = uri?.host?.lowercase()
            val isTrustedHost = host != null && TRUSTED_BANK_DOMAINS.any { domain ->
                host == domain || host.endsWith(".$domain")
            }

            if (isTrustedHost && uri != null) {
                // Extract query parameter value or path segment as transaction reference
                val paramNames = uri.queryParameterNames
                val refValue = if (!paramNames.isNullOrEmpty()) {
                    // Check common bank query params: ref, tx, id, slip, tr, transactionId
                    val preferredParam = listOf("ref", "tx", "id", "tr", "slip", "transactionId", "reference")
                        .firstOrNull { key -> uri.getQueryParameter(key) != null }
                    if (preferredParam != null) {
                        uri.getQueryParameter(preferredParam)
                    } else {
                        paramNames.firstNotNullOfOrNull { name -> uri.getQueryParameter(name) }
                    }
                } else {
                    uri.lastPathSegment
                }

                val cleanRef = refValue?.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
                if (!cleanRef.isNullOrBlank() && cleanRef.length in 6..64) {
                    return ThaiQrAnalysis(
                        isValidThaiSlipQr = true,
                        normalizedTransactionId = cleanRef,
                        bankCode = extractBankCode(trimmed),
                        rawPayload = trimmed
                    )
                }
            }
        }

        // Generic non-bank QR code (URL, plain text, unknown schema)
        return ThaiQrAnalysis(
            isValidThaiSlipQr = false,
            normalizedTransactionId = null,
            bankCode = null,
            rawPayload = trimmed
        )
    }

    private fun extractSlipReferenceFromEmv(payload: String, tags: Map<String, String>): String? {
        // Tag 51 (BOT e-Payment sub-template)
        val tag51 = tags["51"]
        if (!tag51.isNullOrBlank()) {
            val subTags = parseEmvTags(tag51)
            val subRef = subTags["02"] ?: subTags["01"] ?: tag51
            if (subRef.isNotBlank()) return subRef
        }

        // Tag 30 / 31 (Bank biller / slip reference)
        val tag30 = tags["30"]
        if (!tag30.isNullOrBlank()) {
            val subTags = parseEmvTags(tag30)
            val subRef = subTags["02"] ?: subTags["01"] ?: tag30
            if (subRef.isNotBlank()) return subRef
        }

        val tag31 = tags["31"]
        if (!tag31.isNullOrBlank()) {
            val subTags = parseEmvTags(tag31)
            val subRef = subTags["02"] ?: subTags["01"] ?: tag31
            if (subRef.isNotBlank()) return subRef
        }

        // Tag 62 (Additional Data Field Template)
        val tag62 = tags["62"]
        if (!tag62.isNullOrBlank()) {
            val subTags = parseEmvTags(tag62)
            val subRef = subTags["05"] ?: subTags["01"] ?: subTags["07"]
            if (!subRef.isNullOrBlank()) return subRef
        }

        // Direct search after PROMPTPAY_SLIP_AID
        if (payload.contains(PROMPTPAY_SLIP_AID)) {
            val afterAid = payload.substringAfter(PROMPTPAY_SLIP_AID)
            val subTags = parseEmvTags(afterAid)
            val ref = subTags["02"] ?: subTags["01"]
            if (!ref.isNullOrBlank()) return ref
        }

        return null
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
