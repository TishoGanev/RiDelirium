package com.bgdeveloper.ridelirium.domain

enum class MotionKind(val csv: String) {
    AUTOMOTIVE("automotive"), WALKING("walking"), CYCLING("cycling"), STATIONARY("stationary"), UNKNOWN("unknown")
}

/** [confidence]: 0 low, 1 medium, 2 high. */
data class MotionSample(val kind: MotionKind, val confidence: Int)
