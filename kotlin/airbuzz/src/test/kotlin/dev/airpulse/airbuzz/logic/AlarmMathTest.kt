package dev.airpulse.airbuzz.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class AlarmMathTest {
    // 2026-11-02 is a Monday.
    private val mondayEvening = LocalDateTime.of(2026, 11, 2, 22, 0)
    private val settings = AlarmSettings(weekdayEnabled = true, weekdayMinutes = 7 * 60, weekendEnabled = true, weekendMinutes = 9 * 60 + 30)

    @Test fun nextWeekdayMorning() {
        assertEquals(LocalDateTime.of(2026, 11, 3, 7, 0), AlarmMath.nextWake(settings, mondayEvening))
    }

    @Test fun sameDayWhenStillAhead() {
        assertEquals(LocalDateTime.of(2026, 11, 2, 7, 0), AlarmMath.nextWake(settings, LocalDateTime.of(2026, 11, 2, 6, 59)))
        assertEquals(LocalDateTime.of(2026, 11, 3, 7, 0), AlarmMath.nextWake(settings, LocalDateTime.of(2026, 11, 2, 7, 0)))
    }

    @Test fun fridayNightGoesToSaturdayWeekendTime() {
        assertEquals(LocalDateTime.of(2026, 11, 7, 9, 30), AlarmMath.nextWake(settings, LocalDateTime.of(2026, 11, 6, 23, 0)))
    }

    @Test fun weekdaysOnlySkipsWeekend() {
        val weekdays = settings.copy(weekendEnabled = false)
        assertEquals(LocalDateTime.of(2026, 11, 9, 7, 0), AlarmMath.nextWake(weekdays, LocalDateTime.of(2026, 11, 6, 23, 0)))
        assertNull(AlarmMath.nextWake(AlarmSettings(), mondayEvening))
    }

    @Test fun smartWindowStartsEarlier() {
        val wake = LocalDateTime.of(2026, 11, 3, 7, 0)
        assertEquals(wake, AlarmMath.serviceStart(wake, settings, mondayEvening))
        val smart = settings.copy(smartWindowMinutes = 30)
        assertEquals(LocalDateTime.of(2026, 11, 3, 6, 20), AlarmMath.serviceStart(wake, smart, mondayEvening))
        val late = LocalDateTime.of(2026, 11, 3, 6, 50)
        assertEquals(late, AlarmMath.serviceStart(wake, smart, late))
    }

    @Test fun repeatsAndFallback() {
        assertEquals(120_000L, AlarmMath.nextWristAt(0, true))
        assertEquals(20_000L, AlarmMath.nextWristAt(0, false))
        assertFalse(AlarmMath.phoneSoundDue(settings, 4 * 60_000, 0))
        assertTrue(AlarmMath.phoneSoundDue(settings, 5 * 60_000, 0))
        assertTrue(AlarmMath.phoneSoundDue(settings.copy(phoneFallback = false), 0, 2))
        assertFalse(AlarmMath.phoneSoundDue(settings.copy(phoneFallback = false), 10 * 60_000, 0))
    }
}
