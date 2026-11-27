package com.charles.livecaptionn.translation

import android.util.Log
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Optional online Japanese/other-language translator using Google's web
 * endpoint. Recognized caption text is sent to Google when this backend is
 * explicitly selected by the user.
 */
class GoogleWebTranslationRepository : TranslationRepository {

    private val api: GoogleWebTranslationApi by lazy {
        val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .build()
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create())
            .build()
            .create(GoogleWebTranslationApi::class.java)
    }

    override suspend fun translate(
        text: String,
        sourceCode: String,
        targetCode: String,
        autoDetect: Boolean
    ): String {
        val clean = text.trim()
        if (clean.isEmpty()) return clean
        if (targetCode.isBlank()) {
            Log.w(TAG, "Translation skipped: target language is blank.")
            return ""
        }

        return try {
            val response = api.translate(
                source = if (autoDetect || sourceCode.isBlank()) "auto" else sourceCode,
                target = targetCode,
                text = clean
            )
            val contentType = response.headers()["Content-Type"]?.substringBefore(";") ?: "unknown"
            if (!response.isSuccessful) {
                response.errorBody()?.close()
                Log.w(TAG, "HTTP failure: code=${response.code()}, contentType=$contentType.")
                return ""
            }

            val body = response.body()
            if (body == null) {
                Log.w(TAG, "HTTP success without response body: code=${response.code()}.")
                return ""
            }

            val raw = body.string()
            if (raw.isBlank()) {
                Log.w(TAG, "HTTP success with empty response body: code=${response.code()}, contentType=$contentType.")
                return ""
            }

            try {
                GoogleWebTranslationResponseParser.parse(raw, targetCode).also { translated ->
                    if (translated.isBlank()) {
                        // Log only the top-level JSON shape, never response text.
                        val shape = raw.trimStart().firstOrNull() ?: ' '
                        Log.w(TAG, "Parser returned blank: code=${response.code()}, contentType=$contentType, bodyStart=$shape.")
                    } else {
                        Log.d(TAG, "Translation succeeded: code=${response.code()}, contentType=$contentType.")
                    }
                }
            } catch (error: Exception) {
                Log.w(TAG, "Response parsing failed: ${error.javaClass.simpleName}, code=${response.code()}, contentType=$contentType.")
                ""
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Do not log caption text, request URLs, or exception messages.
            Log.w(TAG, "Request failed: ${error.javaClass.simpleName}.")
            ""
        }
    }

    private companion object {
        const val BASE_URL = "https://translate.googleapis.com/"
        const val TAG = "GoogleWebTranslation"
    }
}
