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
    private val onSingingUpdate: (Long) -> Unit = {},
    /** Called once after 2s of capture-time silence following speech; never gates STT. */
    private val onSilenceBoundary: () -> Unit = {},
    private val speakerModelDir: File? = null,
    private val yamnetModelFile: File? = null
) : SpeechEngine {

    private val statusMutable = MutableStateFlow(RecognitionStatus.IDLE)
    val status: StateFlow<RecognitionStatus> = statusMutable

    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    private var session: VoskStreamingSession? = null
    private val sessionLock = Any()
    private var yamnetJob: Job? = null
    // A channel is scoped to one recognition session; stop() closes it.
    // Recreate it on every start so later sessions can classify audio too.
    @Volatile private var yamnetWindows = newYamnetChannel()
    private val singingEvidenceLock = Any()
    private val timedSingingEvidence = ArrayDeque<TimedSingingEvidence>()
    private val pendingSingingSegments = ArrayDeque<PendingSingingSegment>()
    private var nextSegmentId = 0L
    private var yamnetClassifiedWindows = 0L
    // Highest capture-time sample whose classifier window has completed.
    // Used to avoid deciding CS from only the early part of a Vosk segment.
    private var yamnetProcessedThroughSample = 0L
    @Volatile private var captureSamplePosition = 0L
    @Volatile private var lastFinalizedSegmentSample = 0L

    @Volatile private var running = false
    @Volatile private var paused = false

    override fun start() {
        if (running) return
        yamnetWindows = newYamnetChannel()
        captureSamplePosition = 0L
        lastFinalizedSegmentSample = 0L
        synchronized(singingEvidenceLock) {
            timedSingingEvidence.clear()
            pendingSingingSegments.clear()
            nextSegmentId = 0L
            yamnetClassifiedWindows = 0L
            yamnetProcessedThroughSample = 0L
        }
        running = true
        paused = false
        scope.launch(Dispatchers.IO) { startInternal() }
    }

    private fun newYamnetChannel() = Channel<YamnetWindow>(
        capacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    override fun stop() {
        running = false
        paused = false
        val activeCaptureJob = captureJob
        captureJob = null
        try { audioRecord?.stop() } catch (_: Throwable) {}
        activeCaptureJob?.cancel()
        yamnetWindows.close()
        yamnetJob?.cancel()
        yamnetJob = null
        try { audioRecord?.release() } catch (_: Throwable) {}
        audioRecord = null
        synchronized(sessionLock) {
            // Stop policy clears the transcript immediately, so flushing and
            // publishing a final Vosk result here would only create work that
            // is discarded by CaptionForegroundService.stopFlow(). Close the
            // native session directly instead.
            session?.close()
            session = null
        }
        if (audioSource == AudioSource.SYSTEM) {
            try { mediaProjection?.stop() } catch (_: Throwable) {}
        }
        statusMutable.value = RecognitionStatus.IDLE
    }

    private fun closeSessionIfCurrent(localSession: VoskStreamingSession) {
        synchronized(sessionLock) {
            if (session === localSession) {
                session = null
                localSession.close()
            }
        }
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
        val attached = synchronized(sessionLock) {
            if (!running) false
            else {
                session = localSession
                true
            }
        }
        if (!attached) {
            localSession.close()
            return
        }

        val record = try {
            buildAudioRecord()
        } catch (t: Throwable) {
            Log.e(TAG, "buildAudioRecord failed", t)
            onError("Audio capture failed: ${t.message ?: t::class.java.simpleName}")
            statusMutable.value = RecognitionStatus.ERROR
            closeSessionIfCurrent(localSession)
            running = false
            return
        }

        if (!running) {
            record?.release()
            closeSessionIfCurrent(localSession)
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
            closeSessionIfCurrent(localSession)
            running = false
            return
        }
        audioRecord = record
        if (!running) {
            record.release()
            if (audioRecord === record) audioRecord = null
            closeSessionIfCurrent(localSession)
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
            closeSessionIfCurrent(localSession)
            running = false
            return
        }

        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            onError("Audio capture did not start")
            statusMutable.value = RecognitionStatus.ERROR
            record.release()
            audioRecord = null
            closeSessionIfCurrent(localSession)
            running = false
            return
        }

        if (!running) {
            try { record.stop() } catch (_: Throwable) {}
            record.release()
            if (audioRecord === record) audioRecord = null
            closeSessionIfCurrent(localSession)
            return
        }

        statusMutable.value = RecognitionStatus.LISTENING
        onError(null)

        startYamnetWorker()
        // AudioRecord must be drained promptly; keep its blocking read loop off the
        // CPU-bound Default dispatcher shared with YAMNet inference.
        captureJob = scope.launch(Dispatchers.IO) { captureLoop(record) }
    }

    private suspend fun captureLoop(record: AudioRecord) {
        val buffer = ByteArray(CHUNK_BYTES)
        var silentChunkStreak = 0
        var lastEmittedPartial = ""
        var sawAnyAudioEver = false
        var speechSinceSilenceBoundary = false
        var silentSamplesSinceSpeech = 0L
        val yamnetRing = FloatArray(YamnetSingingClassifier.WINDOW_SAMPLES)
        var yamnetWriteIndex = 0
        var yamnetSampleCount = 0
        var samplesSinceYamnetWindow = 0
        var captureSampleCursor = 0L

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

            // Track capture-time sample positions independently of classifier
            // availability so Vosk segment boundaries share the same timeline.
            val chunkStartSample = captureSampleCursor
            captureSampleCursor += read / 2
            captureSamplePosition = captureSampleCursor

            // Copy the same captured PCM into a bounded, asynchronous classifier
            // branch. Each window carries its exact capture-time interval.
            // Vosk never waits for YAMNet inference.
            if (yamnetModelFile != null) {
                var sampleOffset = 0
                var absoluteSamplePosition = chunkStartSample
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
                        val windowEndSample = absoluteSamplePosition + 1
                        yamnetWindows.trySend(
                            YamnetWindow(
                                samples = window,
                                startSample = windowEndSample - YamnetSingingClassifier.WINDOW_SAMPLES,
                                endSample = windowEndSample
                            )
                        )
                        samplesSinceYamnetWindow = 0
                    }
                    absoluteSamplePosition += 1
                    sampleOffset += 2
                }
            }

            if (isSilent) {
                silentChunkStreak += 1
                if (speechSinceSilenceBoundary) {
                    silentSamplesSinceSpeech += read / 2L
                    if (silentSamplesSinceSpeech >= SILENCE_LINE_BREAK_SAMPLES) {
                        speechSinceSilenceBoundary = false
                        silentSamplesSinceSpeech = 0L
                        onSilenceBoundary()
                    }
                }
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
                speechSinceSilenceBoundary = true
                silentSamplesSinceSpeech = 0L
                sawAnyAudioEver = true
                onError(null)
            }

            val localSession = session ?: break
            val result = synchronized(sessionLock) {
                if (!running || session !== localSession) null
                else localSession.feed(buffer, read)
            } ?: break

            if (!running) break

            if (result.accepted) {
                // Evaluate only classifier windows wholly contained in this
                // exact Vosk segment. A prior song can never tag the next line.
                val segmentStartSample = lastFinalizedSegmentSample
                val segmentEndSample = captureSampleCursor
                val segmentId = synchronized(singingEvidenceLock) {
                    ++nextSegmentId
                }
                val pending = PendingSingingSegment(
                    id = segmentId,
                    startSample = segmentStartSample,
                    endSample = segmentEndSample
                )
                synchronized(singingEvidenceLock) {
                    pendingSingingSegments.addLast(pending)
                    while (pendingSingingSegments.size > MAX_PENDING_SINGING_SEGMENTS) {
                        pendingSingingSegments.removeFirst()
                    }
                }
                val evidenceSnapshot = synchronized(singingEvidenceLock) {
                    timedSingingEvidence.toList()
                }
                val classifierCaughtUp = synchronized(singingEvidenceLock) {
                    SingingEvidenceReadiness.isComplete(
                        yamnetProcessedThroughSample,
                        segmentEndSample
                    )
                }
                val containedEvidence = evidenceSnapshot.filter {
                    it.startSample >= segmentStartSample && it.endSample <= segmentEndSample
                }
                val singingDominantWindows = containedEvidence.count {
                    it.scores.singing >= 0.55f && it.scores.singing > it.scores.speech + 0.10f
                }
                val speechOrAmbiguousWindows = containedEvidence.count {
                    it.scores.speech >= it.scores.singing - 0.10f
                }
                val isSinging = classifierCaughtUp &&
                    SegmentSingingEvidence.hasConfidentSinging(
                        segmentStartSample, segmentEndSample, evidenceSnapshot
                    )
                Log.i(TAG, "segmentEvidence id=$segmentId durationMs=${(segmentEndSample - segmentStartSample) * 1000L / SAMPLE_RATE} containedWindows=${containedEvidence.size} singingDominant=$singingDominantWindows speechOrAmbiguous=$speechOrAmbiguousWindows cs=$isSinging")
                if (isSinging) {
                    synchronized(singingEvidenceLock) { pending.singingReported = true }
                }
                lastFinalizedSegmentSample = segmentEndSample

                if (result.text.isNotBlank()) {
                    onResult(
                        SpeechResult(
                            result.text,
                            isFinal = true,
                            speakerEmbedding = result.speakerEmbedding,
                            speakerFrames = result.speakerFrames,
                            isSinging = isSinging,
                            segmentId = segmentId
                        )
                    )
                    synchronized(singingEvidenceLock) { pending.resultEmitted = true }
                    reportLateSingingIfReady()
                    lastEmittedPartial = ""
                } else {
                    synchronized(singingEvidenceLock) {
                        pendingSingingSegments.removeAll { it.id == segmentId }
                    }
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
        val sessionWindows = yamnetWindows
        yamnetJob = scope.launch(Dispatchers.Default) {
            val classifier = try {
                YamnetSingingClassifier(modelFile)
            } catch (t: Throwable) {
                Log.w(TAG, "YAMNet unavailable; continuing Vosk without singing labels", t)
                return@launch
            }
            Log.i(TAG, "YAMNet worker started; classifier ready")
            try {
                for (window in sessionWindows) {
                    val scores = try {
                        classifier.classify(window.samples)
                    } catch (t: Throwable) {
                        Log.w(TAG, "YAMNet inference failed; dropping this window", t)
                        continue
                    }
                    synchronized(singingEvidenceLock) {
                        yamnetClassifiedWindows += 1
                        if (yamnetClassifiedWindows % 5L == 0L) {
                            val top = scores.topClasses.joinToString(",") {
                                "${it.index}:${it.score}"
                            }
                            Log.d(
                                TAG,
                                "yamnet windows=$yamnetClassifiedWindows " +
                                    "rms=${scores.rms} peak=${scores.peak} " +
                                    "speech=${scores.speech} singing=${scores.singing} top=[$top]"
                            )
                        }
                        timedSingingEvidence.addLast(
                            TimedSingingEvidence(
                                startSample = window.startSample,
                                endSample = window.endSample,
                                scores = scores
                            )
                        )
                        yamnetProcessedThroughSample = maxOf(
                            yamnetProcessedThroughSample,
                            window.endSample
                        )
                        val oldestAllowed = window.endSample - MAX_EVIDENCE_HISTORY_SAMPLES
                        while (timedSingingEvidence.isNotEmpty() &&
                            timedSingingEvidence.first().endSample < oldestAllowed
                        ) {
                            timedSingingEvidence.removeFirst()
                        }
                    }
                    reportLateSingingIfReady()
                }
            } finally {
                classifier.close()
            }
        }
    }

    private data class PendingSingingSegment(
        val id: Long,
        val startSample: Long,
        val endSample: Long,
        var resultEmitted: Boolean = false,
        var singingReported: Boolean = false
    )

    /**
     * YAMNet is intentionally asynchronous. If a matching window finishes after
     * Vosk emitted its line, notify the service to mark that exact line CS.
     * Never delay or revise the Vosk transcript itself.
     */
    private fun reportLateSingingIfReady() {
        val readyIds = synchronized(singingEvidenceLock) {
            val evidence = timedSingingEvidence.toList()
            pendingSingingSegments.filter { segment ->
                segment.resultEmitted &&
                    !segment.singingReported &&
                    SingingEvidenceReadiness.isComplete(
                        yamnetProcessedThroughSample,
                        segment.endSample
                    ) &&
                    SegmentSingingEvidence.hasConfidentSinging(
                        segment.startSample,
                        segment.endSample,
                        evidence
                    )
            }.map { segment ->
                segment.singingReported = true
                segment.id
            }
        }
        readyIds.forEach { id ->
            try { onSingingUpdate(id) }
            catch (t: Throwable) { Log.w(TAG, "Late singing update callback failed", t) }
        }
    }

    private fun hasSingingEvidenceForSegment(
        segmentStartSample: Long,
        segmentEndSample: Long
    ): Boolean {
        if (yamnetModelFile == null || segmentEndSample <= segmentStartSample) return false
        val snapshot = synchronized(singingEvidenceLock) {
            timedSingingEvidence.toList()
        }
        return SegmentSingingEvidence.hasConfidentSinging(
            segmentStartSample = segmentStartSample,
            segmentEndSample = segmentEndSample,
            windows = snapshot
        )
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
        ).coerceAtLeast(CHUNK_BYTES * 16)

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
        private const val MAX_EVIDENCE_HISTORY_SAMPLES = SAMPLE_RATE * 20L
        private const val MAX_PENDING_SINGING_SEGMENTS = 64
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
