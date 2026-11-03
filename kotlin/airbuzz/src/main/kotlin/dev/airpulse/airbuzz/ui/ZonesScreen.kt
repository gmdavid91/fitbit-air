package dev.airpulse.airbuzz.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.airpulse.airbuzz.R
import dev.airpulse.airbuzz.Setup
import dev.airpulse.airbuzz.app
import dev.airpulse.airbuzz.logic.HeartZones
import dev.airpulse.airbuzz.logic.ZoneStatus
import dev.airpulse.airbuzz.session.Session
import dev.airpulse.airbuzz.session.SessionKind
import dev.airpulse.airbuzz.session.SessionRequest
import dev.airpulse.airbuzz.session.SessionUi

@Composable
fun ZonesScreen(setup: Setup) {
    val ui by Session.ui.collectAsStateWithLifecycle()
    when (ui.kind) {
        SessionKind.ZONE -> ZoneRunning(ui)
        SessionKind.INTERVAL -> NoteCard(stringResource(R.string.session_other_running))
        SessionKind.NONE -> ZoneSetup(setup)
    }
}

@Composable
private fun ZoneSetup(setup: Setup) {
    val context = LocalContext.current
    val prefs = context.app.prefs
    var age by remember { mutableStateOf(prefs.age.takeIf { it > 0 }?.toString() ?: "") }
    var manual by remember { mutableStateOf(prefs.maxHrManual.takeIf { it > 0 }?.toString() ?: "") }
    var zone by remember { mutableStateOf(prefs.targetZone) }
    var maxHr by remember { mutableStateOf(prefs.maxHr) }

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(age, onValueChange = { text ->
            age = text.filter(Char::isDigit).take(3)
            prefs.age = age.toIntOrNull() ?: 0
            maxHr = prefs.maxHr
        }, label = { Text(stringResource(R.string.age)) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
        OutlinedTextField(manual, onValueChange = { text ->
            manual = text.filter(Char::isDigit).take(3)
            prefs.maxHrManual = manual.toIntOrNull() ?: 0
            maxHr = prefs.maxHr
        }, label = { Text(stringResource(R.string.max_hr_optional)) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
    }
    Text(
        maxHr?.let { stringResource(R.string.max_hr_is, it) } ?: stringResource(R.string.max_hr_needed),
        style = MaterialTheme.typography.bodyMedium,
    )

    SectionTitle(stringResource(R.string.target_zone))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (z in 1..5) FilterChip(selected = zone == z, onClick = { zone = z; prefs.targetZone = z },
            label = { Text("Z$z") })
    }
    maxHr?.let { max ->
        val range = HeartZones.range(zone, max)
        Text(stringResource(R.string.zone_range, zone, range.first, range.last), style = MaterialTheme.typography.titleLarge)
    }
    Text(stringResource(R.string.zone_names), style = MaterialTheme.typography.bodySmall)
    NoteCard(stringResource(R.string.zone_vocab))

    Button(
        onClick = {
            maxHr?.let { max ->
                val range = HeartZones.range(zone, max)
                Session.start(context, SessionRequest.Zone(zone, range.first, range.last))
            }
        },
        enabled = setup.ready && maxHr != null,
        modifier = Modifier.fillMaxWidth().height(56.dp),
    ) { Text(stringResource(R.string.start), style = MaterialTheme.typography.titleMedium) }
}

@Composable
private fun ZoneRunning(ui: SessionUi) {
    val context = LocalContext.current
    val (label, color) = when (ui.zoneStatus) {
        ZoneStatus.NO_DATA -> stringResource(R.string.zone_no_data) to MaterialTheme.colorScheme.outline
        ZoneStatus.BELOW -> stringResource(R.string.zone_below) to StatusColors.below
        ZoneStatus.IN -> stringResource(R.string.zone_in) to StatusColors.inZone
        ZoneStatus.ABOVE -> stringResource(R.string.zone_above) to StatusColors.above
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.zone_range, ui.zone, ui.low, ui.high), style = MaterialTheme.typography.titleMedium)
        Text(ui.bpm?.toString() ?: "–", fontSize = 112.sp, fontWeight = FontWeight.Bold, color = color)
        Text(label, style = MaterialTheme.typography.headlineSmall, color = color)
        Text(stringResource(R.string.time_in_zone, formatClock(ui.timeInZoneMillis), formatClock(ui.elapsedMillis)),
            style = MaterialTheme.typography.titleMedium)
        if (ui.zoneStatus == ZoneStatus.NO_DATA) Text(stringResource(R.string.hr_hint), style = MaterialTheme.typography.bodySmall)
        ui.wristError?.let { Text(stringResource(R.string.wrist_error, it), color = MaterialTheme.colorScheme.error) }
        Button(onClick = { Session.stop(context) }, modifier = Modifier.fillMaxWidth().height(56.dp).padding(top = 8.dp)) {
            Text(stringResource(if (ui.running) R.string.stop else R.string.close))
        }
    }
}
