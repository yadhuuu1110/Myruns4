package com.yadhuChoudhary.MyRuns5

import kotlin.math.*

/**
 * Fast Fourier Transform - Exact implementation from MyRuns Data Collector
 * Based on MEAPsoft FFT by Mike Mandel (mim@ee.columbia.edu)
 *
 * Copyright 2006-2007 Columbia University
 *
 * Utility class to perform a fast fourier transform without allocating any extra memory.
 * This is a direct Kotlin port of the Java FFT used in the data collector.
 *
 * Usage:
 *   val fft = FFT(64)  // n must be power of 2
 *   val re = DoubleArray(64) { ... }  // Real part (input data)
 *   val im = DoubleArray(64) { 0.0 }  // Imaginary part (all zeros initially)
 *   fft.fft(re, im)  // Transforms in-place
 *   // After: re and im contain FFT coefficients
 */
class FFT(private val n: Int) {
    private val m: Int = (ln(n.toDouble()) / ln(2.0)).toInt()

    // Lookup tables - only need to recompute when size of FFT changes
    private val cos: DoubleArray
    private val sin: DoubleArray

    init {
        // Make sure n is a power of 2
        if (n != (1 shl m)) {
            throw RuntimeException("FFT length must be power of 2")
        }

        // Precompute tables
        cos = DoubleArray(n / 2)
        sin = DoubleArray(n / 2)

        for (i in 0 until n / 2) {
            cos[i] = kotlin.math.cos(-2 * PI * i / n)
            sin[i] = kotlin.math.sin(-2 * PI * i / n)
        }
    }

    /**
     * In-place radix-2 DIT DFT of a complex input
     *
     * Douglas L. Jones, University of Illinois at Urbana-Champaign
     * January 19, 1992
     * http://cnx.rice.edu/content/m12016/latest/
     *
     * @param x Real part (input/output) - modified in place
     * @param y Imaginary part (input/output) - modified in place
     *
     * Input: n-length arrays x and y containing real and imaginary parts
     * Output: Arrays are modified to contain FFT coefficients
     */
    fun fft(x: DoubleArray, y: DoubleArray) {
        var i: Int
        var j: Int
        var k: Int
        var n1: Int
        var n2: Int
        var a: Int
        var c: Double
        var s: Double
        var t1: Double
        var t2: Double

        // Bit-reverse
        j = 0
        n2 = n / 2
        i = 1
        while (i < n - 1) {
            n1 = n2
            while (j >= n1) {
                j -= n1
                n1 /= 2
            }
            j += n1

            if (i < j) {
                t1 = x[i]
                x[i] = x[j]
                x[j] = t1
                t1 = y[i]
                y[i] = y[j]
                y[j] = t1
            }
            i++
        }

        // FFT
        n1 = 0
        n2 = 1

        for (level in 0 until m) {
            n1 = n2
            n2 += n2
            a = 0

            for (jj in 0 until n1) {
                c = cos[a]
                s = sin[a]
                a += 1 shl (m - level - 1)

                k = jj
                while (k < n) {
                    t1 = c * x[k + n1] - s * y[k + n1]
                    t2 = s * x[k + n1] + c * y[k + n1]
                    x[k + n1] = x[k] - t1
                    y[k + n1] = y[k] - t2
                    x[k] = x[k] + t1
                    y[k] = y[k] + t2
                    k += n2
                }
            }
        }
    }
}