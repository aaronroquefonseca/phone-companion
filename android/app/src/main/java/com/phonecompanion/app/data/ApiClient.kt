package com.phonecompanion.app.data

import com.phonecompanion.app.BuildConfig
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Retrofit factory. No hardcoded hosts: every client is built for an
 * explicitly supplied, validated base URL (user-entered or QR-provided).
 */
object ApiClient {

    // Network Moshi is owned by AppMoshi so the parse-path regression test
    // exercises the exact production instance (no config drift).
    private val moshi get() = AppMoshi.moshi

    /**
     * Normalizes and validates a server URL. Accepts bare hosts
     * ("example.com") by assuming https. Returns a URL with trailing slash
     * suitable for Retrofit.
     *
     * @throws IllegalArgumentException when the URL is empty, malformed, or
     * uses cleartext http outside debug loopback (emulator/dev server).
     */
    fun normalizeBaseUrl(raw: String): String {
        var url = raw.trim()
        require(url.isNotBlank()) { "Server URL is empty" }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://$url"
        }
        if (!url.startsWith("https://") && !(BuildConfig.DEBUG && isLoopback(url))) {
            throw IllegalArgumentException("Server URL must use https")
        }
        if (!url.endsWith("/")) url += "/"
        return url
    }

    private fun isLoopback(url: String): Boolean {
        val host = url.substringAfter("://").substringBefore("/").substringBefore(":")
            .lowercase().trimStart('[').trimEnd(']')
        return host == "localhost" || host == "127.0.0.1" || host == "::1" ||
            host == "10.0.2.2"
    }

    /** Extracts the stable machine-readable `error` code, if present. */
    fun parseErrorCode(rawJson: String?): String? {
        if (rawJson.isNullOrBlank()) return null
        return Regex(""""error"\s*:\s*"([^"]+)"""").find(rawJson)?.groupValues?.get(1)
    }

    /**
     * Builds an [ApiService] for [baseUrl] (validated via [normalizeBaseUrl]).
     *
     * @param tokenProvider supplies the per-device Bearer token, or null
     * when unauthenticated (enroll call). Evaluated per request so rotation
     * and revocation take effect without rebuilding the client.
     */
    fun create(baseUrl: String, tokenProvider: () -> String? = { null }): ApiService {
        val normalized = normalizeBaseUrl(baseUrl)

        val auth = Interceptor { chain ->
            val token = tokenProvider()
            val request = if (token.isNullOrBlank()) {
                chain.request()
            } else {
                chain.request().newBuilder()
                    .header("Authorization", "Bearer $token")
                    .build()
            }
            chain.proceed(request)
        }

        val http = baseBuilder()
            .addInterceptor(auth)
            .build()

        return Retrofit.Builder()
            .baseUrl(normalized)
            .client(http)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(ApiService::class.java)
    }

    /**
     * Shared OkHttp stack for non-API downloads (APK fetch). Same timeouts
     * as the API client, but deliberately WITHOUT the auth interceptor
     * (APK URLs may be signed third-party URLs, and the device Bearer
     * token must never be sent to a non-server host) and WITHOUT any
     * logging interceptor (Level.NONE even in debug builds: APK URLs may
     * carry signed query tokens that must never reach logcat).
     */
    fun downloadClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Long-poll client for instant mode: same auth + URL validation as
     * [create], but with a read timeout that outlives the server's 300 s
     * hold, so `GET /api/v1/events/wait` is not cut off as a failure.
     */
    /**
     * Streaming client for the fast link: no read timeout of its own, so a
     * quiet device keeps its socket. The server pings every 15 s, so a
     * [STREAM_READ_TIMEOUT_SECONDS] read timeout is what detects a dead
     * connection without waiting forever.
     */
    fun createStreaming(baseUrl: String, tokenProvider: () -> String?): ApiService {
        val auth = Interceptor { chain ->
            val token = tokenProvider()
            val request = if (token.isNullOrBlank()) {
                chain.request()
            } else {
                chain.request().newBuilder().header("Authorization", "Bearer $token").build()
            }
            chain.proceed(request)
        }
        val http = baseBuilder()
            .readTimeout(STREAM_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .addInterceptor(auth)
            .build()
        return Retrofit.Builder()
            .baseUrl(normalizeBaseUrl(baseUrl))
            .client(http)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(ApiService::class.java)
    }

    private const val STREAM_READ_TIMEOUT_SECONDS = 45L

    fun createLongPoll(baseUrl: String, tokenProvider: () -> String?): ApiService {
        val normalized = normalizeBaseUrl(baseUrl)
        val auth = Interceptor { chain ->
            val token = tokenProvider()
            val request = if (token.isNullOrBlank()) {
                chain.request()
            } else {
                chain.request().newBuilder().header("Authorization", "Bearer $token").build()
            }
            chain.proceed(request)
        }
        val http = baseBuilder()
            .readTimeout(LONG_POLL_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .addInterceptor(auth)
            .build()
        return Retrofit.Builder()
            .baseUrl(normalized)
            .client(http)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(ApiService::class.java)
    }

    /** Server holds events/wait for 300 s; allow 30 s of slack. */
    private const val LONG_POLL_READ_TIMEOUT_SECONDS = 330L

    private fun baseBuilder(): OkHttpClient.Builder = OkHttpClient.Builder()
        .apply {
            // Debug-only BASIC logs method/URL/status only, compiled out of
            // release builds entirely (see create()).
            if (BuildConfig.DEBUG) {
                addInterceptor(HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BASIC))
            }
        }
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
}
