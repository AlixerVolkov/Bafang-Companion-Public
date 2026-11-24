package dev.alixer.bafang.protocol

/** Maintains the user's selected PAS level despite out-of-order BLE readbacks.
 * A write request is not an ACK from the bicycle; a different reported level after the
 * grace period wins and should be shown to the user. No motor parameters are changed.
 */
class AssistSelection(private val maxLevel: Int = 5, private val graceMs: Long = 3500L) {
    enum class Feedback { CONFIRMED, STALE_IGNORED, DIFFERENT_AFTER_TIMEOUT, REPORTED, INVALID }
    var level: Int = 0
        private set
    var pendingLevel: Int? = null
        private set
    private var requestedAt: Long = 0L

    @Synchronized fun request(level: Int, nowMs: Long) {
        require(level in 0..maxLevel)
        this.level = level
        pendingLevel = level
        requestedAt = nowMs
    }

    @Synchronized fun observe(level: Int, nowMs: Long): Feedback {
        if (level !in 0..maxLevel) return Feedback.INVALID
        val pending = pendingLevel
        if (pending == null) {
            this.level = level
            return Feedback.REPORTED
        }
        if (level == pending) {
            this.level = level
            pendingLevel = null
            return Feedback.CONFIRMED
        }
        if (nowMs - requestedAt < graceMs) return Feedback.STALE_IGNORED
        this.level = level
        pendingLevel = null
        return Feedback.DIFFERENT_AFTER_TIMEOUT
    }

    @Synchronized fun reset() { level = 0; pendingLevel = null; requestedAt = 0 }
}