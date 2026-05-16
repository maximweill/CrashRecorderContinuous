package com.example.crashrecordercontinuous

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
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
        bindService(intent, connection, Context.BIND_AUTO_CREATE)

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

@Composable
fun IMURecorderScreen(
    modifier: Modifier = Modifier,
    imuService: IMURecorderService?,
    isBound: Boolean
) {
    val context = LocalContext.current
    val deviceName = remember {
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) ?: "Phone000"
    }
    var isRecording by remember { mutableStateOf(false) }
    var fileNameInput by remember { mutableStateOf(deviceName) }

    var accelText by remember { mutableStateOf("Accel: Waiting...") }
    var gyroText by remember { mutableStateOf("Gyro: Waiting...") }
    var magText by remember { mutableStateOf("Mag: Waiting...") }
    var hzText by remember { mutableStateOf("Rate: 0.0 Hz") }
    var statusText by remember { mutableStateOf("Status: Not Connected") }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(context, "Notification permission required", Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(isBound, imuService) {
        if (isBound && imuService != null) {
            while (true) {
                isRecording = imuService.isRecording()
                statusText = if (isRecording) "Status: RECORDING" else "Status: READY"
                
                val accel = imuService.getCurrentAccel()
                accelText = String.format(Locale.US, "Accel (g): X: %.3f, Y: %.3f, Z: %.3f", accel[0], accel[1], accel[2])
                
                val gyro = imuService.getCurrentGyro()
                gyroText = String.format(Locale.US, "Gyro (dps): X: %.2f, Y: %.2f, Z: %.2f", gyro[0], gyro[1], gyro[2])

                val mag = imuService.getCurrentMag()
                magText = String.format(Locale.US, "Mag (uT): X: %.1f, Y: %.1f, Z: %.1f", mag[0], mag[1], mag[2])

                hzText = String.format(Locale.US, "Sampling Rate: %.1f Hz", imuService.getCurrentHz())
                
                delay(500)
            }
        } else {
            statusText = "Status: Connecting to Service..."
        }
    }

    // Background color turns red when recording
    val backgroundColor = if (isRecording) Color(0xFFcb1725) else MaterialTheme.colorScheme.background

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(text = statusText, style = MaterialTheme.typography.headlineSmall)
        
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

        OutlinedTextField(
            value = fileNameInput,
            onValueChange = { fileNameInput = it },
            label = { Text("File Name Suffix") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isRecording
        )

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

                    val intent = Intent(context, IMURecorderService::class.java)
                    context.startForegroundService(intent)
                    imuService?.startRecording(fileNameInput)
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
                val file = imuService?.getOutputFile() ?: context.getExternalFilesDir(null)?.listFiles { _, name -> name.endsWith(".csv") }?.maxByOrNull { it.lastModified() }
                if (file != null && file.exists()) {
                    shareFile(context, file)
                } else {
                    Toast.makeText(context, "No recording found to share", Toast.LENGTH_SHORT).show()
                }
            },
            enabled = !isRecording,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Share Data")
        }
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
