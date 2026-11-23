package com.charles.livecaptionn.speech

import kotlin.math.sqrt

/**
 * Conservative online clustering for Vosk x-vectors.
 *
 * Thresholds are mandatory inputs: they must be calibrated with representative
 * device/audio samples before production use. This class deliberately does
 * not choose a universal cosine threshold.
 */
internal class SpeakerEmbeddingClusterer(
    private val minFrames: Int,
    private val matchThreshold: Float,
    private val minMatchMargin: Float,
    private val confirmationSegments: Int = 2,
    private val maxSpeakers: Int = 6
) {
    data class Decision(
        /** Zero-based speaker index; convert to A/B/C only when displaying. */
        val speakerIndex: Int? = null,
        /** Segment IDs confirmed together when a new speaker profile is created. */
        val confirmedSegmentIds: List<Long> = emptyList(),
        val ignored: Boolean = false
    )

    private data class Profile(
        val index: Int,
        var centroid: FloatArray,
        var observations: Int = 1
    )

    private data class Candidate(
        var centroid: FloatArray,
        var observations: Int,
        val segmentIds: MutableList<Long>
    )

    private val profiles = mutableListOf<Profile>()
    private val candidates = mutableListOf<Candidate>()

    init {
        require(minFrames > 0)
        require(matchThreshold in -1f..1f)
        require(minMatchMargin in 0f..2f)
        require(confirmationSegments >= 2)
        require(maxSpeakers > 0)
    }

    @Synchronized
    fun observe(segmentId: Long, embedding: FloatArray, frameCount: Int): Decision {
        if (frameCount < minFrames) return Decision(ignored = true)
        val normalized = normalize(embedding) ?: return Decision(ignored = true)

        val matchingProfiles = profiles
            .filter { it.centroid.size == normalized.size }
            .map { it to cosine(it.centroid, normalized) }
            .sortedByDescending { it.second }

        val best = matchingProfiles.getOrNull(0)
        val secondScore = matchingProfiles.getOrNull(1)?.second
        if (best != null && best.second >= matchThreshold) {
            if (secondScore != null && best.second - secondScore < minMatchMargin) {
                return Decision()
            }
            updateCentroid(best.first, normalized)
            return Decision(speakerIndex = best.first.index)
        }

        // If the vector is dimensionally incompatible with existing profiles,
        // it cannot safely create a profile in this session.
        if (profiles.any { it.centroid.size != normalized.size }) return Decision()

        val candidate = candidates
            .filter { it.centroid.size == normalized.size }
            .map { it to cosine(it.centroid, normalized) }
            .maxByOrNull { it.second }
            ?.takeIf { it.second >= matchThreshold }
            ?.first

        if (candidate == null) {
            candidates += Candidate(normalized, 1, mutableListOf(segmentId))
            trimCandidates()
            return Decision()
        }

        // A repeated callback/revision for the same Vosk segment is not
        // independent evidence and must never confirm a new speaker.
        if (segmentId in candidate.segmentIds) return Decision()

        updateCandidate(candidate, normalized)
        candidate.observations += 1
        if (candidate.segmentIds.size < confirmationSegments) {
            candidate.segmentIds += segmentId
        }

        if (candidate.observations < confirmationSegments) return Decision()
        if (profiles.size >= maxSpeakers) {
            candidates.remove(candidate)
            return Decision()
        }

        val profile = Profile(
            index = profiles.size,
            centroid = candidate.centroid,
            observations = candidate.observations
        )
        profiles += profile
        candidates.remove(candidate)
        return Decision(
            speakerIndex = profile.index,
            confirmedSegmentIds = candidate.segmentIds.toList()
        )
    }

    @Synchronized
    fun reset() {
        profiles.clear()
        candidates.clear()
    }

    @Synchronized
    fun speakerCount(): Int = profiles.size

    private fun trimCandidates() {
        while (candidates.size > 8) candidates.removeAt(0)
    }

    private fun updateCentroid(profile: Profile, vector: FloatArray) {
        val count = profile.observations.toFloat()
        for (i in profile.centroid.indices) {
            profile.centroid[i] =
                (profile.centroid[i] * count + vector[i]) / (count + 1f)
        }
        profile.centroid = normalize(profile.centroid) ?: vector
        profile.observations += 1
    }

    private fun updateCandidate(candidate: Candidate, vector: FloatArray) {
        val count = candidate.observations.toFloat()
        for (i in candidate.centroid.indices) {
            candidate.centroid[i] =
                (candidate.centroid[i] * count + vector[i]) / (count + 1f)
        }
        candidate.centroid = normalize(candidate.centroid) ?: vector
    }

    private fun normalize(input: FloatArray): FloatArray? {
        if (input.isEmpty() || input.any { !it.isFinite() }) return null
        var squaredNorm = 0.0
        for (value in input) squaredNorm += value.toDouble() * value.toDouble()
        if (!squaredNorm.isFinite() || squaredNorm <= 1e-12) return null
        val norm = sqrt(squaredNorm).toFloat()
        return FloatArray(input.size) { input[it] / norm }
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return -1f
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        return dot.coerceIn(-1f, 1f)
    }
}
