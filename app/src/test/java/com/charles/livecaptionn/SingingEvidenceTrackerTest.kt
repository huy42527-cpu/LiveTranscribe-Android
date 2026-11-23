package com.charles.livecaptionn

import com.charles.livecaptionn.speech.SingingEvidenceTracker
import com.charles.livecaptionn.speech.YamnetSingingClassifier
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SingingEvidenceTrackerTest {
    @Test fun requiresRepeatedSingingEvidence() {
        val tracker = SingingEvidenceTracker()
        assertFalse(tracker.add(YamnetSingingClassifier.Scores(0.1f, 0.9f)).confidentSinger)
        assertTrue(tracker.add(YamnetSingingClassifier.Scores(0.1f, 0.9f)).confidentSinger)
    }

    @Test fun speechDominantOverlapIsNotLabeledAsSinger() {
        val tracker = SingingEvidenceTracker()
        tracker.add(YamnetSingingClassifier.Scores(0.9f, 0.8f))
        val evidence = tracker.add(YamnetSingingClassifier.Scores(0.9f, 0.8f))
        assertTrue(evidence.singingPresent)
        assertTrue(evidence.speechDominant)
        assertFalse(evidence.confidentSinger)
    }

    @Test fun ambiguousSingleWindowDoesNotPassHysteresis() {
        val tracker = SingingEvidenceTracker()
        tracker.add(YamnetSingingClassifier.Scores(0.1f, 0.9f))
        val evidence = tracker.add(YamnetSingingClassifier.Scores(0.1f, 0.1f))
        assertFalse(evidence.confidentSinger)
    }

    @Test fun resetClearsPreviousAudioEvidence() {
        val tracker = SingingEvidenceTracker()
        tracker.add(YamnetSingingClassifier.Scores(0.1f, 0.9f))
        tracker.add(YamnetSingingClassifier.Scores(0.1f, 0.9f))
        tracker.reset()
        val afterReset = tracker.add(YamnetSingingClassifier.Scores(0.1f, 0.9f))
        assertFalse(afterReset.confidentSinger)
    }
}
