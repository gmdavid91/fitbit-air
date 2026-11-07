package dev.airpulse.fitbit_air

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AirHapticSettingsTest {
    @Test fun `single write matches the verified settings body`() {
        assertArrayEquals(byteArrayOf(10, 4, 10, 2, 8, 3), AirHapticSettings.write(3))
    }

    @Test fun `tap sends the alternate strength first and the original last`() {
        assertArrayEquals(byteArrayOf(10, 4, 10, 2, 8, 2, 10, 4, 10, 2, 8, 3), AirHapticSettings.tap(3))
        assertArrayEquals(byteArrayOf(10, 4, 10, 2, 8, 3, 10, 4, 10, 2, 8, 2), AirHapticSettings.tap(2))
    }

    @Test fun `off and unknown strengths are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { AirHapticSettings.tap(1) }
        assertThrows(IllegalArgumentException::class.java) { AirHapticSettings.write(4) }
    }

    @Test fun `settings replies parse to the intensity, off included`() {
        assertEquals(1, AirHapticSettings.parse(byteArrayOf(10, 4, 10, 2, 8, 1)))
        assertEquals(3, AirHapticSettings.parse(byteArrayOf(10, 4, 10, 2, 8, 3)))
        assertNull(AirHapticSettings.parse(byteArrayOf(10, 4, 10, 2, 8, 4)))
        assertNull(AirHapticSettings.parse(byteArrayOf(10, 2, 8, 3)))
    }

    @Test fun `burst from a strength alternates and ends on it`() {
        assertEquals(listOf(3, 2, 3, 2), AirHapticSettings.burstTargets(2, 4))
        assertEquals(listOf(2, 3), AirHapticSettings.burstTargets(3, 2))
    }

    @Test fun `burst from off alternates high and low, off is restored after`() {
        assertEquals(listOf(3, 2, 3, 2), AirHapticSettings.burstTargets(1, 4))
        assertArrayEquals(byteArrayOf(10, 4, 10, 2, 8, 1), AirHapticSettings.restore(1))
        assertThrows(IllegalArgumentException::class.java) { AirHapticSettings.restore(0) }
    }
}
