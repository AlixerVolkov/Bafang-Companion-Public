package dev.alixer.bafang.protocol

import org.junit.Assert.*
import org.junit.Test

class NavStepDistancesTest {
    @Test fun turnDistancesAreSegmentRelative() {
        val t = listOf(NavStepDistances.Turn(250.0, NavEncoder.RIGHT),
            NavStepDistances.Turn(600.0, NavEncoder.LEFT),
            NavStepDistances.Turn(1350.0, NavEncoder.STRAIGHT))
        assertEquals(listOf(150 to NavEncoder.RIGHT,350 to NavEncoder.LEFT,
            750 to NavEncoder.STRAIGHT), NavStepDistances.nextThree(100.0,t))
    }
    @Test fun remainingManeuversArePaddedAsArrival() {
        assertEquals(listOf(40 to NavEncoder.LEFT, 0 to NavEncoder.ARRIVE, 0 to NavEncoder.ARRIVE),
            NavStepDistances.nextThree(100.0,listOf(NavStepDistances.Turn(140.0,NavEncoder.LEFT))))
    }
    @Test fun noFurtherTurnMeansArrival() {
        assertEquals(List(3){0 to NavEncoder.ARRIVE},NavStepDistances.nextThree(99.0,emptyList()))
    }
    @Test fun distancesClampToProtocolMaximum() {
        assertEquals(0xFFFFFF,NavStepDistances.nextThree(0.0,
            listOf(NavStepDistances.Turn(20_000_000.0,NavEncoder.RIGHT)))[0].first)
    }
    @Test fun missedTurnCannotBecomeNegativeDistance() {
        assertEquals(0,NavStepDistances.nextThree(100.0,
            listOf(NavStepDistances.Turn(98.0,NavEncoder.RIGHT)))[0].first)
    }
}