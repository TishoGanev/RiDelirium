package com.bgdeveloper.snowman.domain

enum class MotionKind(val csv: String) {
    AUTOMOTIVE("automotive"), WALKING("walking"), CYCLING("cycling"), STATIONARY("stationary"), UNKNOWN("unknown")
}

/** [confidence]: 0 low, 1 medium, 2 high. */
data class MotionSample(val kind: MotionKind, val confidence: Int)

/**
 * Decides when motion activity should start or stop a recording. The recorded points are never touched;
 * stopping only ends the session. Keep in sync with `MotionAutoControl` in `MotionActivity.swift` on iOS.
 */
class MotionAutoControl(
    var autoStart: Boolean = false,
    var autoStop: Boolean = false,
    var stopAfterMillis: Long = 180_000,
) {
    enum class Action { NONE, START, STOP }

    private var idleSince: Long? = null

    /**
     * Call on every activity change and on a timer: activity APIs only report changes, so "still idle after
     * 3 minutes" has to be noticed by the clock.
     */
    fun evaluate(sample: MotionSample?, nowMillis: Long, isTracking: Boolean): Action {
        if (sample == null) return Action.NONE
        if (!isTracking) {
            idleSince = null
            return if (autoStart && sample.kind == MotionKind.AUTOMOTIVE && sample.confidence >= 1) Action.START else Action.NONE
        }
        if (!autoStop) {
            idleSince = null
            return Action.NONE
        }
        when (sample.kind) {
            MotionKind.STATIONARY, MotionKind.WALKING -> {
                val since = idleSince ?: nowMillis
                idleSince = since
                if (nowMillis - since >= stopAfterMillis) {
                    idleSince = null
                    return Action.STOP
                }
            }
            MotionKind.AUTOMOTIVE, MotionKind.CYCLING, MotionKind.UNKNOWN -> idleSince = null
        }
        return Action.NONE
    }

    fun reset() {
        idleSince = null
    }
}
