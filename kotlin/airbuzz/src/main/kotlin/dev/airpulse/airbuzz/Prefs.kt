package dev.airpulse.airbuzz

import android.content.Context
import dev.airpulse.airbuzz.logic.AlarmSettings
import dev.airpulse.airbuzz.logic.HeartZones
import dev.airpulse.airbuzz.logic.IntervalConfig
import dev.airpulse.airbuzz.logic.Preset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Everything the app stores, on this phone only. */
class Prefs(context: Context) {
    private val prefs = context.getSharedPreferences("airbuzz", Context.MODE_PRIVATE)

    private val _alarm = MutableStateFlow(readAlarm())
    val alarm: StateFlow<AlarmSettings> = _alarm

    private fun readAlarm() = AlarmSettings(
        weekdayEnabled = prefs.getBoolean("alarm_weekday_on", false),
        weekdayMinutes = prefs.getInt("alarm_weekday_min", 7 * 60),
        weekendEnabled = prefs.getBoolean("alarm_weekend_on", false),
        weekendMinutes = prefs.getInt("alarm_weekend_min", 9 * 60),
        smartWindowMinutes = prefs.getInt("alarm_smart_min", 0),
        phoneFallback = prefs.getBoolean("alarm_phone_fallback", true),
    )

    fun setAlarm(settings: AlarmSettings) {
        prefs.edit()
            .putBoolean("alarm_weekday_on", settings.weekdayEnabled)
            .putInt("alarm_weekday_min", settings.weekdayMinutes)
            .putBoolean("alarm_weekend_on", settings.weekendEnabled)
            .putInt("alarm_weekend_min", settings.weekendMinutes)
            .putInt("alarm_smart_min", settings.smartWindowMinutes)
            .putBoolean("alarm_phone_fallback", settings.phoneFallback)
            .apply()
        _alarm.value = settings
    }

    /** Seconds between sending a command and feeling it. */
    var leadSeconds: Int
        get() = prefs.getInt("lead_s", 4)
        set(value) { prefs.edit().putInt("lead_s", value.coerceIn(1, 10)).apply() }

    var phoneBeepsAlways: Boolean
        get() = prefs.getBoolean("phone_beeps", false)
        set(value) { prefs.edit().putBoolean("phone_beeps", value).apply() }

    var preset: Preset
        get() = runCatching { Preset.valueOf(prefs.getString("preset", Preset.HIIT.name)!!) }.getOrDefault(Preset.HIIT)
        set(value) { prefs.edit().putString("preset", value.name).apply() }

    var customInterval: IntervalConfig
        get() = runCatching {
            IntervalConfig(prefs.getInt("custom_work", 30), prefs.getInt("custom_rest", 30), prefs.getInt("custom_rounds", 10))
        }.getOrDefault(IntervalConfig(30, 30, 10))
        set(value) {
            prefs.edit().putInt("custom_work", value.workSeconds).putInt("custom_rest", value.restSeconds)
                .putInt("custom_rounds", value.rounds).apply()
        }

    /** 0 = not set. */
    var age: Int
        get() = prefs.getInt("age", 0)
        set(value) { prefs.edit().putInt("age", value).apply() }

    /** User-entered max heart rate; 0 = use 220 − age. */
    var maxHrManual: Int
        get() = prefs.getInt("max_hr", 0)
        set(value) { prefs.edit().putInt("max_hr", value).apply() }

    val maxHr: Int?
        get() = maxHrManual.takeIf { it in 100..230 } ?: age.takeIf { it in 10..100 }?.let(HeartZones::maxFromAge)

    var targetZone: Int
        get() = prefs.getInt("zone", 2).coerceIn(1, 5)
        set(value) { prefs.edit().putInt("zone", value).apply() }
}
