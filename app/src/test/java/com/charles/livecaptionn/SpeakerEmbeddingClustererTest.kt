package com.charles.livecaptionn

import com.charles.livecaptionn.speech.SpeakerEmbeddingClusterer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerEmbeddingClustererTest {
    private fun clusterer(
        threshold: Float = 0.8f,
        margin: Float = 0.1f,
        confirmations: Int = 2,
        maxSpeakers: Int = 6
    ) = SpeakerEmbeddingClusterer(
        minFrames = 50,
        matchThreshold = threshold,
        minMatchMargin = margin,
        confirmationSegments = confirmations,
        maxSpeakers = maxSpeakers
    )

    @Test fun rejectsShortAndInvalidVectors() {
        val c = clusterer()
        assertTrue(c.observe(1, floatArrayOf(1f, 0f), 49).ignored)
        assertTrue(c.observe(2, floatArrayOf(0f, 0f), 80).ignored)
        assertTrue(c.observe(3, floatArrayOf(Float.NaN, 1f), 80).ignored)
        assertTrue(c.observe(4, floatArrayOf(Float.POSITIVE_INFINITY, 1f), 80).ignored)
        assertEquals(0, c.speakerCount())
    }

    @Test fun requiresIndependentConfirmationBeforeCreatingSpeaker() {
        val c = clusterer()
        assertNull(c.observe(10, floatArrayOf(1f, 0f), 80).speakerIndex)
        assertEquals(0, c.speakerCount())
        val confirmed = c.observe(11, floatArrayOf(0.99f, 0.01f), 80)
        assertEquals(0, confirmed.speakerIndex)
        assertEquals(listOf(10L, 11L), confirmed.confirmedSegmentIds)
        assertEquals(1, c.speakerCount())
    }

    @Test fun knownSpeakerReturnsSameIndex() {
        val c = clusterer()
        c.observe(1, floatArrayOf(1f, 0f), 80)
        c.observe(2, floatArrayOf(1f, 0.01f), 80)
        assertEquals(0, c.observe(3, floatArrayOf(0.98f, 0.02f), 80).speakerIndex)
        assertEquals(1, c.speakerCount())
    }

    @Test fun secondSpeakerGetsNextIndex() {
        val c = clusterer()
        c.observe(1, floatArrayOf(1f, 0f), 80)
        c.observe(2, floatArrayOf(1f, 0.01f), 80)
        assertNull(c.observe(3, floatArrayOf(0f, 1f), 80).speakerIndex)
        val second = c.observe(4, floatArrayOf(0.01f, 1f), 80)
        assertEquals(1, second.speakerIndex)
        assertEquals(listOf(3L, 4L), second.confirmedSegmentIds)
    }

    @Test fun ambiguousMatchIsNotAssigned() {
        val c = clusterer(threshold = 0.5f, margin = 0.2f)
        c.observe(1, floatArrayOf(1f, 0f), 80)
        c.observe(2, floatArrayOf(1f, 0.01f), 80)
        c.observe(3, floatArrayOf(0f, 1f), 80)
        c.observe(4, floatArrayOf(0.01f, 1f), 80)
        // Equidistant to the two learned profiles.
        assertNull(c.observe(5, floatArrayOf(1f, 1f), 80).speakerIndex)
    }

    @Test fun dimensionMismatchDoesNotCreateAnotherProfile() {
        val c = clusterer()
        c.observe(1, floatArrayOf(1f, 0f), 80)
        c.observe(2, floatArrayOf(1f, 0.01f), 80)
        assertNull(c.observe(3, floatArrayOf(0f, 0f, 1f), 80).speakerIndex)
        assertNull(c.observe(4, floatArrayOf(0f, 0f, 1f), 80).speakerIndex)
        assertEquals(1, c.speakerCount())
    }

    @Test fun resetClearsProfilesAndPendingCandidates() {
        val c = clusterer()
        c.observe(1, floatArrayOf(1f, 0f), 80)
        c.reset()
        assertEquals(0, c.speakerCount())
        assertFalse(c.observe(2, floatArrayOf(1f, 0f), 80).confirmedSegmentIds.isNotEmpty())
    }

    @Test fun maximumSpeakerCountIsEnforced() {
        val c = clusterer(maxSpeakers = 1)
        c.observe(1, floatArrayOf(1f, 0f), 80)
        c.observe(2, floatArrayOf(1f, 0.01f), 80)
        c.observe(3, floatArrayOf(0f, 1f), 80)
        c.observe(4, floatArrayOf(0.01f, 1f), 80)
        assertEquals(1, c.speakerCount())
    }
}
