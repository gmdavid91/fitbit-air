package dev.airpulse.airbuzz.session

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dev.airpulse.airbuzz.logic.IntervalConfig
import dev.airpulse.airbuzz.logic.SegmentKind
import dev.airpulse.airbuzz.logic.ZoneStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

sealed interface SessionRequest {
    data class Interval(val config: IntervalConfig, val leadMillis: Long, val phoneBeepsAlways: Boolean) : SessionRequest
    data class Zone(val zone: Int, val low: Int, val high: Int) : SessionRequest
}

enum class SessionKind { NONE, INTERVAL, ZONE }

/** What the running (or just finished) session shows. */
data class SessionUi(
    val kind: SessionKind = SessionKind.NONE,
    val running: Boolean = false,
    val bpm: Int? = null,
    val elapsedMillis: Long = 0,
    val wristError: String? = null,
    // Interval
    val segment: SegmentKind? = null,
    val round: Int = 0,
    val rounds: Int = 0,
    val segmentRemainingMillis: Long = 0,
    val totalRemainingMillis: Long = 0,
    val finished: Boolean = false,
    // Zone
    val zone: Int = 0,
    val low: Int = 0,
    val high: Int = 0,
    val zoneStatus: ZoneStatus = ZoneStatus.NO_DATA,
    val timeInZoneMillis: Long = 0,
)

/** Shared between the UI and [SessionService]. */
object Session {
    internal val _ui = MutableStateFlow(SessionUi())
    val ui: StateFlow<SessionUi> = _ui

    @Volatile internal var pending: SessionRequest? = null

    fun start(context: Context, request: SessionRequest) {
        pending = request
        ContextCompat.startForegroundService(context, Intent(context, SessionService::class.java).setAction(SessionService.ACTION_START))
    }

    fun stop(context: Context) {
        if (_ui.value.running) context.startService(Intent(context, SessionService::class.java).setAction(SessionService.ACTION_STOP))
        else close()
    }

    /** Clears a finished session from the screen. */
    fun close() {
        if (!_ui.value.running) _ui.value = SessionUi()
    }
}
