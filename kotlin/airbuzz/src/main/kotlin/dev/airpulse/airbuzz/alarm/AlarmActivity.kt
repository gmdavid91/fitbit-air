package dev.airpulse.airbuzz.alarm

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.airpulse.airbuzz.R
import dev.airpulse.airbuzz.ui.AirBuzzTheme
import java.text.DateFormat
import java.util.Date

/** Full-screen "stop" screen shown over the lock screen while the alarm rings. */
class AlarmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            AirBuzzTheme {
                val status by AlarmService.status.collectAsStateWithLifecycle()
                LaunchedEffect(status.phase) { if (status.phase == AlarmPhase.IDLE) finish() }
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically)) {
                        Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date()), fontSize = 72.sp)
                        Text(stringResource(R.string.alarm_ringing_title), style = MaterialTheme.typography.headlineSmall)
                        if (status.phoneSound) Text(stringResource(R.string.alarm_phone_ringing))
                        Button(onClick = { AlarmService.dismiss(this@AlarmActivity); finish() },
                            modifier = Modifier.fillMaxWidth().height(96.dp)) {
                            Text(stringResource(R.string.alarm_stop), fontSize = 28.sp)
                        }
                    }
                }
            }
        }
    }
}
