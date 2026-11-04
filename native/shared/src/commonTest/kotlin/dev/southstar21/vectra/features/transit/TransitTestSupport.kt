package dev.southstar21.vectra.features.transit

import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.assertTrue

/** vitest `toBeCloseTo(expected, numDigits)`: |expected - actual| < 10^-digits / 2. */
internal fun assertCloseTo(expected: Double, actual: Double?, digits: Int = 2) {
    val a = actual ?: Double.NaN
    assertTrue(abs(expected - a) < 10.0.pow(-digits) / 2, "expected $a to be close to $expected ($digits digits)")
}
