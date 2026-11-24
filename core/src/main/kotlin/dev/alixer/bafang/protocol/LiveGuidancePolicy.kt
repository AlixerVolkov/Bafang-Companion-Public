package dev.alixer.bafang.protocol

/** Stateless, testable safety checks for streaming turn-by-turn B02NC navigation. */
object LiveGuidancePolicy {
    const val MAX_GPS_AGE_MS = 10_000L
    const val MAX_ACCURACY_M = 40.0
    const val MAX_OFF_ROUTE_M = 50.0
    const val REFRESH_MS = 2_000L

    fun usableFix(ageMs: Long, accuracyM: Double): Boolean =
        ageMs in 0L..MAX_GPS_AGE_MS &&
            accuracyM.isFinite() && accuracyM in 0.0..MAX_ACCURACY_M

    fun shouldSend(
        nowMs: Long, lastSentMs: Long, turnIndex: Int, lastTurnIndex: Int,
        force: Boolean = false,
    ): Boolean = force || lastSentMs < 0L || turnIndex != lastTurnIndex ||
        (nowMs >= lastSentMs && nowMs - lastSentMs >= REFRESH_MS)

    /** GPS jitter must never make remaining distances grow by sending backwards progress. */
    fun progress(previousM: Double, newM: Double): Double =
        if (newM.isFinite() && newM >= 0.0) maxOf(previousM, newM) else previousM

    fun arrived(remainingM: Double, offRouteM: Double): Boolean =
        remainingM.isFinite() && remainingM in 0.0..25.0 &&
            offRouteM.isFinite() && offRouteM in 0.0..20.0
}
