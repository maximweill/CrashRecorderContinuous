package com.example.crashrecordercontinuous

import android.hardware.Sensor
import android.os.Build
import java.util.Locale

/**
 * Handles CSV formatting and file naming logic.
 */
object StorageProvider {

    /**
     * Generates a consistent filename for sensor data.
     */
    fun generateFileName(timeStamp: String, deviceName: String, sensorName: String): String {
        return "${timeStamp}_${deviceName}_${sensorName}.csv"
    }

    /**
     * Returns the CSV header including device metadata and column names.
     */
    fun getCsvHeader(sensors: DeviceSensors, sensorType: Int, sensorName: String): String {
        val sb = StringBuilder()
        sb.append("# Device: ${Build.MANUFACTURER} ${Build.MODEL}\n")
        sb.append("# Accelerometer: ${sensors.accel?.vendor} ${sensors.accel?.name}\n")
        sb.append("# Gyroscope: ${sensors.gyro?.vendor} ${sensors.gyro?.name}\n")
        sb.append("# Magnetometer: ${sensors.mag?.vendor} ${sensors.mag?.name}\n")
        sb.append("# sensor: $sensorName\n")
        
        val columns = when (sensorType) {
            Sensor.TYPE_ACCELEROMETER -> "time_ns,time_s,accelX,accelY,accelZ,batt_temp_c,triggered\n"
            Sensor.TYPE_GYROSCOPE -> "time_ns,time_s,gyroX,gyroY,gyroZ,batt_temp_c,triggered\n"
            Sensor.TYPE_MAGNETIC_FIELD -> "time_ns,time_s,magX,magY,magZ,batt_temp_c,triggered\n"
            else -> "time_ns,time_s,val0,val1,val2,batt_temp_c,triggered\n"
        }
        sb.append(columns)
        return sb.toString()
    }

    /**
     * Formats a single data row into a CSV-compliant string.
     */
    fun formatCsvLine(
        timestampNs: Long,
        timeS: Double,
        values: FloatArray,
        batteryTemp: Float,
        triggerCount: Double
    ): String {
        return String.format(
            Locale.US,
            "%d,%.6f,%.6f,%.6f,%.6f,%.1f,%.2f",
            timestampNs,
            timeS,
            values[0],
            values[1],
            values[2],
            batteryTemp,
            triggerCount
        )
    }
}
