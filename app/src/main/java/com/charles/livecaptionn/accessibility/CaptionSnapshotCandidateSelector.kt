package com.charles.livecaptionn.accessibility

/**
 * Chooses the freshest caption snapshot. Accessibility trees can retain old
 * caption nodes while another node is being updated, so source priority alone
 * is not enough after the first snapshot.
 */
internal object CaptionSnapshotCandidateSelector {
    fun choose(
        sourceCandidates: Set<String>,
        eventTextCandidates: Set<String>,
        windowCandidates: Set<String>,
        previousSnapshot: String = ""
    ): String {
        data class Candidate(val text: String, val sourcePriority: Int)

        fun valid(values: Set<String>, sourcePriority: Int): List<Candidate> =
            values.asSequence()
                .map(String::trim)
                .filter { it.isNotEmpty() && it.length <= 20000 && containsJapanese(it) }
                .map { Candidate(it, sourcePriority) }
                .toList()

        val candidates = valid(sourceCandidates, 3) +
            valid(eventTextCandidates, 2) +
            valid(windowCandidates, 1)

        return candidates.maxWithOrNull(
            compareBy<Candidate> { freshness(it.text, previousSnapshot) }
                .thenBy { it.sourcePriority }
                .thenBy { it.text.length }
        )?.text.orEmpty()
    }

    /**
     * Prefer a changed snapshot that continues or rolls forward from the last
     * one over an identical stale node, even if that stale node is longer.
     */
    private fun freshness(candidate: String, previous: String): Int {
        if (previous.isBlank()) return 0
        if (candidate == previous) return 0
        if (candidate.startsWith(previous) || candidate.contains(previous)) return 4
        if (hasRollingOverlap(previous, candidate)) return 3
        if (previous.startsWith(candidate)) return 1
        return 2
    }

    private fun hasRollingOverlap(previous: String, candidate: String): Boolean {
        val maxOverlap = minOf(previous.length, candidate.length)
        for (overlap in maxOverlap downTo 3) {
            if (previous.regionMatches(
                    previous.length - overlap,
                    candidate,
                    0,
                    overlap
                )
            ) return true
        }
        return false
    }

    private fun containsJapanese(value: String): Boolean = value.any {
        it in '\u3040'..'\u30ff' || it in '\u3400'..'\u9fff'
    }
}
