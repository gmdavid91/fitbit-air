package dev.airpulse.airbuzz.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import dev.airpulse.airbuzz.MainActivity
import dev.airpulse.airbuzz.app
import dev.airpulse.airbuzz.logic.AlarmMath
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** Keeps exactly one system alarm clock set for the next wake time. */
object AlarmScheduler {
    const val ACTION_FIRE = "dev.airpulse.airbuzz.ALARM_FIRE"
    const val EXTRA_WAKE_AT = "wakeAt"
    const val EXTRA_SMART_MIN = "smartMin"
    const val EXTRA_TEST = "test"
    private const val REQUEST_ALARM = 1
    private const val REQUEST_TEST = 2

    fun canScheduleExact(context: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    /**
     * Schedules the next wake strictly after [after] (default: now), or cancels
     * the alarm when none is enabled. Returns the wake time.
     */
    fun schedule(context: Context, after: LocalDateTime = LocalDateTime.now()): LocalDateTime? {
        val settings = context.app.prefs.alarm.value
        val manager = context.getSystemService(AlarmManager::class.java)
        val wake = AlarmMath.nextWake(settings, after)
        if (wake == null) {
            manager.cancel(firePending(context, REQUEST_ALARM, null))
            return null
        }
        val start = AlarmMath.serviceStart(wake, settings, LocalDateTime.now())
        val intent = fireIntent(context).putExtra(EXTRA_WAKE_AT, wake.toMillis()).putExtra(EXTRA_SMART_MIN, settings.smartWindowMinutes)
        set(context, start.toMillis(), firePending(context, REQUEST_ALARM, intent))
        return wake
    }

    /** A one-off alarm in [seconds], without smart wake, to try the whole chain. */
    fun scheduleTest(context: Context, seconds: Long = 60) {
        val at = System.currentTimeMillis() + seconds * 1000
        val intent = fireIntent(context).putExtra(EXTRA_WAKE_AT, at).putExtra(EXTRA_SMART_MIN, 0).putExtra(EXTRA_TEST, true)
        set(context, at, firePending(context, REQUEST_TEST, intent))
    }

    private fun set(context: Context, atMillis: Long, operation: PendingIntent) {
        val manager = context.getSystemService(AlarmManager::class.java)
        val show = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        try {
            if (canScheduleExact(context)) {
                // An alarm clock fires on time even in Doze and lets the service start from the background.
                manager.setAlarmClock(AlarmManager.AlarmClockInfo(atMillis, show), operation)
                return
            }
        } catch (_: SecurityException) { }
        // Without exact-alarm access: the closest Android allows. The UI warns about it.
        if (Build.VERSION.SDK_INT >= 23) manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, operation)
        else manager.set(AlarmManager.RTC_WAKEUP, atMillis, operation)
    }

    private fun fireIntent(context: Context) = Intent(context, AlarmReceiver::class.java).setAction(ACTION_FIRE)

    private fun firePending(context: Context, request: Int, intent: Intent?): PendingIntent =
        PendingIntent.getBroadcast(context, request, intent ?: fireIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun LocalDateTime.toMillis(): Long = atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    fun Long.toLocalDateTime(): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(this), ZoneId.systemDefault())
}
