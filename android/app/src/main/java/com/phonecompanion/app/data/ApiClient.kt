package com.phonecompanion.app.data

import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

/**
 * Retrofit holder stub.
 * TODO (pairing PR): configurable base URL (no hardcoding), OkHttp auth
 * interceptor with the device Bearer token, logging only in debug builds.
 */
object ApiClient {

    fun create(baseUrl: String): ApiService {
        // TODO: validate baseUrl is https (release) before building.
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .addConverterFactory(MoshiConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}
