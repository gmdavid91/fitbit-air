package dev.airpulse.airbuzz.logic

/**
 * The buzz vocabulary. Every signal is one `burst` command: an even number of
 * pulses (2-30) with a fixed gap, at most 30 s in total. Lengths and rhythms
 * differ so they can be told apart without looking.
 */
enum class WristSignal(val pulses: Int, val gapMillis: Long) {
    /** Work starts: long and fast. */
    GO(12, 300),
    /** Rest starts: short and slow. */
    REST(6, 800),
    /** Workout finished: very long. */
    DONE(20, 400),
    /** Heart rate below the zone: short ("push"). */
    BELOW(4, 600),
    /** Heart rate above the zone: long ("ease off"). */
    ABOVE(16, 400),
    /** Alarm. */
    WAKE(20, 500);

    val durationMillis: Long get() = pulses * gapMillis

    init {
        require(pulses in 2..30 && pulses % 2 == 0)
        require(gapMillis in 300..3000)
        require(pulses * gapMillis <= 30_000)
    }
}
