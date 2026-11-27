package com.charles.livecaptionn.translation

import org.junit.Assert.assertEquals
import org.junit.Test

class GoogleWebTranslationResponseParserTest {

    @Test
    fun parsesNestedDictChromeExResponse() {
        val json = """[["Xin chào", "こんにちは", null, null, 10]]"""

        assertEquals(
            "Xin chào",
            GoogleWebTranslationResponseParser.parse(json)
        )
    }

    @Test
    fun joinsMultipleTranslatedSegmentsWithWordSpaces() {
        val json = """[["Xin chào", "こんにちは"], ["bạn", "あなた"]]"""

        assertEquals(
            "Xin chào bạn",
            GoogleWebTranslationResponseParser.parse(json, "vi")
        )
    }

    @Test
    fun doesNotAddSpaceBeforePunctuation() {
        val json = """[["Xin chào"], [","], ["bạn"], ["!"]]"""

        assertEquals(
            "Xin chào, bạn!",
            GoogleWebTranslationResponseParser.parse(json, "vi")
        )
    }

    @Test
    fun joinsJapaneseSegmentsWithoutAddingSpaces() {
        val json = """[["こんにちは"], ["世界"]]"""

        assertEquals(
            "こんにちは世界",
            GoogleWebTranslationResponseParser.parse(json, "ja")
        )
    }

    @Test
    fun parsesFlatStringResponseForCompatibility() {
        val json = """["Xin chào", "こんにちは", null, 1]"""

        assertEquals(
            "Xin chào こんにちは",
            GoogleWebTranslationResponseParser.parse(json, "en")
        )
    }

    @Test
    fun returnsEmptyWhenNoTranslatedStringExists() {
        val json = """[[null, "こんにちは", 1]]"""

        assertEquals(
            "",
            GoogleWebTranslationResponseParser.parse(json)
        )
    }
}
