package dev.airpulse.airbuzz.logic

import kotlin.math.roundToInt

/** Five zones as a share of max heart rate: 50-60, 60-70, 70-80, 80-90, 90-100 %. */
object HeartZones {
    private val bounds = doubleArrayOf(0.5, 0.6, 0.7, 0.8, 0.9, 1.0)

    fun maxFromAge(age: Int): Int = 220 - age

    /** Lower and upper bpm of [zone] (1-5), inclusive. */
    fun range(zone: Int, maxHr: Int): IntRange {
        require(zone in 1..5)
        val low = (maxHr * bounds[zone - 1]).roundToInt()
        val high = (maxHr * bounds[zone]).roundToInt() - if (zone < 5) 1 else 0
        return low..high
    }
}

enum class ZoneStatus { NO_DATA, BELOW, IN, ABOVE }

/**
 * Turns 1 Hz heart-rate samples into wrist alerts for a target range.
 *
 * Uses the average of the last [smoothingMillis]; alerts only after being out
 * of range for [sustainMillis], then at most once per [cooldownMillis]. No
 * "below" alerts during the first [warmupMillis] while the heart rate climbs.
 */
class ZoneCoach(
    val low: Int,
    val high: Int,
    private val sustainMillis: Long = 10_000,
    private val cooldownMillis: Long = 45_000,
    private val warmupMillis: Long = 60_000,
    private val smoothingMillis: Long = 5_000,
) {
    init { require(low in 30..250 && high in low..250) }

    var status: ZoneStatus = ZoneStatus.NO_DATA; private set
    var smoothedBpm: Int? = null; private set
    var timeInZoneMillis: Long = 0; private set

    private val recent = ArrayDeque<Pair<Long, Int>>()
    private var startedAt: Long? = null
    private var outSince: Long? = null
    private var lastAlertAt: Long? = null
    private var lastSampleAt: Long? = null

    /** Feeds one sample; returns the signal to send now, if any. */
    fun onSample(bpm: Int, nowMillis: Long): WristSignal? {
        if (startedAt == null) startedAt = nowMillis
        val previousAt = lastSampleAt
        if (previousAt != null && status == ZoneStatus.IN) {
            timeInZoneMillis += (nowMillis - previousAt).coerceIn(0, 5_000)
        }
        lastSampleAt = nowMillis
        if (bpm <= 0) return null
        recent.addLast(nowMillis to bpm)
        while (recent.first().first < nowMillis - smoothingMillis) recent.removeFirst()
        val average = recent.sumOf { it.second }.toDouble() / recent.size
        smoothedBpm = average.roundToInt()
        val newStatus = when {
            average < low -> ZoneStatus.BELOW
            average > high -> ZoneStatus.ABOVE
            else -> ZoneStatus.IN
        }
        if (newStatus != status) outSince = if (newStatus == ZoneStatus.IN) null else nowMillis
        status = newStatus
        val since = outSince ?: return null
        if (nowMillis - since < sustainMillis) return null
        if (status == ZoneStatus.BELOW && nowMillis - startedAt!! < warmupMillis) return null
        val last = lastAlertAt
        if (last != null && nowMillis - last < cooldownMillis) return null
        lastAlertAt = nowMillis
        return if (status == ZoneStatus.BELOW) WristSignal.BELOW else WristSignal.ABOVE
    }

    /** Marks the data as stale (no samples), so time in zone stops counting. */
    fun onNoData() {
        status = ZoneStatus.NO_DATA
        outSince = null
        lastSampleAt = null
        recent.clear()
        smoothedBpm = null
    }
}
