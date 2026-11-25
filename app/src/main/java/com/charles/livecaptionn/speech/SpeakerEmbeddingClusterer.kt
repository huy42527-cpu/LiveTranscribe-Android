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
    private val maxSpeakers: Int = 32,
    /** Higher evidence bar used only to confirm a brand-new speaker profile. */
    private val newSpeakerThreshold: Float = matchThreshold.coerceAtLeast(0.90f)
) {
    data class Decision(
        /** Zero-based speaker index; convert to A/B/C only when displaying. */
        val speakerIndex: Int? = null,
        /** Segment IDs confirmed together when a new speaker profile is created. */
        val confirmedSegmentIds: List<Long> = emptyList(),
        /** Current speaker assignment for every segment retained by confirmed profiles. */
        val speakerAssignments: Map<Long, Int> = emptyMap(),
        val ignored: Boolean = false,
        /** Machine-readable reason for the latest assignment decision; useful for diagnostics. */
        val diagnostic: String = "unassigned",
        /** Similarity scores for diagnosing profile/candidate merges without exposing vectors. */
        val similaritySummary: String = ""
    )

    private data class Profile(
        var index: Int,
        val firstSegmentId: Long,
        var centroid: FloatArray,
        var observations: Int = 1,
        val segmentIds: MutableSet<Long> = linkedSetOf()
    )

    private data class Candidate(
        var centroid: FloatArray,
        var observations: Int,
        val segmentIds: MutableList<Long>,
        var lastObservedAtMs: Long
    )

    private val profiles = mutableListOf<Profile>()
    private val candidates = mutableListOf<Candidate>()

    init {
        require(minFrames > 0)
        require(matchThreshold in -1f..1f)
        require(minMatchMargin in 0f..2f)
        require(confirmationSegments >= 2)
        require(maxSpeakers > 0)
        require(newSpeakerThreshold in matchThreshold..1f)
    }

    @Synchronized
    fun observe(
        segmentId: Long,
        embedding: FloatArray,
        frameCount: Int,
        nowMs: Long = System.nanoTime() / 1_000_000L
    ): Decision {
        expireCandidates(nowMs)
        if (frameCount < minFrames) return Decision(ignored = true, diagnostic = "insufficient-frames")
        val normalized = normalize(embedding) ?: return Decision(ignored = true, diagnostic = "invalid-embedding")

        val matchingProfiles = profiles
            .filter { it.centroid.size == normalized.size }
            .map { it to cosine(it.centroid, normalized) }
            .sortedByDescending { it.second }

        val best = matchingProfiles.getOrNull(0)
        val secondScore = matchingProfiles.getOrNull(1)?.second
        val profileScores = matchingProfiles.take(3).joinToString(",") { it.second.toString() }

        // Evaluate pending candidates even when a confirmed profile also matches.
        // Otherwise a new speaker that resembles an existing profile can never
        // accumulate the independent observations needed for confirmation.
        val matchingCandidates = candidates
            .filter { it.centroid.size == normalized.size }
            .map { it to cosine(it.centroid, normalized) }
            .sortedByDescending { it.second }
        val candidateScores = matchingCandidates.take(3).joinToString(",") { it.second.toString() }
        val similaritySummary = "profiles=[$profileScores];candidates=[$candidateScores]"
        val bestCandidate = matchingCandidates.getOrNull(0)
        val secondCandidate = matchingCandidates.getOrNull(1)
        if (
            bestCandidate != null &&
            secondCandidate != null &&
            bestCandidate.second >= matchThreshold &&
            secondCandidate.second >= matchThreshold &&
            bestCandidate.second - secondCandidate.second < minMatchMargin
        ) {
            return Decision(
                diagnostic = "ambiguous-candidate-match",
                similaritySummary = similaritySummary
            )
        }
        val candidate = bestCandidate
            ?.takeIf { it.second >= matchThreshold }
            ?.first
        val bestCandidateScore = matchingCandidates.firstOrNull()?.second
        val profileMatches = best != null && best.second >= matchThreshold
        val candidateMatches = candidate != null && bestCandidateScore != null

        if (profileMatches && candidateMatches) {
            val difference = best!!.second - bestCandidateScore!!
            if (kotlin.math.abs(difference) < minMatchMargin) {
                return Decision(
                    diagnostic = "ambiguous-profile-candidate-match",
                    similaritySummary = similaritySummary
                )
            }
            if (difference > 0f) {
                if (secondScore != null && best.second - secondScore < minMatchMargin) {
                    return Decision(
                        diagnostic = "ambiguous-profile-match",
                        similaritySummary = similaritySummary
                    )
                }
                if (best.second >= (matchThreshold + 0.08f).coerceAtMost(1f)) {
                    updateCentroid(best.first, normalized)
                }
                best.first.segmentIds += segmentId
                trimProfileSegments(best.first)
                return Decision(
                    speakerIndex = best.first.index,
                    speakerAssignments = currentAssignments(),
                    diagnostic = "matched-existing-speaker",
                    similaritySummary = similaritySummary
                )
            }
            // Candidate is a materially better match; let it collect evidence.
        } else if (profileMatches) {
            if (secondScore != null && best!!.second - secondScore < minMatchMargin) {
                return Decision(
                    diagnostic = "ambiguous-profile-match",
                    similaritySummary = similaritySummary
                )
            }
            if (best!!.second >= (matchThreshold + 0.08f).coerceAtMost(1f)) {
                updateCentroid(best.first, normalized)
            }
            best.first.segmentIds += segmentId
            trimProfileSegments(best.first)
            return Decision(
                speakerIndex = best.first.index,
                speakerAssignments = currentAssignments(),
                diagnostic = "matched-existing-speaker",
                similaritySummary = similaritySummary
            )
        }

        // If the vector is dimensionally incompatible with existing profiles,
        // it cannot safely create a profile in this session.
        if (profiles.any { it.centroid.size != normalized.size }) {
            return Decision(
                diagnostic = "embedding-dimension-mismatch",
                similaritySummary = "$similaritySummary;candidate=not-evaluated"
            )
        }

        if (candidate == null) {
            candidates += Candidate(normalized, 1, mutableListOf(segmentId), nowMs)
            trimCandidates()
            return Decision(
                diagnostic = "new-candidate-pending",
                similaritySummary = similaritySummary
            )
        }

        // A repeated callback/revision for the same Vosk segment is not
        // independent evidence and must never confirm a new speaker.
        if (segmentId in candidate.segmentIds) return Decision(diagnostic = "duplicate-segment", similaritySummary = similaritySummary)

        updateCandidate(candidate, normalized)
        candidate.observations += 1
        candidate.lastObservedAtMs = nowMs
        if (candidate.segmentIds.size < confirmationSegments) {
            candidate.segmentIds += segmentId
        }

        if (candidate.observations < confirmationSegments) return Decision(diagnostic = "candidate-awaiting-confirmation", similaritySummary = similaritySummary)
        // Require the latest independent segment to strongly agree before minting a permanent profile.
        if ((bestCandidateScore ?: -1f) < newSpeakerThreshold) {
            return Decision(diagnostic = "candidate-awaiting-stability", similaritySummary = similaritySummary)
        }
        if (profiles.size >= maxSpeakers) {
            candidates.remove(candidate)
            return Decision(diagnostic = "maximum-speakers-reached", similaritySummary = similaritySummary)
        }

        val profile = Profile(
            index = profiles.size,
            firstSegmentId = candidate.segmentIds.minOrNull() ?: segmentId,
            centroid = candidate.centroid,
            observations = candidate.observations,
            segmentIds = candidate.segmentIds.toMutableSet()
        )
        profiles += profile
        candidates.remove(candidate)

        // A candidate can be confirmed later than another speaker despite
        // having appeared earlier. Reorder all profiles by their earliest
        // observed segment, then return assignments so the service can
        // retroactively correct labels already present in the transcript.
        profiles.sortBy { it.firstSegmentId }
        profiles.forEachIndexed { index, item -> item.index = index }

        return Decision(
            speakerIndex = profile.index,
            confirmedSegmentIds = candidate.segmentIds.toList(),
            speakerAssignments = currentAssignments(),
            diagnostic = "new-speaker-confirmed",
            similaritySummary = similaritySummary
        )
    }

    @Synchronized
    fun reset() {
        profiles.clear()
        candidates.clear()
    }

    @Synchronized
    fun speakerCount(): Int = profiles.size

    private fun expireCandidates(nowMs: Long) {
        candidates.removeAll { candidate ->
            nowMs < candidate.lastObservedAtMs ||
                nowMs - candidate.lastObservedAtMs > CANDIDATE_TTL_MS
        }
    }

    private fun trimProfileSegments(profile: Profile) {
        while (profile.segmentIds.size > MAX_RETAINED_SEGMENTS) {
            val oldest = profile.segmentIds.minOrNull() ?: break
            profile.segmentIds.remove(oldest)
        }
    }

    private fun currentAssignments(): Map<Long, Int> =
        profiles.flatMap { profile ->
            profile.segmentIds.map { segmentId -> segmentId to profile.index }
        }.toMap()

    private fun trimCandidates() {
        while (candidates.size > MAX_CANDIDATES) candidates.removeAt(0)
    }

    private companion object {
        const val CANDIDATE_TTL_MS = 30_000L
        const val MAX_CANDIDATES = 8
        const val MAX_RETAINED_SEGMENTS = 30
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
