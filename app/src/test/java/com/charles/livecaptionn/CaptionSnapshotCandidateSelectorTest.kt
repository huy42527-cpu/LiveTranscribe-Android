package com.charles.livecaptionn.accessibility

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptionSnapshotCandidateSelectorTest {
    @Test
    fun currentEventSourceWinsOverLongerStaleWindowNode() {
        assertEquals(
            "今の字幕です",
            CaptionSnapshotCandidateSelector.choose(
                sourceCandidates = setOf("今の字幕です"),
                eventTextCandidates = setOf("今の字幕です"),
                windowCandidates = setOf("前の字幕はもっと長い文章として残っています")
            )
        )
    }

    @Test
    fun eventTextIsUsedWhenEventSourceHasNoJapaneseText() {
        assertEquals(
            "更新された字幕",
            CaptionSnapshotCandidateSelector.choose(
                sourceCandidates = setOf("Caption container"),
                eventTextCandidates = setOf("更新された字幕"),
                windowCandidates = setOf("古い長い字幕テキスト")
            )
        )
    }

    @Test
    fun changedWindowSnapshotWinsOverIdenticalStaleEventSource() {
        assertEquals(
            "今の字幕です更新中",
            CaptionSnapshotCandidateSelector.choose(
                sourceCandidates = setOf("今の字幕です"),
                eventTextCandidates = emptySet(),
                windowCandidates = setOf("今の字幕です更新中"),
                previousSnapshot = "今の字幕です"
            )
        )
    }

    @Test
    fun changedEventSourceWinsOverLongerStaleWindowNode() {
        assertEquals(
            "新しい字幕です",
            CaptionSnapshotCandidateSelector.choose(
                sourceCandidates = setOf("新しい字幕です"),
                eventTextCandidates = emptySet(),
                windowCandidates = setOf("前の字幕はかなり長く残っている古い文章です"),
                previousSnapshot = "前の字幕はかなり長く残っている古い文章です"
            )
        )
    }

    @Test
    fun windowScanIsOnlyFallback() {
        assertEquals(
            "字幕の予備",
            CaptionSnapshotCandidateSelector.choose(
                sourceCandidates = emptySet(),
                eventTextCandidates = emptySet(),
                windowCandidates = setOf("字幕の予備")
            )
        )
    }
    @Test
    fun exactNoJapaneseAudioStatusIsIgnored() {
        assertEquals(
            "",
            CaptionSnapshotCandidateSelector.choose(
                sourceCandidates = setOf("【日本語の音声が検出されませんでした】"),
                eventTextCandidates = emptySet(),
                windowCandidates = emptySet()
            )
        )
    }

    @Test
    fun realJapaneseCaptionStillWinsAlongsideStatusMessage() {
        assertEquals(
            "今日は何をしますか",
            CaptionSnapshotCandidateSelector.choose(
                sourceCandidates = setOf("日本語の音声が検出されませんでした"),
                eventTextCandidates = setOf("今日は何をしますか"),
                windowCandidates = emptySet()
            )
        )
    }

    @Test
    fun statusDetectorNormalizesWhitespaceAndDecorativeBrackets() {
        assertEquals(
            true,
            CaptionSnapshotCandidateSelector.isKnownStatusMessage("【日本語の音声が検出されませんでした】")
        )
        assertEquals(
            false,
            CaptionSnapshotCandidateSelector.isKnownStatusMessage("日本語の音声が検出されませんでした。今日は晴れです")
        )
    }
}
