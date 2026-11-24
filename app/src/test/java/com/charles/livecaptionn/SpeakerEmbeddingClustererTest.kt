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


    @Test fun staleCandidateExpiresAfterThirtySeconds() {
        val c = clusterer()
        assertNull(c.observe(1, floatArrayOf(1f, 0f), 80, nowMs = 0L).speakerIndex)

        // A different segment after the candidate TTL must start fresh.
        assertNull(c.observe(2, floatArrayOf(1f, 0f), 80, nowMs = 30_001L).speakerIndex)
        assertEquals(0, c.speakerCount())

        val confirmed = c.observe(3, floatArrayOf(0.99f, 0.01f), 80, nowMs = 30_002L)
        assertEquals(0, confirmed.speakerIndex)
        assertEquals(listOf(2L, 3L), confirmed.confirmedSegmentIds)
    }

    @Test fun duplicateSegmentIdCannotConfirmNewSpeaker() {
        val c = clusterer()
        assertNull(c.observe(7, floatArrayOf(1f, 0f), 80).speakerIndex)
        assertNull(c.observe(7, floatArrayOf(0.99f, 0.01f), 80).speakerIndex)
        assertEquals(0, c.speakerCount())
        assertEquals(0, c.observe(8, floatArrayOf(0.99f, 0.01f), 80).speakerIndex)
    }


    @Test fun retainedSpeakerAssignmentsStayBounded() {
        val c = clusterer()
        c.observe(1, floatArrayOf(1f, 0f), 80)
        val confirmed = c.observe(2, floatArrayOf(0.99f, 0.01f), 80)
        assertEquals(0, confirmed.speakerIndex)

        var latest = confirmed
        for (segmentId in 3L..45L) {
            latest = c.observe(segmentId, floatArrayOf(1f, 0.01f), 80)
        }
        assertTrue(latest.speakerAssignments.size <= 30)
        assertFalse(latest.speakerAssignments.containsKey(1L))
        assertTrue(latest.speakerAssignments.containsKey(45L))
    }

    @Test fun threeIndependentSegmentsAreAllReturnedForRetroactiveLabeling() {
        val c = clusterer(confirmations = 3)
        c.observe(20, floatArrayOf(1f, 0f), 80)
        c.observe(21, floatArrayOf(0.99f, 0.01f), 80)
        val confirmed = c.observe(22, floatArrayOf(0.98f, 0.02f), 80)
        assertEquals(0, confirmed.speakerIndex)
        assertEquals(listOf(20L, 21L, 22L), confirmed.confirmedSegmentIds)
    }

    @Test fun closerPendingCandidateWinsOverSimilarConfirmedProfile() {
        val c = clusterer(threshold = 0.8f, margin = 0.1f)
        fun vector(degrees: Double): FloatArray {
            val radians = Math.toRadians(degrees)
            return floatArrayOf(kotlin.math.cos(radians).toFloat(), kotlin.math.sin(radians).toFloat())
        }

        // B is first seen, then A is confirmed. B's later vectors also pass
        // the profile threshold, but are substantially closer to B's candidate.
        assertNull(c.observe(1, vector(40.0), 80).speakerIndex)
        assertNull(c.observe(2, vector(0.0), 80).speakerIndex)
        assertEquals(0, c.observe(3, vector(1.0), 80).speakerIndex)
        assertEquals(1, c.speakerCount())

        val pendingB = c.observe(4, vector(35.0), 80)
        assertNull(pendingB.speakerIndex)
        assertEquals("candidate-awaiting-confirmation", pendingB.diagnostic)

        val confirmedB = c.observe(5, vector(36.0), 80)
        assertEquals("new-speaker-confirmed", confirmedB.diagnostic)
        assertEquals(1, c.speakerCount())
        assertEquals(0, confirmedB.speakerAssignments[2L])
        assertEquals(0, confirmedB.speakerAssignments[3L])
        assertEquals(1, confirmedB.speakerAssignments[1L])
        assertEquals(1, confirmedB.speakerAssignments[4L])
        assertEquals(1, confirmedB.speakerAssignments[5L])
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


    @Test fun speakerLabelsFollowFirstObservedSegmentNotConfirmationOrder() {
        val c = clusterer()

        // Speaker A appears first but is confirmed after speaker B.
        assertNull(c.observe(10, floatArrayOf(1f, 0f), 80).speakerIndex)
        assertNull(c.observe(11, floatArrayOf(0f, 1f), 80).speakerIndex)

        val confirmedB = c.observe(12, floatArrayOf(0.01f, 1f), 80)
        assertEquals(0, confirmedB.speakerIndex)
        assertEquals(0, confirmedB.speakerAssignments[11L])
        assertEquals(0, confirmedB.speakerAssignments[12L])

        val confirmedA = c.observe(13, floatArrayOf(0.99f, 0.01f), 80)
        assertEquals(0, confirmedA.speakerIndex)
        assertEquals(0, confirmedA.speakerAssignments[10L])
        assertEquals(0, confirmedA.speakerAssignments[13L])
        assertEquals(1, confirmedA.speakerAssignments[11L])
        assertEquals(1, confirmedA.speakerAssignments[12L])
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
