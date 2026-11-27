package com.charles.livecaptionn.translation

import com.squareup.moshi.JsonReader
import okio.Buffer
import java.util.Locale

/**
 * Extracts translated text from Google's dict-chrome-ex response.
 * Sentence chunks are joined with a word separator for languages that use
 * spaces; CJK/Thai/Lao/Khmer/Myanmar output is joined without adding one.
 */
internal object GoogleWebTranslationResponseParser {
    fun parse(json: String, targetCode: String = "en"): String {
        val reader = JsonReader.of(Buffer().writeUtf8(json))
        val translated = StringBuilder()
        val needsWordSpaces = targetCode.substringBefore('-').substringBefore('_')
            .lowercase(Locale.ROOT) !in LANGUAGES_WITHOUT_WORD_SPACES

        fun appendSegment(segment: String) {
            if (segment.isEmpty()) return
            if (translated.isNotEmpty() && needsWordSpaces &&
                !translated.last().isWhitespace() && !segment.first().isWhitespace()
            ) {
                translated.append(' ')
            }
            translated.append(segment)
        }

        reader.beginArray()
        while (reader.hasNext()) {
            when (reader.peek()) {
                JsonReader.Token.BEGIN_ARRAY -> {
                    reader.beginArray()
                    if (reader.hasNext() && reader.peek() == JsonReader.Token.STRING) {
                        appendSegment(reader.nextString())
                    }
                    while (reader.hasNext()) reader.skipValue()
                    reader.endArray()
                }
                JsonReader.Token.STRING -> appendSegment(reader.nextString())
                else -> reader.skipValue()
            }
        }
        reader.endArray()
        return translated.toString().trim()
    }

    private val LANGUAGES_WITHOUT_WORD_SPACES = setOf(
        "zh", "ja", "th", "lo", "km", "my"
    )
}
