package com.charles.livecaptionn.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.charles.livecaptionn.LiveCaptionApp
import com.charles.livecaptionn.MainActivity
import com.charles.livecaptionn.R
import com.charles.livecaptionn.data.TranscriptEntry
import com.charles.livecaptionn.data.applyGlossary
import com.charles.livecaptionn.overlay.OverlayController
import com.charles.livecaptionn.overlay.OverlayFontCatalog
import com.charles.livecaptionn.overlay.OverlayThemeCatalog
import com.charles.livecaptionn.overlay.OverlayUiState
import com.charles.livecaptionn.settings.AudioSource
import com.charles.livecaptionn.settings.CaptionSettings
import com.charles.livecaptionn.settings.LocaleMap
import com.charles.livecaptionn.settings.SttBackend
import com.charles.livecaptionn.speech.AndroidSpeechRecognizerManager
import com.charles.livecaptionn.speech.RecognitionStatus
import com.charles.livecaptionn.speech.SpeechEngine
import com.charles.livecaptionn.speech.SpeechResult
import com.charles.livecaptionn.speech.SpeakerEmbeddingClusterer
import com.charles.livecaptionn.speech.StreamingSttEngine
import com.charles.livecaptionn.speech.SystemAudioEngine
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentLinkedQueue

@OptIn(FlowPreview::class)
class CaptionForegroundService : Service() {
    private var serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var app: LiveCaptionApp

    private val captionSessionActive = AtomicBoolean(false)

    /** Bumps when the overlay view is attached so combine() re-runs (was skipping while overlay was null). */
    private val overlayReady = MutableStateFlow(0)

    private var speechEngine: SpeechEngine? = null
    private var speakerClusterer: SpeakerEmbeddingClusterer? = null
    private var overlayController: OverlayController? = null
    private var paused = false
    private var failureMessage: String? = null
    private var bufferedText = ""
    private val lineIdCounter = java.util.concurrent.atomic.AtomicLong(0)
    private val captionGeneration = java.util.concurrent.atomic.AtomicLong(0)
    private val singingSegmentLineIds = java.util.concurrent.ConcurrentHashMap<Long, Long>()
    @Volatile
    private var silenceFinalizedText: String? = null
    @Volatile
    private var bufferedLineId: Long = NO_LINE_ID
    @Volatile
    private var bufferedRevision: Long = 0L
    @Volatile
    private var historyOnNextTranslate = false
    private val pendingFinalTranslations = ConcurrentLinkedQueue<TranslationRequest>()

    /** Mic partials arrive faster than 400ms; cancel+restart jobs never finish. Debounce coalesces to one translate. */
    private val translateRequests = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private var currentAudioSource = AudioSource.MIC
    private var liveCaptionMode = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Must happen here, not in startFlow(): if the process was killed while
        // the notification was still showing, Android can deliver ACTION_STOP
        // (from the notification's "Stop" action) as the very first intent to a
        // freshly-created service instance, going straight to stopFlow() without
        // ACTION_START ever running. Initializing here guarantees `app` is set
        // no matter which action arrives first.
        app = application as LiveCaptionApp
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            // System restarted our sticky service with a null intent
            // (e.g., after process death). We cannot proceed without an
            // action, so stop cleanly to avoid foreground crash.
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent.action) {
            ACTION_START -> {
                // CRITICAL: call startForeground synchronously here, BEFORE
                // any conditional / async work. Android's 5-second deadline
                // after startForegroundService() is enforced strictly (and
                // tighter on slower devices like the Fire tablet, which was
                // crashing during cold-start DataStore reads). Any early-exit
                // path below must therefore use stopForeground + stopSelf
                // instead of raw stopSelf, which previously crashed the app.
                val audioSource = intent.getStringExtra(EXTRA_AUDIO_SOURCE)
                    ?.let { runCatching { AudioSource.valueOf(it) }.getOrNull() }
                    ?: AudioSource.MIC
                currentAudioSource = audioSource
                liveCaptionMode = intent.getBooleanExtra(EXTRA_LIVE_CAPTION, false)
                if (enterForeground(audioSource)) startFlow()
            }
            ACTION_STOP -> stopFlow()
            ACTION_PAUSE_RESUME -> togglePause()
            ACTION_TOGGLE_MINIMIZE -> toggleMinimized()
        }
        return START_STICKY
    }

    private fun enterForeground(audioSource: AudioSource): Boolean {
        val wantsMicrophone = audioSource != AudioSource.SYSTEM && !liveCaptionMode
        val hasMicPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        // Android throws a SecurityException from startForeground() itself if
        // FOREGROUND_SERVICE_TYPE_MICROPHONE is requested without RECORD_AUDIO
        // actually granted. MainActivity checks this before the initial launch,
        // but startFlow()'s audioSource-mismatch re-entry can call this again
        // mid-session — fail closed instead of crashing with the OS exception.
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            when {
                audioSource == AudioSource.SYSTEM -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                wantsMicrophone && hasMicPermission -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                else -> 0
            }
        } else {
            0
        }
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, buildNotification(), type)
            } else {
                startForeground(NOTIF_ID, buildNotification())
            }
            if (wantsMicrophone && !hasMicPermission) {
                failAndStop("Microphone permission not granted. Allow microphone access and try again.")
                false
            } else true
        } catch (t: Throwable) {
            Log.e("CaptionService", "Unable to enter foreground", t)
            failAndStop("Captioning could not start: ${t.message ?: "foreground service permission denied"}")
            false
        }
    }

    private fun startFlow() {
        if (!captionSessionActive.compareAndSet(false, true)) return
        val sessionGeneration = captionGeneration.incrementAndGet()
        singingSegmentLineIds.clear()
        failureMessage = null
        // Lock session-scoped settings immediately, before asynchronous setup.
        app.container.runtimeStore.update {
            it.copy(
                running = true,
                paused = false,
                status = RecognitionStatus.PROCESSING,
                lastError = null
            )
        }
        if (!Settings.canDrawOverlays(this)) {
            Log.e("CaptionService", "Overlay permission missing.")
            failAndStop("Overlay permission is required. Enable it in system settings and try again.")
            return
        }

        serviceScope.launch {
            // Always start a fresh session un-minimized; stale overlayMinimized=true from a
            // prior run was hiding the caption body entirely.
            app.container.settingsRepository.update { it.copy(overlayMinimized = false) }
            val settings = app.container.settingsRepository.settingsFlow.first()
            Log.d("CaptionService", "startFlow settings minimized=${settings.overlayMinimized} w=${settings.overlayWidthDp}dp h=${settings.overlayHeightDp}dp audio=${settings.audioSource}")
            // If the activity sent the wrong audio source in the intent extra
            // (legacy intent, settings changed between launch and service start),
            // re-enter foreground with the correct service type.
            if (!liveCaptionMode && settings.audioSource != currentAudioSource) {
                currentAudioSource = settings.audioSource
                if (!enterForeground(currentAudioSource)) return@launch
            } else if (liveCaptionMode) {
                // Live Caption is a text-only accessibility source, independent
                // of the app's mic/system-audio capture preference.
                currentAudioSource = AudioSource.MIC
            }
            val sttLanguageCode = settings.sourceLanguageCode.ifBlank { "en" }
            val localeForSpeechRec = LocaleMap.bcp47(sttLanguageCode)

            val premium = app.container.premiumRepository.state.first()
            if (com.charles.livecaptionn.translation.ProStartGate.requiresPro(
                    backend = settings.translationBackend,
                    sourceLanguageCode = settings.sourceLanguageCode,
                    targetLanguageCode = settings.targetLanguageCode,
                    hasPro = premium.hasPro
                )
            ) {
                failAndStop("This ML Kit language requires Pro. Choose a free language or upgrade.")
                return@launch
            }
            if (settings.sttBackend == SttBackend.LOCAL_VOSK) {
                val model = app.container.voskRegistry.installedModelFor(sttLanguageCode)
                if (model == null) {
                    failAndStop("No on-device model installed for '$sttLanguageCode'. Download one first.")
                    return@launch
                }
                if (model.quality == com.charles.livecaptionn.speech.ModelQuality.LARGE && !premium.hasPro) {
                    failAndStop("The large Vosk model requires Pro. Install or select a small model.")
                    return@launch
                }
            }

            // Auxiliary models are optional: a missing or unavailable model must
            // disable only its own enhancement, never the core Vosk caption session.
            // Resolve them independently so one feature can still work if the other
            // model is absent or fails to load.
            val speakerModelDir = if (
                settings.sttBackend == SttBackend.LOCAL_VOSK &&
                settings.speakerRecognitionEnabled
            ) {
                runCatching { app.container.voskSpeakerRegistry.resolveModelDir() }
                    .onFailure { Log.w("CaptionService", "Speaker model unavailable; continuing without speaker labels", it) }
                    .getOrNull()
            } else {
                null
            }

            val yamnetModelFile = if (
                settings.sttBackend == SttBackend.LOCAL_VOSK &&
                settings.speakerRecognitionEnabled
            ) {
                runCatching { app.container.yamnetRegistry.modelFile() }
                    .onFailure { Log.w("CaptionService", "Singing model unavailable; continuing without CS labels", it) }
                    .getOrNull()
            } else {
                null
            }

            Log.i("CaptionService", "auxModels speakerDirResolved=${speakerModelDir != null} yamnetResolved=${yamnetModelFile != null} speakerEnabled=${settings.speakerRecognitionEnabled} backend=${settings.sttBackend}")

            // Provisional thresholds are deliberately conservative and remain
            // experimental until calibrated against real Japanese live audio.
            speakerClusterer = if (speakerModelDir != null) {
                SpeakerEmbeddingClusterer(
                    minFrames = 50,
                    matchThreshold = 0.80f,
                    minMatchMargin = 0.10f,
                    confirmationSegments = 3,
                    maxSpeakers = 32,
                    newSpeakerThreshold = 0.90f
                )
            } else {
                null
            }

            app.container.runtimeStore.update {
                // Clear a reason left over from an earlier failed start.
                it.copy(running = true, paused = false, status = RecognitionStatus.LISTENING, lastError = null)
            }

            // Prewarm the active translation backend so the first spoken
            // word doesn't stall waiting on a 30 MB ML Kit model download.
            launch(Dispatchers.IO) {
                val s = app.container.settingsRepository.settingsFlow.first()
                runCatching {
                    app.container.translationRepository.prewarm(
                        s.sourceLanguageCode,
                        s.targetLanguageCode
                    )
                }
            }

            launch(Dispatchers.IO) {
                translateRequests
                    .debounce(TRANSLATE_DEBOUNCE_MS)
                    .collect {
                        while (true) {
                            val final = pendingFinalTranslations.poll() ?: break
                            try {
                                translateSnapshot(final)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                Log.w("CaptionService", "Final translation failed (${error.javaClass.simpleName}).")
                                finishTranslationWithoutResult(final)
                            }
                        }
                        val partial = synchronized(this@CaptionForegroundService) {
                            TranslationRequest(
                                bufferedText,
                                bufferedLineId,
                                bufferedRevision,
                                historyOnNextTranslate
                            )
                                .also { historyOnNextTranslate = false }
                        }
                        if (partial.text.isNotBlank()) {
                            try {
                                translateSnapshot(partial)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                Log.w("CaptionService", "Partial translation failed (${error.javaClass.simpleName}).")
                                finishTranslationWithoutResult(partial)
                            }
                        }
                    }
            }

            val onSpeechResult = { result: SpeechResult ->
                val transcript = result.text.trim()
                val pendingSilenceText = silenceFinalizedText
                val alreadyClosedBySilence = result.isFinal && transcript.isNotBlank() && transcript == pendingSilenceText
                val repeatedClosedPartial = !result.isFinal && transcript.isNotBlank() &&
                    transcript == pendingSilenceText &&
                    app.container.runtimeStore.state.value.transcriptLines.lastOrNull()
                        ?.let { it.isFinal && it.originalText == transcript } == true
                if (result.isFinal || (transcript.isNotBlank() && transcript != pendingSilenceText)) {
                    silenceFinalizedText = null
                }
                Log.d("CaptionService", "onSpeechResult final=${result.isFinal} chars=${transcript.length} embeddingPresent=${result.speakerEmbedding != null} embeddingSize=${result.speakerEmbedding?.size ?: 0} frames=${result.speakerFrames} singing=${result.isSinging} segmentId=${result.segmentId}")
                val candidateId = lineIdCounter.incrementAndGet()

                val updatedRuntime = app.container.runtimeStore.update { runtime ->
                    runtime.copy(
                        originalText = transcript,
                        transcriptLines = if ((alreadyClosedBySilence || repeatedClosedPartial) && runtime.transcriptLines.lastOrNull()?.let { it.isFinal && it.originalText == transcript } == true) {
                            runtime.transcriptLines
                        } else recordTranscriptResult(
                            lines = runtime.transcriptLines,
                            originalText = transcript,
                            isFinal = result.isFinal,
                            newLineId = candidateId,
                            isSungLyrics = result.isFinal && result.isSinging
                        ),
                        status = RecognitionStatus.PROCESSING,
                        lastError = null
                    )
                }

                val actualLine = if (repeatedClosedPartial) null else updatedRuntime.transcriptLines.lastOrNull()
                    ?.takeIf {
                        it.originalText == transcript &&
                            it.isFinal == result.isFinal
                    }

                if (transcript.isNotBlank() && actualLine != null) {
                    if (result.isFinal) {
                        result.segmentId?.let { segmentId ->
                            singingSegmentLineIds[segmentId] = actualLine.id
                            while (singingSegmentLineIds.size > MAX_PENDING_SINGING_LINES) {
                                val oldest = singingSegmentLineIds.keys.minOrNull() ?: break
                                singingSegmentLineIds.remove(oldest)
                            }
                        }
                        val clusterer = speakerClusterer
                        val embedding = result.speakerEmbedding
                        if (clusterer != null && embedding != null && !result.isSinging) {
                            val decision = clusterer.observe(
                                segmentId = actualLine.id,
                                embedding = embedding,
                                frameCount = result.speakerFrames
                            )
                            Log.i(
                                "CaptionService",
                                "speakerCluster outcome=${decision.diagnostic} " +
                                    "ignored=${decision.ignored} frames=${result.speakerFrames} " +
                                    "embeddingSize=${embedding.size} speakers=${clusterer.speakerCount()} " +
                                    "assignments=${decision.speakerAssignments.size} " +
                                    "similarity=${decision.similaritySummary}"
                            )
                            app.container.runtimeStore.update { state ->
                                val assignments = decision.speakerAssignments
                                state.copy(
                                    speakerCount = clusterer.speakerCount(),
                                    transcriptLines = if (assignments.isEmpty()) {
                                        state.transcriptLines
                                    } else {
                                        state.transcriptLines.map { line ->
                                            assignments[line.id]?.let { index ->
                                                line.copy(speakerIndex = index)
                                            } ?: line
                                        }
                                    }
                                )
                            }
                        } else if (clusterer == null) {
                            Log.w("CaptionService", "speakerCluster skipped: clustererUnavailable")
                        } else if (embedding == null) {
                            Log.w("CaptionService", "speakerCluster skipped: embeddingMissing frames=${result.speakerFrames}")
                        } else if (result.isSinging) {
                            Log.d("CaptionService", "speakerCluster skipped: singingFlagged")
                        }
                    }
                    queueTranslation(
                        transcript,
                        actualLine.id,
                        actualLine.revision,
                        result.isFinal
                    )
                }
            }

            val mediaProjection = if (currentAudioSource == AudioSource.SYSTEM) {
                val mpManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val data = MediaProjectionHolder.data
                if (data == null) {
                    Log.e("CaptionService", "MediaProjection data missing.")
                    failAndStop("System-audio capture permission is missing. Start again and allow capture.")
                    return@launch
                }
                mpManager.getMediaProjection(MediaProjectionHolder.resultCode, data)
                    ?: run {
                        failAndStop("System-audio capture could not be initialized. Try again.")
                        return@launch
                    }
            } else {
                null
            }

            val errorSink: (String?) -> Unit = { msg ->
                app.container.runtimeStore.update { it.copy(lastError = msg) }
            }

            // Routing:
            //   LOCAL_VOSK  → streaming Vosk for both mic and system audio (preferred: low-latency, live partials)
            //   REMOTE_WHISPER:
            //       system  → legacy batch SystemAudioEngine (posts WAVs to remote Whisper)
            //       mic     → AndroidSpeechRecognizerManager (Google recognizer; kept as fallback)
            val backend = settings.sttBackend
            val useStreaming = backend == SttBackend.LOCAL_VOSK

            if (backend == SttBackend.ANDROID_LIVE_CAPTION) {
                val engine = LiveCaptionAccessibilityEngine(
                    context = this@CaptionForegroundService,
                    onResult = onSpeechResult
                )
                speechEngine = engine
                observeEngineStatus(engine.status)
                engine.start()
            } else if (useStreaming) {
                val engine = StreamingSttEngine(
                    context = this@CaptionForegroundService,
                    audioSource = currentAudioSource,
                    mediaProjection = mediaProjection,
                    languageCode = sttLanguageCode,
                    localSttClient = app.container.localVoskClient,
                    scope = serviceScope,
                    onResult = onSpeechResult,
                    onError = errorSink,
                    onSingingUpdate = { segmentId ->
                        if (captionGeneration.get() == sessionGeneration &&
                            captionSessionActive.get()
                        ) {
                            singingSegmentLineIds.remove(segmentId)?.let { lineId ->
                                app.container.runtimeStore.update { state ->
                                    state.copy(
                                        transcriptLines = state.transcriptLines.map { line ->
                                            if (line.id == lineId && line.isFinal) {
                                                line.copy(isSungLyrics = true)
                                            } else line
                                        }
                                    )
                                }
                            }
                        }
                    },
                    onSilenceBoundary = { finalizeOpenTranscriptOnSilence() },
                    speakerModelDir = speakerModelDir,
                    yamnetModelFile = yamnetModelFile
                )
                speechEngine = engine
                observeEngineStatus(engine.status)
                engine.start()
            } else {
                when (currentAudioSource) {
                    AudioSource.SYSTEM -> {
                        val engine = SystemAudioEngine(
                            context = this@CaptionForegroundService,
                            projection = mediaProjection!!,
                            sttUrl = settings.sttBaseUrl,
                            languageCode = sttLanguageCode,
                            sourceLanguageCode = sttLanguageCode,
                            sttBackend = settings.sttBackend,
                            localSttClient = app.container.localVoskClient,
                            scope = serviceScope,
                            onResult = onSpeechResult,
                            onSttError = errorSink
                        )
                        speechEngine = engine
                        observeEngineStatus(engine.status)
                        engine.start()
                    }
                    AudioSource.MIC -> {
                        val engine = AndroidSpeechRecognizerManager(
                            this@CaptionForegroundService, onSpeechResult, onError = errorSink
                        )
                        engine.setLanguage(localeForSpeechRec)
                        speechEngine = engine
                        observeEngineStatus(engine.status)
                        engine.start()
                    }
                }
            }

            showOverlay()
            observeOverlayUpdates()
        }
    }

    private fun observeEngineStatus(statusFlow: StateFlow<RecognitionStatus>) {
        serviceScope.launch {
            statusFlow.collectLatest { status ->
                app.container.runtimeStore.update { it.copy(status = status) }
            }
        }
    }

    /**
     * Drive the overlay from a single combined stream so rapid runtime + settings changes
     * cannot apply out-of-order (stale coroutines overwriting newer translation text).
     */
    private fun observeOverlayUpdates() {
        serviceScope.launch {
            combine(
                app.container.runtimeStore.state,
                app.container.settingsRepository.settingsFlow,
                app.container.premiumRepository.state,
                overlayReady
            ) { runtime, settings, premium, _ -> Triple(runtime, settings, premium.hasPro) }
                .collectLatest { (runtime, settings, hasPro) ->
                    val overlay = overlayController ?: return@collectLatest
                    val ui = buildOverlayUi(runtime, settings, hasPro)
                    Log.d(
                        "CaptionService",
                        "overlay update lines=${runtime.transcriptLines.size} " +
                            "orig='${runtime.originalText.take(40)}' " +
                            "trans='${runtime.translatedText.take(40)}' " +
                            "body='${ui.transcriptText.take(60)}'"
                    )
                    overlay.update(ui)
                }
        }
    }

    private fun buildOverlayUi(
        runtime: CaptionRuntimeState,
        settings: CaptionSettings,
        hasPro: Boolean
    ) = OverlayUiState(
        originalText = runtime.originalText,
        translatedText = runtime.translatedText.ifBlank { runtime.originalText },
        transcriptText = overlayTranscriptText(runtime),
        status = runtime.status,
        statusDetail = runtime.lastError,
        textSizeSp = settings.textSizeSp,
        opacity = settings.overlayOpacity,
        showOriginal = settings.showOriginal,
        minimized = settings.overlayMinimized,
        // Defensive fallback: a lapsed subscription must never leave a stale
        // Pro theme/font rendering — always re-check entitlement here, not
        // just at the point the setting was saved.
        themeId = if (hasPro) settings.overlayThemeId else OverlayThemeCatalog.FREE_THEME_ID,
        fontId = if (hasPro) settings.overlayFontId else OverlayFontCatalog.FREE_FONT_ID
    )

    /** Close a visible partial line after 2s silence without stopping the streaming recognizer. */
    private fun finalizeOpenTranscriptOnSilence() {
        var newlyClosed: CaptionRuntimeLine? = null
        app.container.runtimeStore.update { state ->
            val last = state.transcriptLines.lastOrNull()
            if (last == null || last.isFinal || last.originalText.isBlank()) state
            else {
                val closed = last.copy(isFinal = true, revision = last.revision + 1)
                newlyClosed = closed
                silenceFinalizedText = last.originalText
                state.copy(transcriptLines = state.transcriptLines.dropLast(1) + closed)
            }
        }
        newlyClosed?.let { closedLine ->
            queueTranslation(closedLine.originalText, closedLine.id, closedLine.revision, isFinal = true)
            Log.i("CaptionService", "transcriptLine closedBySilence id=" + closedLine.id + " pauseMs=2000")
        }
    }
    private fun queueTranslation(
        text: String,
        lineId: Long,
        revision: Long,
        isFinal: Boolean = false
    ) {
        synchronized(this) {
            bufferedText = text
            bufferedLineId = lineId
            bufferedRevision = revision

            if (isFinal) {
                pendingFinalTranslations.add(
                    TranslationRequest(text, lineId, revision, true)
                )
                // Finals are retained in the queue; do not translate the same
                // immutable snapshot again through the mutable partial slot.
                bufferedText = ""
                bufferedLineId = NO_LINE_ID
                bufferedRevision = 0L
                historyOnNextTranslate = false
            }
        }
        translateRequests.tryEmit(Unit)
    }

    private suspend fun translateSnapshot(request: TranslationRequest) {
        val captionSettings = app.container.settingsRepository.settingsFlow.first()
        val translated = app.container.translationRepository.translate(
            text = request.text,
            sourceCode = captionSettings.sourceLanguageCode,
            targetCode = captionSettings.targetLanguageCode,
            autoDetect = currentAudioSource == AudioSource.SYSTEM || captionSettings.autoDetectSource
        )
        if (translated.isBlank()) {
            Log.w("CaptionService", "Translation returned blank for lineId=${request.lineId}.")
            finishTranslationWithoutResult(request)
            return
        }

        val translatedWithGlossary = applyGlossary(translated, app.container.glossary.list())

        val updatedRuntime = app.container.runtimeStore.update { state ->
            val updatedLines = updateTranscriptTranslation(
                lines = state.transcriptLines,
                lineId = request.lineId,
                translatedText = translatedWithGlossary,
                expectedOriginalText = request.text,
                expectedRevision = request.revision
            )
            val accepted = updatedLines.any { line ->
                line.id == request.lineId &&
                    line.originalText == request.text &&
                    line.revision == request.revision &&
                    line.translatedText == translatedWithGlossary
            }

            state.copy(
                translatedText = if (accepted) translatedWithGlossary else state.translatedText,
                transcriptLines = updatedLines,
                status = if (paused) RecognitionStatus.PAUSED else RecognitionStatus.LISTENING
            )
        }

        val accepted = updatedRuntime.transcriptLines.any { line ->
            line.id == request.lineId &&
                line.originalText == request.text &&
                line.revision == request.revision &&
                line.translatedText == translatedWithGlossary
        }

        if (accepted && request.saveHistory && captionSettings.saveHistory) {
            app.container.transcriptHistory.add(
                TranscriptEntry(
                    originalText = request.text,
                    translatedText = translatedWithGlossary,
                    sourceLanguage = if (currentAudioSource == AudioSource.SYSTEM) "auto" else captionSettings.sourceLanguageCode,
                    targetLanguage = captionSettings.targetLanguageCode
                )
            )
        }
    }

    /**
     * Leave PROCESSING only when this request still belongs to the newest visible
     * transcript line. Older partial results must not overwrite a newer request's status.
     */
    private suspend fun finishTranslationWithoutResult(request: TranslationRequest) {
        app.container.runtimeStore.update { state ->
            val isCurrentRequest = state.originalText == request.text &&
                state.transcriptLines.lastOrNull()?.let { line ->
                    line.id == request.lineId && line.revision == request.revision
                } == true
            if (isCurrentRequest && state.status == RecognitionStatus.PROCESSING) {
                state.copy(status = if (paused) RecognitionStatus.PAUSED else RecognitionStatus.LISTENING)
            } else {
                state
            }
        }
    }

    private data class TranslationRequest(
        val text: String,
        val lineId: Long,
        val revision: Long,
        val saveHistory: Boolean
    )

    private fun showOverlay() {
        if (overlayController != null) return
        serviceScope.launch {
            val settings = app.container.settingsRepository.settingsFlow.first()
            overlayController = OverlayController(
                context = this@CaptionForegroundService,
                scope = serviceScope,
                settingsRepository = app.container.settingsRepository,
                onPauseResume = { togglePause() },
                onClose = { stopFlow() },
                onToggleMinimize = { toggleMinimized() },
                uiStrings = { app.container.uiLocalization.latestStrings() }
            ).apply {
                show(settings.overlayX, settings.overlayY, settings.overlayWidthDp, settings.overlayHeightDp)
            }
            val runtime = app.container.runtimeStore.state.value
            val hasPro = app.container.premiumRepository.state.first().hasPro
            overlayController?.update(buildOverlayUi(runtime, settings, hasPro))
            overlayReady.value = overlayReady.value + 1
        }
    }

    private fun togglePause() {
        paused = !paused
        if (paused) speechEngine?.pause() else speechEngine?.resume()
        app.container.runtimeStore.update {
            it.copy(
                paused = paused,
                status = if (paused) RecognitionStatus.PAUSED else RecognitionStatus.LISTENING
            )
        }
    }

    private fun toggleMinimized() {
        serviceScope.launch {
            app.container.settingsRepository.update { it.copy(overlayMinimized = !it.overlayMinimized) }
        }
    }

    private fun stopFlow() {
        captionSessionActive.set(false)
        captionGeneration.incrementAndGet()
        singingSegmentLineIds.clear()
        speakerClusterer?.reset()
        speakerClusterer = null
        try { speechEngine?.stop() } catch (_: Throwable) {}
        speechEngine = null
        overlayController?.hide()
        overlayController = null
        overlayReady.value = 0
        MediaProjectionHolder.clear()
        val error = failureMessage
        failureMessage = null
        app.container.runtimeStore.update {
            if (error == null) CaptionRuntimeState()
            else CaptionRuntimeState(status = RecognitionStatus.ERROR, lastError = error)
        }
        serviceScope.cancel()
        serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun failAndStop(message: String) {
        captionSessionActive.set(false)
        captionGeneration.incrementAndGet()
        singingSegmentLineIds.clear()
        failureMessage = message
        try { speechEngine?.stop() } catch (_: Throwable) {}
        speechEngine = null
        overlayController?.hide()
        overlayController = null
        MediaProjectionHolder.clear()
        app.container.runtimeStore.update {
            it.copy(running = false, paused = false, status = RecognitionStatus.ERROR, lastError = message)
        }
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { stopFlow() } catch (_: Throwable) {}
    }

    private fun buildNotification(): Notification {
        createNotificationChannel()
        val openIntent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val pauseIntent = PendingIntent.getService(
            this, 2,
            Intent(this, CaptionForegroundService::class.java).apply { action = ACTION_PAUSE_RESUME },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this, 3,
            Intent(this, CaptionForegroundService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val uiStrings = app.container.uiLocalization.latestStrings()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(uiStrings["Captioning in progress"])
            .setContentText(uiStrings["Listening and translating speech."])
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pending)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, uiStrings["Pause/Resume"], pauseIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, uiStrings["Stop"], stopIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_desc)
        }
        nm.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_START = "caption.action.START"
        const val ACTION_STOP = "caption.action.STOP"
        const val ACTION_PAUSE_RESUME = "caption.action.PAUSE_RESUME"
        const val ACTION_TOGGLE_MINIMIZE = "caption.action.TOGGLE_MINIMIZE"
        const val EXTRA_AUDIO_SOURCE = "caption.extra.AUDIO_SOURCE"
        const val EXTRA_LIVE_CAPTION = "caption.extra.LIVE_CAPTION"

        private const val CHANNEL_ID = "caption_channel"
        private const val NOTIF_ID = 2001
        private const val TRANSLATE_DEBOUNCE_MS = 450L
        private const val NO_LINE_ID = -1L
        private const val MAX_PENDING_SINGING_LINES = 64
    }
}


/**
 * Receives Android Live Caption's accessibility text snapshots. The Android
 * recognizer remains system-owned and continuous; this engine only forwards
 * text and finalizes a visible line after 1s without a caption update.
 */
private class LiveCaptionAccessibilityEngine(
    private val context: Context,
    private val onResult: (SpeechResult) -> Unit
) : SpeechEngine {
    private val statusMutable = MutableStateFlow(RecognitionStatus.IDLE)
    val status: StateFlow<RecognitionStatus> = statusMutable
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var paused = false
    private var currentText = ""
    private var finalizedBaseline = ""
    private var receiverRegistered = false

    private val finalizeLine = Runnable {
        val snapshot = currentText
        if (running && !paused && snapshot.isNotBlank()) {
            onResult(SpeechResult(snapshot, isFinal = true))
            finalizedBaseline = (finalizedBaseline + " " + snapshot).trim().takeLast(1800)
            currentText = ""
            statusMutable.value = RecognitionStatus.LISTENING
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!running || paused || intent?.action !=
                com.charles.livecaptionn.accessibility.GoogleLiveTranscribeAccessibilityService.ACTION_LIVE_CAPTION_TEXT
            ) return
            val snapshot = intent.getStringExtra(
                com.charles.livecaptionn.accessibility.GoogleLiveTranscribeAccessibilityService.EXTRA_TEXT
            )?.trim().orEmpty()
            if (snapshot.isBlank()) return

            // Android may report a growing whole-caption snapshot. Remove the
            // already-finalized prefix to prevent replaying previous lines.
            val text = when {
                finalizedBaseline.isNotBlank() && snapshot.startsWith(finalizedBaseline) ->
                    snapshot.removePrefix(finalizedBaseline).trim()
                finalizedBaseline.isNotBlank() && snapshot.contains(finalizedBaseline) ->
                    snapshot.substring(snapshot.indexOf(finalizedBaseline) + finalizedBaseline.length).trim()
                else -> snapshot
            }
            if (text.isBlank() || text == currentText) return
            currentText = text
            statusMutable.value = RecognitionStatus.PROCESSING
            onResult(SpeechResult(text, isFinal = false))
            handler.removeCallbacks(finalizeLine)
            handler.postDelayed(finalizeLine, 1_000L)
        }
    }

    override fun start() {
        if (running) return
        running = true
        paused = false
        currentText = ""
        finalizedBaseline = ""
        val filter = IntentFilter(
            com.charles.livecaptionn.accessibility.GoogleLiveTranscribeAccessibilityService.ACTION_LIVE_CAPTION_TEXT
        )
        ContextCompat.registerReceiver(
            context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )
        receiverRegistered = true
        statusMutable.value = RecognitionStatus.LISTENING
    }

    override fun stop() {
        running = false
        paused = false
        handler.removeCallbacks(finalizeLine)
        if (receiverRegistered) {
            runCatching { context.unregisterReceiver(receiver) }
            receiverRegistered = false
        }
        currentText = ""
        finalizedBaseline = ""
        statusMutable.value = RecognitionStatus.IDLE
    }

    override fun pause() {
        paused = true
        handler.removeCallbacks(finalizeLine)
        statusMutable.value = RecognitionStatus.PAUSED
    }

    override fun resume() {
        if (!running) return
        paused = false
        if (currentText.isNotBlank()) {
            onResult(SpeechResult(currentText, isFinal = false))
            handler.postDelayed(finalizeLine, 1_000L)
        }
        statusMutable.value = RecognitionStatus.LISTENING
    }
}
