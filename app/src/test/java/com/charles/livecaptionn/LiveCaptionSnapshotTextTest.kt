package com.charles.livecaptionn

import com.charles.livecaptionn.speech.LiveCaptionSnapshotText
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveCaptionSnapshotTextTest {

    @Test
    fun growingSnapshot_returnsOnlyNewSuffix() {
        assertEquals(
            "new words",
            LiveCaptionSnapshotText.unfinalizedText("old words new words", "old words")
        )
    }

    @Test
    fun unchangedSnapshot_returnsEmpty() {
        assertEquals(
            "",
            LiveCaptionSnapshotText.unfinalizedText("already finalized", "already finalized")
        )
    }

    @Test
    fun snapshotContainingBaseline_returnsTextAfterBaseline() {
        assertEquals(
            "next line",
            LiveCaptionSnapshotText.unfinalizedText(
                "prefix already finalized next line",
                "already finalized"
            )
        )
    }

    @Test
    fun rollingSnapshot_usesLongSuffixPrefixOverlap() {
        assertEquals(
            "new words",
            LiveCaptionSnapshotText.unfinalizedText(
                "finalized phrase overlap new words",
                "older text finalized phrase overlap"
            )
        )
    }

    @Test
    fun rollingJapaneseSnapshot_usesCharacterOverlap() {
        assertEquals(
            "新しい文です",
            LiveCaptionSnapshotText.unfinalizedText(
                "前の字幕と重なる部分新しい文です",
                "古い字幕前の字幕と重なる部分"
            )
        )
    }

    @Test
    fun noReliableOverlap_keepsSnapshotRatherThanDroppingText() {
        assertEquals(
            "unrelated fresh caption",
            LiveCaptionSnapshotText.unfinalizedText(
                "unrelated fresh caption",
                "previous caption text"
            )
        )
    }
}
