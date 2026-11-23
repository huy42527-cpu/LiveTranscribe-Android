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
    fun joinsMultipleTranslatedSegments() {
        val json = """[["Xin chào", "こんにちは"], ["bạn", "あなた"]]"""

        assertEquals(
            "Xin chàobạn",
            GoogleWebTranslationResponseParser.parse(json)
        )
    }

    @Test
    fun parsesFlatStringResponseForCompatibility() {
        val json = """["Xin chào", "こんにちは", null, 1]"""

        assertEquals(
            "Xin chàoこんにちは",
            GoogleWebTranslationResponseParser.parse(json)
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
