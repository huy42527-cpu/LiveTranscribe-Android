package com.charles.livecaptionn.accessibility

/**
 * Chooses caption text by freshness of its accessibility source, not by length
 * across unrelated nodes. Window-wide text is only a fallback because Android
 * may retain stale Live Caption nodes in other windows.
 */
internal object CaptionSnapshotCandidateSelector {
    fun choose(
        sourceCandidates: Set<String>,
        eventTextCandidates: Set<String>,
        windowCandidates: Set<String>
    ): String {
        fun valid(values: Set<String>): String? = values.asSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && it.length <= 20000 && containsJapanese(it) }
            .maxByOrNull { it.length }

        return valid(sourceCandidates)
            ?: valid(eventTextCandidates)
            ?: valid(windowCandidates)
            ?: ""
    }

    private fun containsJapanese(value: String): Boolean = value.any {
        it in '\u3040'..'\u30ff' || it in '\u3400'..'\u9fff'
    }
}
