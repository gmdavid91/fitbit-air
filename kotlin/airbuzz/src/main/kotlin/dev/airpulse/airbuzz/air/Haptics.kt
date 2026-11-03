package dev.airpulse.airbuzz.air

import android.content.Context
import dev.airpulse.airbuzz.logic.WristSignal
import dev.airpulse.fitbit_air.client.AirException
import dev.airpulse.fitbit_air.client.BatteryInfo
import dev.airpulse.fitbit_air.client.FitbitAirClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicBoolean

/** One app-wide connection to the band. Commands never queue: a busy band says no. */
class Haptics(context: Context) {
    private val client = FitbitAirClient(context.applicationContext)
    private val inFlight = AtomicBoolean(false)

    private val _lastError = MutableStateFlow<String?>(null)
    /** Code of the last failed command (AIR_NOT_FOUND, ...), null after a success. */
    val lastError: StateFlow<String?> = _lastError

    val isBusy: Boolean get() = inFlight.get()

    /**
     * Sends [signal]. Returns false at once if another command is running, or
     * after the band fails. Suspends for the whole command (several seconds).
     */
    suspend fun send(signal: WristSignal): Boolean {
        if (!inFlight.compareAndSet(false, true)) return false
        return try {
            client.burst(signal.pulses, signal.gapMillis)
            _lastError.value = null
            true
        } catch (error: AirException) {
            _lastError.value = error.code
            false
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            _lastError.value = error.javaClass.simpleName
            false
        } finally {
            inFlight.set(false)
        }
    }

    suspend fun isPaired(): Boolean = runCatching { client.isAirPaired() }.getOrDefault(false)

    suspend fun battery(): BatteryInfo? = if (!inFlight.compareAndSet(false, true)) null else try {
        client.readBattery()
    } catch (error: AirException) {
        _lastError.value = error.code; null
    } finally {
        inFlight.set(false)
    }
}
