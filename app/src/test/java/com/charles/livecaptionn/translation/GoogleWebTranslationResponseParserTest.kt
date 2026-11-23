package com.charles.livecaptionn.translation

import org.junit.Assert.assertEquals
import org.junit.Test

class GoogleWebTranslationResponseParserTest {

    @Test
    fun parsesTranslatedTextAndIgnoresMixedTrailingFields() {
        val json = """["Xin chào", "こんにちは", null, null, 1]"""

        assertEquals(
            "Xin chào",
            GoogleWebTranslationResponseParser.parse(json)
        )
    }

    @Test
    fun returnsEmptyWhenFirstFieldIsNotAString() {
        val json = """[null, "こんにちは", 1]"""

        assertEquals(
            "",
            GoogleWebTranslationResponseParser.parse(json)
        )
    }

    @Test
    fun trimsTranslatedText() {
        val json = """["  Xin chào  ", "こんにちは"]"""

        assertEquals(
            "Xin chào",
            GoogleWebTranslationResponseParser.parse(json)
        )
    }
}
