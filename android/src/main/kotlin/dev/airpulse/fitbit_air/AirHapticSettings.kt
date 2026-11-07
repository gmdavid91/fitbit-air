package dev.airpulse.fitbit_air

/**
 * `/settings` haptics bodies. Each entry is `Setting { haptics { intensity } }`;
 * intensities are 2 (low) and 3 (high); 1 means vibration is off.
 *
 * The Air pulses when the strength becomes different from the last non-off
 * strength. When a second change arrives before the first pulse plays, only
 * the second pulse is felt. [tap] therefore sends [alternate, original] in one
 * message: one pulse at the wearer's own strength, with that strength retained.
 */
internal object AirHapticSettings {
    fun write(intensity: Int): ByteArray = entries(intensity)

    fun tap(original: Int): ByteArray = entries(alternate(original), original)

    fun alternate(intensity: Int): Int {
        require(intensity == 2 || intensity == 3) { "Unsupported haptic intensity." }
        return if (intensity == 2) 3 else 2
    }

    const val OFF = 1

    /** Restores the wearer's own setting, off included (only used to put back what was read). */
    fun restore(intensity: Int): ByteArray {
        require(intensity in OFF..3) { "Unsupported haptic intensity." }
        return byteArrayOf(10, 4, 10, 2, 8, intensity.toByte())
    }

    /** The intensity in a settings reply (1 = off, 2, 3), or null for any other body. */
    fun parse(body: ByteArray): Int? {
        if (body.size != 6) return null
        if (body[0] != 10.toByte() || body[1] != 4.toByte() || body[2] != 10.toByte() || body[3] != 2.toByte() || body[4] != 8.toByte()) return null
        return body[5].toInt().takeIf { it in OFF..3 }
    }

    /** Burst steps (the last one is followed by restoring [original]). From off it alternates high and low. */
    fun burstTargets(original: Int, pulses: Int): List<Int> {
        val (a, b) = if (original == OFF) 3 to 2 else alternate(original) to original
        return (1..pulses).map { if (it % 2 == 1) a else b }
    }

    private fun entries(vararg intensities: Int): ByteArray = intensities.flatMap { value ->
        require(value == 2 || value == 3) { "Unsupported haptic intensity." }
        listOf<Byte>(10, 4, 10, 2, 8, value.toByte())
    }.toByteArray()
}
