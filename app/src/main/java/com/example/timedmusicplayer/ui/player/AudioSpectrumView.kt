package com.example.timedmusicplayer.ui.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import kotlin.math.hypot
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

class AudioSpectrumView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val levels = FloatArray(POINT_COUNT) { IDLE_LEVEL }
    private val rawLevels = FloatArray(POINT_COUNT)
    private val spatiallySmoothedLevels = FloatArray(POINT_COUNT)
    private var active = false

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        if (height <= 0) return
        barPaint.shader = LinearGradient(
            0f,
            height.toFloat(),
            0f,
            0f,
            intArrayOf(
                resolveThemeColor(com.google.android.material.R.attr.colorPrimaryVariant),
                resolveThemeColor(com.google.android.material.R.attr.colorPrimary),
                resolveThemeColor(com.google.android.material.R.attr.colorSecondary)
            ),
            floatArrayOf(0f, 0.58f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    private fun resolveThemeColor(attribute: Int): Int {
        val value = TypedValue()
        check(context.theme.resolveAttribute(attribute, value, true)) {
            "Required theme color attribute $attribute is missing"
        }
        return value.data
    }

    fun updateFft(fft: ByteArray, samplingRateMilliHertz: Int) {
        if (!active || fft.size < 4) return

        // Android Visualizer reports the sample rate in milliHertz.
        val sampleRateHz = samplingRateMilliHertz / 1000f
        if (sampleRateHz <= 0f) return
        val binCount = fft.size / 2
        val frequencyResolution = sampleRateHz / fft.size
        val endFrequency = min(END_FREQUENCY_HZ, sampleRateHz / 2f)
        if (endFrequency <= START_FREQUENCY_HZ) return
        val bandRatio = (endFrequency / START_FREQUENCY_HZ)
            .toDouble()
            .pow(1.0 / POINT_COUNT)
            .toFloat()

        // Divide the FFT into logarithmic bands, matching how pitch is perceived.
        var lowerFrequency = START_FREQUENCY_HZ
        for (bandIndex in levels.indices) {
            val upperFrequency = if (bandIndex == levels.lastIndex) {
                endFrequency
            } else {
                lowerFrequency * bandRatio
            }
            val startBin = floor(lowerFrequency / frequencyResolution).toInt()
                .coerceIn(1, binCount - 1)
            val endBin = ceil(upperFrequency / frequencyResolution).toInt()
                .coerceIn(startBin, binCount - 1)
            var peak = 0f
            for (bin in startBin..endBin) {
                val real = fft[bin * 2].toInt().toFloat()
                val imaginary = fft[bin * 2 + 1].toInt().toFloat()
                peak = max(peak, hypot(real, imaginary))
            }
            val centerFrequency = sqrt(lowerFrequency * upperFrequency)
            val perceivedPeak = peak * aWeight(centerFrequency)
            val normalized = (ln(1f + perceivedPeak) / MAX_LOG_MAGNITUDE).coerceIn(0f, 1f)
            rawLevels[bandIndex] = (normalized * INPUT_GAIN).coerceAtMost(1f)
            lowerFrequency = upperFrequency
        }

        // Seven-point weighted averaging removes sharp teeth between adjacent bands.
        for (index in rawLevels.indices) {
            var weightedSum = 0f
            for (offset in SPATIAL_WEIGHTS.indices) {
                val sourceIndex = (index + offset - SPATIAL_WEIGHT_RADIUS)
                    .coerceIn(0, rawLevels.lastIndex)
                weightedSum += rawLevels[sourceIndex] * SPATIAL_WEIGHTS[offset]
            }
            spatiallySmoothedLevels[index] = weightedSum / SPATIAL_WEIGHT_TOTAL
        }

        // Retain part of the previous frame so the spectrum flows instead of flickering.
        for (index in levels.indices) {
            val target = IDLE_LEVEL + spatiallySmoothedLevels[index] * (1f - IDLE_LEVEL)
            levels[index] = levels[index] * FRAME_SMOOTHING + target * (1f - FRAME_SMOOTHING)
        }
        postInvalidateOnAnimation()
    }

    fun setActive(isActive: Boolean) {
        if (active == isActive) return
        active = isActive
        if (!active) {
            levels.fill(IDLE_LEVEL)
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        val availableWidth = (width - paddingLeft - paddingRight).toFloat()
        val availableHeight = (height - paddingTop - paddingBottom).toFloat()
        if (availableWidth <= 0f || availableHeight <= 0f) return
        val gap = resources.displayMetrics.density * BAR_GAP_DP
        val barWidth = (availableWidth - gap * (levels.size - 1)) / levels.size
        if (barWidth <= 0f) return
        val bottom = height - paddingBottom.toFloat()
        val minimumHeight = resources.displayMetrics.density * MIN_BAR_HEIGHT_DP
        val cornerRadius = min(barWidth / 2f, resources.displayMetrics.density * BAR_RADIUS_DP)

        for (index in levels.indices) {
            val barHeight = max(minimumHeight, availableHeight * levels[index])
            val left = paddingLeft + index * (barWidth + gap)
            canvas.drawRoundRect(
                left,
                bottom - barHeight,
                left + barWidth,
                bottom,
                cornerRadius,
                cornerRadius,
                barPaint
            )
        }
    }

    private fun aWeight(frequencyHz: Float): Float {
        val frequencySquared = frequencyHz.toDouble().pow(2.0)
        val numerator = REFERENCE_FREQUENCY_SQUARED * frequencySquared.pow(2.0)
        val denominator =
            (frequencySquared + LOW_FREQUENCY_SQUARED) *
                sqrt(
                    (frequencySquared + MID_LOW_FREQUENCY_SQUARED) *
                        (frequencySquared + MID_HIGH_FREQUENCY_SQUARED)
                ) *
                (frequencySquared + REFERENCE_FREQUENCY_SQUARED)
        if (denominator == 0.0) return 0f
        val relativeAmplitude = numerator / denominator
        val decibels = 20.0 * kotlin.math.log10(relativeAmplitude) + A_WEIGHT_NORMALIZATION_DB
        return 10.0.pow(decibels / 20.0).toFloat().coerceIn(0f, MAX_A_WEIGHT)
    }

    private companion object {
        const val POINT_COUNT = 96
        const val BAR_GAP_DP = 1f
        const val MIN_BAR_HEIGHT_DP = 2f
        const val BAR_RADIUS_DP = 1.5f
        const val INPUT_GAIN = 1.8f
        const val IDLE_LEVEL = 0.025f
        const val FRAME_SMOOTHING = 0.68f
        const val START_FREQUENCY_HZ = 40f
        const val END_FREQUENCY_HZ = 18_000f
        const val SPATIAL_WEIGHT_RADIUS = 3
        val SPATIAL_WEIGHTS = floatArrayOf(1f, 2f, 3f, 5f, 3f, 2f, 1f)
        val SPATIAL_WEIGHT_TOTAL = SPATIAL_WEIGHTS.sum()
        val MAX_LOG_MAGNITUDE = ln(182f)
        const val A_WEIGHT_NORMALIZATION_DB = 2.0
        const val MAX_A_WEIGHT = 1.25f
        val LOW_FREQUENCY_SQUARED = 20.598997.pow(2.0)
        val MID_LOW_FREQUENCY_SQUARED = 107.65265.pow(2.0)
        val MID_HIGH_FREQUENCY_SQUARED = 737.86223.pow(2.0)
        val REFERENCE_FREQUENCY_SQUARED = 12194.217.pow(2.0)
    }
}
