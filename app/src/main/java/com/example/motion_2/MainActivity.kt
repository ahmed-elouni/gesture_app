package com.example.motion_2

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.Image
import android.os.Bundle
import android.os.Environment
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.motion_2.ui.theme.Motion_2Theme
import kotlin.math.*
import java.io.File
import java.util.LinkedList

class MainActivity : ComponentActivity(), SensorEventListener {

    // --- Sensor and accelerometer buffers ---
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private val accelX = mutableListOf<Float>()
    private val accelY = mutableListOf<Float>()
    private val accelZ = mutableListOf<Float>()
    private val historyBuffer = LinkedList<AccelSample>()
    private val HISTORY_WINDOW_NS = 200_000_000L // 200ms

    private var collecting = false
    private var startX = 0f
    private var startY = 0f
    private var gestureStartWallMs = 0L
    lateinit var csvFile: File

    var gestureData by mutableStateOf("Waiting for gesture...")


    data class AccelSample(val x: Float, val y: Float, val z: Float, val timestampNs: Long)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // --- Create CSV file in Downloads (one per app session) ---
        val timestamp = System.currentTimeMillis()
        csvFile = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "gesture_$timestamp.csv"
        )
        if (!csvFile.exists()) {
            val header = "Timestamp,StartX,StartY,Dx,Dy,Surface,Distance,Speed,Angle,Duration,Category," +
                    "BeforeX,BeforeY,BeforeZ,DuringX,DuringY,DuringZ\n"
            csvFile.writeText(header)
        }

        // --- Setup accelerometer ---
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        accelerometer?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }

        setContent {
            Motion_2Theme {
                val navController = rememberNavController()
                // Wrap all screens with a touch collector
                Box(modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                    detectTapGestures(
                        onPress = { /* Called when the gesture starts */ },
                        onTap = { /* Called on tap */ },
                        onDoubleTap = { /* Called on Double Tap */ },
                        onLongPress = { /* Called on Long Press */ }
                    )
                }) {
                    NavHost(navController, startDestination = "login") {
                        composable("login") { LoginPage(navController) }
                        composable("home") { HomePage(navController) }
                        composable("devices") { DevicesPage(navController) }
                        composable("wifi") { WifiSettingsPage(navController) }
                        composable("gesture") { GesturePage() }
                        // Device details screen (pass deviceName)
                        composable("deviceDetails/{deviceName}") { backStackEntry ->
                            val deviceName = backStackEntry.arguments?.getString("deviceName") ?: "Unknown"
                            DeviceDetailsPage(navController, deviceName)
                        }
                    }
                }
            }
        }
    }

    // --- Override dispatchTouchEvent to capture all touch events in the activity ---
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                accelX.clear()
                accelY.clear()
                accelZ.clear()
                startX = event.rawX
                startY = event.rawY
                gestureStartWallMs = System.currentTimeMillis()
                collecting = true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                collecting = false
                val gestureEndWallMs = System.currentTimeMillis()
                val durationSec = (gestureEndWallMs - gestureStartWallMs) / 1000.0

                val dx = event.rawX - startX
                val dy = event.rawY - startY
                val distancePx = sqrt(dx * dx + dy * dy)
                val speedPxPerSec = if (durationSec > 0) distancePx / durationSec else 0.0
                val angleDeg = Math.toDegrees(atan2(dy, dx).toDouble())
                val major = event.touchMajor
                val minor = event.touchMinor
                val surface = (PI * major * minor / 4.0).toFloat()

                val beforeX = if (historyBuffer.isNotEmpty()) historyBuffer.map { it.x }.average().toFloat() else 0f
                val beforeY = if (historyBuffer.isNotEmpty()) historyBuffer.map { it.y }.average().toFloat() else 0f
                val beforeZ = if (historyBuffer.isNotEmpty()) historyBuffer.map { it.z }.average().toFloat() else 0f

                val duringX = if (accelX.isNotEmpty()) accelX.average().toFloat() else 0f
                val duringY = if (accelY.isNotEmpty()) accelY.average().toFloat() else 0f
                val duringZ = if (accelZ.isNotEmpty()) accelZ.average().toFloat() else 0f

                val category = classifyGesture(distancePx.toDouble(), speedPxPerSec, angleDeg, durationSec, event.pointerCount)

                saveGestureToCSV(
                    System.currentTimeMillis(),
                    startX, startY, dx, dy, surface,
                    distancePx.toDouble(), speedPxPerSec, angleDeg, durationSec, category,
                    beforeX, beforeY, beforeZ, duringX, duringY, duringZ
                )

                gestureData = """
                    Catégorie: $category
                    
                    StartX: $startX
                    
                    StartY: $startY
                    
                    Dx: $dx
                    
                    Dy: $dy
                    
                    Distance: ${"%.2f".format(distancePx)}
                    
                    Speed: ${"%.2f".format(speedPxPerSec)}
                    
                    Angle: ${"%.2f".format(angleDeg)}
                    
                    Surface: ${"%.2f".format(surface)}
                    
                    Duration: ${"%.3f".format(durationSec)}
                """.trimIndent()
            }
        }
        return super.dispatchTouchEvent(event)
    }

    // --- Sensor callbacks ---
    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            val sample = AccelSample(event.values[0], event.values[1], event.values[2], event.timestamp)
            historyBuffer.add(sample)
            val cutoff = event.timestamp - HISTORY_WINDOW_NS
            while (historyBuffer.isNotEmpty() && historyBuffer.first.timestampNs < cutoff) {
                historyBuffer.removeFirst()
            }
            if (collecting) {
                accelX.add(sample.x)
                accelY.add(sample.y)
                accelZ.add(sample.z)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // --- Compose Pages ---
    @Composable
    fun GesturePage() {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Spacer(modifier = Modifier.height(40.dp))

            Text(
                text = "Gesture Demo",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                ),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))
            Text(gestureData, style = MaterialTheme.typography.bodyLarge)
        }
    }

    @Composable
    fun LoginPage(navController: NavHostController) {
        var username by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0xFF1E88E5), Color(0xFF42A5F5)) // gradient background
                    )
                )
        ) {
            // 🔹 Top-right "signature" (logo + text)
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp),
                horizontalAlignment = Alignment.End
            ) {
                Image(
                    painter = painterResource(id = R.drawable.isi),
                    contentDescription = "ISI Logo",
                    modifier = Modifier.size(100.dp)
                )
                Text(
                    text = "Created by Ahmed Elouni",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold

                    ),
                    textAlign = TextAlign.End
                )
            }

            // 🔹 Main login content (centered)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.Center)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Image(
                    painter = painterResource(id = R.drawable.iot),
                    contentDescription = "ISI Logo",
                    modifier = Modifier.size(100.dp)
                )
                Spacer(modifier = Modifier.height(20.dp))

                // Welcome text
                Text(
                    text = "Welcome to IoT Demo App",
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    ),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(32.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    elevation = CardDefaults.cardElevation(8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        OutlinedTextField(
                            value = username,
                            onValueChange = { username = it },
                            label = { Text("Username") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("Password") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        Button(
                            onClick = { navController.navigate("home") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Login")
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun HomePage(navController: NavHostController) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0xFF1E88E5), Color(0xFF42A5F5))
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "Welcome to IoT Dashboard",
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    ),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(32.dp))

                // 🔹 Devices
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp)
                        .clickable { navController.navigate("devices") },
                    shape = RoundedCornerShape(16.dp),
                    elevation = CardDefaults.cardElevation(8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Row(
                        modifier = Modifier.padding(24.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.AddCircle,
                            contentDescription = "Devices",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text("Manage Devices", style = MaterialTheme.typography.bodyLarge)
                    }
                }

                // 🔹 WiFi
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp)
                        .clickable { navController.navigate("wifi") },
                    shape = RoundedCornerShape(16.dp),
                    elevation = CardDefaults.cardElevation(8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Row(
                        modifier = Modifier.padding(24.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "WiFi",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text("Wi-Fi Settings", style = MaterialTheme.typography.bodyLarge)
                    }
                }

                // 🔹 Gesture Demo
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp)
                        .clickable { navController.navigate("gesture") },
                    shape = RoundedCornerShape(16.dp),
                    elevation = CardDefaults.cardElevation(8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Row(
                        modifier = Modifier.padding(24.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Home,
                            contentDescription = "Gesture",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text("Gesture Demo", style = MaterialTheme.typography.bodyLarge)

                    }
                }
            }
        }
    }

    @Composable
    fun DevicesPage(navController: NavHostController) {
        val devices = listOf("IP Cam", "Smart Lock", "Light")

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0xFF1E88E5), Color(0xFF42A5F5))
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                Spacer(Modifier.height(24.dp))

                Text(
                    text = "My Devices",
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    ),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(24.dp))

                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(devices) { device ->
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            elevation = CardDefaults.cardElevation(6.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { navController.navigate("deviceDetails/$device") } // 👈 Navigate
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AddCircle,
                                    contentDescription = "Device Icon",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(32.dp)
                                )

                                Spacer(modifier = Modifier.width(16.dp))

                                Text(
                                    text = device,
                                    style = MaterialTheme.typography.bodyLarge.copy(
                                        fontWeight = FontWeight.SemiBold
                                    )
                                )
                            }
                        }
                    }
                }
            }

            Button(
                onClick = { navController.navigate("gesture") },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = "Go to Gesture Demo",
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                )
            }
        }
    }
    private fun saveGestureToCSV(
        timestamp: Long,
        x: Float, y: Float, dx: Float, dy: Float,
        surface: Float, distance: Double, speed: Double,
        angle: Double, duration: Double, category: String,
        beforeX: Float, beforeY: Float, beforeZ: Float,
        duringX: Float, duringY: Float, duringZ: Float
    ) {
        val line = "$timestamp,$x,$y,$dx,$dy,$surface," +
                "${"%.2f".format(distance)}," +
                "${"%.2f".format(speed)}," +
                "${"%.2f".format(angle)}," +
                "${"%.3f".format(duration)}," +
                "$category," +
                "${"%.2f".format(beforeX)}," +
                "${"%.2f".format(beforeY)}," +
                "${"%.2f".format(beforeZ)}," +
                "${"%.2f".format(duringX)}," +
                "${"%.2f".format(duringY)}," +
                "${"%.2f".format(duringZ)}\n"
        csvFile.appendText(line)
    }
}

@Composable
fun DeviceDetailsPage(navController: NavHostController, deviceName: String) {
    var deviceStatus by remember { mutableStateOf(true) } // ON by default

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF5F5F5)),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(24.dp))

            // Device title
            Text(
                text = deviceName,
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            )

            Spacer(Modifier.height(24.dp))

            // Device status toggle
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (deviceStatus) "Status: ON" else "Status: OFF",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Switch(
                    checked = deviceStatus,
                    onCheckedChange = { deviceStatus = it }
                )
            }

            Spacer(Modifier.height(32.dp))

            // Control buttons
            Button(
                onClick = { /* Restart logic */ },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Restart Device")
            }

            Button(
                onClick = { /* Configure logic */ },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Configure Device")
            }

            Button(
                onClick = { /* Delete logic */ },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Delete Device", color = Color.White)
            }

            Spacer(Modifier.height(32.dp))

            // Back button
            OutlinedButton(
                onClick = { navController.popBackStack() },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Back to Devices")
            }
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WifiSettingsPage(navController: NavHostController) {
    val networks = listOf("WiFi-Home", "WiFi-Office", "WiFi-Cafe")
    var selectedNetwork by remember { mutableStateOf<String?>(null) }
    var showPasswordDialog by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Wi-Fi Settings") },
                navigationIcon = {
                    IconButton(onClick = { navController.navigate("home") }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Home,
                contentDescription = "WiFi Icon",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(64.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                "Available Networks",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            LazyColumn(
                modifier = Modifier.fillMaxWidth()
            ) {
                items(networks) { network ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        shape = RoundedCornerShape(16.dp),
                        elevation = CardDefaults.cardElevation(6.dp),
                        colors = CardDefaults.cardColors(
                            containerColor =
                                if (selectedNetwork == network) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(16.dp)
                                .fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Home,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(network, style = MaterialTheme.typography.bodyLarge)
                            }

                            Button(
                                onClick = {
                                    selectedNetwork = network
                                    showPasswordDialog = true
                                },
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Connect")
                            }
                        }
                    }
                }
            }
        }
    }

    // 🔑 Password Dialog
    if (showPasswordDialog && selectedNetwork != null) {
        AlertDialog(
            onDismissRequest = { showPasswordDialog = false },
            title = { Text("Connect to ${selectedNetwork}") },
            text = {
                Column {
                    Text("Enter Wi-Fi Password")
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    // Simulate connection
                    showPasswordDialog = false
                    password = ""
                    // Here you could trigger actual Wi-Fi connection logic
                }) {
                    Text("Connect")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showPasswordDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

// --- Gesture classification & CSV ---
private fun classifyGesture(
    distance: Double,
    speed: Double,
    angle: Double,
    duration: Double,
    pointerCount: Int
): String {
    // 🔹 Multi-touch
    if (pointerCount > 1) {
        return when {
            pointerCount == 2 && distance > 50 -> "Pinch/Zoom"
            pointerCount == 2 -> "Two-Finger Gesture"
            pointerCount > 2 -> "Multi-Finger Gesture"
            else -> "Multi-touch"
        }
    }

    // 🔹 Very small movement → taps
    if (distance < 10) {
        return when {
            duration < 0.3 -> "Tap"
            //duration in 0.3..0.5 -> "Double Tap"
            duration > 0.5 -> "Long Press"
            else -> "Double Tap"
        }
    }

    // 🔹 Quick directional movements → swipes
    if (distance > 50 && duration < 0.5) {
        return when {
            abs(angle) < 30 -> "Swipe Right"
            angle in 150.0..210.0 || angle in -210.0..-150.0 -> "Swipe Left"
            angle in 60.0..120.0 -> "Swipe Down"
            angle in -120.0..-60.0 -> "Swipe Up"
            else -> "Swipe"
        }
    }

    // 🔹 Longer movements
    if (distance > 50) {
        return when {
            duration >= 0.5 && speed < 800 -> "Drag"
            duration >= 0.5 && (abs(angle) < 45 || abs(angle) > 135) -> "Scroll"
            duration >= 0.5 -> "Pan"
            else -> "Flick"
        }
    }

    // 🔹 Special gestures
    return when {
        speed > 1200 && distance > 200 -> "Fling"
        distance > 300 && duration > 1.0 -> "Edge Swipe"
        else -> "Complex Gesture"
    }
}

