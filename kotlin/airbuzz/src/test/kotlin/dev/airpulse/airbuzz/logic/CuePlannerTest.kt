package dev.airpulse.airbuzz.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CuePlannerTest {
    private val lead = 4_000L

    @Test fun segmentsSkipLastRest() {
        val segments = IntervalConfig(40, 20, 3).segments()
        assertEquals(listOf(SegmentKind.PREP, SegmentKind.WORK, SegmentKind.REST, SegmentKind.WORK, SegmentKind.REST, SegmentKind.WORK),
            segments.map { it.kind })
        assertEquals(10_000L + 3 * 40_000 + 2 * 20_000, IntervalConfig(40, 20, 3).totalMillis)
    }

    @Test fun hiitGoesToTheWristEarly() {
        val segments = IntervalConfig.preset(Preset.HIIT)!!.segments()
        val cues = CuePlanner.plan(segments, lead)
        assertTrue(cues.all { it.channel == CueChannel.WRIST })
        val first = cues.first()
        assertEquals(WristSignal.GO, first.signal)
        assertEquals(10_000L, first.atMillis)
        assertEquals(6_000L, first.sendAtMillis)
        assertEquals(WristSignal.REST, cues[1].signal)
        assertEquals(WristSignal.DONE, cues.last().signal)
        assertEquals(segments.last().endMillis, cues.last().atMillis)
        // 8 GO + 7 REST + DONE
        assertEquals(16, cues.size)
    }

    @Test fun tabataRestIsTooShortForTheWrist() {
        val segments = IntervalConfig.preset(Preset.TABATA)!!.segments()
        val cues = CuePlanner.plan(segments, lead)
        val rests = cues.filter { it.signal == WristSignal.REST }
        assertTrue(rests.isNotEmpty())
        assertTrue(rests.all { it.channel == CueChannel.PHONE && it.sendAtMillis == it.atMillis })
        assertTrue(cues.filter { it.signal == WristSignal.GO }.all { it.channel == CueChannel.WRIST })
        assertEquals(setOf(SegmentKind.REST), CuePlanner.shortKinds(segments))
    }

    @Test fun emomBuzzesEveryMinute() {
        val segments = IntervalConfig.preset(Preset.EMOM)!!.segments()
        val cues = CuePlanner.plan(segments, lead)
        val gos = cues.filter { it.signal == WristSignal.GO }
        assertEquals(10, gos.size)
        assertEquals((0 until 10).map { 10_000L + it * 60_000L }, gos.map { it.atMillis })
        assertTrue(cues.all { it.channel == CueChannel.WRIST })
    }

    @Test fun busyWristFallsBackToPhone() {
        // With a 12 s lead, GO (sent at 8 s) keeps the wrist busy until 25.6 s,
        // so REST, which would be sent at 23 s, goes to the phone.
        val segments = IntervalConfig(15, 15, 2, prepSeconds = 20).segments()
        val cues = CuePlanner.plan(segments, 12_000)
        assertEquals(CueChannel.WRIST, cues[0].channel)
        assertEquals(CueChannel.PHONE, cues[1].channel)
    }

    @Test fun noPrepMeansFirstCueOnPhone() {
        val cues = CuePlanner.plan(IntervalConfig(30, 30, 1, prepSeconds = 0).segments(), lead)
        assertEquals(CueChannel.PHONE, cues.first().channel)
    }

    @Test fun segmentAtFindsTheRunningSegment() {
        val segments = IntervalConfig(40, 20, 2).segments()
        assertEquals(0, CuePlanner.segmentAt(segments, 0))
        assertEquals(1, CuePlanner.segmentAt(segments, 10_000))
        assertEquals(2, CuePlanner.segmentAt(segments, 50_000))
        assertEquals(segments.size, CuePlanner.segmentAt(segments, 1_000_000))
    }

    @Test fun signalsAreValidBursts() {
        for (signal in WristSignal.entries) {
            assertTrue(signal.pulses % 2 == 0 && signal.durationMillis <= 30_000)
        }
        assertTrue(WristSignal.GO.durationMillis != WristSignal.REST.durationMillis)
        assertTrue(WristSignal.BELOW.durationMillis < WristSignal.ABOVE.durationMillis)
    }
}
