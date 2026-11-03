package dev.airpulse.airbuzz.ui

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.airpulse.airbuzz.R
import dev.airpulse.airbuzz.Setup
import dev.airpulse.airbuzz.air.HrState
import dev.airpulse.airbuzz.app
import dev.airpulse.airbuzz.logic.WristSignal
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun TestScreen(setup: Setup) {
    val context = LocalContext.current
    val app = context.app
    val scope = rememberCoroutineScope()
    var sending by remember { mutableStateOf<WristSignal?>(null) }
    var sentAt by remember { mutableStateOf(0L) }
    var felt by remember { mutableStateOf<Double?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    var lead by remember { mutableStateOf(app.prefs.leadSeconds) }
    var hrOn by remember { mutableStateOf(false) }
    val hrState by app.heartRate.state.collectAsStateWithLifecycle()
    val hr by app.heartRate.latest.collectAsStateWithLifecycle()
    val lastError by app.haptics.lastError.collectAsStateWithLifecycle()

    DisposableEffect(hrOn) {
        if (hrOn) app.heartRate.acquire()
        onDispose { if (hrOn) app.heartRate.release() }
    }

    Text(stringResource(R.string.test_intro), style = MaterialTheme.typography.bodyMedium)

    for (signal in listOf(WristSignal.GO, WristSignal.REST, WristSignal.DONE, WristSignal.BELOW, WristSignal.ABOVE, WristSignal.WAKE)) {
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(signalName(signal)), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.signal_shape, signal.pulses, "%.1f".format(signal.durationMillis / 1000.0)),
                        style = MaterialTheme.typography.bodySmall)
                }
                FilledTonalButton(
                    enabled = setup.ready && sending == null,
                    onClick = {
                        sending = signal
                        felt = null
                        result = null
                        sentAt = SystemClock.elapsedRealtime()
                        scope.launch {
                            val ok = app.haptics.send(signal)
                            result = if (ok) context.getString(R.string.test_sent_ok, (SystemClock.elapsedRealtime() - sentAt) / 1000.0)
                            else context.getString(R.string.wrist_error, app.haptics.lastError.value ?: "BUSY")
                            sending = null
                        }
                    },
                ) { Text(stringResource(R.string.buzz)) }
            }
        }
    }

    if (sending != null || felt != null) {
        Button(
            onClick = { if (felt == null) felt = (SystemClock.elapsedRealtime() - sentAt) / 1000.0 },
            modifier = Modifier.fillMaxWidth().height(72.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary),
        ) { Text(felt?.let { stringResource(R.string.felt_after, it) } ?: stringResource(R.string.felt_it), style = MaterialTheme.typography.titleLarge) }
    }
    felt?.let { seconds ->
        val suggested = seconds.roundToInt().coerceIn(1, 10)
        OutlinedButton(onClick = { lead = suggested; app.prefs.leadSeconds = suggested }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.use_as_lead, suggested))
        }
    }
    result?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

    SectionTitle(stringResource(R.string.lead_title))
    Stepper(stringResource(R.string.lead_label), stringResource(R.string.seconds_short, lead),
        onMinus = { lead = (lead - 1).coerceAtLeast(1); app.prefs.leadSeconds = lead },
        onPlus = { lead = (lead + 1).coerceAtMost(10); app.prefs.leadSeconds = lead })
    Text(stringResource(R.string.lead_explain), style = MaterialTheme.typography.bodySmall)

    SectionTitle(stringResource(R.string.hr_title))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = { hrOn = !hrOn }, enabled = setup.ready) {
            Text(stringResource(if (hrOn) R.string.stop else R.string.hr_check))
        }
        Text(
            when {
                !hrOn -> ""
                hr != null -> "♥ " + stringResource(R.string.bpm, hr!!.bpm)
                hrState == HrState.NO_BAND -> stringResource(R.string.setup_no_band_short)
                else -> stringResource(R.string.hr_waiting)
            },
            style = MaterialTheme.typography.titleLarge,
        )
    }
    Text(stringResource(R.string.hr_hint), style = MaterialTheme.typography.bodySmall)
    lastError?.let { Text(stringResource(R.string.last_error, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    Text(stringResource(R.string.impossible), style = MaterialTheme.typography.bodySmall)
}

private fun signalName(signal: WristSignal) = when (signal) {
    WristSignal.GO -> R.string.signal_go
    WristSignal.REST -> R.string.signal_rest
    WristSignal.DONE -> R.string.signal_done
    WristSignal.BELOW -> R.string.signal_below
    WristSignal.ABOVE -> R.string.signal_above
    WristSignal.WAKE -> R.string.signal_wake
}
