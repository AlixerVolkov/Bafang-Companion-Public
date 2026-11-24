package dev.alixer.bafang.protocol

import kotlin.math.roundToInt

/** Distances in the navigation packet are segment distances: rider -> first turn,
 * first -> second, second -> third; not three distances measured from the rider.
 * This is based on the BIKEGO decompilation notes in the supplied PROTOCOL.md.
 */
object NavStepDistances {
    data class Turn(val progressM: Double, val maneuver: Int)

    fun nextThree(progressM: Double, upcoming: List<Turn>): List<Pair<Int, Int>> {
        var previousM = progressM
        return (0 until 3).map { i ->
            val step = upcoming.getOrNull(i)
            if (step == null) 0 to NavEncoder.ARRIVE
            else {
                val segment = (step.progressM - previousM).coerceAtLeast(0.0)
                    .coerceAtMost(0xFFFFFF.toDouble()).roundToInt()
                previousM = step.progressM
                segment to step.maneuver
            }
        }
    }
}