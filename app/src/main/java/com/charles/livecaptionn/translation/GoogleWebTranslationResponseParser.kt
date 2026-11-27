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
            val previous = translated.lastOrNull()
            val next = segment.first()
            if (previous != null && needsWordSpaces &&
                !previous.isWhitespace() && !next.isWhitespace() &&
                !isClosingPunctuation(next) && !isOpeningPunctuation(previous)
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

    private fun isClosingPunctuation(char: Char): Boolean = when (Character.getType(char)) {
        Character.END_PUNCTUATION.toInt(),
        Character.FINAL_QUOTE_PUNCTUATION.toInt(),
        Character.OTHER_PUNCTUATION.toInt() -> true
        else -> false
    }

    private fun isOpeningPunctuation(char: Char): Boolean = when (Character.getType(char)) {
        Character.START_PUNCTUATION.toInt(),
        Character.INITIAL_QUOTE_PUNCTUATION.toInt() -> true
        else -> false
    }

    private val LANGUAGES_WITHOUT_WORD_SPACES = setOf(
        "zh", "ja", "th", "lo", "km", "my"
    )
}
