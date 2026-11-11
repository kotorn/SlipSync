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
import java.util.concurrent.TimeUnit

class SlipUploadApi {

    private val moshi: Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val uploadAdapter = moshi.adapter(SlipUploadRequest::class.java)
    private val pingAdapter = moshi.adapter(SlipPingRequest::class.java)
    private val responseAdapter = moshi.adapter(SlipApiResponse::class.java)

    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
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

    private fun executeWithRedirectHandling(initialRequest: Request): Result<SlipApiResponse> {
        var currentRequest = initialRequest
        var redirects = 0
        val maxRedirects = 5

        while (redirects < maxRedirects) {
            client.newCall(currentRequest).execute().use { response ->
                val code = response.code
                // Check if Google Apps Script issued a 301/302/303 redirect
                if (response.isRedirect && code in listOf(301, 302, 303, 307)) {
                    val location = response.header("Location")
                    if (!location.isNullOrBlank()) {
                        redirects++
                        // Google Apps Script redirect should be followed via GET
                        currentRequest = Request.Builder()
                            .url(location)
                            .get()
                            .build()
                        return@use // continue while loop
                    }
                }

                val bodyString = response.body?.string().orEmpty()

                if (!response.isSuccessful && bodyString.isBlank()) {
                    return Result.failure(IOException("Server error HTTP $code"))
                }

                return try {
                    val apiResponse = responseAdapter.fromJson(bodyString)
                    if (apiResponse != null) {
                        Result.success(apiResponse)
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
            else -> e.message ?: "Unknown communication error"
        }
        return IOException(msg, e)
    }
}
