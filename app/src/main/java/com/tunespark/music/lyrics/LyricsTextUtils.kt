package com.tunespark.music.lyrics

import java.util.Locale
import kotlin.math.abs

private val timestampRegex = Regex("""\[\d{1,3}:\d{2}(?:[.:]\d{1,3})?]""")

/** True when the payload has at least one LRC-style timestamp. */
internal fun hasTimestamps(raw: String): Boolean = timestampRegex.containsMatchIn(raw)

internal fun normalizeForMatch(value: String): String {
    return value
        .lowercase(Locale.ROOT)
        .replace(Regex("""[^\p{L}\p{N}\s]"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()
}

/**
 * Lightweight similarity used only to pick between provider search results.
 * Contains-relationships score high; otherwise a Levenshtein ratio is used.
 */
internal fun matchSimilarity(a: String, b: String): Double {
    val left = normalizeForMatch(a)
    val right = normalizeForMatch(b)
    if (left.isEmpty() || right.isEmpty()) return 0.0
    if (left == right) return 1.0
    if (left.contains(right) || right.contains(left)) return 0.8

    val maxLength = maxOf(left.length, right.length)
    return 1.0 - levenshtein(left, right).toDouble() / maxLength
}

internal fun durationPenalty(expectedSec: Int, actualSec: Int): Double {
    if (expectedSec <= 0 || actualSec <= 0) return 0.0
    return (abs(expectedSec - actualSec) / 30.0).coerceAtMost(0.5)
}

private fun levenshtein(a: String, b: String): Int {
    val previous = IntArray(b.length + 1) { it }
    val current = IntArray(b.length + 1)
    for (i in 1..a.length) {
        current[0] = i
        for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            current[j] = minOf(
                current[j - 1] + 1,
                previous[j] + 1,
                previous[j - 1] + cost,
            )
        }
        previous.indices.forEach { previous[it] = current[it] }
    }
    return previous[b.length]
}

internal fun decodeXmlEntities(value: String): String {
    return value
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
        .replace(Regex("""&#(\d+);""")) { match ->
            match.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: match.value
        }
        .replace(Regex("""&#x([0-9a-fA-F]+);""")) { match ->
            match.groupValues[1].toIntOrNull(16)?.toChar()?.toString() ?: match.value
        }
}
