package com.charles.livecaptionn.speech

data class SpeechResult(
    val text: String,
    val isFinal: Boolean,
    /** Vosk x-vector; null for partial or backends without speaker metadata. */
    val speakerEmbedding: FloatArray? = null,
    /** Number of Vosk frames represented by [speakerEmbedding]. */
    val speakerFrames: Int = 0,
    /** True only when recent YAMNet evidence strongly favors singing over speech. */
    val isSinging: Boolean = false
)
