package dev.airpulse.airbuzz.logic

import java.time.DayOfWeek
import java.time.LocalDateTime

data class AlarmSettings(
    val weekdayEnabled: Boolean = false,
    /** Minutes after midnight. */
    val weekdayMinutes: Int = 7 * 60,
    val weekendEnabled: Boolean = false,
    val weekendMinutes: Int = 9 * 60,
    /** 0 = off. */
    val smartWindowMinutes: Int = 0,
    val phoneFallback: Boolean = true,
) {
    val enabled: Boolean get() = weekdayEnabled || weekendEnabled
}

object AlarmMath {
    /** Heart rate recorded before the smart window to learn the night baseline. */
    const val BASELINE_MINUTES = 10L
    const val REPEAT_MILLIS = 2 * 60_000L
    const val PHONE_FALLBACK_MILLIS = 5 * 60_000L
    /** Stop everything after this long without a dismiss. */
    const val GIVE_UP_MILLIS = 30 * 60_000L

    fun isWeekend(day: DayOfWeek) = day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY

    /** The next wake time strictly after [now], or null when no day is enabled. */
    fun nextWake(settings: AlarmSettings, now: LocalDateTime): LocalDateTime? {
        for (offset in 0L..7L) {
            val date = now.toLocalDate().plusDays(offset)
            val weekend = isWeekend(date.dayOfWeek)
            val enabled = if (weekend) settings.weekendEnabled else settings.weekdayEnabled
            if (!enabled) continue
            val minutes = if (weekend) settings.weekendMinutes else settings.weekdayMinutes
            val candidate = date.atStartOfDay().plusMinutes(minutes.toLong())
            if (candidate.isAfter(now)) return candidate
        }
        return null
    }

    /** When the phone should start working for [wake]: earlier with a smart window, never before [now]. */
    fun serviceStart(wake: LocalDateTime, settings: AlarmSettings, now: LocalDateTime): LocalDateTime {
        val lead = if (settings.smartWindowMinutes > 0) settings.smartWindowMinutes + BASELINE_MINUTES else 0
        val start = wake.minusMinutes(lead)
        return if (start.isAfter(now)) start else now
    }

    /** Retry a failed wrist burst this soon instead of waiting for the next repeat. */
    const val RETRY_MILLIS = 20_000L

    /** When to send the next wake burst, given the last attempt and whether it worked. */
    fun nextWristAt(lastAttemptMillis: Long, succeeded: Boolean): Long =
        lastAttemptMillis + if (succeeded) REPEAT_MILLIS else RETRY_MILLIS

    /**
     * Phone sound starts [PHONE_FALLBACK_MILLIS] after waking when the fallback
     * is on, and right away after two failed wrist bursts even when it is off:
     * an alarm that cannot reach the band must not stay silent.
     */
    fun phoneSoundDue(settings: AlarmSettings, sinceWakeMillis: Long, consecutiveWristFailures: Int): Boolean =
        (settings.phoneFallback && sinceWakeMillis >= PHONE_FALLBACK_MILLIS) || consecutiveWristFailures >= 2
}
