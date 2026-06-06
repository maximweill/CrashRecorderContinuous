package com.example.crashrecordercontinuous

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager

/**
 * Immutable data class holding the device's primary IMU sensors.
 */
data class DeviceSensors(
    val accel: Sensor? = null,
    val gyro: Sensor? = null,
    val mag: Sensor? = null
) {
    /**
     * Maps a set of sensor type IDs to the actual [Sensor] objects available in this container.
     */
    fun getEnabledSensors(types: Set<Int>): List<Sensor> {
        return types.mapNotNull { type ->
            when (type) {
                Sensor.TYPE_ACCELEROMETER -> accel
                Sensor.TYPE_GYROSCOPE -> gyro
                Sensor.TYPE_MAGNETIC_FIELD -> mag
                else -> null
            }
        }
    }
}

object SensorProvider {
    /**
     * Discovers and returns the primary IMU sensors available on the device.
     */
    fun provideSensors(context: Context): DeviceSensors {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        return DeviceSensors(
            accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
            gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE),
            mag = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        )
    }

    /**
     * Returns a string label for the given sensor type.
     */
    fun getSensorName(sensorType: Int): String {
        return when (sensorType) {
            Sensor.TYPE_ACCELEROMETER -> "accelerometer"
            Sensor.TYPE_GYROSCOPE -> "gyroscope"
            Sensor.TYPE_MAGNETIC_FIELD -> "magnetometer"
            else -> "unknown"
        }
    }
}
