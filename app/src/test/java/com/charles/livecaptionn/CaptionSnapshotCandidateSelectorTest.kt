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
}
