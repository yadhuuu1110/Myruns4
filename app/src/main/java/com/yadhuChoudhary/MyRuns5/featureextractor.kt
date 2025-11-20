package com.yadhuChoudhary.MyRuns5

import kotlin.math.sqrt

/**
 * Feature extractor for activity recognition - OPTIMIZED FOR INSTANT RESPONSE
 * Converts accelerometer data blocks into feature vectors for classification
 *
 * REDUCED from original implementation for ultra-low latency:
 * 1. Collect only 16 consecutive magnitude readings (was 64)
 * 2. Find the maximum magnitude in the block
 * 3. Perform FFT on the block
 * 4. Compute magnitudes of FFT coefficients
 * 5. Create feature vector: [16 FFT magnitudes, 1 max] = 17 features
 *
 * This provides ~0.1-0.3 second detection latency instead of 3+ seconds
 */
object FeatureExtractor {
    const val BLOCK_CAPACITY = 16  // Reduced from 64 for instant response
    const val FEATURE_SIZE = BLOCK_CAPACITY + 1 // FFT coefficients (16) + max (1)

    /**
     * Extracts features from a block of accelerometer magnitude readings
     * OPTIMIZED for instant response (reduced from 64 to 16 samples)
     *
     * @param accBlock Array of 16 accelerometer magnitude values
     * @return DoubleArray of 17 features (16 FFT magnitudes + 1 max value)
     *
     * Feature vector structure:
     *   features[0..15]  = Magnitudes of FFT coefficients (frequency domain)
     *   features[16]     = Maximum magnitude in time domain
     */
    fun extractFeatures(accBlock: DoubleArray): DoubleArray {
        require(accBlock.size == BLOCK_CAPACITY) {
            "Block size must be $BLOCK_CAPACITY, got ${accBlock.size}"
        }

        val features = DoubleArray(FEATURE_SIZE)

        // Find maximum value in the block
        // This represents the peak acceleration magnitude
        var max = 0.0
        for (value in accBlock) {
            if (max < value) {
                max = value
            }
        }

        // Prepare for FFT - copy to avoid modifying original
        val re = accBlock.copyOf()
        val im = DoubleArray(BLOCK_CAPACITY) { 0.0 }

        // Perform FFT to transform time domain → frequency domain
        val fft = FFT(BLOCK_CAPACITY)
        fft.fft(re, im)

        // Compute magnitudes of FFT coefficients
        // Each coefficient represents the strength of a particular frequency
        for (i in 0 until BLOCK_CAPACITY) {
            val mag = sqrt(re[i] * re[i] + im[i] * im[i])
            features[i] = mag
            // Clear imaginary part (as done in data collector)
            im[i] = 0.0
        }

        // Append max as the last feature (index 64)
        // This provides time-domain information alongside frequency-domain features
        features[BLOCK_CAPACITY] = max

        return features
    }

    /**
     * Calculates the magnitude of 3D accelerometer reading
     * m = sqrt(x^2 + y^2 + z^2)
     *
     * This is the Euclidean norm of the acceleration vector.
     * It represents the total acceleration magnitude regardless of direction.
     *
     * @param x Acceleration in X-axis (m/s²)
     * @param y Acceleration in Y-axis (m/s²)
     * @param z Acceleration in Z-axis (m/s²)
     * @return Magnitude of acceleration vector
     */
    fun calculateMagnitude(x: Float, y: Float, z: Float): Double {
        return sqrt((x * x + y * y + z * z).toDouble())
    }
}