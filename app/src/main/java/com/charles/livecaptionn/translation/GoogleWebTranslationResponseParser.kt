package com.charles.livecaptionn.translation

import com.squareup.moshi.JsonReader
import okio.Buffer

/**
 * Extracts translated text from Google's dict-chrome-ex response.
 * The endpoint normally returns nested sentence pairs such as:
 * [["translated sentence", "original sentence"]].
 */
internal object GoogleWebTranslationResponseParser {
    fun parse(json: String): String {
        val reader = JsonReader.of(Buffer().writeUtf8(json))
        val translated = StringBuilder()
        reader.beginArray()

        while (reader.hasNext()) {
            when (reader.peek()) {
                JsonReader.Token.BEGIN_ARRAY -> {
                    reader.beginArray()
                    if (reader.hasNext() && reader.peek() == JsonReader.Token.STRING) {
                        translated.append(reader.nextString())
                    }
                    while (reader.hasNext()) reader.skipValue()
                    reader.endArray()
                }
                JsonReader.Token.STRING -> translated.append(reader.nextString())
                else -> reader.skipValue()
            }
        }

        reader.endArray()
        return translated.toString().trim()
    }
}
