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
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

// Simple data class to represent paired devices inside the Controller UI
data class ControllerDevice(
    val id: String,
    val name: String,
    val status: String,
    val battery_level: Int,
    val is_charging: Int,
    val screen_status: String,
    val last_seen: String
)

class MainActivity : ComponentActivity() {

    private lateinit var prefs: SharedPreferences
    private val client = OkHttpClient()
    private val gson = Gson()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pollRunnable: Runnable? = null

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Toast.makeText(this, "Notifications allowed", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Notifications denied.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("lockit_prefs", Context.MODE_PRIVATE)

        renderContent()
    }

    override fun onResume() {
        super.onResume()
        renderContent()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPairingPoll()
    }

    private fun renderContent() {
        setContent {
            RemoteCompanionTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF090D16)
                ) {
                    val appRole = remember { mutableStateOf(prefs.getString("app_role", null)) }

                    when (appRole.value) {
                        "companion" -> CompanionScreen(appRole)
                        "controller" -> ControllerScreen(appRole)
                        else -> RoleSelectionScreen(appRole)
                    }
                }
            }
        }
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
    // System Permission Handlers
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
    // Screen 1: Role Selection
    // ----------------------------------------------------
    @Composable
    fun RoleSelectionScreen(appRole: MutableState<String?>) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = "🔒", fontSize = 56.sp)
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Welcome to LockIT",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Select this device's role:",
                fontSize = 14.sp,
                color = Color(0xFF94A3B8),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(32.dp))

            // Companion Option
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF111827).copy(alpha = 0.6f)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        prefs.edit().putString("app_role", "companion").apply()
                        appRole.value = "companion"
                    }
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(text = "📱 Companion Device", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 16.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = "This device will be monitored and controlled (e.g. partner's or family phone). Runs background connection service.", fontSize = 12.sp, color = Color(0xFF94A3B8))
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Controller Option
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF111827).copy(alpha = 0.6f)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        prefs.edit().putString("app_role", "controller").apply()
                        appRole.value = "controller"
                    }
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(text = "🎮 Controller Device", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 16.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = "This device acts as the control panel. Use it to lock screen, ring, or send alert alerts to paired companion devices.", fontSize = 12.sp, color = Color(0xFF94A3B8))
                }
            }
        }
    }

    // ----------------------------------------------------
    // Screen 2: Companion Mode (Controlled Phone)
    // ----------------------------------------------------
    @Composable
    fun CompanionScreen(appRole: MutableState<String?>) {
        val scrollState = rememberScrollState()

        var serverUrlInput by remember { mutableStateOf(prefs.getString("server_url", "http://10.0.2.2:4000") ?: "http://10.0.2.2:4000") }
        var deviceToken by remember { mutableStateOf(prefs.getString("device_token", null)) }
        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}"

        var pairingKey by remember { mutableStateOf("") }
        var countdownSeconds by remember { mutableStateOf(0) }
        var isGeneratingKey by remember { mutableStateOf(false) }
        var serviceActive by remember { mutableStateOf(CompanionService.isServiceRunning) }

        val updateChecker = remember { UpdateChecker(this@MainActivity) }
        var updateStatusText by remember { mutableStateOf("Up to date (v1.0.0)") }
        var updateDownloadUrl by remember { mutableStateOf<String?>(null) }
        var isDownloadingUpdate by remember { mutableStateOf(false) }
        var downloadProgress by remember { mutableStateOf(0) }

        val hasNotificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this@MainActivity, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        val isAccessibilityEnabled = LockAccessibilityService.isServiceRunning()
        
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent = ComponentName(this@MainActivity, DeviceAdminRcvr::class.java)
        val isDeviceAdminEnabled = dpm.isAdminActive(adminComponent)

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
            // Header with Reset Option
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "📱 Companion Mode", fontSize = 14.sp, color = Color(0xFF3B82F6), fontWeight = FontWeight.Bold)
                Text(
                    text = "Reset Role",
                    color = Color(0xFFEF4444),
                    fontSize = 12.sp,
                    modifier = Modifier.clickable {
                        val intent = Intent(this@MainActivity, CompanionService::class.java)
                        stopService(intent)
                        prefs.edit().remove("app_role").remove("device_token").apply()
                        deviceToken = null
                        appRole.value = null
                    }
                )
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(text = "🔒", fontSize = 36.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = "LockIT Client", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }

            // Connection Status
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF111827).copy(alpha = 0.6f)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = "Connection Link Status", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(
                                    color = if (serviceActive) Color(0xFF10B981) else Color(0xFFEF4444),
                                    shape = RoundedCornerShape(50)
                                )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (serviceActive) "Linked & Background Service Active" else "Disconnected / Idle",
                            color = if (serviceActive) Color(0xFF10B981) else Color(0xFFEF4444),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
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
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(if (serviceActive) "Pause Link" else "Resume Link", fontSize = 12.sp)
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
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Unpair", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            // Pairing Form
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF111827).copy(alpha = 0.6f)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = "Link to Web / Phone Controller", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(10.dp))

                    if (deviceToken == null) {
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

                        Spacer(modifier = Modifier.height(12.dp))

                        if (pairingKey.isEmpty()) {
                            Button(
                                onClick = {
                                    isGeneratingKey = true
                                    prefs.edit().putString("server_url", serverUrlInput).apply()

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
                                                        startPairingPoll(deviceId, serverUrlInput) { token ->
                                                            stopPairingPoll()
                                                            prefs.edit().putString("device_token", token).apply()
                                                            deviceToken = token
                                                            pairingKey = ""
                                                            countdownSeconds = 0
                                                            startForegroundService(Intent(this@MainActivity, CompanionService::class.java))
                                                            serviceActive = true
                                                            Toast.makeText(this@MainActivity, "Device paired successfully!", Toast.LENGTH_LONG).show()
                                                        }
                                                    }
                                                } catch (e: Exception) {
                                                    mainHandler.post { Toast.makeText(this@MainActivity, "Failed parsing key.", Toast.LENGTH_LONG).show() }
                                                }
                                            } else {
                                                mainHandler.post { Toast.makeText(this@MainActivity, "Server error: ${response.code}", Toast.LENGTH_LONG).show() }
                                            }
                                        }
                                    })
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(6.dp),
                                enabled = !isGeneratingKey,
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6))
                            ) {
                                Text(if (isGeneratingKey) "Generating Key..." else "Generate Pairing Key")
                            }
                        } else {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(text = "Enter key in Web or Phone console:", fontSize = 11.sp, color = Color(0xFF94A3B8))
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = pairingKey,
                                    fontSize = 28.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF10B981),
                                    fontFamily = FontFamily.Monospace,
                                    letterSpacing = 2.sp
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                val minutes = countdownSeconds / 60
                                val seconds = countdownSeconds % 60
                                Text(
                                    text = String.format("Expires in: %02d:%02d", minutes, seconds),
                                    fontSize = 12.sp,
                                    color = Color(0xFFF59E0B),
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Button(
                                    onClick = {
                                        stopPairingPoll()
                                        pairingKey = ""
                                        countdownSeconds = 0
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155)),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text("Cancel request")
                                }
                            }
                        }
                    } else {
                        Text("Device Name: $deviceName", color = Color.White, fontSize = 13.sp)
                        Text("Device ID: $deviceId", color = Color(0xFF94A3B8), fontSize = 11.sp)
                        Text("Connected Server: $serverUrlInput", color = Color(0xFF94A3B8), fontSize = 11.sp)
                    }
                }
            }

            // Permissions Checklist
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF111827).copy(alpha = 0.6f)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = "Device Permissions Checklist", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Accessibility Service", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text(text = if (isAccessibilityEnabled) "Active" else "Inactive", color = if (isAccessibilityEnabled) Color(0xFF10B981) else Color(0xFFEF4444), fontSize = 11.sp)
                        }
                        Button(
                            onClick = { requestAccessibilityPermission() },
                            colors = ButtonDefaults.buttonColors(containerColor = if (isAccessibilityEnabled) Color(0xFF1E293B) else Color(0xFF3B82F6)),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text("Configure", fontSize = 11.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    HorizontalDivider(color = Color(0xFF1E293B))
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Device Administrator", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text(text = if (isDeviceAdminEnabled) "Active" else "Inactive", color = if (isDeviceAdminEnabled) Color(0xFF10B981) else Color(0xFFEF4444), fontSize = 11.sp)
                        }
                        Button(
                            onClick = { requestDeviceAdminPermission() },
                            colors = ButtonDefaults.buttonColors(containerColor = if (isDeviceAdminEnabled) Color(0xFF1E293B) else Color(0xFF3B82F6)),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text("Configure", fontSize = 11.sp)
                        }
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        Spacer(modifier = Modifier.height(10.dp))
                        HorizontalDivider(color = Color(0xFF1E293B))
                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Post Notifications", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Text(text = if (hasNotificationPermission) "Allowed" else "Not Allowed", color = if (hasNotificationPermission) Color(0xFF10B981) else Color(0xFFEF4444), fontSize = 11.sp)
                            }
                            Button(
                                onClick = { requestNotificationPermission() },
                                colors = ButtonDefaults.buttonColors(containerColor = if (hasNotificationPermission) Color(0xFF1E293B) else Color(0xFF3B82F6)),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text("Configure", fontSize = 11.sp)
                            }
                        }
                    }
                }
            }

            // Update checker
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF111827).copy(alpha = 0.6f)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = "App Updates", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = "Status: $updateStatusText", color = Color(0xFF94A3B8), fontSize = 12.sp)
                    
                    if (isDownloadingUpdate) {
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { downloadProgress / 100f },
                            modifier = Modifier.fillMaxWidth(),
                            color = Color(0xFF3B82F6),
                            trackColor = Color(0xFF1E293B),
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    if (updateDownloadUrl == null) {
                        Button(
                            onClick = {
                                updateChecker.checkForUpdates(object : UpdateChecker.UpdateCallback {
                                    override fun onChecking() { updateStatusText = "Checking..." }
                                    override fun onNoUpdate() { updateStatusText = "App is up to date (v1.0.0)" }
                                    override fun onUpdateAvailable(newVersion: String, downloadUrl: String) {
                                        updateStatusText = "Update available: $newVersion"
                                        updateDownloadUrl = downloadUrl
                                    }
                                    override fun onDownloadProgress(progress: Int) {}
                                    override fun onError(errorMsg: String) { updateStatusText = "Error: $errorMsg" }
                                })
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155)),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Check for Updates", fontSize = 12.sp)
                        }
                    } else {
                        Button(
                            onClick = {
                                isDownloadingUpdate = true
                                updateChecker.downloadAndInstallApk(updateDownloadUrl!!, object : UpdateChecker.UpdateCallback {
                                    override fun onChecking() {}
                                    override fun onNoUpdate() {}
                                    override fun onUpdateAvailable(newVersion: String, downloadUrl: String) {}
                                    override fun onDownloadProgress(progress: Int) { downloadProgress = progress }
                                    override fun onError(errorMsg: String) {
                                        isDownloadingUpdate = false
                                        updateStatusText = "Download error: $errorMsg"
                                    }
                                })
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isDownloadingUpdate
                        ) {
                            Text("Download and Install Update", fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }

    // ----------------------------------------------------
    // Screen 3: Controller Mode (Dashboard Phone)
    // ----------------------------------------------------
    @Composable
    fun ControllerScreen(appRole: MutableState<String?>) {
        val scrollState = rememberScrollState()

        var serverUrlInput by remember { mutableStateOf(prefs.getString("controller_server_url", "http://10.0.2.2:4000") ?: "http://10.0.2.2:4000") }
        var adminPasscode by remember { mutableStateOf("") }
        var adminToken by remember { mutableStateOf(prefs.getString("admin_token", null)) }

        var pairedDevices by remember { mutableStateOf<List<ControllerDevice>>(emptyList()) }
        var isFetchingDevices by remember { mutableStateOf(false) }
        var pairingKey by remember { mutableStateOf("") }
        var pairingMsg by remember { mutableStateOf("") }

        // Fetch list of devices
        val fetchDevicesList = {
            if (adminToken != null) {
                isFetchingDevices = true
                val request = Request.Builder()
                    .url("$serverUrlInput/api/admin/devices")
                    .header("Authorization", "Bearer $adminToken")
                    .build()

                client.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        mainHandler.post {
                            isFetchingDevices = false
                            Toast.makeText(this@MainActivity, "Network error fetching devices", Toast.LENGTH_SHORT).show()
                        }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        mainHandler.post { isFetchingDevices = false }
                        if (response.isSuccessful) {
                            try {
                                val bodyString = response.body?.string() ?: "[]"
                                val jsonArr = gson.fromJson(bodyString, JsonArray::class.java)
                                val list = mutableListOf<ControllerDevice>()
                                for (i in 0 until jsonArr.size()) {
                                    val obj = jsonArr.get(i).asJsonObject
                                    list.add(
                                        ControllerDevice(
                                            id = obj.get("id").asString,
                                            name = obj.get("name").asString,
                                            status = obj.get("status").asString,
                                            battery_level = obj.get("battery_level")?.asInt ?: 50,
                                            is_charging = obj.get("is_charging")?.asInt ?: 0,
                                            screen_status = obj.get("screen_status")?.asString ?: "UNKNOWN",
                                            last_seen = obj.get("last_seen")?.asString ?: ""
                                        )
                                    )
                                }
                                mainHandler.post {
                                    pairedDevices = list
                                }
                            } catch (e: Exception) {
                                Log.e("MainActivity", "Parse devices error", e)
                            }
                        } else if (response.code == 401) {
                            mainHandler.post {
                                prefs.edit().remove("admin_token").apply()
                                adminToken = null
                            }
                        }
                    }
                })
            }
        }

        // Trigger command dispatch
        val dispatchCommand = { deviceId: String, command: String, payload: JsonObject? ->
            if (adminToken != null) {
                val requestBody = JsonObject().apply {
                    addProperty("deviceId", deviceId)
                    addProperty("command", command)
                    if (payload != null) {
                        add("payload", payload)
                    }
                }

                val request = Request.Builder()
                    .url("$serverUrlInput/api/admin/command")
                    .header("Authorization", "Bearer $adminToken")
                    .post(gson.toJson(requestBody).toRequestBody("application/json".toMediaType()))
                    .build()

                client.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        mainHandler.post { Toast.makeText(this@MainActivity, "Failed sending command", Toast.LENGTH_SHORT).show() }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        mainHandler.post {
                            if (response.isSuccessful) {
                                Toast.makeText(this@MainActivity, "Command sent successfully!", Toast.LENGTH_SHORT).show()
                                fetchDevicesList()
                            } else {
                                Toast.makeText(this@MainActivity, "Error: ${response.code}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                })
            }
        }

        // Trigger pairing check
        val executePairing = {
            if (adminToken != null && pairingKey.length == 8) {
                pairingMsg = "Pairing..."
                val requestBody = JsonObject().apply {
                    addProperty("pairingKey", pairingKey)
                }

                val request = Request.Builder()
                    .url("$serverUrlInput/api/admin/pair")
                    .header("Authorization", "Bearer $adminToken")
                    .post(gson.toJson(requestBody).toRequestBody("application/json".toMediaType()))
                    .build()

                client.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        mainHandler.post { pairingMsg = "Network error pairing." }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        mainHandler.post {
                            if (response.isSuccessful) {
                                pairingMsg = "Paired successfully!"
                                pairingKey = ""
                                fetchDevicesList()
                            } else {
                                pairingMsg = "Failed pairing (check key/expiration)."
                            }
                        }
                    }
                })
            }
        }

        // Fetch list on screen entry
        LaunchedEffect(adminToken) {
            if (adminToken != null) {
                fetchDevicesList()
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Header with Reset Option
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "🎮 Controller Mode", fontSize = 14.sp, color = Color(0xFF8B5CF6), fontWeight = FontWeight.Bold)
                Text(
                    text = "Reset Role",
                    color = Color(0xFFEF4444),
                    fontSize = 12.sp,
                    modifier = Modifier.clickable {
                        prefs.edit().remove("app_role").remove("admin_token").apply()
                        adminToken = null
                        appRole.value = null
                    }
                )
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(text = "🎮", fontSize = 36.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = "LockIT Dashboard", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }

            if (adminToken == null) {
                // Admin Login Panel
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF111827).copy(alpha = 0.6f)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(text = "Admin Login", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
                        
                        OutlinedTextField(
                            value = serverUrlInput,
                            onValueChange = { serverUrlInput = it },
                            label = { Text("Server URL", color = Color(0xFF94A3B8)) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFF8B5CF6),
                                unfocusedBorderColor = Color(0xFF334155)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = adminPasscode,
                            onValueChange = { adminPasscode = it },
                            label = { Text("Admin Passcode", color = Color(0xFF94A3B8)) },
                            visualTransformation = PasswordVisualTransformation(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFF8B5CF6),
                                unfocusedBorderColor = Color(0xFF334155)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Button(
                            onClick = {
                                if (adminPasscode.isEmpty()) return@Button
                                prefs.edit().putString("controller_server_url", serverUrlInput).apply()

                                val requestBody = JsonObject().apply {
                                    addProperty("passcode", adminPasscode)
                                }
                                val request = Request.Builder()
                                    .url("$serverUrlInput/api/admin/login")
                                    .post(gson.toJson(requestBody).toRequestBody("application/json".toMediaType()))
                                    .build()

                                client.newCall(request).enqueue(object : Callback {
                                    override fun onFailure(call: Call, e: IOException) {
                                        mainHandler.post { Toast.makeText(this@MainActivity, "Network error: check server.", Toast.LENGTH_LONG).show() }
                                    }

                                    override fun onResponse(call: Call, response: Response) {
                                        if (response.isSuccessful) {
                                            try {
                                                val body = response.body?.string() ?: ""
                                                val resObj = gson.fromJson(body, JsonObject::class.java)
                                                val token = resObj.get("token").asString
                                                mainHandler.post {
                                                    prefs.edit().putString("admin_token", token).apply()
                                                    adminToken = token
                                                    adminPasscode = ""
                                                    Toast.makeText(this@MainActivity, "Logged in!", Toast.LENGTH_SHORT).show()
                                                }
                                            } catch (e: Exception) {
                                                mainHandler.post { Toast.makeText(this@MainActivity, "Failed parsing login token.", Toast.LENGTH_SHORT).show() }
                                            }
                                        } else {
                                            mainHandler.post { Toast.makeText(this@MainActivity, "Invalid Admin passcode.", Toast.LENGTH_LONG).show() }
                                        }
                                    }
                                })
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B5CF6)),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Verify passcode")
                        }
                    }
                }
            } else {
                // Logged in: Devices Grid and Action Panel
                
                // Pair Device Card
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF111827).copy(alpha = 0.6f)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(text = "Link New Device", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = pairingKey,
                                onValueChange = { pairingKey = it.toUpperCase() },
                                placeholder = { Text("ABCDEF12", color = Color(0xFF334155)) },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedBorderColor = Color(0xFF8B5CF6),
                                    unfocusedBorderColor = Color(0xFF334155)
                                )
                            )
                            Button(
                                onClick = { executePairing() },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B5CF6)),
                                shape = RoundedCornerShape(6.dp),
                                enabled = pairingKey.length == 8
                            ) {
                                Text("Link")
                            }
                        }
                        if (pairingMsg.isNotEmpty()) {
                            Text(text = pairingMsg, fontSize = 11.sp, color = Color(0xFF94A3B8))
                        }
                    }
                }

                // Devices Listing
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "Paired Devices (${pairedDevices.size})", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
                    Text(
                        text = if (isFetchingDevices) "Syncing..." else "Refresh",
                        color = Color(0xFF3B82F6),
                        fontSize = 12.sp,
                        modifier = Modifier.clickable { fetchDevicesList() }
                    )
                }

                if (pairedDevices.isEmpty()) {
                    Text(
                        text = "No devices linked to this controller. Generate a key on the companion device and pair it here.",
                        fontSize = 12.sp,
                        color = Color(0xFF94A3B8),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp)
                    )
                } else {
                    pairedDevices.forEach { device ->
                        var notificationMsg by remember { mutableStateOf("") }
                        
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF111827).copy(alpha = 0.6f)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(text = device.name, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
                                    Box(
                                        modifier = Modifier
                                            .background(
                                                color = if (device.status == "online") Color(0xFF10B981).copy(alpha = 0.15f) else Color(0xFFEF4444).copy(alpha = 0.15f),
                                                shape = RoundedCornerShape(10.dp)
                                            )
                                            .padding(horizontal = 8.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = device.status.uppercase(),
                                            color = if (device.status == "online") Color(0xFF10B981) else Color(0xFFEF4444),
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(text = "🔋 Battery: ${device.battery_level}% ${if (device.is_charging == 1) "(Charging)" else ""}", fontSize = 12.sp, color = Color(0xFF94A3B8))
                                    Text(text = "📱 Screen: ${device.screen_status}", fontSize = 12.sp, color = Color(0xFF94A3B8))
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Button(
                                        onClick = { dispatchCommand(device.id, "lock", null) },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                                        shape = RoundedCornerShape(6.dp),
                                        modifier = Modifier.weight(1f),
                                        enabled = device.status == "online"
                                    ) {
                                        Text("Lock Screen", fontSize = 11.sp)
                                    }
                                    Button(
                                        onClick = { dispatchCommand(device.id, "ring", null) },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6)),
                                        shape = RoundedCornerShape(6.dp),
                                        modifier = Modifier.weight(1f),
                                        enabled = device.status == "online"
                                    ) {
                                        Text("Ring Phone", fontSize = 11.sp)
                                    }
                                }

                                // Send Notification Panel
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedTextField(
                                        value = notificationMsg,
                                        onValueChange = { notificationMsg = it },
                                        placeholder = { Text("Alert Message...", color = Color(0xFF334155)) },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedTextColor = Color.White,
                                            unfocusedTextColor = Color.White,
                                            focusedBorderColor = Color(0xFF8B5CF6),
                                            unfocusedBorderColor = Color(0xFF334155)
                                        ),
                                        enabled = device.status == "online"
                                    )
                                    Button(
                                        onClick = {
                                            val payloadObj = JsonObject().apply {
                                                addProperty("message", notificationMsg)
                                            }
                                            dispatchCommand(device.id, "notify", payloadObj)
                                            notificationMsg = ""
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                                        shape = RoundedCornerShape(6.dp),
                                        enabled = device.status == "online" && notificationMsg.isNotEmpty()
                                    ) {
                                        Text("Alert", fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }
                }

                Button(
                    onClick = {
                        prefs.edit().remove("admin_token").apply()
                        adminToken = null
                        pairedDevices = emptyList()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155)),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Disconnect Controller")
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
