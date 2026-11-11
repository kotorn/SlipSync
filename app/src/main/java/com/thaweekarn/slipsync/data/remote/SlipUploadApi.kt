package com.thaweekarn.slipsync.data.remote

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit

class SlipUploadApi {

    private val moshi: Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val uploadAdapter = moshi.adapter(SlipUploadRequest::class.java)
    private val pingAdapter = moshi.adapter(SlipPingRequest::class.java)
    private val responseAdapter = moshi.adapter(SlipApiResponse::class.java)

    // Manual redirect handling for Google Apps Script 302 redirects with domain validation
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(true)
        .connectTimeout(45, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * Executes ping health check with POST body { action: "ping", token: "..." }
     */
    suspend fun ping(url: String, token: String): Result<SlipApiResponse> = withContext(Dispatchers.IO) {
        try {
            validateHttpsUrl(url)
            val payload = pingAdapter.toJson(SlipPingRequest(token = token))
            val requestBody = payload.toRequestBody(jsonMediaType)
            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()

            executeWithRedirectHandling(request)
        } catch (e: Exception) {
            Result.failure(mapException(e))
        }
    }

    /**
     * Uploads the slip payload with base64 image and metadata.
     */
    suspend fun uploadSlip(url: String, requestDto: SlipUploadRequest): Result<SlipApiResponse> = withContext(Dispatchers.IO) {
        try {
            validateHttpsUrl(url)
            val payload = uploadAdapter.toJson(requestDto)
            val requestBody = payload.toRequestBody(jsonMediaType)
            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()

            executeWithRedirectHandling(request)
        } catch (e: Exception) {
            Result.failure(mapException(e))
        }
    }

    private fun validateHttpsUrl(url: String) {
        if (!url.startsWith("https://", ignoreCase = true)) {
            throw IllegalArgumentException("URL must use secure HTTPS protocol")
        }
    }

    private fun isTrustedGoogleDomain(host: String): Boolean {
        val lowerHost = host.lowercase()
        return lowerHost.endsWith("google.com") ||
                lowerHost.endsWith("googleusercontent.com") ||
                lowerHost.endsWith("googleapis.com")
    }

    private fun executeWithRedirectHandling(initialRequest: Request): Result<SlipApiResponse> {
        var currentRequest = initialRequest
        var redirects = 0
        val maxRedirects = 5

        while (redirects < maxRedirects) {
            client.newCall(currentRequest).execute().use { response ->
                val code = response.code

                // Check if Google Apps Script issued a 301/302/303/307 redirect
                if (code in listOf(301, 302, 303, 307)) {
                    val location = response.header("Location")
                    if (!location.isNullOrBlank()) {
                        redirects++
                        val redirectUri = try {
                            URI.create(location)
                        } catch (e: Exception) {
                            return Result.failure(IOException("Invalid redirect URI: $location"))
                        }

                        val host = redirectUri.host
                        if (host == null || !isTrustedGoogleDomain(host)) {
                            return Result.failure(
                                SecurityException("Untrusted redirect host '$host'. Only Google domains are permitted.")
                            )
                        }

                        // Google Apps Script redirect executes the response via GET on googleusercontent.com
                        currentRequest = Request.Builder()
                            .url(location)
                            .get()
                            .build()
                        return@use // continue while loop
                    }
                }

                val bodyString = response.body?.string().orEmpty()

                if (code == 401 || code == 403) {
                    return Result.failure(IOException("Authentication failed (HTTP $code). Verify your token."))
                }

                if (code == 400) {
                    return Result.failure(IOException("Invalid request payload (HTTP 400): $bodyString"))
                }

                if (code >= 500) {
                    return Result.failure(IOException("Google Apps Script server error (HTTP $code): $bodyString"))
                }

                if (!response.isSuccessful && bodyString.isBlank()) {
                    return Result.failure(IOException("Server error HTTP $code"))
                }

                return try {
                    val apiResponse = responseAdapter.fromJson(bodyString)
                    if (apiResponse != null) {
                        if (!apiResponse.ok && !apiResponse.error.isNullOrBlank()) {
                            Result.failure(IOException(apiResponse.error))
                        } else {
                            Result.success(apiResponse)
                        }
                    } else {
                        Result.failure(IOException("Invalid response payload from server"))
                    }
                } catch (parseErr: Exception) {
                    Result.failure(IOException("Could not parse server response: ${parseErr.message}"))
                }
            }
        }
        return Result.failure(IOException("Too many redirects ($redirects)"))
    }

    private fun mapException(e: Exception): Exception {
        val msg = when {
            e is java.net.UnknownHostException -> "No internet connection or invalid domain"
            e is java.net.SocketTimeoutException -> "Connection timed out"
            e is java.net.ConnectException -> "Failed to connect to server"
            e is SecurityException -> e.message ?: "Security validation error"
            e is IllegalArgumentException -> e.message ?: "Invalid request argument"
            else -> e.message ?: "Unknown communication error"
        }
        return IOException(msg, e)
    }
}
