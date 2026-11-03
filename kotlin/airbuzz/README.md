# Air Buzz

A small Android app that turns a **Google Fitbit Air** (the screenless band)
into a silent alarm, an interval timer and a heart-rate zone coach, using
buzzes on your wrist. Built on the Flutter-free [Kotlin library](../README.md)
of this repository.

> [!WARNING]
> Experimental and unofficial. It relies on private, firmware-specific
> protocols that can break with any band or Google Health update. Tested on
> firmware 67.20001.253.2. Do not rely on it alone for anything important:
> **keep the band's own alarm set as a backup.**

## What it does

| Feature | How it works |
|---|---|
| **Silent alarm** | Separate weekday and weekend wake times. The band buzzes 20 times and repeats every 2 minutes until you tap Stop; after 5 minutes the phone's alarm sound starts as a fallback (or right away if the band can't be reached twice). Uses exact alarm-clock alarms and a foreground service, so it fires in Doze and is re-armed after a reboot. |
| **Smart wake** (optional) | 10, 20 or 30 min window. Air Buzz listens to your live heart rate from 10 minutes before the window to learn your night baseline, and wakes you early once it stays at least 8 bpm above it for 2 minutes. |
| **Interval timer** | HIIT (40/20 × 8), EMOM (60 s × 10), Tabata (20/10 × 8) or custom. Work = long fast buzz, rest = short slow buzz, done = very long buzz. Buzzes are sent a few seconds early to cover the band's delay. Segments shorter than 15 s are too short for the wrist, so the phone beeps for them (the app tells you before you start). Big countdown and live heart rate; keeps running with the screen off. |
| **Heart-rate zones** | Max HR from 220 − age or your own measured value, five zones (50-100 %). Short buzz = below the zone (speed up), long buzz = above (ease off), after 10 s out of the zone and at most every 45 s. Shows live HR and time in zone. |
| **Test buzz** | Try every buzz, measure how long it takes to reach your wrist ("I felt it") and set how early workout buzzes are sent. |

Everything stays on the phone: no accounts, no analytics, and the app has no
internet permission.

## Install

1. Pair the Air with your phone in the **Google Health** app as usual.
2. In Google Health, turn on **share heart rate** for the Air (needed for
   smart wake, zones and the live HR shown during intervals).
3. Download `airbuzz-*.apk` from the
   [releases](https://github.com/gmdavid91/fitbit-air/releases) and install it
   (allow installing from your browser or file manager when asked).
4. Open Air Buzz, allow **Nearby devices** and **notifications**, and keep
   Bluetooth on.
5. For reliable alarms, set Air Buzz's battery use to **Unrestricted** (the
   Alarm tab has a shortcut), then try **Test alarm in 1 minute** with the
   screen locked.
6. Open **Test buzz** and learn the buzzes before relying on them.

Android 7.0 (API 24) or newer, arm64 phones. Developed on a Nothing Phone 2a.

### About the signing key

The APK is signed with a **public debug key** that is committed to this
repository (`airbuzz-debug.keystore`, password `android`). Its only purpose is
that each new build installs over the previous one when you sideload it. Since
anyone can sign with it, it proves nothing about who built an APK: only
install APKs from this repository's releases or CI, or build your own.

## Limits (what the Air cannot do)

- **The band's own alarm cannot be set** from outside Google Health (it needs a
  pairing key only Google Health has). Air Buzz buzzes the band from the phone,
  so the phone must be nearby, on, and with Bluetooth enabled.
- **Each buzz takes about 3-5 s to arrive**, and only one command runs at a
  time. That is why workout buzzes are sent early and short segments use the
  phone.
- **No tap detection**: tapping the band cannot stop the alarm; use the phone.
- **No HRV**: the band's standard heart-rate service sends no RR intervals.
- Firmware or Google Health updates may break it without warning.

## Build

CI builds it with the rest of the Kotlin project; locally:

```bash
gradle -p kotlin :airbuzz:testDebugUnitTest :airbuzz:assembleDebug
```

The scheduling, zone and smart-wake logic lives in plain Kotlin under
`src/main/kotlin/dev/airpulse/airbuzz/logic/` with JVM unit tests in
`src/test/`.

## Credits and licenses

- [ThoreKoritzius/fitbit-air](https://github.com/ThoreKoritzius/fitbit-air)
  (MIT): the Fitbit Air transport and research this app is built on.
- [Golden Gate](https://github.com/Google-Health-API/golden-gate) (Apache-2.0):
  the Gattlink / DTLS / CoAP stack bundled by the library. See
  [THIRD_PARTY_NOTICES.md](../../THIRD_PARTY_NOTICES.md).

Not affiliated with or endorsed by Google or Fitbit. "Fitbit" and "Fitbit Air"
are trademarks of Google LLC.
