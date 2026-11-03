package dev.airpulse.airbuzz.ui

import android.app.TimePickerDialog
import android.text.format.DateFormat
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.airpulse.airbuzz.MainActivity
import dev.airpulse.airbuzz.R
import dev.airpulse.airbuzz.Setup
import dev.airpulse.airbuzz.alarm.AlarmPhase
import dev.airpulse.airbuzz.alarm.AlarmScheduler
import dev.airpulse.airbuzz.alarm.AlarmService
import dev.airpulse.airbuzz.app
import dev.airpulse.airbuzz.logic.AlarmMath
import dev.airpulse.airbuzz.logic.AlarmSettings
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun AlarmScreen(setup: Setup, activity: MainActivity) {
    val context = LocalContext.current
    val prefs = context.app.prefs
    val settings by prefs.alarm.collectAsStateWithLifecycle()
    val status by AlarmService.status.collectAsStateWithLifecycle()

    fun update(new: AlarmSettings) {
        prefs.setAlarm(new)
        AlarmScheduler.schedule(context)
    }
    fun pickTime(current: Int, onPicked: (Int) -> Unit) {
        TimePickerDialog(context, { _, hour, minute -> onPicked(hour * 60 + minute) },
            current / 60, current % 60, DateFormat.is24HourFormat(context)).show()
    }

    if (status.phase != AlarmPhase.IDLE) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(if (status.phase == AlarmPhase.WATCHING) R.string.alarm_status_watching else R.string.alarm_status_ringing),
                    style = MaterialTheme.typography.titleMedium)
                if (status.phase == AlarmPhase.WATCHING) Text(stringResource(R.string.alarm_status_hr,
                    status.bpm?.toString() ?: "–", status.baseline?.toString() ?: "–"))
                Button(onClick = { AlarmService.dismiss(context) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.alarm_stop)) }
            }
        }
    }

    NoteCard(stringResource(R.string.alarm_backup_note))

    SectionTitle(stringResource(R.string.alarm_when))
    AlarmRow(stringResource(R.string.alarm_weekdays), settings.weekdayEnabled, settings.weekdayMinutes,
        onToggle = { update(settings.copy(weekdayEnabled = it)) },
        onTime = { pickTime(settings.weekdayMinutes) { m -> update(settings.copy(weekdayMinutes = m, weekdayEnabled = true)) } })
    AlarmRow(stringResource(R.string.alarm_weekend), settings.weekendEnabled, settings.weekendMinutes,
        onToggle = { update(settings.copy(weekendEnabled = it)) },
        onTime = { pickTime(settings.weekendMinutes) { m -> update(settings.copy(weekendMinutes = m, weekendEnabled = true)) } })

    val next = AlarmMath.nextWake(settings, LocalDateTime.now())
    Text(
        if (next == null) stringResource(R.string.alarm_off)
        else stringResource(R.string.alarm_next, next.format(DateTimeFormatter.ofPattern("EEE")) + " " +
            next.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))),
        style = MaterialTheme.typography.bodyLarge,
    )

    SectionTitle(stringResource(R.string.alarm_smart))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (minutes in listOf(0, 10, 20, 30)) {
            FilterChip(selected = settings.smartWindowMinutes == minutes, onClick = { update(settings.copy(smartWindowMinutes = minutes)) },
                label = { Text(if (minutes == 0) stringResource(R.string.off) else stringResource(R.string.minutes_short, minutes)) })
        }
    }
    Text(stringResource(R.string.alarm_smart_explain), style = MaterialTheme.typography.bodySmall)

    SectionTitle(stringResource(R.string.alarm_fallback))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.alarm_phone_fallback), modifier = Modifier.weight(1f))
        Switch(checked = settings.phoneFallback, onCheckedChange = { update(settings.copy(phoneFallback = it)) })
    }
    Text(stringResource(R.string.alarm_fallback_explain), style = MaterialTheme.typography.bodySmall)

    if (!setup.exactAlarms) NoteCard(stringResource(R.string.alarm_exact_missing), warning = true,
        action = stringResource(R.string.open_settings)) { activity.openExactAlarmSettings() }
    if (!setup.batteryUnrestricted) NoteCard(stringResource(R.string.alarm_battery), warning = false,
        action = stringResource(R.string.open_settings)) { activity.openBatterySettings() }

    OutlinedButton(
        onClick = {
            AlarmScheduler.scheduleTest(context)
            Toast.makeText(context, R.string.alarm_test_set, Toast.LENGTH_LONG).show()
        },
        enabled = setup.ready,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.alarm_test)) }
}

@Composable
private fun AlarmRow(label: String, enabled: Boolean, minutes: Int, onToggle: (Boolean) -> Unit, onTime: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = onTime, modifier = Modifier.padding(end = 12.dp)) {
            Text(formatMinutesOfDay(minutes), style = MaterialTheme.typography.titleLarge)
        }
        Switch(checked = enabled, onCheckedChange = onToggle)
    }
}
