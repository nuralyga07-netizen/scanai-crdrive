package kz.crdrive.wear

data class LowPulseState(val active: Boolean, val newlyTriggered: Boolean)

/** Pilot warning, not a diagnosis. Requires a sustained run of fresh sensor readings. */
class LowPulseDetector {
    private var firstLowAt = 0L
    private var lastAt = 0L
    private var lowCount = 0
    private var recoveryCount = 0
    private var active = false

    fun reset() {
        firstLowAt = 0L
        lastAt = 0L
        lowCount = 0
        recoveryCount = 0
        active = false
    }

    fun accept(bpm: Int, measuredAt: Long, now: Long): LowPulseState {
        if (measuredAt <= lastAt || measuredAt > now + 5_000 || now - measuredAt > 15_000) {
            return LowPulseState(active, false)
        }
        if (lastAt != 0L && measuredAt - lastAt > 30_000) {
            active = false
            lowCount = 0
        }
        lastAt = measuredAt
        if (bpm <= 50) {
            recoveryCount = 0
            if (lowCount == 0) firstLowAt = measuredAt
            lowCount++
            val triggered = !active && lowCount >= 3 && measuredAt - firstLowAt >= 10_000
            if (triggered) active = true
            return LowPulseState(active, triggered)
        }
        lowCount = 0
        if (bpm >= 55) {
            recoveryCount++
            if (recoveryCount >= 2) active = false
        } else {
            recoveryCount = 0
        }
        return LowPulseState(active, false)
    }
}
