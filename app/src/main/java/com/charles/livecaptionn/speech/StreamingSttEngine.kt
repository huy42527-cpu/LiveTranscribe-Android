package com.charles.livecaptionn.speech

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.charles.livecaptionn.settings.AudioSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import java.io.File

/**
 * Continuous, low-latency speech-to-text engine backed by a live
 * [VoskStreamingSession]. Reads 16kHz mono PCM from either the mic or
 * MediaProjection system-audio capture and feeds ~100ms chunks straight into
 * Vosk, emitting partial results as they evolve and final segments on
 * silence boundaries.
 *
 * This replaces the old batch SystemAudioEngine, which accumulated 2-6s
 * windows and created a fresh Recognizer per chunk, throwing away streaming
 * state and making captions feel laggy and incoherent.
 */
class StreamingSttEngine(
    private val context: Context,
    private val audioSource: AudioSource,
    private val mediaProjection: MediaProjection?,
    private val languageCode: String,
    private val localSttClient: LocalVoskSttClient,
    private val scope: CoroutineScope,
    private val onResult: (SpeechResult) -> Unit,
    private val onError: (String?) -> Unit = {},
    private val speakerModelDir: File? = null,
    private val yamnetModelFile: File? = null
) : SpeechEngine {

    private val statusMutable = MutableStateFlow(RecognitionStatus.IDLE)
    val status: StateFlow<RecognitionStatus> = statusMutable

    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    private var session: VoskStreamingSession? = null
    private var yamnetJob: Job? = null
    private val yamnetWindows = Channel<FloatArray>(
        capacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    @Volatile private var latestSingingConfident = false
    @Volatile private var latestSingingAtMs = 0L

    @Volatile private var running = false
    @Volatile private var paused = false

    override fun start() {
        if (running) return
        running = true
        paused = false
        scope.launch(Dispatchers.IO) { startInternal() }
    }

    override fun stop() {
        running = false
        paused = false
        val finalSingingEvidence = hasRecentSingingEvidence()
        val activeCaptureJob = captureJob
        captureJob = null
        try { audioRecord?.stop() } catch (_: Throwable) {}
        activeCaptureJob?.cancel()
        yamnetWindows.close()
        yamnetJob?.cancel()
        yamnetJob = null
        latestSingingConfident = false
        latestSingingAtMs = 0L
        try { audioRecord?.release() } catch (_: Throwable) {}
        audioRecord = null
        session?.finish()?.let { finalResult ->
            if (finalResult.text.isNotBlank()) {
                onResult(
                    SpeechResult(
                        finalResult.text,
                        isFinal = true,
                        speakerEmbedding = finalResult.speakerEmbedding,
                        speakerFrames = finalResult.speakerFrames,
                        isSinging = finalSingingEvidence
                    )
                )
            }
        }
        session?.close()
        session = null
        if (audioSource == AudioSource.SYSTEM) {
            try { mediaProjection?.stop() } catch (_: Throwable) {}
        }
        statusMutable.value = RecognitionStatus.IDLE
    }

    override fun pause() {
        paused = true
        statusMutable.value = RecognitionStatus.PAUSED
    }

    override fun resume() {
        if (!running) return
        paused = false
        statusMutable.value = RecognitionStatus.LISTENING
    }

    private suspend fun startInternal() {
        val localSession = localSttClient.openSession(
            languageCode = languageCode,
            sampleRate = SAMPLE_RATE,
            speakerModelDir = speakerModelDir
        )
        if (localSession == null) {
            onError(
                "No on-device model installed for '$languageCode'. " +
                    "Open the language picker to download one."
            )
            statusMutable.value = RecognitionStatus.ERROR
            running = false
            return
        }
        // A stop can arrive while the model is loading. Do not create audio
        // resources for a session that has already been cancelled.
        if (!running) {
            localSession.close()
            return
        }
        session = localSession

        val record = try {
            buildAudioRecord()
        } catch (t: Throwable) {
            Log.e(TAG, "buildAudioRecord failed", t)
            onError("Audio capture failed: ${t.message ?: t::class.java.simpleName}")
            statusMutable.value = RecognitionStatus.ERROR
            session?.close(); session = null
            running = false
            return
        }

        if (!running) {
            record?.release()
            localSession.close()
            if (session === localSession) session = null
            return
        }

        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            onError(
                if (audioSource == AudioSource.SYSTEM)
                    "System audio capture failed to initialize"
                else
                    "Microphone capture failed to initialize"
            )
            statusMutable.value = RecognitionStatus.ERROR
            record?.release()
            session?.close(); session = null
            running = false
            return
        }
        audioRecord = record
        if (!running) {
            record.release()
            if (audioRecord === record) audioRecord = null
            localSession.close()
            if (session === localSession) session = null
            return
        }

        try {
            record.startRecording()
        } catch (t: Throwable) {
            Log.e(TAG, "startRecording failed", t)
            onError("Audio capture failed to start: ${t.message ?: t::class.java.simpleName}")
            statusMutable.value = RecognitionStatus.ERROR
            record.release()
            audioRecord = null
            session?.close(); session = null
            running = false
            return
        }

        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            onError("Audio capture did not start")
            statusMutable.value = RecognitionStatus.ERROR
            record.release()
            audioRecord = null
            session?.close(); session = null
            running = false
            return
        }

        if (!running) {
            try { record.stop() } catch (_: Throwable) {}
            record.release()
            if (audioRecord === record) audioRecord = null
            localSession.close()
            if (session === localSession) session = null
            return
        }

        statusMutable.value = RecognitionStatus.LISTENING
        onError(null)

        startYamnetWorker()
        captureJob = scope.launch(Dispatchers.Default) { captureLoop(record) }
    }

    private suspend fun captureLoop(record: AudioRecord) {
        val buffer = ByteArray(CHUNK_BYTES)
        var silentChunkStreak = 0
        var lastEmittedPartial = ""
        var sawAnyAudioEver = false
        val yamnetRing = FloatArray(YamnetSingingClassifier.WINDOW_SAMPLES)
        var yamnetWriteIndex = 0
        var yamnetSampleCount = 0
        var samplesSinceYamnetWindow = 0

        while (scope.isActive && running) {
            if (paused) {
                delay(120)
                continue
            }

            val read = try {
                record.read(buffer, 0, buffer.size)
            } catch (t: Throwable) {
                Log.w(TAG, "AudioRecord.read threw", t)
                onError("Audio capture read failed")
                statusMutable.value = RecognitionStatus.ERROR
                delay(500)
                continue
            }

            if (read <= 0) {
                // read == 0 is transient on some devices; anything negative is fatal-ish.
                if (read < 0) {
                    Log.w(TAG, "AudioRecord.read returned $read")
                    delay(100)
                }
                continue
            }

            val avgAbs = averageAbsAmplitude(buffer, read)
            val isSilent = avgAbs < SILENCE_AVERAGE_ABS_THRESHOLD

            // Copy the same captured PCM into a bounded, asynchronous classifier
            // branch. Vosk never waits for YAMNet inference.
            if (yamnetModelFile != null) {
                var sampleOffset = 0
                while (sampleOffset + 1 < read) {
                    val raw = (buffer[sampleOffset].toInt() and 0xFF) or
                        (buffer[sampleOffset + 1].toInt() shl 8)
                    yamnetRing[yamnetWriteIndex] = raw.toShort().toInt() / 32768f
                    yamnetWriteIndex = (yamnetWriteIndex + 1) % yamnetRing.size
                    yamnetSampleCount = (yamnetSampleCount + 1).coerceAtMost(yamnetRing.size)
                    samplesSinceYamnetWindow += 1
                    if (yamnetSampleCount == yamnetRing.size &&
                        samplesSinceYamnetWindow >= YAMNET_HOP_SAMPLES
                    ) {
                        val window = FloatArray(yamnetRing.size) { index ->
                            yamnetRing[(yamnetWriteIndex + index) % yamnetRing.size]
                        }
                        yamnetWindows.trySend(window)
                        samplesSinceYamnetWindow = 0
                    }
                    sampleOffset += 2
                }
            }

            if (isSilent) {
                silentChunkStreak += 1
                // For system audio, flag when we've been silent for a long time so the
                // user knows nothing is actually playing.
                if (audioSource == AudioSource.SYSTEM &&
                    !sawAnyAudioEver &&
                    silentChunkStreak >= SILENT_CHUNKS_BEFORE_SYSTEM_HINT
                ) {
                    onError(NO_CAPTURABLE_AUDIO_MESSAGE)
                }
            } else {
                silentChunkStreak = 0
                sawAnyAudioEver = true
                onError(null)
            }

            val localSession = session ?: break
            val result = localSession.feed(buffer, read)

            if (!running) break

            if (result.accepted) {
                // Vosk hit a silence/segment boundary and emitted a finalized chunk.
                if (result.text.isNotBlank()) {
                    onResult(
                        SpeechResult(
                            result.text,
                            isFinal = true,
                            speakerEmbedding = result.speakerEmbedding,
                            speakerFrames = result.speakerFrames,
                            isSinging = hasRecentSingingEvidence()
                        )
                    )
                    lastEmittedPartial = ""
                }
            } else if (result.text.isNotBlank() && result.text != lastEmittedPartial) {
                // Live partial — drives the "words appearing as you speak" overlay.
                onResult(SpeechResult(result.text, isFinal = false))
                lastEmittedPartial = result.text
            }

            if (!paused && running) {
                statusMutable.value = RecognitionStatus.LISTENING
            }
        }
    }

    private fun startYamnetWorker() {
        val modelFile = yamnetModelFile ?: return
        yamnetJob = scope.launch(Dispatchers.Default) {
            val classifier = try {
                YamnetSingingClassifier(modelFile)
            } catch (t: Throwable) {
                Log.w(TAG, "YAMNet unavailable; continuing Vosk without singing labels", t)
                return@launch
            }
            val tracker = SingingEvidenceTracker()
            try {
                for (window in yamnetWindows) {
                    val scores = try {
                        classifier.classify(window)
                    } catch (t: Throwable) {
                        Log.w(TAG, "YAMNet inference failed; dropping this window", t)
                        continue
                    }
                    val evidence = tracker.add(scores)
                    latestSingingConfident = evidence.confidentSinger
                    latestSingingAtMs = SystemClock.elapsedRealtime()
                }
            } finally {
                classifier.close()
            }
        }
    }

    private fun hasRecentSingingEvidence(): Boolean {
        return latestSingingConfident &&
            SystemClock.elapsedRealtime() - latestSingingAtMs <= YAMNET_EVIDENCE_MAX_AGE_MS
    }

    private fun buildAudioRecord(): AudioRecord? {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(CHUNK_BYTES * 8)

        return when (audioSource) {
            AudioSource.SYSTEM -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    onError("System audio capture requires Android 10 (API 29) or newer. Please switch to Microphone mode.")
                    return null
                }
                val projection = mediaProjection
                    ?: run {
                        onError("MediaProjection not available for system audio")
                        return null
                    }
                val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()
                AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(config)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(minBuf)
                    .build()
            }
            AudioSource.MIC -> {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    onError("Microphone permission not granted. Allow microphone access and try again.")
                    return null
                }
                AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(minBuf)
                    .build()
            }
        }
    }

    companion object {
        private const val TAG = "StreamingSttEngine"
        private const val SAMPLE_RATE = 16_000
        /** ~100ms at 16kHz mono 16-bit = 3200 bytes. Small chunks = live partials. */
        private const val CHUNK_BYTES = 3200
        private const val YAMNET_HOP_SAMPLES = 7_680 // 480 ms, 50% overlap
        private const val YAMNET_EVIDENCE_MAX_AGE_MS = 1_200L
        private const val SILENCE_AVERAGE_ABS_THRESHOLD = 120
        private const val SILENT_CHUNKS_BEFORE_SYSTEM_HINT = 30 // ~3s of silence
        private const val NO_CAPTURABLE_AUDIO_MESSAGE =
            "No capturable system audio yet. Play unmuted media in an app that allows audio capture."

        private fun averageAbsAmplitude(pcmData: ByteArray, length: Int): Int {
            var sum = 0L
            var samples = 0
            var i = 0
            val end = minOf(length, pcmData.size) - 1
            while (i < end) {
                val low = pcmData[i].toInt() and 0xFF
                val high = pcmData[i + 1].toInt()
                val sample = (high shl 8) or low
                sum += abs(sample)
                samples += 1
                i += 2
            }
            return if (samples == 0) 0 else (sum / samples).toInt()
        }
    }
}
