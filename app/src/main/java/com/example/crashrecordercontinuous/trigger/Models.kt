package com.example.crashrecordercontinuous.trigger

import android.content.Context

/**
 * Data model for a single multi-sensor snapshot.
 */
data class SensorSample(
    val accel: FloatArray? = null,
    val gyro: FloatArray? = null,
    val mag: FloatArray? = null,
    val timestampNs: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as SensorSample

        if (accel != null) {
            if (other.accel == null) return false
            if (!accel.contentEquals(other.accel)) return false
        } else if (other.accel != null) return false
        if (gyro != null) {
            if (other.gyro == null) return false
            if (!gyro.contentEquals(other.gyro)) return false
        } else if (other.gyro != null) return false
        if (mag != null) {
            if (other.mag == null) return false
            if (!mag.contentEquals(other.mag)) return false
        } else if (other.mag != null) return false
        if (timestampNs != other.timestampNs) return false

        return true
    }

    override fun hashCode(): Int {
        var result = accel?.contentHashCode() ?: 0
        result = 31 * result + (gyro?.contentHashCode() ?: 0)
        result = 31 * result + (mag?.contentHashCode() ?: 0)
        result = 31 * result + timestampNs.hashCode()
        return result
    }
}

/**
 * Immutable context for tracking state across sensor samples.
 * [variables] stores arbitrary state (e.g., timestamps for duration checks).
 */
data class TriggerContext(
    val variables: Map<String, Any> = emptyMap(),
    val lastUpdateTimeNs: Long = 0L
)

/**
 * Result of a trigger evaluation.
 */
data class TriggerResult(
    val triggerIncrement: Double,
    val newContext: TriggerContext
)

/**
 * Functional type aliases for modular trigger logic and responses.
 */
typealias TriggerStrategy = (SensorSample, TriggerContext) -> TriggerResult
typealias TriggerResponse = (Context) -> Unit
