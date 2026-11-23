package com.charles.livecaptionn.service

import com.charles.livecaptionn.speech.RecognitionStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CaptionRuntimeLine(
    val id: Long,
    val originalText: String,
    val translatedText: String = "",
    val isFinal: Boolean = false,
    val revision: Long = 0,
    /** Internal zero-based Vosk speaker cluster; hidden while only one exists. */
    val speakerIndex: Int? = null,
    /** This line was recognized while singing was the dominant vocal event. */
    val isSungLyrics: Boolean = false
)

data class CaptionRuntimeState(
    val originalText: String = "",
    val translatedText: String = "",
    val transcriptLines: List<CaptionRuntimeLine> = emptyList(),
    val speakerCount: Int = 0,
    val status: RecognitionStatus = RecognitionStatus.IDLE,
    val running: Boolean = false,
    val paused: Boolean = false,
    val lastError: String? = null
)

class CaptionRuntimeStore {
    private val mutable = MutableStateFlow(CaptionRuntimeState())

    val state: StateFlow<CaptionRuntimeState> = mutable.asStateFlow()

    @Synchronized
    fun update(
        transform: (CaptionRuntimeState) -> CaptionRuntimeState
    ): CaptionRuntimeState {
        val updatedState = transform(mutable.value)
        mutable.value = updatedState
        return updatedState
    }
}
