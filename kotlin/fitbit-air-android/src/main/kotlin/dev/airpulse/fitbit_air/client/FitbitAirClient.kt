package dev.airpulse.fitbit_air.client

import android.Manifest
import android.content.Context
import android.os.Build
import dev.airpulse.fitbit_air.AirEngine
import dev.airpulse.fitbit_air.AirEventSink
import dev.airpulse.fitbit_air.AirReply
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Vibration strength of the Air, as set in Google Health. */
enum class HapticIntensity(internal val wire: Int) {
    LOW(2), HIGH(3);

    internal companion object {
        fun of(wire: Any?): HapticIntensity? = entries.firstOrNull { it.wire == wire }
    }
}

/** A failed Air operation. [code] matches the Dart API's error codes (BUSY, AIR_NOT_FOUND, ...). */
class AirException(val code: String, message: String?) : Exception("$code: ${message ?: "Fitbit Air error"}")

data class TapResult(val intensity: HapticIntensity?, val previous: HapticIntensity?, val changed: Boolean)

data class BatteryInfo(val percent: Int, val onCharger: Boolean?, val voltageMillivolts: Int?)

data class HeartRateSample(
    val beatsPerMinute: Int,
    val receivedAtMillis: Long,
    /** Empty on the tested Air firmware, which sends no RR intervals. */
    val rrIntervalsMillis: List<Double>,
)

/**
 * Kotlin access to a Fitbit Air paired in Google Health on this phone.
 *
 * Operations run one at a time (the Air has a single secure channel shared
 * with Google Health) and each haptic takes a few seconds. Grant
 * [requiredPermissions] before calling; this client never shows a permission
 * dialog itself.
 *
 * Experimental: relies on private, firmware-specific protocols.
 */
class FitbitAirClient(context: Context) : AutoCloseable {
    private val engine = AirEngine(context)
    private val mutex = Mutex()

    /** One pulse at the wearer's strength, which is left unchanged. */
    suspend fun tap(): TapResult = tapResult(call("tap"))

    /**
     * One pulse at [intensity]. If it differs from the wearer's setting, the
     * Air keeps the new strength (it only vibrates when the strength changes).
     */
    suspend fun tap(intensity: HapticIntensity): TapResult = tapResult(call("tap", mapOf("intensity" to intensity.wire)))

    /** Two pulses; the wearer's strength is restored and verified. */
    suspend fun vibrate() { call("vibrate") }

    /**
     * Experimental: [pulses] buzzes about [gapMillis] apart in one session,
     * the closest this path gets to an alarm. [pulses] must be even (2-30);
     * the wearer's strength is restored and verified.
     */
    suspend fun burst(pulses: Int = 12, gapMillis: Long = 600) {
        call("burst", mapOf("pulses" to pulses, "gapMillis" to gapMillis))
    }

    /** One pulse that switches the strength between low and high. */
    suspend fun pulse(): HapticIntensity? = HapticIntensity.of((call("pulse") as Map<*, *>)["currentIntensity"])

    suspend fun hapticIntensity(): HapticIntensity? = HapticIntensity.of((call("getHapticIntensity") as Map<*, *>)["intensity"])

    suspend fun setHapticIntensity(intensity: HapticIntensity): Boolean =
        (call("setHapticIntensity", mapOf("intensity" to intensity.wire)) as Map<*, *>)["changed"] == true

    /** Plays [pattern] step by step. Throws on the first failed step. */
    suspend fun play(pattern: AirPattern) {
        for (step in pattern.steps) when (step) {
            AirPattern.Step.Tap -> tap()
            AirPattern.Step.DoubleTap -> vibrate()
            is AirPattern.Step.Wait -> delay(step.millis)
        }
    }

    suspend fun isAirPaired(): Boolean = call("isAirPaired") == true

    suspend fun isAirConnected(): Boolean = call("isAirConnected") == true

    suspend fun readBattery(): BatteryInfo {
        val value = call("readBattery") as Map<*, *>
        return BatteryInfo(
            percent = (value["percent"] as Number).toInt(),
            onCharger = value["onCharger"] as? Boolean,
            voltageMillivolts = (value["voltageMillivolts"] as? Number)?.toInt(),
        )
    }

    /** Model, firmware, hardware, manufacturer and battery, where readable. */
    suspend fun readDeviceInfo(): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return (call("readDeviceInfo") as Map<String, Any?>).filterKeys { it != "services" }
    }

    /**
     * Live heart rate over the standard Heart Rate Service. On the tested
     * firmware, subscribing starts heart-rate sharing (the Air vibrates once).
     * Cancel the collection to stop sharing; haptics fail with BUSY while it runs.
     */
    fun heartRate(): Flow<HeartRateSample> = callbackFlow {
        val sink = object : AirEventSink {
            override fun success(event: Any?) {
                val value = event as? Map<*, *> ?: return
                trySend(HeartRateSample(
                    beatsPerMinute = (value["beatsPerMinute"] as Number).toInt(),
                    receivedAtMillis = (value["receivedAtMillis"] as Number).toLong(),
                    rrIntervalsMillis = (value["rrIntervalsMillis"] as? List<*>)?.mapNotNull { (it as? Number)?.toDouble() } ?: emptyList(),
                ))
            }
            override fun error(code: String, message: String?, details: Any?) {
                close(AirException(code, message))
            }
            override fun endOfStream() { close() }
        }
        engine.listenHeartRate(null, sink)
        awaitClose { engine.cancelHeartRate() }
    }.flowOn(Dispatchers.Main)

    /**
     * Keeps one Bluetooth link open between commands so each haptic starts
     * faster. Costs some battery on the phone and the Air.
     */
    suspend fun keepLinkWarm(enabled: Boolean) { call("keepLinkWarm", mapOf("enabled" to enabled)) }

    override fun close() = engine.dispose()

    private suspend fun call(method: String, args: Map<String, Any?> = emptyMap()): Any? = mutex.withLock {
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val reply = object : AirReply {
                    override fun success(value: Any?) {
                        if (continuation.isActive) continuation.resume(value)
                    }
                    override fun error(code: String, message: String?, details: Any?) {
                        if (continuation.isActive) continuation.resumeWithException(AirException(code, message))
                    }
                }
                if (!engine.handle(method, args, reply)) {
                    continuation.resumeWithException(AirException("NOT_IMPLEMENTED", method))
                }
            }
        }
    }

    private fun tapResult(value: Any?): TapResult {
        val map = value as Map<*, *>
        return TapResult(HapticIntensity.of(map["intensity"]), HapticIntensity.of(map["previousIntensity"]), map["changed"] == true)
    }

    companion object {
        /** Runtime permissions to grant before using the client. */
        val requiredPermissions: Array<String>
            get() = if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyArray()
    }
}
