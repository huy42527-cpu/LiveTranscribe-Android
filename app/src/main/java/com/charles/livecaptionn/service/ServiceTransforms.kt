package com.charles.livecaptionn.service

/**
 * Pure functions extracted from CaptionForegroundService for testability.
 *
 * These transforms drive the core captioning pipeline:
 * recording incoming transcript lines, pairing them with translations,
 * and rendering the scrolling overlay body.
 */

/** Maximum number of transcript lines kept in the scroll-back buffer. */
internal const val MAX_TRANSCRIPT_LINES = 30

/**
 * Record a new STT result into the transcript line list.
 *
 * Partial results update the same open line and preserve its ID.
 * Final results close the current line.
 */
internal fun recordTranscriptResult(
    lines: List<CaptionRuntimeLine>,
    originalText: String,
    isFinal: Boolean,
    newLineId: Long
): List<CaptionRuntimeLine> {
    if (originalText.isBlank()) return lines

    // Open/closed state is driven only by isFinal (the STT segment boundary),
    // never by whether a translation has landed. A translation may arrive
    // mid-sentence and must not make the next partial create a duplicate line.
    val last = lines.lastOrNull()
    val lastIsOpen = last != null && !last.isFinal

    if (lastIsOpen) {
        if (last!!.originalText == originalText &&
            last.isFinal == isFinal
        ) {
            return lines
        }

        val updatedLine = if (last.originalText == originalText) {
            last.copy(
                isFinal = isFinal,
                revision = last.revision + 1
            )
        } else {
            last.copy(
                originalText = originalText,
                translatedText = "",
                isFinal = isFinal,
                revision = last.revision + 1
            )
        }

        return (lines.dropLast(1) + updatedLine)
            .takeLast(MAX_TRANSCRIPT_LINES)
    }

    return (
        lines + CaptionRuntimeLine(
            id = newLineId,
            originalText = originalText,
            isFinal = isFinal
        )
    ).takeLast(MAX_TRANSCRIPT_LINES)
}

/**
 * Pair a translated text with its matching transcript line.
 *
 * If expectedOriginalText is supplied, discard the translation when
 * the original text has changed since the translation was requested.
 */
internal fun updateTranscriptTranslation(
    lines: List<CaptionRuntimeLine>,
    lineId: Long,
    translatedText: String,
    expectedOriginalText: String? = null,
    expectedRevision: Long? = null
): List<CaptionRuntimeLine> {
    val existingIndex = lines.indexOfFirst { line ->
        line.id == lineId &&
            (expectedOriginalText == null ||
                line.originalText == expectedOriginalText) &&
            (expectedRevision == null ||
                line.revision == expectedRevision)
    }

    if (existingIndex < 0) return lines

    return lines.mapIndexed { index, line ->
        if (index == existingIndex) {
            line.copy(translatedText = translatedText)
        } else {
            line
        }
    }
}

/**
 * Build the scrolling overlay body text.
 *
 * Shows only translated lines so the user does not see both
 * raw source text and translation stacked together.
 */
internal fun overlayTranscriptText(
    runtime: CaptionRuntimeState
): String {
    val rendered = runtime.transcriptLines
        .mapNotNull { line ->
            val translated = line.translatedText.trim().takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val speakerIndex = line.speakerIndex
            if (runtime.speakerCount >= 2 && speakerIndex != null) {
                val label = ('A'.code + speakerIndex).toChar()
                "$label: $translated"
            } else {
                translated
            }
        }

    val historyText = if (rendered.isNotEmpty()) {
        rendered.joinToString("\n")
    } else {
        ""
    }

    val livePartial = runtime.translatedText.trim()

    return when {
        historyText.isBlank() -> livePartial
        livePartial.isBlank() -> historyText
        historyText.endsWith(livePartial) -> historyText
        else -> "$historyText\n$livePartial"
    }
}
