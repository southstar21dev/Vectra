package dev.southstar21.vectra.core.util

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow

/**
 * Nearest integer with ties toward +∞ (2.5 → 3, -2.5 → -2), unlike
 * [kotlin.math.round], which rounds ties to even. NaN and infinities pass through.
 */
fun roundHalfUp(x: Double): Double = if (x.isNaN() || x.isInfinite()) x else floor(x + 0.5)

/** [roundHalfUp] as an Int (NaN → 0, infinities clamp). */
fun roundHalfUpToInt(x: Double): Int = roundHalfUp(x).toInt()

/** [roundHalfUp] as a Long (NaN → 0, infinities clamp). */
fun roundHalfUpToLong(x: Double): Long = roundHalfUp(x).toLong()

/**
 * Fixed-point formatting with [digits] decimals: rounds half away from zero on
 * the magnitude and keeps a leading "-" for negative inputs.
 */
fun toFixed(value: Double, digits: Int): String {
    if (value.isNaN()) return "NaN"
    if (value.isInfinite()) return if (value > 0) "Infinity" else "-Infinity"
    val negative = value < 0
    val p = 10.0.pow(digits)
    val n = floor(abs(value) * p + 0.5).toLong()
    val intPart = n / p.toLong()
    val frac = n % p.toLong()
    val sb = StringBuilder()
    if (negative) sb.append('-')
    sb.append(intPart)
    if (digits > 0) sb.append('.').append(frac.toString().padStart(digits, '0'))
    return sb.toString()
}

private val LeadingNumber = Regex("""^\s*[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?""")

/** The leading numeric prefix of [s] ("12px" → 12.0), NaN when there is none. */
fun parseLeadingNumber(s: String): Double {
    val m = LeadingNumber.find(s) ?: return Double.NaN
    return m.value.trim().toDoubleOrNull() ?: Double.NaN
}

/** [value] as text, with integral values printed without a trailing ".0" (47.0 → "47"). */
fun formatCompactNumber(value: Double): String =
    if (value == floor(value) && abs(value) < 1e15) value.toLong().toString() else value.toString()
