package dev.airpulse.fitbit_air.client

/**
 * A sequence of haptics with pauses between them.
 *
 * The Air has no native patterns: each pulse is a verified settings change
 * that takes a few seconds, so a [Step.Wait] is the minimum gap, not an exact
 * rhythm. Steps use the wearer's strength and leave it unchanged.
 */
class AirPattern(val steps: List<Step>) {
    sealed interface Step {
        /** One pulse. */
        data object Tap : Step
        /** Two pulses. */
        data object DoubleTap : Step
        data class Wait(val millis: Long) : Step
    }

    class Builder {
        private val steps = mutableListOf<Step>()
        fun tap() = apply { steps += Step.Tap }
        fun doubleTap() = apply { steps += Step.DoubleTap }
        fun waitMillis(millis: Long) = apply { require(millis >= 0); steps += Step.Wait(millis) }
        fun repeat(times: Int, block: Builder.() -> Unit) = apply { kotlin.repeat(times) { block() } }
        fun build() = AirPattern(steps.toList())
    }

    companion object {
        fun build(block: Builder.() -> Unit) = Builder().apply(block).build()

        /** One pulse: start the next set. */
        val NEXT_SET = build { tap() }

        /** Two pulses: rest is over. */
        val REST_OVER = build { doubleTap() }

        /** A soft wake-up that builds: single pulses, then double pulses, 30 s apart. */
        val GENTLE_WAKE = build {
            tap(); waitMillis(30_000)
            tap(); waitMillis(30_000)
            doubleTap(); waitMillis(30_000)
            doubleTap()
        }

        /** Hard to ignore: three double pulses in a row. */
        val ALERT = build { repeat(3) { doubleTap(); waitMillis(1_000) } }
    }
}
