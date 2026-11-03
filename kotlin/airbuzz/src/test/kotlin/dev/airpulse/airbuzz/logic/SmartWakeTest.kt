package dev.airpulse.airbuzz.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartWakeTest {
    private val min = 60_000L

    @Test fun wakesAfterTwoMinutesOfRiseInsideWindow() {
        val detector = SmartWakeDetector(windowStartMillis = 10 * min)
        var woke: Long? = null
        for (s in 0 until 30 * 60) {
            val t = s * 1000L
            val bpm = if (t < 15 * min) 52 + (s % 3) else 63
            if (detector.onSample(bpm, t)) { woke = t; break }
        }
        assertEquals(52, detector.baseline)
        // Rise starts at 15:00; 90 % of the last 2 minutes must be >= 60.
        assertTrue("woke at $woke", woke!! in (16 * min + 30_000)..(17 * min + 15_000))
    }

    @Test fun doesNotWakeBeforeWindow() {
        val detector = SmartWakeDetector(windowStartMillis = 20 * min)
        var woke = false
        for (s in 0 until 19 * 60) {
            val bpm = if (s < 8 * 60) 50 else 70
            woke = woke || detector.onSample(bpm, s * 1000L)
        }
        assertFalse(woke)
    }

    @Test fun shortSpikeDoesNotWake() {
        val detector = SmartWakeDetector(windowStartMillis = 0)
        var woke = false
        for (s in 0 until 20 * 60) {
            val bpm = if (s in 600..660) 75 else 55
            woke = woke || detector.onSample(bpm, s * 1000L)
        }
        assertFalse(woke)
    }

    @Test fun needsBaselineAndCoverage() {
        val detector = SmartWakeDetector(windowStartMillis = 0)
        // Only 3 minutes of data: no baseline yet.
        var woke = false
        for (s in 0 until 180) woke = woke || detector.onSample(if (s < 60) 50 else 80, s * 1000L)
        assertFalse(woke)
        // Gap in data: sparse samples in the last two minutes do not count.
        val sparse = SmartWakeDetector(windowStartMillis = 0)
        for (s in 0 until 600) sparse.onSample(50, s * 1000L)
        var sparseWoke = false
        for (s in 600 until 900 step 10) sparseWoke = sparseWoke || sparse.onSample(80, s * 1000L)
        assertFalse(sparseWoke)
    }
}
