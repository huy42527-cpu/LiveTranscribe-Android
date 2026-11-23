package com.charles.livecaptionn.speech

import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

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
        return Scores(
            speech = output[0][SPEECH_INDEX].coerceIn(0f, 1f),
            singing = output[0][SINGING_INDEX].coerceIn(0f, 1f)
        )
    }

    override fun close() {
        interpreter.close()
    }

    data class Scores(val speech: Float, val singing: Float)

    companion object {
        const val WINDOW_SAMPLES = 15_600
        private const val CLASS_COUNT = 521
        // Verified against the model's companion label list.
        private const val SPEECH_INDEX = 0
        private const val SINGING_INDEX = 24
    }
}

/**
 * Hysteresis over overlapping windows. Two positive windows among the latest
 * three are required; speech-dominant windows remain ambiguous and do not
 * qualify for a singer label.
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
        val speechDominant = speech >= singing + speechDominanceMargin
        return Evidence(
            singingScore = singing,
            speechScore = speech,
            singingPresent = present,
            speechDominant = speechDominant,
            confidentSinger = present && !speechDominant
        )
    }

    @Synchronized
    fun reset() = recent.clear()
}
