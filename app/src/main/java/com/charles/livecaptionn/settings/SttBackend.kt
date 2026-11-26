package com.charles.livecaptionn.settings

enum class SttBackend(val label: String) {
    LOCAL_VOSK("On-device (streaming)"),
    REMOTE_WHISPER("Remote Whisper"),
    ANDROID_LIVE_CAPTION("Android Live Caption");

    companion object {
        fun fromName(name: String): SttBackend =
            entries.firstOrNull { it.name == name } ?: LOCAL_VOSK
    }
}
