package com.phonecompanion.app.data

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

/**
 * Backend API surface stub. Shapes follow docs/api-contract.md.
 * TODO (pairing/widgets/notifications PRs): wire base URL, auth headers,
 * models, and error mapping.
 */
interface ApiService {

    // TODO (widgets PR): response models for the widget envelope.
    @GET("api/v1/widgets")
    suspend fun getWidgets(): String

    // TODO (pairing PR): enroll request/response models.
    @POST("api/v1/enroll")
    suspend fun enroll(@Body request: String): String
}
