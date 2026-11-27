package com.charles.livecaptionn.speech

/**
 * Extracts the portion of a Live Caption snapshot that has not already been
 * finalized by the app. Android may expose either a growing transcript or a
 * rolling window, so the tracker handles exact baseline matches and a
 * sufficiently long suffix/prefix overlap.
 */
internal object LiveCaptionSnapshotText {
    private const val MIN_ROLLING_OVERLAP = 8

    fun unfinalizedText(snapshot: String, finalizedBaseline: String): String {
        if (snapshot.isBlank()) return ""
        if (finalizedBaseline.isBlank()) return snapshot.trim()

        if (snapshot.startsWith(finalizedBaseline)) {
            return snapshot.removePrefix(finalizedBaseline).trim()
        }

        val baselineIndex = snapshot.indexOf(finalizedBaseline)
        if (baselineIndex >= 0) {
            return snapshot.substring(baselineIndex + finalizedBaseline.length).trim()
        }

        // Caption UI may keep only a rolling tail, dropping the beginning of
        // the finalized text. Find the largest exact suffix/prefix overlap.
        val maxOverlap = minOf(finalizedBaseline.length, snapshot.length)
        for (overlap in maxOverlap downTo MIN_ROLLING_OVERLAP) {
            if (finalizedBaseline.regionMatches(
                    finalizedBaseline.length - overlap,
                    snapshot,
                    0,
                    overlap
                )
            ) {
                return snapshot.substring(overlap).trim()
            }
        }

        // No reliable overlap: treat it as a new snapshot rather than
        // aggressively deleting text that may genuinely be new.
        return snapshot.trim()
    }
}
