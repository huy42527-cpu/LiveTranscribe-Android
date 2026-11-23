package com.charles.livecaptionn.translation

import com.squareup.moshi.JsonReader
import okio.Buffer

/**
 * Extracts the first translated-text field from Google's array response.
 * Other fields may be null, numeric, or nested and are intentionally ignored.
 */
internal object GoogleWebTranslationResponseParser {
    fun parse(json: String): String {
        val reader = JsonReader.of(Buffer().writeUtf8(json))
        reader.beginArray()
        if (!reader.hasNext()) return ""
        val translated = if (reader.peek() == JsonReader.Token.STRING) {
            reader.nextString()
        } else {
            reader.skipValue()
            ""
        }
        return translated.trim()
    }
}
