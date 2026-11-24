package dev.alixer.bafang.protocol

import org.junit.Assert.*
import org.junit.Test

class LiveGuidancePolicyTest {
    @Test fun gpsMustBeFreshAndAccurate() {
        assertTrue(LiveGuidancePolicy.usableFix(0L, 4.0))
        assertTrue(LiveGuidancePolicy.usableFix(9_000L, 35.0))
        assertFalse(LiveGuidancePolicy.usableFix(-1L, 4.0))
        assertFalse(LiveGuidancePolicy.usableFix(10_001L, 4.0))
        assertFalse(LiveGuidancePolicy.usableFix(400L, 43.0))
        assertFalse(LiveGuidancePolicy.usableFix(400L, Double.NaN))
    }

    @Test fun publishImmediatelyAtStartAndTurnChangeButNotEveryHalfSecond() {
        assertTrue(LiveGuidancePolicy.shouldSend(1000, -1, 0, -1))
        assertFalse(LiveGuidancePolicy.shouldSend(1500, 1000, 0, 0))
        assertTrue(LiveGuidancePolicy.shouldSend(1510, 1000, 1, 0))
        assertTrue(LiveGuidancePolicy.shouldSend(3000, 1000, 0, 0))
        assertTrue(LiveGuidancePolicy.shouldSend(1510, 1000, 0, 0, force = true))
    }

    @Test fun progressNeverMovesBackwardOnGpsJitter() {
        assertEquals(400.0, LiveGuidancePolicy.progress(400.0, 380.0), 0.001)
        assertEquals(420.0, LiveGuidancePolicy.progress(400.0, 420.0), 0.001)
        assertEquals(400.0, LiveGuidancePolicy.progress(400.0, Double.NaN), 0.001)
    }

    @Test fun arrivalRequiresClosenessToRouteAndDestination() {
        assertTrue(LiveGuidancePolicy.arrived(12.0, 6.0))
        assertFalse(LiveGuidancePolicy.arrived(90.0, 2.0))
        assertFalse(LiveGuidancePolicy.arrived(10.0, 45.0))
    }

    @Test fun successiveTurnPacketsChangeDistancesAndArrowCode() {
        val first=NavEncoder.frame(0, 180, NavEncoder.RIGHT, 400, NavEncoder.LEFT,
            750, NavEncoder.STRAIGHT, 1550)
        val second=NavEncoder.frame(1, 95, NavEncoder.RIGHT, 400, NavEncoder.LEFT,
            750, NavEncoder.STRAIGHT, 1465)
        val after=NavEncoder.frame(2, 385, NavEncoder.LEFT, 750, NavEncoder.STRAIGHT,
            0, NavEncoder.ARRIVE, 1190)
        assertFalse(first.contentEquals(second))
        assertFalse(second.contentEquals(after))
        assertEquals(0x55, first[0].toInt() and 0xff)
        assertEquals(0xAA, first[1].toInt() and 0xff)
        assertEquals(0xF1, first[4].toInt() and 0xff)
        assertEquals(0x03, first[5].toInt() and 0xff)
    }
}
