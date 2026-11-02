package dev.airpulse.fitbit_air.sample

import android.content.pm.PackageManager
import android.os.Bundle
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import dev.airpulse.fitbit_air.client.AirException
import dev.airpulse.fitbit_air.client.AirPattern
import dev.airpulse.fitbit_air.client.FitbitAirClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Minimal manual test bench for the Kotlin client. One button per operation. */
class MainActivity : ComponentActivity() {
    private lateinit var air: FitbitAirClient
    private lateinit var log: TextView
    private var heartRateJob: Job? = null
    private var busy = false

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        print(if (granted.values.all { it }) "Nearby devices permission granted." else "Nearby devices permission denied.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        air = FitbitAirClient(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
        }
        fun button(label: String, action: suspend () -> String) {
            column.addView(Button(this).apply {
                text = label
                setOnClickListener { run(label, action) }
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        button("Is the Air paired?") { "Paired: ${air.isAirPaired()}" }
        button("Tap (1 pulse)") { air.tap().let { "Tap at ${it.intensity}" } }
        button("Vibrate (2 pulses)") { air.vibrate(); "Vibrated" }
        button("Pulse (switch strength)") { "Strength now ${air.pulse()}" }
        button("Pattern: next set") { air.play(AirPattern.NEXT_SET); "Played NEXT_SET" }
        button("Pattern: rest over") { air.play(AirPattern.REST_OVER); "Played REST_OVER" }
        button("Pattern: gentle wake (~2 min)") { air.play(AirPattern.GENTLE_WAKE); "Played GENTLE_WAKE" }
        button("Pattern: alert") { air.play(AirPattern.ALERT); "Played ALERT" }
        button("Battery") { air.readBattery().let { "Battery ${it.percent}% charging=${it.onCharger}" } }
        button("Device info") { air.readDeviceInfo().entries.joinToString { "${it.key}=${it.value}" } }
        column.addView(Button(this).apply {
            text = "Live heart rate: start / stop"
            setOnClickListener { toggleHeartRate() }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        log = TextView(this).apply { setTextIsSelectable(true); setPadding(0, 24, 0, 0) }
        column.addView(log)
        setContentView(ScrollView(this).apply { addView(column) })

        val missing = FitbitAirClient.requiredPermissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) permissions.launch(missing.toTypedArray())
        print("Pair the Air in Google Health first. Each haptic takes a few seconds.")
    }

    private fun run(label: String, action: suspend () -> String) {
        if (busy) { print("Wait: another operation is running."); return }
        if (heartRateJob != null) { print("Stop live heart rate first (one channel at a time)."); return }
        busy = true
        print("$label…")
        lifecycleScope.launch {
            try { print(action()) }
            catch (error: AirException) { print("Failed: ${error.message}") }
            finally { busy = false }
        }
    }

    private fun toggleHeartRate() {
        heartRateJob?.let { it.cancel(); heartRateJob = null; print("Heart rate stopped."); return }
        if (busy) { print("Wait: another operation is running."); return }
        print("Heart rate: subscribing…")
        heartRateJob = lifecycleScope.launch {
            try {
                air.heartRate().collect { print("♥ ${it.beatsPerMinute} bpm" + if (it.rrIntervalsMillis.isEmpty()) "" else " RR ${it.rrIntervalsMillis}") }
            } catch (error: AirException) {
                print("Heart rate failed: ${error.message}")
            } finally {
                heartRateJob = null
            }
        }
    }

    private fun print(line: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        log.text = "$time  $line\n" + log.text
    }

    override fun onDestroy() {
        air.close()
        super.onDestroy()
    }
}
