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
    val revision: Long = 0
)

data class CaptionRuntimeState(
    val originalText: String = "",
    val translatedText: String = "",
    val transcriptLines: List<CaptionRuntimeLine> = emptyList(),
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
