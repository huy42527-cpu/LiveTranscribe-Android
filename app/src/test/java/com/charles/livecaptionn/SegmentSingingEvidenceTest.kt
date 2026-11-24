package com.charles.livecaptionn

import com.charles.livecaptionn.speech.SegmentSingingEvidence
import com.charles.livecaptionn.speech.TimedSingingEvidence
import com.charles.livecaptionn.speech.YamnetSingingClassifier
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentSingingEvidenceTest {
    private fun window(
        start: Long,
        end: Long,
        speech: Float,
        singing: Float
    ) = TimedSingingEvidence(
        startSample = start,
        endSample = end,
        scores = YamnetSingingClassifier.Scores(speech = speech, singing = singing)
    )

    @Test fun singingInPreviousSegmentCannotTagFollowingSpeech() {
        val history = listOf(
            window(0, 15_600, 0.1f, 0.9f),
            window(7_680, 23_280, 0.1f, 0.9f),
            window(23_280, 38_880, 0.9f, 0.1f)
        )
        assertFalse(
            SegmentSingingEvidence.hasConfidentSinging(
                segmentStartSample = 23_280,
                segmentEndSample = 40_000,
                windows = history
            )
        )
    }

    @Test fun speechOnlySegmentIsNotTagged() {
        val history = listOf(
            window(0, 15_600, 0.9f, 0.1f),
            window(7_680, 23_280, 0.9f, 0.2f)
        )
        assertFalse(
            SegmentSingingEvidence.hasConfidentSinging(0, 24_000, history)
        )
    }

    @Test fun twoSingingDominantWindowsInsideSegmentAreTagged() {
        val history = listOf(
            window(0, 15_600, 0.1f, 0.9f),
            window(7_680, 23_280, 0.2f, 0.85f)
        )
        assertTrue(
            SegmentSingingEvidence.hasConfidentSinging(0, 24_000, history)
        )
    }

    @Test fun oneAvailableWindowIsInsufficient() {
        val history = listOf(window(0, 15_600, 0.1f, 0.9f))
        assertFalse(
            SegmentSingingEvidence.hasConfidentSinging(0, 20_000, history)
        )
    }

    @Test fun speechAndSingingOverlapRemainsUnlabeledWhenAmbiguous() {
        val history = listOf(
            window(0, 15_600, 0.8f, 0.8f),
            window(7_680, 23_280, 0.9f, 0.85f)
        )
        assertFalse(
            SegmentSingingEvidence.hasConfidentSinging(0, 24_000, history)
        )
    }

    @Test fun streamerSpeechInsideSameVoskSegmentPreventsCsEvenIfSongAppearsLater() {
        val history = listOf(
            window(0, 15_600, 0.9f, 0.2f),
            window(7_680, 23_280, 0.1f, 0.9f),
            window(15_360, 30_960, 0.1f, 0.9f)
        )
        assertFalse(
            SegmentSingingEvidence.hasConfidentSinging(
                segmentStartSample = 0,
                segmentEndSample = 32_000,
                windows = history
            )
        )
    }

    @Test fun quietBackgroundMusicDuringStreamerSpeechDoesNotGetCs() {
        val history = listOf(
            window(0, 15_600, 0.85f, 0.25f),
            window(7_680, 23_280, 0.8f, 0.3f),
            window(15_360, 30_960, 0.9f, 0.2f)
        )
        assertFalse(
            SegmentSingingEvidence.hasConfidentSinging(
                segmentStartSample = 0,
                segmentEndSample = 32_000,
                windows = history
            )
        )
    }

    @Test fun partiallyOverlappingWindowIsExcluded() {
        val history = listOf(
            window(0, 15_600, 0.1f, 0.9f),
            window(7_680, 23_280, 0.1f, 0.9f)
        )
        assertFalse(
            SegmentSingingEvidence.hasConfidentSinging(
                segmentStartSample = 8_000,
                segmentEndSample = 24_000,
                windows = history
            )
        )
    }
}
