package com.kingo.lockit

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class MainActivity : ComponentActivity() {

    private lateinit var prefs: SharedPreferences
    private val client = OkHttpClient()
    private val gson = Gson()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pollRunnable: Runnable? = null

    // Permission request contract for Android 13+ notifications
    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Toast.makeText(this, "Notifications allowed", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Notifications denied. Background service updates may not display.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("lockit_prefs", Context.MODE_PRIVATE)

        setContent {
            RemoteCompanionTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF090D16) // Match web slate dark bg
                ) {
                    MainScreen()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Trigger recomposition or update checks on resume (e.g. returning from settings screens)
        setContent {
            RemoteCompanionTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF090D16)
                ) {
                    MainScreen()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPairingPoll()
    }

    // ----------------------------------------------------
    // Pairing Polling Logic
    // ----------------------------------------------------
    private fun startPairingPoll(deviceId: String, serverUrl: String, onPaired: (String) -> Unit) {
        stopPairingPoll()
        val checkUrl = "$serverUrl/api/device/pair-status?deviceId=$deviceId"
        
        pollRunnable = object : Runnable {
            override fun run() {
                val request = Request.Builder().url(checkUrl).build()
                client.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        Log.e("MainActivity", "Pair status check failed", e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        if (response.isSuccessful) {
                            try {
                                val body = response.body?.string() ?: ""
                                val statusObj = gson.fromJson(body, JsonObject::class.java)
                                val isPaired = statusObj.get("paired")?.asBoolean ?: false
                                if (isPaired) {
                                    val token = statusObj.get("token")?.asString ?: ""
                                    mainHandler.post {
                                        onPaired(token)
                                    }
                                    return
                                }
                            } catch (e: Exception) {
                                Log.e("MainActivity", "Error parsing pair status", e)
                            }
                        }
                        // Poll again in 3 seconds
                        mainHandler.postDelayed(pollRunnable!!, 3000)
                    }
                })
            }
        }
        mainHandler.postDelayed(pollRunnable!!, 3000)
    }

    private fun stopPairingPoll() {
        pollRunnable?.let {
            mainHandler.removeCallbacks(it)
            pollRunnable = null
        }
    }

    // ----------------------------------------------------
    // System Intents Helper
    // ----------------------------------------------------
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun requestAccessibilityPermission() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
        Toast.makeText(this, "Locate 'LockIT' in list and enable it", Toast.LENGTH_LONG).show()
    }

    private fun requestDeviceAdminPermission() {
        val adminComponent = ComponentName(this, DeviceAdminRcvr::class.java)
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Enables LockIT to lock the screen instantly when commanded from web dashboard.")
        }
        startActivity(intent)
    }

    // ----------------------------------------------------
    // Main UI Component
    // ----------------------------------------------------
    @Composable
    fun MainScreen() {
        val scrollState = rememberScrollState()

        // Persistent State
        var serverUrlInput by remember { mutableStateOf(prefs.getString("server_url", "http://10.0.2.2:4000") ?: "http://10.0.2.2:4000") }
        var deviceToken by remember { mutableStateOf(prefs.getString("device_token", null)) }
        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}"

        // UI States
        var pairingKey by remember { mutableStateOf("") }
        var countdownSeconds by remember { mutableStateOf(0) }
        var isGeneratingKey by remember { mutableStateOf(false) }
        var serviceActive by remember { mutableStateOf(CompanionService.isServiceRunning) }

        // Update Checker States
        val updateChecker = remember { UpdateChecker(this@MainActivity) }
        var updateStatusText by remember { mutableStateOf("Up to date (v1.0.0)") }
        var updateDownloadUrl by remember { mutableStateOf<String?>(null) }
        var isDownloadingUpdate by remember { mutableStateOf(false) }
        var downloadProgress by remember { mutableStateOf(0) }

        // Live permissions status (checks on compose redraw)
        val hasNotificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this@MainActivity, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        val isAccessibilityEnabled = LockAccessibilityService.isServiceRunning()
        
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent = ComponentName(this@MainActivity, DeviceAdminRcvr::class.java)
        val isDeviceAdminEnabled = dpm.isAdminActive(adminComponent)

        // Count-down Timer Thread
        LaunchedEffect(countdownSeconds) {
            if (countdownSeconds > 0) {
                kotlinx.coroutines.delay(1000L)
                countdownSeconds -= 1
                if (countdownSeconds == 0) {
                    pairingKey = ""
                    stopPairingPoll()
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // App Logo / Title
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "🔒",
                    fontSize = 44.sp,
                    color = Color(0xFF3B82F6)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "LockIT",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = "Security Background Client",
                    fontSize = 13.sp,
                    color = Color(0xFF94A3B8)
                )
            }

            // Connection Status Card
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF111827).copy(alpha = 0.6f)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Connection Status",
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 15.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .background(
                                        color = if (serviceActive) Color(0xFF10B981) else Color(0xFFEF4444),
                                        shape = RoundedCornerShape(50)
                                    )
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (serviceActive) "Linked & Background Service Active" else "Disconnected / Idle",
                                color = if (serviceActive) Color(0xFF10B981) else Color(0xFFEF4444),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    if (deviceToken != null) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = {
                                    val intent = Intent(this@MainActivity, CompanionService::class.java)
                                    if (serviceActive) {
                                        stopService(intent)
                                        serviceActive = false
                                    } else {
                                        startForegroundService(intent)
                                        serviceActive = true
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6)),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(if (serviceActive) "Pause Link" else "Resume Link")
                            }
                            Button(
                                onClick = {
                                    val intent = Intent(this@MainActivity, CompanionService::class.java)
                                    stopService(intent)
                                    prefs.edit().remove("device_token").apply()
                                    deviceToken = null
                                    pairingKey = ""
                                    countdownSeconds = 0
                                    serviceActive = false
                                    Toast.makeText(this@MainActivity, "Device Token Reset", Toast.LENGTH_SHORT).show()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Unpair")
                            }
                        }
                    }
                }
            }

            // Pairing Details Card
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF111827).copy(alpha = 0.6f)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Pairing Configuration",
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 15.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    if (deviceToken == null) {
                        // Enter Server URL
                        OutlinedTextField(
                            value = serverUrlInput,
                            onValueChange = { serverUrlInput = it },
                            label = { Text("Server URL", color = Color(0xFF94A3B8)) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFF3B82F6),
                                unfocusedBorderColor = Color(0xFF334155)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        if (pairingKey.isEmpty()) {
                            Button(
                                onClick = {
                                    isGeneratingKey = true
                                    // Save server URL preference
                                    prefs.edit().putString("server_url", serverUrlInput).apply()

                                    // Request pairing key from server
                                    val requestBody = JsonObject().apply {
                                        addProperty("deviceId", deviceId)
                                        addProperty("deviceName", deviceName)
                                    }
                                    val request = Request.Builder()
                                        .url("$serverUrlInput/api/device/pair-request")
                                        .post(gson.toJson(requestBody).toRequestBody("application/json".toMediaType()))
                                        .build()

                                    client.newCall(request).enqueue(object : Callback {
                                        override fun onFailure(call: Call, e: IOException) {
                                            mainHandler.post {
                                                isGeneratingKey = false
                                                Toast.makeText(this@MainActivity, "Network error: check server address.", Toast.LENGTH_LONG).show()
                                            }
                                        }

                                        override fun onResponse(call: Call, response: Response) {
                                            mainHandler.post { isGeneratingKey = false }
                                            if (response.isSuccessful) {
                                                try {
                                                    val body = response.body?.string() ?: ""
                                                    val resObj = gson.fromJson(body, JsonObject::class.java)
                                                    val key = resObj.get("pairingKey")?.asString ?: ""
                                                    val expires = resObj.get("expiresInSeconds")?.asInt ?: 300
                                                    mainHandler.post {
                                                        pairingKey = key
                                                        countdownSeconds = expires
                                                        // Start polling for pairing success
                                                        startPairingPoll(deviceId, serverUrlInput) { token ->
                                                            // Success Callback
                                                            stopPairingPoll()
                                                            prefs.edit().putString("device_token", token).apply()
                                                            deviceToken = token
                                                            pairingKey = ""
                                                            countdownSeconds = 0
                                                            // Start background service
                                                            startForegroundService(Intent(this@MainActivity, CompanionService::class.java))
                                                            serviceActive = true
                                                            Toast.makeText(this@MainActivity, "Device paired successfully!", Toast.LENGTH_LONG).show()
                                                        }
                                                    }
                                                } catch (e: Exception) {
                                                    mainHandler.post { Toast.makeText(this@MainActivity, "Failed to parse pairing key response.", Toast.LENGTH_LONG).show() }
                                                }
                                            } else {
                                                mainHandler.post { Toast.makeText(this@MainActivity, "Server returned error: ${response.code}", Toast.LENGTH_LONG).show() }
                                            }
                                        }
                                    })
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                enabled = !isGeneratingKey,
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6))
                            ) {
                                Text(if (isGeneratingKey) "Generating Key..." else "Generate Pairing Key")
                            }
                        } else {
                            // Pairing Key screen with countdown timer
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 10.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "Enter key in Web Console:",
                                    fontSize = 12.sp,
                                    color = Color(0xFF94A3B8)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = pairingKey,
                                    fontSize = 32.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF10B981),
                                    fontFamily = FontFamily.Monospace,
                                    letterSpacing = 2.sp
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                val minutes = countdownSeconds / 60
                                val seconds = countdownSeconds % 60
                                Text(
                                    text = String.format("Expires in: %02d:%02d", minutes, seconds),
                                    fontSize = 13.sp,
                                    color = Color(0xFFF59E0B),
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Button(
                                    onClick = {
                                        stopPairingPoll()
                                        pairingKey = ""
                                        countdownSeconds = 0
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155)),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("Cancel pairing request")
                                }
                            }
                        }
                    } else {
                        // Already paired display info
                        Text("Device Name: $deviceName", color = Color.White, fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Device UID: $deviceId", color = Color(0xFF94A3B8), fontSize = 12.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Connected Server: $serverUrlInput", color = Color(0xFF94A3B8), fontSize = 12.sp)
                    }
                }
            }

            // Permissions Card
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF111827).copy(alpha = 0.6f)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Device Permissions Check",
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 15.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Enable the accessibility and admin settings to receive remote lock command triggers.",
                        color = Color(0xFF94A3B8),
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                    Spacer(modifier = Modifier.height(14.dp))

                    // Accessibility Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Accessibility Service", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                text = if (isAccessibilityEnabled) "Active (Lock action available)" else "Inactive",
                                color = if (isAccessibilityEnabled) Color(0xFF10B981) else Color(0xFFEF4444),
                                fontSize = 12.sp
                            )
                        }
                        Button(
                            onClick = { requestAccessibilityPermission() },
                            colors = ButtonDefaults.buttonColors(containerColor = if (isAccessibilityEnabled) Color(0xFF1E293B) else Color(0xFF3B82F6)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(if (isAccessibilityEnabled) "Enabled" else "Configure")
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = Color(0xFF1E293B))
                    Spacer(modifier = Modifier.height(12.dp))

                    // Device Admin Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Device Administrator", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                text = if (isDeviceAdminEnabled) "Active (Legacy lock available)" else "Inactive",
                                color = if (isDeviceAdminEnabled) Color(0xFF10B981) else Color(0xFFEF4444),
                                fontSize = 12.sp
                            )
                        }
                        Button(
                            onClick = { requestDeviceAdminPermission() },
                            colors = ButtonDefaults.buttonColors(containerColor = if (isDeviceAdminEnabled) Color(0xFF1E293B) else Color(0xFF3B82F6)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(if (isDeviceAdminEnabled) "Enabled" else "Configure")
                        }
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider(color = Color(0xFF1E293B))
                        Spacer(modifier = Modifier.height(12.dp))

                        // Notifications Toggle
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Post Notifications", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                Text(
                                    text = if (hasNotificationPermission) "Allowed" else "Not Allowed",
                                    color = if (hasNotificationPermission) Color(0xFF10B981) else Color(0xFFEF4444),
                                    fontSize = 12.sp
                                )
                            }
                            Button(
                                onClick = { requestNotificationPermission() },
                                colors = ButtonDefaults.buttonColors(containerColor = if (hasNotificationPermission) Color(0xFF1E293B) else Color(0xFF3B82F6)),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(if (hasNotificationPermission) "Enabled" else "Configure")
                            }
                        }
                    }
                }
            }

            // Updater Card
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF111827).copy(alpha = 0.6f)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "System Updates Manager",
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 15.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Status: $updateStatusText",
                        color = Color(0xFF94A3B8),
                        fontSize = 13.sp
                    )
                    
                    if (isDownloadingUpdate) {
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { downloadProgress / 100f },
                            modifier = Modifier.fillMaxWidth(),
                            color = Color(0xFF3B82F6),
                            trackColor = Color(0xFF1E293B),
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Downloading Update: $downloadProgress%",
                            fontSize = 11.sp,
                            color = Color(0xFF3B82F6),
                            modifier = Modifier.align(Alignment.End)
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    if (updateDownloadUrl == null) {
                        Button(
                            onClick = {
                                updateChecker.checkForUpdates(object : UpdateChecker.UpdateCallback {
                                    override fun onChecking() {
                                        updateStatusText = "Checking GitHub Releases..."
                                    }

                                    override fun onNoUpdate() {
                                        updateStatusText = "App is up to date (v1.0.0)"
                                        Toast.makeText(this@MainActivity, "You have the latest version", Toast.LENGTH_SHORT).show()
                                    }

                                    override fun onUpdateAvailable(newVersion: String, downloadUrl: String) {
                                        updateStatusText = "Update available: $newVersion"
                                        updateDownloadUrl = downloadUrl
                                    }

                                    override fun onDownloadProgress(progress: Int) {
                                        // Not downloading yet
                                    }

                                    override fun onError(errorMsg: String) {
                                        updateStatusText = "Error: $errorMsg"
                                        Toast.makeText(this@MainActivity, errorMsg, Toast.LENGTH_LONG).show()
                                    }
                                })
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155)),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Check for Updates")
                        }
                    } else {
                        Button(
                            onClick = {
                                isDownloadingUpdate = true
                                updateChecker.downloadAndInstallApk(updateDownloadUrl!!, object : UpdateChecker.UpdateCallback {
                                    override fun onChecking() {}
                                    override fun onNoUpdate() {}
                                    override fun onUpdateAvailable(newVersion: String, downloadUrl: String) {}

                                    override fun onDownloadProgress(progress: Int) {
                                        downloadProgress = progress
                                    }

                                    override fun onError(errorMsg: String) {
                                        isDownloadingUpdate = false
                                        updateStatusText = "Download error: $errorMsg"
                                        Toast.makeText(this@MainActivity, errorMsg, Toast.LENGTH_LONG).show()
                                    }
                                })
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isDownloadingUpdate
                        ) {
                            Text("Download and Install Update")
                        }
                    }
                }
            }
        }
    }
}

// Simple Compose Color Theme
@Composable
fun RemoteCompanionTheme(content: @Composable () -> Unit) {
    val darkColors = darkColorScheme(
        primary = Color(0xFF3B82F6),
        secondary = Color(0xFF8B5CF6),
        background = Color(0xFF090D16),
        surface = Color(0xFF111827),
        onPrimary = Color.White,
        onSecondary = Color.White,
        onBackground = Color.White,
        onSurface = Color.White
    )
    MaterialTheme(
        colorScheme = darkColors,
        content = content
    )
}
