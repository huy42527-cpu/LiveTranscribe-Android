package com.charles.livecaptionn.translation

import okhttp3.ResponseBody
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Google's undocumented web translation endpoint. This is not the official
 * Cloud Translation API; availability and rate limits are not guaranteed.
 *
 * Keep the response raw: Google's payload includes null and numeric fields,
 * so decoding it as List<String> can fail before the translated text is read.
 */
interface GoogleWebTranslationApi {
    @GET("translate_a/t")
    suspend fun translate(
        @Query("client") client: String = "dict-chrome-ex",
        @Query("sl") source: String,
        @Query("tl") target: String,
        @Query("q") text: String
    ): ResponseBody
}
