package com.example.crashrecordercontinuous

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.crashrecordercontinuous.trigger.*
import com.example.crashrecordercontinuous.ui.theme.CrashRecorderContinuousTheme
import kotlinx.coroutines.delay
import java.io.File
import java.util.Locale

class MainActivity : ComponentActivity() {

    private var imuService by mutableStateOf<IMURecorderService?>(null)
    private var isBound by mutableStateOf(false)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as IMURecorderService.LocalBinder
            imuService = binder.getService()
            isBound = true
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
            imuService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val intent = Intent(this, IMURecorderService::class.java)
        bindService(intent, connection, BIND_AUTO_CREATE)

        setContent {
            CrashRecorderContinuousTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    IMURecorderScreen(
                        modifier = Modifier.padding(innerPadding),
                        imuService = imuService,
                        isBound = isBound
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }
}

enum class StrategyOption(val label: String) {
    IMPACT_HIGH("Impact High (16g)"),
    IMPACT_MED("Impact Med (8g)"),
    PORTRAIT_UP("Portrait Up"),
    PORTRAIT_DOWN("Portrait Down"),
    FACE_DOWN("Face Down")
}

enum class ResponseOption(val label: String) {
    SOUND("Sound Beep"),
    FLASH("Camera Flash"),
    NONE("None")
}

@Composable
fun IMURecorderScreen(
    modifier: Modifier = Modifier,
    imuService: IMURecorderService?,
    isBound: Boolean
) {
    val context = LocalContext.current
    var isRecording by remember { mutableStateOf(false) }

    var accelEnabled by remember { mutableStateOf(true) }
    var gyroEnabled by remember { mutableStateOf(true) }
    var magEnabled by remember { mutableStateOf(true) }

    var selectedStrategy by remember { mutableStateOf(StrategyOption.IMPACT_MED) }
    var selectedResponse by remember { mutableStateOf(ResponseOption.SOUND) }

    var accelText by remember { mutableStateOf("Accel: Waiting...") }
    var gyroText by remember { mutableStateOf("Gyro: Waiting...") }
    var magText by remember { mutableStateOf("Mag: Waiting...") }
    var hzText by remember { mutableStateOf("Rate: 0.0 Hz") }
    var statusText by remember { mutableStateOf("Status: Not Connected") }
    var triggerCount by remember { mutableIntStateOf(0) }

    var isBatteryOptimized by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(context, "Permission required", Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(Unit) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        isBatteryOptimized = !pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    LaunchedEffect(isBound, imuService) {
        if (isBound && imuService != null) {
            while (true) {
                isRecording = imuService.isRecording()
                statusText = if (isRecording) "Status: RECORDING" else "Status: READY"
                triggerCount = imuService.getTriggerCount()
                
                val accel = imuService.getCurrentAccel()
                accelText = String.format(Locale.US, "Accel: X: %.3f, Y: %.3f, Z: %.3f", accel[0], accel[1], accel[2])
                
                val gyro = imuService.getCurrentGyro()
                gyroText = String.format(Locale.US, "Gyro: X: %.2f, Y: %.2f, Z: %.2f", gyro[0], gyro[1], gyro[2])

                val mag = imuService.getCurrentMag()
                magText = String.format(Locale.US, "Mag: X: %.1f, Y: %.1f, Z: %.1f", mag[0], mag[1], mag[2])

                hzText = String.format(Locale.US, "Sampling Rate: %.1f Hz", imuService.getCurrentHz())
                
                delay(500)
            }
        } else {
            statusText = "Status: Connecting to Service..."
        }
    }

    val backgroundColor = if (isRecording) Color(0xFFcb1725) else MaterialTheme.colorScheme.background

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (isBatteryOptimized) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Warning, contentDescription = "Warning", tint = MaterialTheme.colorScheme.error)
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Battery optimization is enabled. This may stop recording in the background.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        TextButton(onClick = {
                            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            context.startActivity(intent)
                        }) {
                            Text("Disable Optimization")
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = statusText, style = MaterialTheme.typography.headlineSmall)
            Text(text = "Triggers: $triggerCount", style = MaterialTheme.typography.headlineSmall)
        }
        
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(text = accelText, style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = gyroText, style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = magText, style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = hzText, style = MaterialTheme.typography.titleMedium)
            }
        }

        Text("Active Sensors", style = MaterialTheme.typography.titleMedium)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(8.dp)) {
                SensorToggle("Accelerometer", accelEnabled, onCheckedChange = { accelEnabled = it }, enabled = !isRecording)
                SensorToggle("Gyroscope", gyroEnabled, onCheckedChange = { gyroEnabled = it }, enabled = !isRecording)
                SensorToggle("Magnetometer", magEnabled, onCheckedChange = { magEnabled = it }, enabled = !isRecording)
            }
        }

        Text("Trigger Strategy", style = MaterialTheme.typography.titleMedium)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.selectableGroup().padding(8.dp)) {
                StrategyOption.entries.forEach { option ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .selectable(
                                selected = (selectedStrategy == option),
                                onClick = { selectedStrategy = option },
                                role = Role.RadioButton,
                                enabled = !isRecording
                            )
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = (selectedStrategy == option),
                            onClick = null,
                            enabled = !isRecording
                        )
                        Text(
                            text = option.label,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = 16.dp)
                        )
                    }
                }
            }
        }

        Text("Trigger Response", style = MaterialTheme.typography.titleMedium)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.selectableGroup().padding(8.dp)) {
                ResponseOption.entries.forEach { option ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .selectable(
                                selected = (selectedResponse == option),
                                onClick = { selectedResponse = option },
                                role = Role.RadioButton,
                                enabled = !isRecording
                            )
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = (selectedResponse == option),
                            onClick = null,
                            enabled = !isRecording
                        )
                        Text(
                            text = option.label,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = 16.dp)
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            return@Button
                        }
                    }

                    val enabledSensors = mutableSetOf<Int>()
                    if (accelEnabled) enabledSensors.add(Sensor.TYPE_ACCELEROMETER)
                    if (gyroEnabled) enabledSensors.add(Sensor.TYPE_GYROSCOPE)
                    if (magEnabled) enabledSensors.add(Sensor.TYPE_MAGNETIC_FIELD)

                    if (enabledSensors.isEmpty()) {
                        Toast.makeText(context, "Select at least one sensor", Toast.LENGTH_SHORT).show()
                        return@Button
                    }

                    // Update strategy and response in service
                    val strategy = when(selectedStrategy) {
                        StrategyOption.IMPACT_HIGH -> Triggers.magnitude(157.0)
                        StrategyOption.IMPACT_MED -> Triggers.magnitude(78.5)
                        StrategyOption.PORTRAIT_UP -> Triggers.axisDuration(1, 2.0, 9.0)
                        StrategyOption.PORTRAIT_DOWN -> Triggers.axisDuration(1, 2.0, -9.0)
                        StrategyOption.FACE_DOWN -> Triggers.axisDuration(2, 2.0, -9.0)
                    }
                    val response = when(selectedResponse) {
                        ResponseOption.SOUND -> Responses.sound
                        ResponseOption.FLASH -> Responses.flash
                        ResponseOption.NONE -> Responses.none
                    }
                    
                    imuService?.setTriggerStrategy(strategy)
                    imuService?.setTriggerResponse(response)

                    val intent = Intent(context, IMURecorderService::class.java)
                    context.startForegroundService(intent)
                    imuService?.startRecording(enabledSensors)
                },
                enabled = isBound && !isRecording,
                modifier = Modifier.weight(1f)
            ) {
                Text("Start")
            }

            Button(
                onClick = {
                    imuService?.stopRecording()
                },
                enabled = isBound && isRecording,
                modifier = Modifier.weight(1f)
            ) {
                Text("Stop")
            }
        }

        Button(
            onClick = {
                imuService?.incrementTrigger()
            },
            enabled = isBound,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
        ) {
            Text("Manual Trigger")
        }

        Button(
            onClick = {
                val directory = context.getExternalFilesDir(null)
                val files = directory?.listFiles { _, name -> name.endsWith(".csv") }
                if (files != null && files.isNotEmpty()) {
                    val latestFile = files.maxByOrNull { it.lastModified() }
                    latestFile?.let { shareFile(context, it) }
                } else {
                    Toast.makeText(context, "No recording found to share", Toast.LENGTH_SHORT).show()
                }
            },
            enabled = !isRecording,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Share Latest Data")
        }
    }
}

@Composable
fun SensorToggle(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label)
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

fun shareFile(context: Context, file: File) {
    val uri: Uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file
    )
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri("", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    
    val chooserIntent = Intent.createChooser(shareIntent, "Share IMU Data")
    chooserIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    context.startActivity(chooserIntent)
}
