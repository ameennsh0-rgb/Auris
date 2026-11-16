package com.music.bitchord.playback.audio

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow

/**
 * The listener's own digital output level, process-wide.
 *
 * A USB DAC that exposes no volume the phone can use — an IEM cable with a DSP
 * chip in it, say — plays at whatever level the file carries, and the system
 * volume does nothing audible. This is the level the app itself applies in that
 * case, as the very last stage of every [DspChain].
 *
 * It only ever attenuates (0 dB down to [MIN_DB]): boosting could push a track
 * past full scale after the loudness limiter has already had its say.
 *
 * At 0 dB the stage is parked and returns before it reads a sample, so a chain
 * with nothing else active stays bit-exact. Anywhere below 0 dB it is a real
 * gain multiply, and the Audio Pipeline readout says so through
 * [PrecisionAudioSink]'s exactness report.
 */
object OutputGain {
    const val MIN_DB = -60f

    /** Linear factor the audio threads glide towards. 1 is unity. */
    @Volatile
    var linear: Float = 1f
        private set

    @Volatile
    var db: Float = 0f
        private set

    /** Whether the stage is currently altering samples. */
    val engaged: Boolean
        get() = linear < UNITY_THRESHOLD

    /** Sets the level in dB, clamped to [MIN_DB]..0. Safe from any thread. */
    fun setDb(value: Float) {
        val clamped = value.coerceIn(MIN_DB, 0f)
        db = clamped
        linear = if (clamped >= -0.05f) 1f else 10.0.pow(clamped / 20.0).toFloat()
    }

    private const val UNITY_THRESHOLD = 0.9999f
}

/**
 * Applies [OutputGain] to a block in place, gliding between levels so a slider
 * drag does not click.
 */
class OutputGainProcessor : FloatAudioProcessor {

    /**
     * Told, from the audio thread, whenever [OutputGain] starts or stops
     * altering samples — which is what the exactness readout hangs off.
     */
    @Volatile
    var onEngagedChanged: (() -> Unit)? = null

    private var sampleRate = 0
    private var channelCount = 0
    private var currentGain = 1f
    private var glideCoef = 1f
    private var snap = true
    private var wasEngaged = false

    override fun configure(sampleRate: Int, channelCount: Int) {
        this.sampleRate = sampleRate
        this.channelCount = channelCount
        glideCoef = if (sampleRate <= 0) 1f else (1.0 - exp(-1.0 / (GLIDE_SECONDS * sampleRate))).toFloat()
        snap = true
    }

    override fun process(block: AudioBlock) {
        val frames = block.frameCount
        val channels = channelCount
        if (frames == 0 || channels < 1 || sampleRate <= 0) return

        val target = OutputGain.linear
        if (snap) {
            currentGain = target
            snap = false
        }

        val engaged = target < 1f || abs(currentGain - 1f) >= SETTLED
        if (engaged != wasEngaged) {
            wasEngaged = engaged
            onEngagedChanged?.invoke()
        }
        // Parked at unity: leave every sample alone.
        if (!engaged) {
            currentGain = 1f
            return
        }

        val samples = block.samples
        var gain = currentGain
        var index = 0
        for (frame in 0 until frames) {
            gain += (target - gain) * glideCoef
            for (channel in 0 until channels) {
                samples[index + channel] *= gain
            }
            index += channels
        }
        currentGain = gain
    }

    override fun flush() {
        snap = true
    }

    override fun reset() {
        sampleRate = 0
        channelCount = 0
        snap = true
    }

    private companion object {
        /** Long enough that a slider drag does not zipper, short enough to feel immediate. */
        const val GLIDE_SECONDS = 0.03
        const val SETTLED = 1e-4f
    }
}
