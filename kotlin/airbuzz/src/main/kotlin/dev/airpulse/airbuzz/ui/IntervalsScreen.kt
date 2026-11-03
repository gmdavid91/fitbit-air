package dev.airpulse.airbuzz.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.airpulse.airbuzz.R
import dev.airpulse.airbuzz.Setup
import dev.airpulse.airbuzz.app
import dev.airpulse.airbuzz.logic.CuePlanner
import dev.airpulse.airbuzz.logic.IntervalConfig
import dev.airpulse.airbuzz.logic.Preset
import dev.airpulse.airbuzz.logic.SegmentKind
import dev.airpulse.airbuzz.session.Session
import dev.airpulse.airbuzz.session.SessionKind
import dev.airpulse.airbuzz.session.SessionRequest
import dev.airpulse.airbuzz.session.SessionUi

@Composable
fun IntervalsScreen(setup: Setup) {
    val ui by Session.ui.collectAsStateWithLifecycle()
    when (ui.kind) {
        SessionKind.INTERVAL -> IntervalRunning(ui)
        SessionKind.ZONE -> NoteCard(stringResource(R.string.session_other_running))
        SessionKind.NONE -> IntervalSetup(setup)
    }
}

@Composable
private fun IntervalSetup(setup: Setup) {
    val context = LocalContext.current
    val prefs = context.app.prefs
    var preset by remember { mutableStateOf(prefs.preset) }
    var config by remember { mutableStateOf(IntervalConfig.preset(preset) ?: prefs.customInterval) }
    var phoneBeeps by remember { mutableStateOf(prefs.phoneBeepsAlways) }

    fun edit(work: Int = config.workSeconds, rest: Int = config.restSeconds, rounds: Int = config.rounds) {
        val new = runCatching { IntervalConfig(work, rest, rounds) }.getOrNull() ?: return
        config = new
        preset = Preset.CUSTOM
        prefs.preset = Preset.CUSTOM
        prefs.customInterval = new
    }

    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (p in Preset.entries) {
            FilterChip(selected = preset == p, onClick = {
                preset = p
                prefs.preset = p
                config = IntervalConfig.preset(p) ?: prefs.customInterval
            }, label = { Text(stringResource(presetName(p))) })
        }
    }
    Text(stringResource(presetHelp(preset)), style = MaterialTheme.typography.bodySmall)

    Stepper(stringResource(R.string.work), stringResource(R.string.seconds_short, config.workSeconds),
        onMinus = { edit(work = config.workSeconds - if (config.workSeconds > 60) 15 else 5) },
        onPlus = { edit(work = config.workSeconds + if (config.workSeconds >= 60) 15 else 5) })
    Stepper(stringResource(R.string.rest), stringResource(R.string.seconds_short, config.restSeconds),
        onMinus = { edit(rest = (config.restSeconds - if (config.restSeconds > 60) 15 else 5).coerceAtLeast(0)) },
        onPlus = { edit(rest = config.restSeconds + if (config.restSeconds >= 60) 15 else 5) })
    Stepper(stringResource(R.string.rounds), config.rounds.toString(),
        onMinus = { edit(rounds = config.rounds - 1) }, onPlus = { edit(rounds = config.rounds + 1) })
    Text(stringResource(R.string.interval_total, formatClock(config.totalMillis)), style = MaterialTheme.typography.bodyLarge)

    val short = CuePlanner.shortKinds(config.segments())
    if (short.isNotEmpty()) {
        val what = short.joinToString(" / ") { context.getString(if (it == SegmentKind.WORK) R.string.work else R.string.rest).lowercase() }
        NoteCard(stringResource(R.string.interval_short_warning, what, (CuePlanner.MIN_WRIST_SEGMENT_MILLIS / 1000).toInt()))
    }
    Text(stringResource(R.string.interval_vocab, prefs.leadSeconds), style = MaterialTheme.typography.bodySmall)

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.phone_beeps_too), modifier = Modifier.weight(1f))
        Switch(checked = phoneBeeps, onCheckedChange = { phoneBeeps = it; prefs.phoneBeepsAlways = it })
    }

    Button(
        onClick = { Session.start(context, SessionRequest.Interval(config, prefs.leadSeconds * 1000L, phoneBeeps)) },
        enabled = setup.ready,
        modifier = Modifier.fillMaxWidth().height(56.dp),
    ) { Text(stringResource(R.string.start), style = MaterialTheme.typography.titleMedium) }
}

@Composable
private fun IntervalRunning(ui: SessionUi) {
    val context = LocalContext.current
    val label = when {
        ui.finished -> stringResource(R.string.session_done)
        ui.segment == SegmentKind.PREP -> stringResource(R.string.segment_prep)
        ui.segment == SegmentKind.WORK -> stringResource(R.string.segment_work)
        ui.segment == SegmentKind.REST -> stringResource(R.string.segment_rest)
        else -> ""
    }
    val color = when (ui.segment) {
        SegmentKind.WORK -> StatusColors.above
        SegmentKind.REST -> StatusColors.inZone
        else -> MaterialTheme.colorScheme.primary
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label.uppercase(), style = MaterialTheme.typography.headlineMedium, color = color, fontWeight = FontWeight.Bold)
        Text(formatClock(ui.segmentRemainingMillis), fontSize = 96.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        if (ui.round > 0) Text(stringResource(R.string.round_of, ui.round, ui.rounds), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.remaining_total, formatClock(ui.totalRemainingMillis)), style = MaterialTheme.typography.bodyLarge)
        Text("♥ " + (ui.bpm?.let { stringResource(R.string.bpm, it) } ?: stringResource(R.string.hr_none)),
            style = MaterialTheme.typography.headlineSmall)
        ui.wristError?.let { Text(stringResource(R.string.wrist_error, it), color = MaterialTheme.colorScheme.error) }
        Button(onClick = { Session.stop(context) }, modifier = Modifier.fillMaxWidth().height(56.dp).padding(top = 8.dp)) {
            Text(stringResource(if (ui.running) R.string.stop else R.string.close))
        }
    }
}

fun presetName(preset: Preset) = when (preset) {
    Preset.HIIT -> R.string.preset_hiit
    Preset.EMOM -> R.string.preset_emom
    Preset.TABATA -> R.string.preset_tabata
    Preset.CUSTOM -> R.string.preset_custom
}

private fun presetHelp(preset: Preset) = when (preset) {
    Preset.HIIT -> R.string.preset_hiit_help
    Preset.EMOM -> R.string.preset_emom_help
    Preset.TABATA -> R.string.preset_tabata_help
    Preset.CUSTOM -> R.string.preset_custom_help
}
