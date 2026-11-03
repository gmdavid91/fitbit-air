package dev.airpulse.airbuzz.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ZoneCoachTest {
    @Test fun zonesFromMaxHr() {
        assertEquals(186, HeartZones.maxFromAge(34))
        assertEquals(100..119, HeartZones.range(1, 200))
        assertEquals(140..159, HeartZones.range(3, 200))
        assertEquals(180..200, HeartZones.range(5, 200))
    }

    private fun feed(coach: ZoneCoach, bpm: Int, fromSec: Int, toSec: Int): List<Pair<Int, WristSignal>> {
        val alerts = mutableListOf<Pair<Int, WristSignal>>()
        for (s in fromSec until toSec) coach.onSample(bpm, s * 1000L)?.let { alerts += s to it }
        return alerts
    }

    @Test fun aboveAlertsAfterSustainThenCooldown() {
        val coach = ZoneCoach(140, 159)
        assertEquals(emptyList<Pair<Int, WristSignal>>(), feed(coach, 150, 0, 30))
        assertEquals(ZoneStatus.IN, coach.status)
        val alerts = feed(coach, 175, 30, 140)
        // Smoothing crosses 159 within a few seconds, then 10 s sustain, then every 45 s.
        assertEquals(3, alerts.size)
        assertEquals(WristSignal.ABOVE, alerts[0].second)
        assertEquals(45, alerts[1].first - alerts[0].first)
    }

    @Test fun belowIsQuietDuringWarmup() {
        val coach = ZoneCoach(140, 159)
        val alerts = feed(coach, 100, 0, 75)
        assertEquals(1, alerts.size)
        assertEquals(WristSignal.BELOW, alerts[0].second)
        assertEquals(60, alerts[0].first)
    }

    @Test fun shortExcursionDoesNotAlert() {
        val coach = ZoneCoach(140, 159)
        feed(coach, 150, 0, 100)
        assertEquals(emptyList<Pair<Int, WristSignal>>(), feed(coach, 170, 100, 106))
        assertEquals(emptyList<Pair<Int, WristSignal>>(), feed(coach, 150, 106, 140))
    }

    @Test fun countsTimeInZone() {
        val coach = ZoneCoach(140, 159)
        feed(coach, 150, 0, 61)
        assertEquals(60_000L, coach.timeInZoneMillis)
        coach.onNoData()
        assertNull(coach.smoothedBpm)
        coach.onSample(150, 200_000)
        assertEquals(60_000L, coach.timeInZoneMillis)
    }
}
