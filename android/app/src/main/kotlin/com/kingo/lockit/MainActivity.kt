package com.kingo.lockit

import android.app.Activity
import android.app.KeyguardManager
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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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

    // Default production server URL
    private val defaultServerUrl = "https://lockit-backend-ipfu.onrender.com"

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
                    val onboarded = remember { mutableStateOf(prefs.getBoolean("onboarded", false)) }
                    val appRole = remember { mutableStateOf(prefs.getString("app_role", null)) }

                    if (!onboarded.value) {
                        OnboardingScreen(onboarded)
                    } else {
                        when (appRole.value) {
                            "companion" -> CompanionScreen(appRole, onboarded)
                            "controller" -> ControllerScreen(appRole)
                            else -> RoleSelectionScreen(appRole)
                        }
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
    // Screen 1: First-Launch Onboarding Wizard
    // ----------------------------------------------------
    @Composable
    fun OnboardingScreen(onboarded: MutableState<Boolean>) {
        var currentPage by remember { mutableStateOf(0) }

        // Live checks for permissions
        val isAccessibilityEnabled = LockAccessibilityService.isServiceRunning()
        
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent = ComponentName(this@MainActivity, DeviceAdminRcvr::class.java)
        val isDeviceAdminEnabled = dpm.isAdminActive(adminComponent)

        val hasNotificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this@MainActivity, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(28.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header Page Indicators
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 10.dp)
            ) {
                for (i in 0..4) {
                    Box(
                        modifier = Modifier
                            .height(6.dp)
                            .width(if (i == currentPage) 24.dp else 8.dp)
                            .background(
                                color = if (i == currentPage) Color(0xFF3B82F6) else Color(0xFF334155),
                                shape = RoundedCornerShape(3.dp)
                            )
                    )
                }
            }

            // Page Contents
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when (currentPage) {
                    0 -> {
                        Text(text = "🛡️", fontSize = 72.sp)
                        Spacer(modifier = Modifier.height(20.dp))
                        Text(
                            text = "Welcome to LockIT",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "LockIT enables secure, permission-based remote operations on trusted devices.\n\nLet's get this device set up with the required system access.",
                            fontSize = 14.sp,
                            color = Color(0xFF94A3B8),
                            textAlign = TextAlign.Center,
                            lineHeight = 20.sp
                        )
                    }
                    1 -> {
                        Text(text = "♿", fontSize = 72.sp)
                        Spacer(modifier = Modifier.height(20.dp))
                        Text(
                            text = "Accessibility Lock Service",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Enables LockIT to lock your screen immediately when triggered from the controller.",
                            fontSize = 14.sp,
                            color = Color(0xFF94A3B8),
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(30.dp))

                        if (isAccessibilityEnabled) {
                            Text(text = "✓ Service Enabled", color = Color(0xFF10B981), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        } else {
                            Button(
                                onClick = { requestAccessibilityPermission() },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6)),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Enable Accessibility")
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Note: On Android 13+, if you get a 'Restricted Setting' error, go to Settings -> Apps -> LockIT -> tap 3-dots in top right -> click 'Allow restricted settings', then try again.",
                                fontSize = 11.sp,
                                color = Color(0xFFEF4444),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                    2 -> {
                        Text(text = "👑", fontSize = 72.sp)
                        Spacer(modifier = Modifier.height(20.dp))
                        Text(
                            text = "Device Administrator",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Used as a backup mechanism to trigger screen locks if the accessibility service is disconnected.",
                            fontSize = 14.sp,
                            color = Color(0xFF94A3B8),
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(30.dp))

                        if (isDeviceAdminEnabled) {
                            Text(text = "✓ Admin Active", color = Color(0xFF10B981), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        } else {
                            Button(
                                onClick = { requestDeviceAdminPermission() },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6)),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Activate Device Admin")
                            }
                        }
                    }
                    3 -> {
                        Text(text = "🔔", fontSize = 72.sp)
                        Spacer(modifier = Modifier.height(20.dp))
                        Text(
                            text = "Post Notifications",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Required on Android 13+ to display the background connection service notification and push alert banners.",
                            fontSize = 14.sp,
                            color = Color(0xFF94A3B8),
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(30.dp))

                        if (hasNotificationPermission) {
                            Text(text = "✓ Notifications Allowed", color = Color(0xFF10B981), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        } else {
                            Button(
                                onClick = { requestNotificationPermission() },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6)),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Allow Notifications")
                            }
                        }
                    }
                    4 -> {
                        Text(text = "✨", fontSize = 72.sp)
                        Spacer(modifier = Modifier.height(20.dp))
                        Text(
                            text = "Configuration Complete!",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "You are all set. You can now configure this device as either a Controller or a Companion client.",
                            fontSize = 14.sp,
                            color = Color(0xFF94A3B8),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            // Footer Control Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (currentPage > 0) {
                    TextButton(onClick = { currentPage -= 1 }) {
                        Text("Back", color = Color(0xFF94A3B8))
                    }
                } else {
                    Spacer(modifier = Modifier.width(60.dp))
                }

                Button(
                    onClick = {
                        if (currentPage < 4) {
                            currentPage += 1
                        } else {
                            prefs.edit().putBoolean("onboarded", true).apply()
                            onboarded.value = true
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(if (currentPage == 4) "Get Started" else "Next")
                }
            }
        }
    }

    // ----------------------------------------------------
    // Screen 2: Role Selection (Companion vs Controller)
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
                text = "LockIT Roles",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Select this device's role in your LockIT system:",
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
    // Screen 3: Companion Mode (Premium Tabbed Interface)
    // ----------------------------------------------------
    @Composable
    fun CompanionScreen(appRole: MutableState<String?>, onboarded: MutableState<Boolean>) {
        var currentTab by remember { mutableStateOf(0) }
        val scrollState = rememberScrollState()

        val serverUrl = remember { prefs.getString("server_url", defaultServerUrl) ?: defaultServerUrl }
        var deviceToken by remember { mutableStateOf(prefs.getString("device_token", null)) }
        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}"

        var pairingKey by remember { mutableStateOf("") }
        var countdownSeconds by remember { mutableStateOf(0) }
        var isGeneratingKey by remember { mutableStateOf(false) }
        var serviceActive by remember { mutableStateOf(CompanionService.isServiceRunning) }

        var showAdvancedSettings by remember { mutableStateOf(false) }
        var customServerInput by remember { mutableStateOf(serverUrl) }

        // Fetch local audits list
        val localAuditsJson = remember { prefs.getString("local_audits", "[]") ?: "[]" }
        val auditsList = remember(localAuditsJson) {
            try {
                gson.fromJson(localAuditsJson, JsonArray::class.java)
            } catch (e: Exception) {
                JsonArray()
            }
        }

        // Automatic pairing request handler
        val triggerPairRequest = {
            isGeneratingKey = true
            val requestBody = JsonObject().apply {
                addProperty("deviceId", deviceId)
                addProperty("deviceName", deviceName)
            }
            val request = Request.Builder()
                .url("$serverUrl/api/device/pair-request")
                .post(gson.toJson(requestBody).toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    mainHandler.post {
                        isGeneratingKey = false
                        Toast.makeText(this@MainActivity, "Network error: check connection.", Toast.LENGTH_LONG).show()
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    mainHandler.post { isGeneratingKey = false }
                    val bodyString = response.body?.string() ?: ""
                    if (response.isSuccessful) {
                        try {
                            val resObj = gson.fromJson(bodyString, JsonObject::class.java)
                            val key = resObj.get("pairingKey")?.asString ?: ""
                            val expires = resObj.get("expiresInSeconds")?.asInt ?: 300
                            mainHandler.post {
                                // Save server URL to preferences so the background service knows where to connect!
                                prefs.edit().putString("server_url", serverUrl).apply()
                                pairingKey = key
                                countdownSeconds = expires
                                startPairingPoll(deviceId, serverUrl) { token ->
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
                        var errMsg = "Connection failed (${response.code})"
                        try {
                            val errObj = gson.fromJson(bodyString, JsonObject::class.java)
                            if (errObj.has("error")) {
                                errMsg = errObj.get("error").asString
                            }
                        } catch (e: Exception) {}
                        mainHandler.post { 
                            Toast.makeText(this@MainActivity, errMsg, Toast.LENGTH_LONG).show() 
                        }
                    }
                }
            })
        }

        // On companion screen load, if we have a token, start the service automatically
        LaunchedEffect(deviceToken) {
            if (deviceToken != null && !CompanionService.isServiceRunning) {
                try {
                    // Make sure server_url is written to SharedPreferences
                    prefs.edit().putString("server_url", serverUrl).apply()
                    startForegroundService(Intent(this@MainActivity, CompanionService::class.java))
                    serviceActive = true
                } catch (e: Exception) {
                    Log.e("MainActivity", "Failed auto-starting service", e)
                }
            }
        }

        LaunchedEffect(deviceToken) {
            if (deviceToken == null && pairingKey.isEmpty() && !isGeneratingKey) {
                triggerPairRequest()
            }
        }

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

        Scaffold(
            bottomBar = {
                NavigationBar(
                    containerColor = Color(0xFF111827),
                    tonalElevation = 8.dp
                ) {
                    NavigationBarItem(
                        selected = currentTab == 0,
                        onClick = { currentTab = 0 },
                        icon = { Text("🛡️", fontSize = 20.sp) },
                        label = { Text("Shield", fontSize = 11.sp, color = Color.White) }
                    )
                    NavigationBarItem(
                        selected = currentTab == 1,
                        onClick = { currentTab = 1 },
                        icon = { Text("📊", fontSize = 20.sp) },
                        label = { Text("Stats", fontSize = 11.sp, color = Color.White) }
                    )
                    NavigationBarItem(
                        selected = currentTab == 2,
                        onClick = { currentTab = 2 },
                        icon = { Text("📜", fontSize = 20.sp) },
                        label = { Text("Audits", fontSize = 11.sp, color = Color.White) }
                    )
                    NavigationBarItem(
                        selected = currentTab == 3,
                        onClick = { currentTab = 3 },
                        icon = { Text("⚙️", fontSize = 20.sp) },
                        label = { Text("Settings", fontSize = 11.sp, color = Color.White) }
                    )
                }
            },
            containerColor = Color(0xFF090D16)
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(24.dp)
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // Header brand title
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "LockIT Client",
                        fontSize = 16.sp,
                        color = Color(0xFF3B82F6),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { showAdvancedSettings = true }
                    )
                    Box(
                        modifier = Modifier
                            .background(
                                color = if (deviceToken != null) Color(0xFF10B981).copy(alpha = 0.15f) else Color(0xFFF59E0B).copy(alpha = 0.15f),
                                shape = RoundedCornerShape(10.dp)
                            )
                            .padding(horizontal = 10.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = if (deviceToken != null) "PAIRED" else "UNLINKED",
                            color = if (deviceToken != null) Color(0xFF10B981) else Color(0xFFF59E0B),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Render respective tab layouts
                when (currentTab) {
                    0 -> {
                        // TAB 1: Shield Tab (Connection & pairing status)
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(20.dp)
                        ) {
                            Spacer(modifier = Modifier.height(10.dp))
                            // Pulsing glowing shield circle
                            Box(
                                modifier = Modifier
                                    .size(100.dp)
                                    .border(
                                        width = 2.dp,
                                        color = if (serviceActive) Color(0xFF10B981) else Color(0xFFEF4444),
                                        shape = CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (serviceActive) "🛡️" else "🚨",
                                    fontSize = 44.sp
                                )
                            }
                            Text(
                                text = if (serviceActive) "Shield Connection Active" else "Shield Offline",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            )

                            // Zero-Config Pairing Key Card
                            Card(
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF111827).copy(alpha = 0.6f)),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    if (deviceToken == null) {
                                        if (pairingKey.isEmpty()) {
                                            Text(text = "Connection Offline", fontWeight = FontWeight.Bold, color = Color(0xFFEF4444), fontSize = 14.sp)
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Text(
                                                text = "Unable to fetch pairing details. Verify internet connection or server settings.",
                                                fontSize = 12.sp,
                                                color = Color(0xFF94A3B8),
                                                textAlign = TextAlign.Center
                                            )
                                            Spacer(modifier = Modifier.height(14.dp))
                                            Button(
                                                onClick = { triggerPairRequest() },
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6)),
                                                shape = RoundedCornerShape(6.dp),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Text("Retry Pairing Request")
                                            }
                                        } else {
                                            Text(text = "Enter key in Web or Phone console to pair:", fontSize = 11.sp, color = Color(0xFF94A3B8))
                                            Spacer(modifier = Modifier.height(12.dp))
                                            
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                pairingKey.forEach { char ->
                                                    Box(
                                                        modifier = Modifier
                                                            .size(width = 34.dp, height = 44.dp)
                                                            .background(
                                                                color = Color(0xFF1F2937),
                                                                shape = RoundedCornerShape(6.dp)
                                                            ),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        Text(
                                                            text = char.toString(),
                                                            color = Color(0xFF10B981),
                                                            fontSize = 20.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            fontFamily = FontFamily.Monospace
                                                        )
                                                    }
                                                }
                                            }
                                            
                                            Spacer(modifier = Modifier.height(12.dp))
                                            val minutes = countdownSeconds / 60
                                            val seconds = countdownSeconds % 60
                                            Text(
                                                text = String.format("Expires in: %02d:%02d", minutes, seconds),
                                                fontSize = 13.sp,
                                                color = Color(0xFFF59E0B),
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            Spacer(modifier = Modifier.height(8.dp))
                                            TextButton(onClick = {
                                                stopPairingPoll()
                                                pairingKey = ""
                                                countdownSeconds = 0
                                            }) {
                                                Text("Cancel", color = Color(0xFF94A3B8), fontSize = 12.sp)
                                            }
                                        }
                                    } else {
                                        Text(text = "Security link active", color = Color(0xFF10B981), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text("Linked Server: $serverUrl", color = Color(0xFF94A3B8), fontSize = 11.sp, textAlign = TextAlign.Center)
                                        Spacer(modifier = Modifier.height(14.dp))
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
                                                Text(if (serviceActive) "Pause Service" else "Resume Service", fontSize = 11.sp)
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
                                                Text("Unpair", fontSize = 11.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    1 -> {
                        // TAB 2: Telemetry Stats Tab
                        Text(text = "Live Telemetry Status", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 16.sp)
                        
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF111827).copy(alpha = 0.6f)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("Device Hardware Details", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.sp)
                                
                                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                    Text("Device Name", color = Color(0xFF94A3B8), fontSize = 13.sp)
                                    Text(deviceName, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                }
                                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                    Text("Device ID", color = Color(0xFF94A3B8), fontSize = 13.sp)
                                    Text(deviceId.take(14) + "...", color = Color.White, fontSize = 13.sp)
                                }
                                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                    Text("Android Version", color = Color(0xFF94A3B8), fontSize = 13.sp)
                                    Text("API " + Build.VERSION.SDK_INT, color = Color.White, fontSize = 13.sp)
                                }
                            }
                        }

                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF111827).copy(alpha = 0.6f)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("Power & Display Status", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.sp)
                                
                                val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                                val isLocked = keyguardManager.isKeyguardLocked

                                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                    Text("Display Lock State", color = Color(0xFF94A3B8), fontSize = 13.sp)
                                    Text(
                                        text = if (isLocked) "LOCKED" else "UNLOCKED",
                                        color = if (isLocked) Color(0xFFF59E0B) else Color(0xFF10B981),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                    Text("Power Connection", color = Color(0xFF94A3B8), fontSize = 13.sp)
                                    Text("Battery Powered", color = Color.White, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                    2 -> {
                        // TAB 3: Local Audits Log Tab
                        Text(text = "Remote Command Log Timeline", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 16.sp)
                        
                        if (auditsList.size() == 0) {
                            Text(
                                text = "No commands received yet. When you trigger screen lock or ring from dashboard, logs will appear here.",
                                fontSize = 12.sp,
                                color = Color(0xFF94A3B8),
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 40.dp)
                            )
                        } else {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                for (i in 0 until auditsList.size()) {
                                    val audit = auditsList.get(i).asJsonObject
                                    val cmd = audit.get("command")?.asString ?: "Unknown"
                                    val time = audit.get("timestamp")?.asString ?: ""
                                    val status = audit.get("status")?.asString ?: "SUCCESS"
                                    val details = audit.get("details")?.asString ?: ""

                                    Card(
                                        colors = CardDefaults.cardColors(containerColor = Color(0xFF111827).copy(alpha = 0.4f)),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(14.dp),
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(text = if (status == "SUCCESS") "✓" else "✗", color = if (status == "SUCCESS") Color(0xFF10B981) else Color(0xFFEF4444), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(text = cmd, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.sp)
                                                Text(text = time, color = Color(0xFF94A3B8), fontSize = 10.sp)
                                                if (details.isNotEmpty()) {
                                                    Text(text = details, color = Color(0xFFEF4444), fontSize = 10.sp)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    3 -> {
                        // TAB 4: Local System Settings
                        Text(text = "App Administration", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 16.sp)

                        // Reset Configuration Button
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF111827).copy(alpha = 0.6f)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Re-run Setup Wizard", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.sp)
                                Text("Allows you to reconfigure Accessibility permissions and Device Administrator policy files.", fontSize = 11.sp, color = Color(0xFF94A3B8))
                                Spacer(modifier = Modifier.height(6.dp))
                                Button(
                                    onClick = {
                                        prefs.edit().putBoolean("onboarded", false).apply()
                                        onboarded.value = false
                                        renderContent()
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6)),
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Re-run Permissions Wizard", fontSize = 11.sp)
                                }
                            }
                        }

                        // App Role Reset Button
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF111827).copy(alpha = 0.6f)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Reset Device App Role", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.sp)
                                Text("Switches the app role selection screen back to first launch setup, shutting down active listeners.", fontSize = 11.sp, color = Color(0xFF94A3B8))
                                Spacer(modifier = Modifier.height(6.dp))
                                Button(
                                    onClick = {
                                        val intent = Intent(this@MainActivity, CompanionService::class.java)
                                        stopService(intent)
                                        prefs.edit().remove("app_role").remove("device_token").apply()
                                        deviceToken = null
                                        appRole.value = null
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Reset Device Role", fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Advanced Settings Dialog (Hidden power feature)
        if (showAdvancedSettings) {
            AlertDialog(
                onDismissRequest = { showAdvancedSettings = false },
                title = { Text("Advanced Server Settings", color = Color.White) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("You can change the target WebSocket relay server address if you host it elsewhere.", fontSize = 12.sp, color = Color(0xFF94A3B8))
                        OutlinedTextField(
                            value = customServerInput,
                            onValueChange = { customServerInput = it },
                            label = { Text("Server URL", color = Color(0xFF94A3B8)) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFF3B82F6)
                            )
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            prefs.edit().putString("server_url", customServerInput).apply()
                            showAdvancedSettings = false
                            Toast.makeText(this@MainActivity, "Server URL updated!", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6))
                    ) {
                        Text("Save")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showAdvancedSettings = false }) {
                        Text("Cancel", color = Color(0xFF94A3B8))
                    }
                },
                containerColor = Color(0xFF111827)
            )
        }
    }

    // ----------------------------------------------------
    // Screen 4: Controller Mode (Dashboard Phone)
    // ----------------------------------------------------
    @Composable
    fun ControllerScreen(appRole: MutableState<String?>) {
        val scrollState = rememberScrollState()

        var serverUrlInput by remember { mutableStateOf(prefs.getString("controller_server_url", defaultServerUrl) ?: defaultServerUrl) }
        var adminPasscode by remember { mutableStateOf("") }
        var adminToken by remember { mutableStateOf(prefs.getString("admin_token", null)) }

        var pairedDevices by remember { mutableStateOf<List<ControllerDevice>>(emptyList()) }
        var isFetchingDevices by remember { mutableStateOf(false) }
        var pairingKey by remember { mutableStateOf("") }
        var pairingMsg by remember { mutableStateOf("") }

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
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "LockIT Controller", fontSize = 14.sp, color = Color(0xFF8B5CF6), fontWeight = FontWeight.Bold)
                Text(
                    text = "Reset",
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
                Text(text = "🎮", fontSize = 48.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = "Controller Dashboard", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
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
                                            mainHandler.post { Toast.makeText(this@MainActivity, "Invalid passcode.", Toast.LENGTH_LONG).show() }
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
