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
        if (targetCode.isBlank()) return ""

        return try {
            val result = api.translate(
                source = if (autoDetect || sourceCode.isBlank()) "auto" else sourceCode,
                target = targetCode,
                text = clean
            )
            GoogleWebTranslationResponseParser.parse(result.string())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Do not log caption text or request URLs: they may contain private speech.
            // Avoid logging the exception: Retrofit/OkHttp messages may contain the full URL and caption query.
            Log.w(TAG, "Google Web translation request failed (${error.javaClass.simpleName}).")
            ""
        }
    }

    private companion object {
        const val BASE_URL = "https://translate.googleapis.com/"
        const val TAG = "GoogleWebTranslation"
    }
}
