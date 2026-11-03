package dev.airpulse.airbuzz.air

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import dev.airpulse.airbuzz.logic.WristSignal

/** Short beeps for workout cues the wrist cannot deliver. */
class CueBeeper {
    private val tones = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 90) }.getOrNull()

    fun play(signal: WristSignal) {
        val (tone, millis) = when (signal) {
            WristSignal.GO -> ToneGenerator.TONE_PROP_BEEP2 to 600
            WristSignal.REST -> ToneGenerator.TONE_PROP_NACK to 500
            WristSignal.DONE -> ToneGenerator.TONE_CDMA_CONFIRM to 1200
            else -> ToneGenerator.TONE_PROP_BEEP to 300
        }
        runCatching { tones?.startTone(tone, millis) }
    }

    fun release() { runCatching { tones?.release() } }
}

/** The phone's alarm sound and vibration, looping until [stop]. */
class AlarmSound(private val context: Context) {
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null

    private var fallbackTone: ToneGenerator? = null

    val isPlaying: Boolean get() = player != null || fallbackTone != null

    fun start() {
        if (player != null) return
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        player = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                setDataSource(context, uri)
                isLooping = true
                prepare()
                start()
            }
        }.getOrNull()
        @Suppress("DEPRECATION")
        vibrator = (context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)?.also {
            val pattern = longArrayOf(0, 800, 600)
            if (Build.VERSION.SDK_INT >= 26) {
                it.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                it.vibrate(pattern, 0)
            }
        }
        if (player == null) {
            // No playable alarm sound: fall back to a loud repeating tone.
            fallbackTone = runCatching { ToneGenerator(AudioManager.STREAM_ALARM, 100) }.getOrNull()?.also {
                it.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK)
            }
        }
    }

    fun stop() {
        player?.let { runCatching { it.stop(); it.release() } }
        player = null
        fallbackTone?.let { runCatching { it.stopTone(); it.release() } }
        fallbackTone = null
        vibrator?.cancel()
        vibrator = null
    }
}
