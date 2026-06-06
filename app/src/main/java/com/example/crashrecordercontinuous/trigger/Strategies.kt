package com.example.crashrecordercontinuous.trigger

import kotlin.math.sqrt

/**
 * Factory for pure, stateless trigger strategies.
 */
object Triggers {
    /**
     * Triggers if the Euclidean magnitude of acceleration exceeds [threshold].
     * Includes a 1-second buffer to prevent multiple triggers in rapid succession.
     */
    fun magnitude(threshold: Double): TriggerStrategy = { sample, context ->
        var increment = 0.0
        val newVars = context.variables.toMutableMap()
        val bufferNs = 1_000_000_000L
        val lastTriggerTime = context.variables["last_mag_trigger"] as? Long ?: 0L

        sample.accel?.let { accel ->
            val x = accel[0]
            val y = accel[1]
            val z = accel[2]
            val mag = sqrt(x * x + y * y + z * z)
            
            if (mag > threshold && (sample.timestampNs - lastTriggerTime) >= bufferNs) {
                increment = 1.0
                newVars["last_mag_trigger"] = sample.timestampNs
            }
        }
        
        TriggerResult(
            triggerIncrement = increment,
            newContext = context.copy(
                variables = newVars.toMap(),
                lastUpdateTimeNs = sample.timestampNs
            )
        )
    }

    /**
     * Triggers if the device is held vertically (Y-axis > ~9.0 m/s^2) for [seconds] continuously.
     * Uses [TriggerContext.variables] to track state without mutable member variables.
     */
    fun yAxisDuration(seconds: Double): TriggerStrategy = { sample, context ->
        var increment = 0.0
        val newVars = context.variables.toMutableMap()
        val thresholdY = 9.0
        val durationNs = (seconds * 1_000_000_000).toLong()

        sample.accel?.let { accel ->
            val y = accel[1]
            if (y > thresholdY) {
                val startTime = context.variables["y_start_time"] as? Long ?: sample.timestampNs
                newVars["y_start_time"] = startTime
                
                val elapsed = sample.timestampNs - startTime
                val alreadyTriggered = context.variables["y_triggered"] as? Boolean ?: false
                
                if (elapsed >= durationNs && !alreadyTriggered) {
                    increment = 1.0
                    newVars["y_triggered"] = true
                }
            } else {
                // Reset tracking when phone is no longer vertical
                newVars.remove("y_start_time")
                newVars.remove("y_triggered")
            }
        }

        TriggerResult(
            triggerIncrement = increment,
            newContext = TriggerContext(
                variables = newVars.toMap(),
                lastUpdateTimeNs = sample.timestampNs
            )
        )
    }
}
