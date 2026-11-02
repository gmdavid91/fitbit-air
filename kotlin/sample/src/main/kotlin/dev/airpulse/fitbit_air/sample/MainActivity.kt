package dev.airpulse.fitbit_air.sample

import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Minimal manual test bench for the Kotlin client. One button per operation, each timed. */
class MainActivity : ComponentActivity() {
    private lateinit var air: FitbitAirClient
    private lateinit var log: TextView
    private var heartRateJob: Job? = null
    private var busy = false

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        print(getString(if (granted.values.all { it }) R.string.perm_ok else R.string.perm_denied))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        air = FitbitAirClient(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
        }
        fun button(labelRes: Int, action: suspend () -> String) {
            val label = getString(labelRes)
            column.addView(Button(this).apply {
                text = label
                setOnClickListener { run(label, action) }
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        val done = getString(R.string.done)
        button(R.string.paired) { getString(R.string.paired_result, air.isAirPaired().toString()) }
        button(R.string.tap) { air.tap().let { getString(R.string.tap_result, it.intensity.toString()) } }
        button(R.string.vibrate) { air.vibrate(); done }
        button(R.string.pulse) { getString(R.string.strength_now, air.pulse().toString()) }
        button(R.string.next_set) { air.play(AirPattern.NEXT_SET); done }
        button(R.string.rest_over) { air.play(AirPattern.REST_OVER); done }
        button(R.string.gentle_wake) { air.play(AirPattern.GENTLE_WAKE); done }
        button(R.string.alert) { air.play(AirPattern.ALERT); done }
        val custom = EditText(this).apply {
            hint = getString(R.string.custom_hint)
            setText("t 5 d 5 t")
            isSingleLine = true
        }
        column.addView(custom, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        button(R.string.play_custom) {
            val pattern = try { AirPattern.parse(custom.text.toString()) }
                catch (error: IllegalArgumentException) { return@button error.message ?: "Invalid pattern" }
            air.play(pattern)
            getString(R.string.played, pattern.steps.size)
        }
        button(R.string.battery) { air.readBattery().let { getString(R.string.battery_result, it.percent, it.onCharger.toString()) } }
        button(R.string.device_info) { air.readDeviceInfo().entries.joinToString { "${it.key}=${it.value}" } }
        button(R.string.raw_test) { tapWithSeparateHeartRate() }
        column.addView(Button(this).apply {
            text = getString(R.string.hr_toggle)
            setOnClickListener { toggleHeartRate() }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        log = TextView(this).apply { setTextIsSelectable(true); setPadding(0, 24, 0, 0) }
        column.addView(log)
        setContentView(ScrollView(this).apply { addView(column) })

        val missing = FitbitAirClient.requiredPermissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) permissions.launch(missing.toTypedArray())
        print(getString(R.string.intro))
    }

    private fun run(label: String, action: suspend () -> String) {
        if (busy) { print(getString(R.string.busy)); return }
        if (heartRateJob != null) { print(getString(R.string.stop_hr_first)); return }
        busy = true
        print("$label…")
        val started = SystemClock.elapsedRealtime()
        fun seconds() = (SystemClock.elapsedRealtime() - started) / 1000.0
        lifecycleScope.launch {
            try { print(getString(R.string.took, action(), seconds())) }
            catch (error: AirException) { print(getString(R.string.took, getString(R.string.failed, error.message), seconds())) }
            finally { busy = false }
        }
    }

    /** Test 2: does a tap work while another GATT client streams heart rate? */
    private suspend fun tapWithSeparateHeartRate(): String {
        val raw = RawHeartRate(this)
        print(getString(R.string.raw_opening))
        if (!raw.start()) return getString(R.string.raw_no_air)
        try {
            var waited = 0
            while (raw.samples.get() < 3 && waited < 15_000) { delay(250); waited += 250 }
            if (raw.samples.get() < 3) return getString(R.string.raw_no_hr)
            print(getString(R.string.raw_tapping, raw.samples.get()))
            val before = raw.samples.get()
            val started = SystemClock.elapsedRealtime()
            val outcome = try { air.tap(); getString(R.string.ok) } catch (error: AirException) { getString(R.string.failed, error.message) }
            val tapSeconds = (SystemClock.elapsedRealtime() - started) / 1000.0
            delay(5_000)
            return getString(R.string.raw_result, outcome, tapSeconds, raw.samples.get() - before)
        } finally {
            raw.stop()
        }
    }

    private fun toggleHeartRate() {
        heartRateJob?.let { it.cancel(); heartRateJob = null; print(getString(R.string.hr_stopped)); return }
        if (busy) { print(getString(R.string.busy)); return }
        print(getString(R.string.hr_subscribing))
        heartRateJob = lifecycleScope.launch {
            try {
                air.heartRate().collect { print("♥ ${it.beatsPerMinute} bpm" + if (it.rrIntervalsMillis.isEmpty()) "" else " RR ${it.rrIntervalsMillis}") }
            } catch (error: AirException) {
                print(getString(R.string.failed, error.message))
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
