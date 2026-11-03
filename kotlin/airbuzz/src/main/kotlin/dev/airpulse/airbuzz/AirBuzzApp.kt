package dev.airpulse.airbuzz

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import dev.airpulse.airbuzz.air.Haptics
import dev.airpulse.airbuzz.air.HeartRateMonitor

class AirBuzzApp : Application() {
    lateinit var haptics: Haptics; private set
    lateinit var heartRate: HeartRateMonitor; private set
    lateinit var prefs: Prefs; private set

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        haptics = Haptics(this)
        heartRate = HeartRateMonitor(this)
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ALARM, getString(R.string.channel_alarm), NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                enableVibration(false)
            })
            manager.createNotificationChannel(NotificationChannel(CHANNEL_SESSION, getString(R.string.channel_session), NotificationManager.IMPORTANCE_LOW))
        }
    }

    companion object {
        const val CHANNEL_ALARM = "alarm"
        const val CHANNEL_SESSION = "session"
    }
}

val android.content.Context.app: AirBuzzApp get() = applicationContext as AirBuzzApp
