package dev.airpulse.airbuzz.logic

enum class SegmentKind { PREP, WORK, REST }

data class Segment(val kind: SegmentKind, val round: Int, val startMillis: Long, val durationMillis: Long) {
    val endMillis: Long get() = startMillis + durationMillis
}

enum class Preset { HIIT, EMOM, TABATA, CUSTOM }

/** A workout of [rounds] × (work + rest). The rest after the last round is skipped. */
data class IntervalConfig(
    val workSeconds: Int,
    val restSeconds: Int,
    val rounds: Int,
    val prepSeconds: Int = 10,
) {
    init {
        require(workSeconds in 5..3600) { "work must be 5 s to 60 min" }
        require(restSeconds in 0..3600) { "rest must be 0 s to 60 min" }
        require(rounds in 1..99) { "rounds must be 1-99" }
        require(prepSeconds in 0..120)
    }

    fun segments(): List<Segment> {
        val list = mutableListOf<Segment>()
        var t = 0L
        if (prepSeconds > 0) {
            list += Segment(SegmentKind.PREP, 0, t, prepSeconds * 1000L); t += prepSeconds * 1000L
        }
        for (round in 1..rounds) {
            list += Segment(SegmentKind.WORK, round, t, workSeconds * 1000L); t += workSeconds * 1000L
            if (restSeconds > 0 && round < rounds) {
                list += Segment(SegmentKind.REST, round, t, restSeconds * 1000L); t += restSeconds * 1000L
            }
        }
        return list
    }

    val totalMillis: Long get() = segments().last().endMillis

    companion object {
        fun preset(preset: Preset): IntervalConfig? = when (preset) {
            Preset.HIIT -> IntervalConfig(40, 20, 8)
            Preset.EMOM -> IntervalConfig(60, 0, 10)
            Preset.TABATA -> IntervalConfig(20, 10, 8)
            Preset.CUSTOM -> null
        }
    }
}

enum class CueChannel { WRIST, PHONE }

/**
 * One signal of a workout. [atMillis] is when the segment starts (the user
 * should feel it then); [sendAtMillis] is when the command is sent, earlier by
 * the wrist latency. Times are relative to the workout start.
 */
data class Cue(
    val atMillis: Long,
    val sendAtMillis: Long,
    val signal: WristSignal,
    val channel: CueChannel,
    /** Segment the cue starts, or `segments.size` for the final DONE. */
    val segmentIndex: Int,
)

/**
 * Decides which cues go to the wrist and which to the phone.
 *
 * The Air runs one command at a time and each takes [leadMillis] to arrive,
 * plus the burst itself, plus [overheadMillis] to restore the setting. A cue
 * goes to the phone when its segment is shorter than [minWristSegmentMillis],
 * when it cannot be sent early enough, or when the wrist is still busy.
 */
object CuePlanner {
    const val MIN_WRIST_SEGMENT_MILLIS = 15_000L
    const val OVERHEAD_MILLIS = 2_000L

    fun plan(
        segments: List<Segment>,
        leadMillis: Long,
        minWristSegmentMillis: Long = MIN_WRIST_SEGMENT_MILLIS,
        overheadMillis: Long = OVERHEAD_MILLIS,
    ): List<Cue> {
        val cues = mutableListOf<Cue>()
        var wristBusyUntil = Long.MIN_VALUE
        fun add(at: Long, signal: WristSignal, index: Int, longEnough: Boolean) {
            val sendAt = at - leadMillis
            val wrist = longEnough && sendAt >= 0 && sendAt >= wristBusyUntil
            if (wrist) wristBusyUntil = sendAt + leadMillis + signal.durationMillis + overheadMillis
            cues += Cue(at, if (wrist) sendAt else at, signal, if (wrist) CueChannel.WRIST else CueChannel.PHONE, index)
        }
        segments.forEachIndexed { index, segment ->
            val signal = when (segment.kind) {
                SegmentKind.PREP -> return@forEachIndexed
                SegmentKind.WORK -> WristSignal.GO
                SegmentKind.REST -> WristSignal.REST
            }
            add(segment.startMillis, signal, index, segment.durationMillis >= minWristSegmentMillis)
        }
        if (segments.isNotEmpty()) add(segments.last().endMillis, WristSignal.DONE, segments.size, true)
        return cues
    }

    /** Kinds of segment too short for the wrist, to warn before starting. */
    fun shortKinds(segments: List<Segment>, minWristSegmentMillis: Long = MIN_WRIST_SEGMENT_MILLIS): Set<SegmentKind> =
        segments.filter { it.kind != SegmentKind.PREP && it.durationMillis < minWristSegmentMillis }.map { it.kind }.toSet()

    /** Index of the segment running at [elapsedMillis], or `segments.size` when finished. */
    fun segmentAt(segments: List<Segment>, elapsedMillis: Long): Int {
        val index = segments.indexOfFirst { elapsedMillis < it.endMillis }
        return if (index < 0) segments.size else index
    }
}
