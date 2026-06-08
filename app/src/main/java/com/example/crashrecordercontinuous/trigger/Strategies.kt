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
     * Triggers if a specific axis meets [targetValue] for [seconds] continuously.
     * [axisIndex] 0=X, 1=Y, 2=Z.
     * If [targetValue] is positive, it checks if value > [targetValue].
     * If [targetValue] is negative, it checks if value < [targetValue].
     */
    fun axisDuration(axisIndex: Int, seconds: Double, targetValue: Double): TriggerStrategy = { sample, context ->
        var increment = 0.0
        val newVars = context.variables.toMutableMap()
        val durationNs = (seconds * 1_000_000_000).toLong()

        sample.accel?.let { accel ->
            val value = accel[axisIndex]
            val isMet = if (targetValue >= 0) value > targetValue else value < targetValue
            
            if (isMet) {
                val startTime = context.variables["axis_start_time"] as? Long ?: sample.timestampNs
                newVars["axis_start_time"] = startTime
                
                val elapsed = sample.timestampNs - startTime
                val alreadyTriggered = context.variables["axis_triggered"] as? Boolean ?: false
                
                if (elapsed >= durationNs && !alreadyTriggered) {
                    increment = 1.0
                    newVars["axis_triggered"] = true
                }
            } else {
                newVars.remove("axis_start_time")
                newVars.remove("axis_triggered")
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
