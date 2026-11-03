package dev.airpulse.airbuzz.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Starts the alarm service when the alarm fires; re-arms after reboot, updates and clock changes. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            AlarmScheduler.ACTION_FIRE -> {
                val service = Intent(context, AlarmService::class.java)
                    .setAction(AlarmService.ACTION_START)
                    .putExtras(intent)
                try {
                    ContextCompat.startForegroundService(context, service)
                } catch (error: IllegalStateException) {
                    // Only happens without exact-alarm access (the inexact fallback is not
                    // allowed to start a foreground service). The app warns about it.
                    android.util.Log.w("AirBuzz", "Could not start the alarm service", error)
                }
            }
            else -> AlarmScheduler.schedule(context)
        }
    }
}
