package com.yadhuChoudhary.MyRuns5

import kotlin.math.sqrt

object FeatureExtractor {
    const val BLOCK_CAPACITY = 16
    const val FEATURE_SIZE = BLOCK_CAPACITY + 1

    fun extractFeatures(accBlock: DoubleArray): DoubleArray {
        require(accBlock.size == BLOCK_CAPACITY) {
            "Block size must be $BLOCK_CAPACITY, got ${accBlock.size}"
        }

        val features = DoubleArray(FEATURE_SIZE)

        var max = 0.0
        for (value in accBlock) {
            if (max < value) {
                max = value
            }
        }

        val re = accBlock.copyOf()
        val im = DoubleArray(BLOCK_CAPACITY) { 0.0 }

        val fft = FFT(BLOCK_CAPACITY)
        fft.fft(re, im)

        for (i in 0 until BLOCK_CAPACITY) {
            val mag = sqrt(re[i] * re[i] + im[i] * im[i])
            features[i] = mag
            im[i] = 0.0
        }

        features[BLOCK_CAPACITY] = max

        return features
    }

    fun calculateMagnitude(x: Float, y: Float, z: Float): Double {
        return sqrt((x * x + y * y + z * z).toDouble())
    }
}