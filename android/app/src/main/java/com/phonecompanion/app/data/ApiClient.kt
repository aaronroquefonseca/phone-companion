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

        val http = OkHttpClient.Builder()
            .addInterceptor(auth)
            .apply {
                // Debug-only body logging would leak the Bearer token and the
                // enroll response's device_key: BASIC logs method/URL/status
                // only, and is compiled out of release builds entirely.
                if (BuildConfig.DEBUG) {
                    addInterceptor(HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BASIC))
                }
            }
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        return Retrofit.Builder()
            .baseUrl(normalized)
            .client(http)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(ApiService::class.java)
    }
}
