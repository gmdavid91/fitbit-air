package dev.airpulse.airbuzz.logic

/**
 * Smart-wake decision: wake early once the heart rate has stayed at least
 * [riseBpm] above the night baseline for [sustainMillis], but only inside the
 * window that starts at [windowStartMillis].
 *
 * The baseline is the 25th percentile of samples older than the sustain
 * period, so the rise itself does not lift it. It needs [minBaselineMillis] of
 * data first.
 */
class SmartWakeDetector(
    private val windowStartMillis: Long,
    private val riseBpm: Int = 8,
    private val sustainMillis: Long = 120_000,
    private val minBaselineMillis: Long = 5 * 60_000,
) {
    private val samples = ArrayList<Pair<Long, Int>>()

    var baseline: Int? = null; private set

    /** Returns true when it is time to wake. */
    fun onSample(bpm: Int, nowMillis: Long): Boolean {
        if (bpm in 25..230) samples += nowMillis to bpm
        if (samples.isEmpty()) return false
        val cut = nowMillis - sustainMillis
        val older = samples.filter { it.first < cut }
        if (older.isEmpty() || older.last().first - samples.first().first < minBaselineMillis - sustainMillis) {
            baseline = null
            return false
        }
        val sorted = older.map { it.second }.sorted()
        val base = sorted[(sorted.size - 1) / 4]
        baseline = base
        if (nowMillis < windowStartMillis) return false
        val window = samples.filter { it.first >= cut }
        // Need the last two minutes to be covered: first sample near the start, at least one every 2 s on average.
        if (window.isEmpty() || window.first().first - cut > 15_000) return false
        if (window.size < sustainMillis / 2_000) return false
        val high = window.count { it.second >= base + riseBpm }
        return high * 10 >= window.size * 9
    }
}
