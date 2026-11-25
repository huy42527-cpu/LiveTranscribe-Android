package com.charles.livecaptionn.speech

import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * YAMNet single-window inference. Input is 0.975 s mono PCM at 16 kHz.
 * The model's outputs are independent AudioSet scores, not exclusive classes.
 */
internal class YamnetSingingClassifier(modelFile: File) : AutoCloseable {
    private val interpreter = Interpreter(
        modelFile,
        Interpreter.Options().setNumThreads(1)
    )
    private val input = ByteBuffer.allocateDirect(WINDOW_SAMPLES * 4)
        .order(ByteOrder.nativeOrder())
    private val output = Array(1) { FloatArray(CLASS_COUNT) }

    init {
        require(interpreter.getInputTensor(0).shape().contentEquals(intArrayOf(WINDOW_SAMPLES))) {
            "Unexpected YAMNet input tensor shape"
        }
        require(interpreter.getOutputTensor(0).shape().contentEquals(intArrayOf(1, CLASS_COUNT))) {
            "Unexpected YAMNet output tensor shape"
        }
    }

    @Synchronized
    fun classify(samples: FloatArray): Scores {
        require(samples.size == WINDOW_SAMPLES) {
            "YAMNet requires exactly $WINDOW_SAMPLES samples"
        }
        input.clear()
        samples.forEach { sample ->
            input.putFloat(sample.coerceIn(-1f, 1f))
        }
        input.rewind()
        interpreter.run(input, output)
        var sumSquares = 0.0
        var peak = 0f
        samples.forEach { sample ->
            val magnitude = kotlin.math.abs(sample)
            if (magnitude > peak) peak = magnitude
            sumSquares += sample.toDouble() * sample.toDouble()
        }
        val topClasses = output[0].indices
            .sortedByDescending { output[0][it] }
            .take(TOP_CLASS_COUNT)
            .map { index -> ClassScore(index, output[0][index].coerceIn(0f, 1f)) }
        return Scores(
            speech = output[0][SPEECH_INDEX].coerceIn(0f, 1f),
            singing = output[0][SINGING_INDEX].coerceIn(0f, 1f),
            rms = sqrt(sumSquares / samples.size).toFloat(),
            peak = peak,
            topClasses = topClasses
        )
    }

    override fun close() {
        interpreter.close()
    }

    data class ClassScore(val index: Int, val score: Float)

    data class Scores(
        val speech: Float,
        val singing: Float,
        val rms: Float = 0f,
        val peak: Float = 0f,
        val topClasses: List<ClassScore> = emptyList()
    )

    companion object {
        const val WINDOW_SAMPLES = 15_600
        private const val CLASS_COUNT = 521
        private const val TOP_CLASS_COUNT = 8
        // Verified against the model's companion label list.
        private const val SPEECH_INDEX = 0
        private const val SINGING_INDEX = 24
    }
}

/**
 * One classifier input window with its position on the captured PCM timeline.
 * End positions are exclusive sample indices.
 */
internal data class YamnetWindow(
    val samples: FloatArray,
    val startSample: Long,
    val endSample: Long
)

/** True only after YAMNet has processed at least through a segment's end sample. */
internal object SingingEvidenceReadiness {
    fun isComplete(processedThroughSample: Long, segmentEndSample: Long): Boolean =
        processedThroughSample >= segmentEndSample
}

/** Timestamped YAMNet output retained briefly for Vosk segment association. */
internal data class TimedSingingEvidence(
    val startSample: Long,
    val endSample: Long,
    val scores: YamnetSingingClassifier.Scores
)

/**
 * Conservative segment-level singing decision. Only windows fully contained
 * in the Vosk segment are eligible, preventing adjacent-segment evidence leak.
 * At least two individually singing-dominant windows are required.
 */
internal object SegmentSingingEvidence {
    private const val SINGING_THRESHOLD = 0.55f
    private const val SPEECH_DOMINANCE_MARGIN = 0.10f
    private const val REQUIRED_WINDOWS = 2
    // YAMNet scores close to zero for both classes are lack of evidence,
    // not proof of speech. Require a meaningful Speech score before vetoing.
    private const val MIN_SPEECH_EVIDENCE = 0.25f

    fun hasConfidentSinging(
        segmentStartSample: Long,
        segmentEndSample: Long,
        windows: List<TimedSingingEvidence>
    ): Boolean {
        if (segmentEndSample <= segmentStartSample) return false

        val contained = windows.filter {
            it.startSample >= segmentStartSample && it.endSample <= segmentEndSample
        }
        // Speech is only a veto when YAMNet has meaningful Speech evidence.
        // Scores near zero for both classes mean "uncertain", not "streamer spoke".
        // This remains conservative: a clear Speech-dominant/ambiguous window
        // still prevents CS for the whole Vosk segment.
        val containsSpeechDominantOrAmbiguousWindow = contained.any {
            it.scores.speech >= MIN_SPEECH_EVIDENCE &&
                it.scores.speech >= it.scores.singing - SPEECH_DOMINANCE_MARGIN
        }
        if (containsSpeechDominantOrAmbiguousWindow) return false

        val confident = contained.count {
            it.scores.singing >= SINGING_THRESHOLD &&
                it.scores.singing > it.scores.speech + SPEECH_DOMINANCE_MARGIN
        }
        return confident >= REQUIRED_WINDOWS
    }
}

/**
 * Hysteresis over overlapping windows. Two positive windows among the latest
 * three are required; speech must score clearly below singing. Similar
 * speech/singing scores are treated as mixed/ambiguous and do not qualify.
 */
internal class SingingEvidenceTracker(
    private val singingThreshold: Float = 0.55f,
    private val speechDominanceMargin: Float = 0.10f,
    private val windowCount: Int = 3,
    private val positiveWindowsRequired: Int = 2
) {
    data class Evidence(
        val singingScore: Float,
        val speechScore: Float,
        val singingPresent: Boolean,
        val speechDominant: Boolean,
        val confidentSinger: Boolean
    )

    private val recent = ArrayDeque<YamnetSingingClassifier.Scores>()

    init {
        require(singingThreshold in 0f..1f)
        require(speechDominanceMargin in 0f..1f)
        require(windowCount > 0)
        require(positiveWindowsRequired in 1..windowCount)
    }

    @Synchronized
    fun add(scores: YamnetSingingClassifier.Scores): Evidence {
        recent.addLast(scores)
        while (recent.size > windowCount) recent.removeFirst()
        val singing = recent.map { it.singing }.average().toFloat()
        val speech = recent.map { it.speech }.average().toFloat()
        val positives = recent.count { it.singing >= singingThreshold }
        val present = positives >= positiveWindowsRequired
        val speechDominant = speech >= singing - speechDominanceMargin
        return Evidence(
            singingScore = singing,
            speechScore = speech,
            singingPresent = present,
            speechDominant = speechDominant,
            confidentSinger = present && singing > speech + speechDominanceMargin
        )
    }

    @Synchronized
    fun reset() = recent.clear()
}
