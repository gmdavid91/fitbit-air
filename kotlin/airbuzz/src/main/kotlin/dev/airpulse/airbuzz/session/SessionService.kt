package dev.airpulse.airbuzz.session

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import dev.airpulse.airbuzz.AirBuzzApp
import dev.airpulse.airbuzz.MainActivity
import dev.airpulse.airbuzz.R
import dev.airpulse.airbuzz.air.CueBeeper
import dev.airpulse.airbuzz.app
import dev.airpulse.airbuzz.logic.CueChannel
import dev.airpulse.airbuzz.logic.CuePlanner
import dev.airpulse.airbuzz.logic.SegmentKind
import dev.airpulse.airbuzz.logic.WristSignal
import dev.airpulse.airbuzz.logic.ZoneCoach
import dev.airpulse.airbuzz.logic.ZoneStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Runs an interval workout or a heart-rate zone session in the foreground,
 * with a wake lock, so it keeps time with the screen off.
 */
class SessionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var beeper: CueBeeper? = null
    private var hrAcquired = false
    private var lastNotified = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { finish(); return START_NOT_STICKY }
        val request = Session.pending
        Session.pending = null
        startInForeground(notification(getString(R.string.session_starting)))
        if (request == null || job?.isActive == true) {
            if (job?.isActive != true) finish()
            return START_NOT_STICKY
        }
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "airbuzz:session").apply {
                setReferenceCounted(false)
                acquire(4 * 60 * 60_000L)
            }
        beeper = CueBeeper()
        app.heartRate.acquire(); hrAcquired = true
        job = scope.launch {
            when (request) {
                is SessionRequest.Interval -> runInterval(request)
                is SessionRequest.Zone -> runZone(request)
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun runInterval(request: SessionRequest.Interval) = coroutineScope {
        val segments = request.config.segments()
        val cues = CuePlanner.plan(segments, request.leadMillis)
        val t0 = SystemClock.elapsedRealtime()
        suspend fun until(offset: Long) { delay(t0 + offset - SystemClock.elapsedRealtime()) }
        val beeper = beeper!!
        Session._ui.value = SessionUi(SessionKind.INTERVAL, running = true, rounds = request.config.rounds)

        // Wrist cues, sent early. A busy or failing band falls back to a beep at the cue time.
        launch {
            for (cue in cues.filter { it.channel == CueChannel.WRIST }.sortedBy { it.sendAtMillis }) {
                until(cue.sendAtMillis)
                launch {
                    if (!app.haptics.send(cue.signal) && !request.phoneBeepsAlways) {
                        until(cue.atMillis)
                        if (SystemClock.elapsedRealtime() - t0 - cue.atMillis < 8_000) beeper.play(cue.signal)
                    }
                    Session._ui.value = Session._ui.value.copy(wristError = app.haptics.lastError.value)
                }
            }
        }
        // Phone cues at the exact time.
        launch {
            for (cue in cues.filter { it.channel == CueChannel.PHONE || request.phoneBeepsAlways }.sortedBy { it.atMillis }) {
                until(cue.atMillis)
                beeper.play(cue.signal)
            }
        }

        val total = segments.last().endMillis
        while (isActive) {
            val elapsed = SystemClock.elapsedRealtime() - t0
            val index = CuePlanner.segmentAt(segments, elapsed)
            if (index >= segments.size) break
            val segment = segments[index]
            Session._ui.value = Session._ui.value.copy(
                bpm = app.heartRate.latest.value?.bpm,
                elapsedMillis = elapsed,
                segment = segment.kind,
                round = segment.round,
                segmentRemainingMillis = segment.endMillis - elapsed,
                totalRemainingMillis = total - elapsed,
            )
            val label = when (segment.kind) {
                SegmentKind.PREP -> getString(R.string.segment_prep)
                SegmentKind.WORK -> getString(R.string.segment_work)
                SegmentKind.REST -> getString(R.string.segment_rest)
            }
            notifyIfChanged(getString(R.string.session_interval_notification, label, segment.round, request.config.rounds))
            delay(200)
        }
        Session._ui.value = Session._ui.value.copy(finished = true, segmentRemainingMillis = 0, totalRemainingMillis = 0,
            segment = null, elapsedMillis = total)
        notifyIfChanged(getString(R.string.session_done))
        // coroutineScope waits here for the last (DONE) buzz before the band is released.
    }.also { finish() }

    private suspend fun runZone(request: SessionRequest.Zone) = coroutineScope {
        val coach = ZoneCoach(request.low, request.high)
        val t0 = SystemClock.elapsedRealtime()
        Session._ui.value = SessionUi(SessionKind.ZONE, running = true, zone = request.zone, low = request.low, high = request.high)
        launch {
            app.heartRate.samples.collect { sample ->
                val alert: WristSignal? = coach.onSample(sample.bpm, sample.atElapsedMillis)
                if (alert != null && !app.haptics.isBusy) launch {
                    app.haptics.send(alert)
                    Session._ui.value = Session._ui.value.copy(wristError = app.haptics.lastError.value)
                }
            }
        }
        while (isActive) {
            if (app.heartRate.latest.value == null && coach.status != ZoneStatus.NO_DATA) coach.onNoData()
            val elapsed = SystemClock.elapsedRealtime() - t0
            Session._ui.value = Session._ui.value.copy(
                bpm = coach.smoothedBpm,
                elapsedMillis = elapsed,
                zoneStatus = coach.status,
                timeInZoneMillis = coach.timeInZoneMillis,
            )
            val status = when (coach.status) {
                ZoneStatus.NO_DATA -> getString(R.string.zone_no_data)
                ZoneStatus.BELOW -> getString(R.string.zone_below)
                ZoneStatus.IN -> getString(R.string.zone_in)
                ZoneStatus.ABOVE -> getString(R.string.zone_above)
            }
            notifyIfChanged(getString(R.string.session_zone_notification, request.zone, status))
            delay(1_000)
        }
    }

    private fun finish() {
        job?.cancel()
        job = null
        if (Session._ui.value.running) Session._ui.value = Session._ui.value.copy(running = false)
        release()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun release() {
        if (hrAcquired) { app.heartRate.release(); hrAcquired = false }
        beeper?.release(); beeper = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        release()
        if (Session._ui.value.running) Session._ui.value = Session._ui.value.copy(running = false)
        scope.cancel()
        super.onDestroy()
    }

    private fun startInForeground(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun notifyIfChanged(text: String) {
        if (text == lastNotified) return
        lastNotified = text
        runCatching { NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification(text)) }
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 3, Intent(this, SessionService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, AirBuzzApp.CHANNEL_SESSION)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.stop), stop)
            .build()
    }

    companion object {
        const val ACTION_START = "dev.airpulse.airbuzz.SESSION_START"
        const val ACTION_STOP = "dev.airpulse.airbuzz.SESSION_STOP"
        private const val NOTIFICATION_ID = 20
    }
}
