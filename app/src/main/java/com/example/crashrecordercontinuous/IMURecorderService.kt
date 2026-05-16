package com.example.crashrecordercontinuous

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.hardware.*
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.*
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs
import kotlin.math.sqrt

class IMURecorderService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var accelSensor: Sensor? = null
    private var gyroSensor: Sensor? = null
    private var magSensor: Sensor? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var isRecording = false
    private var fileName: String = ""
    private var outputFile: File? = null
    private var writer: BufferedWriter? = null

    private val dataQueue = ConcurrentLinkedQueue<String>()
    private var recordingJob: Job? = null
    private var batteryJob: Job? = null

    private var currentBatteryTemp = 0f
    private var recordingStartTimeNs = -1L

    // Sampling rate calculation
    private var sampleCount = 0
    private var lastHzCalcTime = 0L
    private var currentHz = 0.0

    // Latest values for storage and UI
    private var lastAccel = FloatArray(3)
    private var lastGyro = FloatArray(3)
    private var lastMag = FloatArray(3)

    // Trigger logic
    private var triggerCount = 0
    private var conditionStartTime: Long = 0
    private val conditionDurationThreshold = 30000L // 30 seconds
    private var isConditionMet = false

    inner class LocalBinder : Binder() {
        fun getService(): IMURecorderService = this@IMURecorderService
    }

    override fun onBind(intent: Intent): IBinder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        magSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "IMURecorder:WakeLock")
        
        registerAllListeners(SensorManager.SENSOR_DELAY_UI)
    }

    private fun registerAllListeners(delay: Int) {
        sensorManager.registerListener(this, accelSensor, delay)
        sensorManager.registerListener(this, gyroSensor, delay)
        sensorManager.registerListener(this, magSensor, delay)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundServiceInternal()
        return START_STICKY
    }

    private fun startForegroundServiceInternal() {
        val channelId = "IMU_RECORDER_CHANNEL"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "IMU Recorder", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("IMU Recorder Active")
            .setContentText(if (isRecording) "Recording IMU to $fileName" else "Ready")
            .setSmallIcon(android.R.drawable.ic_menu_save)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification)
        }
    }

    fun startRecording(customName: String): String {
        if (isRecording) return fileName
        
        sensorManager.unregisterListener(this)
        registerAllListeners(SensorManager.SENSOR_DELAY_FASTEST)

        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val nameSuffix = if (customName.isNotEmpty()) "_$customName" else ""
        fileName = "continuous_${timeStamp}${nameSuffix}.csv"
        
        outputFile = File(getExternalFilesDir(null), fileName)
        
        try {
            writer = BufferedWriter(FileWriter(outputFile, true))
            writer?.write("# Device: ${Build.MANUFACTURER} ${Build.MODEL}\n")
            writer?.write("# Accelerometer: ${accelSensor?.vendor} ${accelSensor?.name}\n")
            writer?.write("# Gyroscope: ${gyroSensor?.vendor} ${gyroSensor?.name}\n")
            writer?.write("# Magnetometer: ${magSensor?.vendor} ${magSensor?.name}\n")
            writer?.write("time_ns,time_s,accelX_g,accelY_g,accelZ_g,accelMag_g,gyroX_dps,gyroY_dps,gyroZ_dps,gyroMag_dps,magX_uT,magY_uT,magZ_uT,magMag_uT,batt_temp_c,triggered\n")
            writer?.flush()
        } catch (e: Exception) { e.printStackTrace() }

        recordingStartTimeNs = -1L
        sampleCount = 0
        lastHzCalcTime = SystemClock.elapsedRealtime()
        currentHz = 0.0
        triggerCount = 0
        conditionStartTime = 0
        isConditionMet = false
        isRecording = true
        wakeLock?.acquire()
        startForegroundServiceInternal()

        recordingJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive && isRecording) {
                try {
                    var count = 0
                    while (dataQueue.isNotEmpty()) {
                        writer?.write(dataQueue.poll() ?: "")
                        writer?.newLine()
                        if (++count > 1000) break
                    }
                    if (count > 0) writer?.flush()
                } catch (e: Exception) { e.printStackTrace() }
                delay(500)
            }
        }

        batteryJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive && isRecording) {
                updateBatteryTemp()
                delay(5000)
            }
        }
        return fileName
    }

    fun stopRecording() {
        if (!isRecording) return
        isRecording = false
        sensorManager.unregisterListener(this)
        registerAllListeners(SensorManager.SENSOR_DELAY_UI)
        
        if (wakeLock?.isHeld == true) wakeLock?.release()
        recordingJob?.cancel()
        batteryJob?.cancel()
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                while (dataQueue.isNotEmpty()) {
                    writer?.write(dataQueue.poll() ?: "")
                    writer?.newLine()
                }
                writer?.flush(); writer?.close(); writer = null
            } catch (e: Exception) { e.printStackTrace() }
        }
        startForegroundServiceInternal()
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                lastAccel[0] = event.values[0] / 9.81f
                lastAccel[1] = event.values[1] / 9.81f
                lastAccel[2] = event.values[2] / 9.81f
                
                // Rate calculation
                sampleCount++
                val now = SystemClock.elapsedRealtime()
                if (now - lastHzCalcTime >= 1000) {
                    currentHz = sampleCount.toDouble() / ((now - lastHzCalcTime) / 1000.0)
                    sampleCount = 0
                    lastHzCalcTime = now
                }

                val am = sqrt(lastAccel[0]*lastAccel[0] + lastAccel[1]*lastAccel[1] + lastAccel[2]*lastAccel[2])
                
                // Stand upright condition: Mag ~ 1g AND -accelY ~ 1g
                val currentConditionMet = abs(am - 1.0f) < 0.15f && abs(-lastAccel[1] - 1.0f) < 0.15f
                
                if (currentConditionMet) {
                    if (!isConditionMet) {
                        isConditionMet = true
                        conditionStartTime = now
                    } else if (now - conditionStartTime > conditionDurationThreshold) {
                        incrementTrigger()
                        conditionStartTime = now // Reset to avoid continuous triggering
                    }
                } else {
                    isConditionMet = false
                }
                
                if (isRecording) {
                    if (recordingStartTimeNs == -1L) recordingStartTimeNs = event.timestamp
                    val timeS = (event.timestamp - recordingStartTimeNs) / 1_000_000_000.0
                    val gm = sqrt(lastGyro[0]*lastGyro[0] + lastGyro[1]*lastGyro[1] + lastGyro[2]*lastGyro[2])
                    val mm = sqrt(lastMag[0]*lastMag[0] + lastMag[1]*lastMag[1] + lastMag[2]*lastMag[2])

                    dataQueue.offer(String.format(Locale.US, "%d,%.3f,%.4f,%.4f,%.4f,%.4f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.1f,%d",
                        event.timestamp, timeS, lastAccel[0], lastAccel[1], lastAccel[2], am,
                        lastGyro[0], lastGyro[1], lastGyro[2], gm, lastMag[0], lastMag[1], lastMag[2], mm, currentBatteryTemp, triggerCount))
                }
            }
            Sensor.TYPE_GYROSCOPE -> {
                lastGyro[0] = event.values[0] * 57.2958f
                lastGyro[1] = event.values[1] * 57.2958f
                lastGyro[2] = event.values[2] * 57.2958f
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                lastMag[0] = event.values[0]; lastMag[1] = event.values[1]; lastMag[2] = event.values[2]
            }
        }
    }

    fun incrementTrigger() {
        triggerCount++
        playTriggerFeedback()
    }

    private fun playTriggerFeedback() {
        // Sound
        val toneGen = ToneGenerator(AudioManager.STREAM_ALARM, 100)
        toneGen.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 200)

        // Flashlight
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val cameraId = cameraManager.cameraIdList[0]
                cameraManager.setTorchMode(cameraId, true)
                delay(200)
                cameraManager.setTorchMode(cameraId, false)
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun updateBatteryTemp() {
        val batteryStatus: Intent? = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        currentBatteryTemp = (batteryStatus?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0f
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    fun getCurrentAccel() = lastAccel
    fun getCurrentGyro() = lastGyro
    fun getCurrentMag() = lastMag
    fun getCurrentHz() = currentHz
    fun isRecording() = isRecording
    fun getOutputFile() = outputFile
    fun getTriggerCount() = triggerCount
}
