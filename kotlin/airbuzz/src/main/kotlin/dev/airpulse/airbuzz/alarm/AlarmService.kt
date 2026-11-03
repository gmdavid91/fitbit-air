package dev.airpulse.airbuzz.alarm

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import dev.airpulse.airbuzz.AirBuzzApp
import dev.airpulse.airbuzz.R
import dev.airpulse.airbuzz.air.AlarmSound
import dev.airpulse.airbuzz.alarm.AlarmScheduler.toLocalDateTime
import dev.airpulse.airbuzz.app
import dev.airpulse.airbuzz.logic.AlarmMath
import dev.airpulse.airbuzz.logic.SmartWakeDetector
import dev.airpulse.airbuzz.logic.WristSignal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.text.DateFormat
import java.util.Date

enum class AlarmPhase { IDLE, WATCHING, RINGING }

data class AlarmStatus(
    val phase: AlarmPhase = AlarmPhase.IDLE,
    val wakeAtMillis: Long = 0,
    val baseline: Int? = null,
    val bpm: Int? = null,
    val phoneSound: Boolean = false,
    val wristBursts: Int = 0,
    val wristError: String? = null,
)

/**
 * Runs one alarm: optional smart-wake watching, then wrist bursts every
 * 2 minutes and, as a fallback, the phone's alarm sound. Foreground with a
 * wake lock so it keeps running in Doze with the screen off.
 */
class AlarmService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var sound: AlarmSound
    private var hrAcquired = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        sound = AlarmSound(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISMISS -> { finish(); return START_NOT_STICKY }
            ACTION_START -> {
                val wakeAt = intent.getLongExtra(AlarmScheduler.EXTRA_WAKE_AT, System.currentTimeMillis())
                val smartMin = intent.getIntExtra(AlarmScheduler.EXTRA_SMART_MIN, 0)
                val test = intent.getBooleanExtra(AlarmScheduler.EXTRA_TEST, false)
                startInForeground(watchingNotification(wakeAt))
                if (!test) AlarmScheduler.schedule(this, after = wakeAt.toLocalDateTime())
                if (job?.isActive == true) return START_NOT_STICKY
                acquireWakeLock(smartMin)
                job = scope.launch { run(wakeAt, smartMin) }
            }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private suspend fun run(wakeAt: Long, smartMin: Int) {
        if (smartMin > 0 && System.currentTimeMillis() < wakeAt) watch(wakeAt, smartMin)
        ring(wakeAt)
    }

    /** Smart window: wait for the heart rate to rise, or for the wake time. */
    private suspend fun watch(wakeAt: Long, smartMin: Int) {
        val hr = app.heartRate
        hr.acquire(); hrAcquired = true
        _status.value = AlarmStatus(AlarmPhase.WATCHING, wakeAt)
        // Detector times are wall-clock millis so the window start is easy to express.
        val detector = SmartWakeDetector(windowStartMillis = wakeAt - smartMin * 60_000L)
        withTimeoutOrNull(wakeAt - System.currentTimeMillis()) {
            hr.samples.first { sample ->
                val wake = detector.onSample(sample.bpm, System.currentTimeMillis())
                _status.value = _status.value.copy(baseline = detector.baseline, bpm = sample.bpm)
                wake
            }
        }
    }

    private suspend fun ring(wakeAt: Long) {
        if (hrAcquired) { app.heartRate.release(); hrAcquired = false }
        val settings = app.prefs.alarm.value
        val started = SystemClock.elapsedRealtime()
        _status.value = AlarmStatus(AlarmPhase.RINGING, wakeAt)
        NotificationManagerCompat.from(this).let { manager ->
            if (manager.areNotificationsEnabled()) runCatching { manager.notify(NOTIFICATION_ID, ringingNotification()) }
        }
        var nextWristAt = started
        var failures = 0
        var bursts = 0
        while (scope.isActive) {
            val now = SystemClock.elapsedRealtime()
            val since = now - started
            if (since >= AlarmMath.GIVE_UP_MILLIS) break
            if (now >= nextWristAt && !app.haptics.isBusy) {
                nextWristAt = Long.MAX_VALUE
                scope.launch {
                    val attempt = SystemClock.elapsedRealtime()
                    val ok = app.haptics.send(WristSignal.WAKE)
                    if (ok) { failures = 0; bursts++ } else failures++
                    nextWristAt = AlarmMath.nextWristAt(attempt, ok)
                    _status.value = _status.value.copy(wristBursts = bursts, wristError = app.haptics.lastError.value)
                }
            }
            if (!sound.isPlaying && AlarmMath.phoneSoundDue(settings, since, failures)) {
                sound.start()
                _status.value = _status.value.copy(phoneSound = true)
            }
            delay(1_000)
        }
        finish()
    }

    private fun finish() {
        job?.cancel()
        job = null
        sound.stop()
        if (hrAcquired) { app.heartRate.release(); hrAcquired = false }
        _status.value = AlarmStatus()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        sound.stop()
        if (hrAcquired) { app.heartRate.release(); hrAcquired = false }
        wakeLock?.let { if (it.isHeld) it.release() }
        if (_status.value.phase != AlarmPhase.IDLE) _status.value = AlarmStatus()
        scope.cancel()
        super.onDestroy()
    }

    private fun acquireWakeLock(smartMin: Int) {
        val power = getSystemService(PowerManager::class.java)
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "airbuzz:alarm").apply {
            setReferenceCounted(false)
            acquire((smartMin + AlarmMath.BASELINE_MINUTES + 35) * 60_000L)
        }
    }

    private fun startInForeground(notification: Notification) {
        val type = when {
            Build.VERSION.SDK_INT < 29 -> 0
            hasBluetoothPermission() -> ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            else -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun hasBluetoothPermission() = Build.VERSION.SDK_INT < 31 ||
        checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun stopAction() = NotificationCompat.Action(0, getString(R.string.alarm_stop),
        PendingIntent.getService(this, 1, dismissIntent(this), PendingIntent.FLAG_IMMUTABLE))

    private fun watchingNotification(wakeAt: Long) = NotificationCompat.Builder(this, AirBuzzApp.CHANNEL_SESSION)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(getString(R.string.app_name))
        .setContentText(getString(R.string.alarm_watching, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(wakeAt))))
        .setOngoing(true)
        .setCategory(NotificationCompat.CATEGORY_ALARM)
        .addAction(stopAction())
        .build()

    private fun ringingNotification(): Notification {
        val screen = PendingIntent.getActivity(this, 2, Intent(this, AlarmActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, AirBuzzApp.CHANNEL_ALARM)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.alarm_ringing_title))
            .setContentText(getString(R.string.alarm_ringing_text))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setContentIntent(screen)
            .setFullScreenIntent(screen, true)
            .addAction(stopAction())
            .build()
    }

    companion object {
        const val ACTION_START = "dev.airpulse.airbuzz.ALARM_START"
        const val ACTION_DISMISS = "dev.airpulse.airbuzz.ALARM_DISMISS"
        private const val NOTIFICATION_ID = 10

        private val _status = MutableStateFlow(AlarmStatus())
        val status: StateFlow<AlarmStatus> = _status

        fun dismissIntent(context: Context) = Intent(context, AlarmService::class.java).setAction(ACTION_DISMISS)

        fun dismiss(context: Context) {
            if (_status.value.phase == AlarmPhase.IDLE) return
            context.startService(dismissIntent(context))
        }
    }
}
