package com.example.crashrecordercontinuous

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.hardware.*
import android.os.*
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.example.crashrecordercontinuous.trigger.*
import kotlinx.coroutines.*
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

class IMURecorderService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private lateinit var sensors: DeviceSensors
    private var wakeLock: PowerManager.WakeLock? = null

    private var isRecording = false
    private val sensorWriters = mutableMapOf<Int, BufferedWriter>()
    private val dataQueues = mutableMapOf<Int, ConcurrentLinkedQueue<String>>()
    private var recordingJob: Job? = null
    private var batteryJob: Job? = null

    private var currentBatteryTemp = 0f
    private val recordingStartTimeNs = mutableMapOf<Int, Long>()

    // Sampling rate calculation (UI only, using accelerometer as proxy or average)
    private var sampleCount = 0
    private var lastHzCalcTime = 0L
    private var currentHz = 0.0

    // Latest values for storage and UI
    private var lastAccel = FloatArray(3)
    private var lastGyro = FloatArray(3)
    private var lastMag = FloatArray(3)

    // Trigger logic
    private var triggerCount = 0.0
    private var triggerContext = TriggerContext()
    private var activeStrategy: TriggerStrategy = Triggers.magnitude(78.5)
    private var activeResponse: TriggerResponse = Responses.sound

    private val sensorListeners = mutableMapOf<Int, SensorEventListener>()

    inner class LocalBinder : Binder() {
        fun getService(): IMURecorderService = this@IMURecorderService
    }

    override fun onBind(intent: Intent): IBinder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        sensors = SensorProvider.provideSensors(this)

        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "IMURecorder:WakeLock")
        
        // Register listeners for UI updates immediately
        registerUIListeners()
    }

    override fun onSensorChanged(event: SensorEvent) {
        updateLastValues(event)
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            sampleCount++
            val now = SystemClock.elapsedRealtime()
            if (now - lastHzCalcTime >= 1000) {
                currentHz = sampleCount.toDouble() / ((now - lastHzCalcTime) / 1000.0)
                sampleCount = 0
                lastHzCalcTime = now
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun registerUIListeners() {
        sensorManager.registerListener(this, sensors.accel, SensorManager.SENSOR_DELAY_UI)
        sensorManager.registerListener(this, sensors.gyro, SensorManager.SENSOR_DELAY_UI)
        sensorManager.registerListener(this, sensors.mag, SensorManager.SENSOR_DELAY_UI)
    }

    private fun updateLastValues(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> System.arraycopy(event.values, 0, lastAccel, 0, 3)
            Sensor.TYPE_GYROSCOPE -> System.arraycopy(event.values, 0, lastGyro, 0, 3)
            Sensor.TYPE_MAGNETIC_FIELD -> System.arraycopy(event.values, 0, lastMag, 0, 3)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundServiceInternal()
        return START_STICKY
    }

    private fun startForegroundServiceInternal() {
        val channelId = "IMU_RECORDER_CHANNEL"
        val channel = NotificationChannel(channelId, "IMU Recorder", NotificationManager.IMPORTANCE_LOW)
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Crash Recorder Active")
            .setContentText(if (isRecording) "Recording sensor data..." else "Ready")
            .setSmallIcon(android.R.drawable.ic_menu_save)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification)
        }
    }

    fun startRecording(enabledSensors: Set<Int>) {
        if (isRecording) return
        
        sensorManager.unregisterListener(this) // Unregister UI listeners
        
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val deviceName = Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME) ?: "Phone000"
        
        recordingStartTimeNs.clear()
        triggerCount = 0.0
        triggerContext = TriggerContext()
        
        sensors.getEnabledSensors(enabledSensors).forEach { sensor ->
            val sensorType = sensor.type
            val sensorName = SensorProvider.getSensorName(sensorType)
            
            val fileName = StorageProvider.generateFileName(timeStamp, deviceName, sensorName)
            val outputFile = File(getExternalFilesDir(null), fileName)
            
            try {
                val writer = BufferedWriter(FileWriter(outputFile, true))
                writer.write(StorageProvider.getCsvHeader(sensors, sensorType, sensorName))
                writer.flush()
                sensorWriters[sensorType] = writer
                dataQueues[sensorType] = ConcurrentLinkedQueue<String>()
            } catch (e: Exception) { e.printStackTrace() }

            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    updateLastValues(event)
                    if (event.sensor.type != sensorType) return
                    
                    // Evaluate trigger logic
                    val sample = SensorSample(
                        accel = if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) event.values.clone() else lastAccel.clone(),
                        gyro = if (event.sensor.type == Sensor.TYPE_GYROSCOPE) event.values.clone() else lastGyro.clone(),
                        mag = if (event.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) event.values.clone() else lastMag.clone(),
                        timestampNs = event.timestamp
                    )
                    
                    val result = activeStrategy(sample, triggerContext)
                    if (result.triggerIncrement > 0) {
                        triggerCount += result.triggerIncrement
                        activeResponse(this@IMURecorderService)
                    }
                    triggerContext = result.newContext

                    if (recordingStartTimeNs[sensorType] == null) recordingStartTimeNs[sensorType] = event.timestamp
                    val startTime = recordingStartTimeNs[sensorType] ?: event.timestamp
                    val timeS = (event.timestamp - startTime) / 1_000_000_000.0
                    
                    val data = StorageProvider.formatCsvLine(
                        event.timestamp,
                        timeS,
                        event.values,
                        currentBatteryTemp,
                        triggerCount
                    )
                    
                    dataQueues[sensorType]?.offer(data)
                }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            }
            sensorListeners[sensorType] = listener
            sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_FASTEST)
        }

        isRecording = true
        wakeLock?.acquire()
        startForegroundServiceInternal()

        recordingJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive && isRecording) {
                try {
                    sensorWriters.forEach { (type, writer) ->
                        val queue = dataQueues[type] ?: return@forEach
                        var count = 0
                        while (queue.isNotEmpty()) {
                            writer.write(queue.poll() ?: "")
                            writer.newLine()
                            if (++count > 1000) break
                        }
                        if (count > 0) writer.flush()
                    }
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
    }

    fun stopRecording() {
        if (!isRecording) return
        isRecording = false
        
        sensorListeners.values.forEach { sensorManager.unregisterListener(it) }
        sensorListeners.clear()
        
        // Register UI listeners again
        registerUIListeners()
        
        if (wakeLock?.isHeld == true) wakeLock?.release()
        recordingJob?.cancel()
        batteryJob?.cancel()
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                sensorWriters.forEach { (type, writer) ->
                    val queue = dataQueues[type] ?: return@forEach
                    while (queue.isNotEmpty()) {
                        writer.write(queue.poll() ?: "")
                        writer.newLine()
                    }
                    writer.flush()
                    writer.close()
                }
                sensorWriters.clear()
                dataQueues.clear()
            } catch (e: Exception) { e.printStackTrace() }
        }
        startForegroundServiceInternal()
    }

    fun setTriggerStrategy(strategy: TriggerStrategy) {
        activeStrategy = strategy
    }

    fun setTriggerResponse(response: TriggerResponse) {
        activeResponse = response
    }

    fun incrementTrigger() {
        triggerCount += 1.0
        activeResponse(this)
    }

    private fun updateBatteryTemp() {
        val batteryStatus: Intent? = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        currentBatteryTemp = (batteryStatus?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0f
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorManager.unregisterListener(this)
        sensorListeners.values.forEach { sensorManager.unregisterListener(it) }
        if (wakeLock?.isHeld == true) wakeLock?.release()
        recordingJob?.cancel()
        batteryJob?.cancel()
    }

    fun getCurrentAccel() = lastAccel
    fun getCurrentGyro() = lastGyro
    fun getCurrentMag() = lastMag
    fun getCurrentHz() = currentHz
    fun isRecording() = isRecording
    fun getTriggerCount() = triggerCount.toInt()
}
