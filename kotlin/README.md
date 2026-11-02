# fitbit-air for Kotlin (no Flutter)

The same Fitbit Air transport as the Flutter plugin, for plain Android apps.
It compiles the shared sources in `android/src/main` (the Flutter bridge lives in
`android/src/flutter` and is left out) and adds a small coroutine API.

> [!WARNING]
> Experimental, like the rest of this repository: private, firmware-specific
> protocols. The Air must be paired in Google Health on the same phone.

## Use

```kotlin
val air = FitbitAirClient(context)          // grant FitbitAirClient.requiredPermissions first

air.tap()                                    // 1 pulse, wearer's strength kept
air.vibrate()                                // 2 pulses
air.play(AirPattern.REST_OVER)               // named patterns
air.play(AirPattern.build { tap(); waitMillis(5_000); doubleTap() })

air.heartRate().collect { println(it.beatsPerMinute) }   // cancel to stop sharing

air.close()
```

| Call | What it does |
|---|---|
| `tap()` / `tap(intensity)` | One pulse. With an intensity different from the wearer's, the Air keeps it. |
| `vibrate()` | Two pulses; strength restored and verified. |
| `pulse()` | One pulse that switches low/high. |
| `play(pattern)` | Steps of `tap`, `doubleTap` and `waitMillis`. Presets: `NEXT_SET`, `REST_OVER`, `GENTLE_WAKE`, `ALERT`. |
| `heartRate()` | `Flow` from the standard Heart Rate Service. Haptics fail with `BUSY` while it runs. |
| `readBattery()`, `readDeviceInfo()`, `isAirPaired()`, `isAirConnected()` | Status. |
| `keepLinkWarm(true)` | Keeps the link open so haptics start faster. |

Errors are `AirException` with the same `code` values as the Dart API
(`BUSY`, `AIR_NOT_FOUND`, `HANDSHAKE_TIMEOUT`, ...).

### Patterns are slow on purpose

The Air has no pattern command. Each pulse is a verified settings change that
takes a few seconds, so `waitMillis` is a minimum gap, not an exact rhythm.

## Build

```bash
gradle -p kotlin :fitbit-air-android:assembleRelease :sample:assembleDebug
```

CI (`.github/workflows/kotlin.yml`) builds the AAR and the sample APK
(`sample/`), a one-screen test bench with a button per operation.

## Status

| Check | Result |
|---|---|
| Builds in CI | see the Kotlin library workflow |
| On a device | not yet verified with this Kotlin build |
