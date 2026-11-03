package dev.airpulse.airbuzz

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.airpulse.airbuzz.alarm.AlarmScheduler
import dev.airpulse.airbuzz.ui.AirBuzzTheme
import dev.airpulse.airbuzz.ui.AlarmScreen
import dev.airpulse.airbuzz.ui.IntervalsScreen
import dev.airpulse.airbuzz.ui.NoteCard
import dev.airpulse.airbuzz.ui.TestScreen
import dev.airpulse.airbuzz.ui.ZonesScreen

/** What the screens need to know about the phone's setup. */
data class Setup(
    val bluetoothPermission: Boolean,
    val notificationPermission: Boolean,
    val bluetoothOn: Boolean,
    val bandBonded: Boolean,
    val exactAlarms: Boolean,
    val batteryUnrestricted: Boolean,
) {
    val ready: Boolean get() = bluetoothPermission && bluetoothOn && bandBonded
}

class MainActivity : ComponentActivity() {
    private var setup by mutableStateOf(Setup(false, false, false, false, true, true))

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        refresh()
        if (missingPermissions().isNotEmpty()) permissions.launch(missingPermissions())
        setContent { AirBuzzTheme { App() } }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        // Re-arm in case the app was updated or the clock changed while it was closed.
        AlarmScheduler.schedule(this)
    }

    private fun missingPermissions(): Array<String> {
        val wanted = buildList {
            if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun refresh() {
        val bluetoothPermission = Build.VERSION.SDK_INT < 31 ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        val bonded = bluetoothPermission && runCatching {
            adapter?.bondedDevices?.any { it.name?.contains("Fitbit Air", ignoreCase = true) == true } == true
        }.getOrDefault(false)
        setup = Setup(
            bluetoothPermission = bluetoothPermission,
            notificationPermission = Build.VERSION.SDK_INT < 33 ||
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
            bluetoothOn = adapter?.isEnabled == true,
            bandBonded = bonded,
            exactAlarms = AlarmScheduler.canScheduleExact(this),
            batteryUnrestricted = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName),
        )
    }

    fun requestPermissions() {
        val missing = missingPermissions()
        if (missing.isNotEmpty()) permissions.launch(missing)
        else openAppSettings()
    }

    fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    fun openBluetoothSettings() { runCatching { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) } }

    fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT >= 31) runCatching {
            startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
        }
    }

    @android.annotation.SuppressLint("BatteryLife")
    fun openBatterySettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }.onFailure { runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun App() {
        var tab by rememberSaveable { mutableIntStateOf(0) }
        val titles = listOf(R.string.tab_alarm, R.string.tab_intervals, R.string.tab_zones, R.string.tab_test)
        val icons = listOf(Icons.Filled.Notifications, Icons.Filled.PlayArrow, Icons.Filled.Favorite, Icons.Filled.Build)
        Scaffold(
            topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
            bottomBar = {
                NavigationBar {
                    titles.forEachIndexed { index, title ->
                        NavigationBarItem(selected = tab == index, onClick = { tab = index },
                            icon = { Icon(icons[index], contentDescription = null) }, label = { Text(stringResource(title)) })
                    }
                }
            },
        ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SetupNotes(setup)
                when (tab) {
                    0 -> AlarmScreen(setup, this@MainActivity)
                    1 -> IntervalsScreen(setup)
                    2 -> ZonesScreen(setup)
                    else -> TestScreen(setup)
                }
            }
        }
    }

    @Composable
    private fun SetupNotes(setup: Setup) {
        when {
            !setup.bluetoothPermission -> NoteCard(stringResource(R.string.setup_bluetooth_permission), warning = true,
                action = stringResource(R.string.allow)) { requestPermissions() }
            !setup.bluetoothOn -> NoteCard(stringResource(R.string.setup_bluetooth_off), warning = true,
                action = stringResource(R.string.open_settings)) { openBluetoothSettings() }
            !setup.bandBonded -> NoteCard(stringResource(R.string.setup_no_band), warning = true)
        }
        if (!setup.notificationPermission) NoteCard(stringResource(R.string.setup_notifications), warning = true,
            action = stringResource(R.string.allow)) { requestPermissions() }
    }
}
