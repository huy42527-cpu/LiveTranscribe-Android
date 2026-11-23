package com.charles.livecaptionn.translation

import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Google's undocumented web translation endpoint. This is not the official
 * Cloud Translation API; availability and rate limits are not guaranteed.
 */
interface GoogleWebTranslationApi {
    @GET("translate_a/t")
    suspend fun translate(
        @Query("client") client: String = "dict-chrome-ex",
        @Query("sl") source: String,
        @Query("tl") target: String,
        @Query("q") text: String
    ): List<String>
}
