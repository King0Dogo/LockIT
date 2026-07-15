package com.kingo.lockit

import android.app.*
import android.app.admin.DevicePolicyManager
import android.content.*
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.ConnectivityManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.google.gson.JsonObject
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject
import java.net.URISyntaxException

class CompanionService : Service() {

    companion object {
        private const val TAG = "CompanionService"
        private const val CHANNEL_ID = "companion_service_channel"
        private const val ALERT_CHANNEL_ID = "companion_alerts_channel"
        private const val NOTIFICATION_ID = 8888
        private const val ALERT_NOTIFICATION_ID = 9999
        
        // Static state to let UI know if service is running
        var isServiceRunning = false
            private set
    }

    private var socket: Socket? = null
    private var telemetryReceiver: BroadcastReceiver? = null
    private val gson = Gson()
    private var ringtone: Ringtone? = null

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        createNotificationChannels()
        startForeground(NOTIFICATION_ID, buildServiceNotification("Connecting to server..."))
        
        registerTelemetryReceiver()
        initializeSocket()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == "STOP") {
            stopSelf()
            return START_NOT_STICKY
        }
        
        // Ensure socket is connected if we get restarted
        if (socket?.connected() == false) {
            socket?.connect()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        
        // Disconnect WebSocket
        socket?.disconnect()
        socket?.off()
        socket = null
        
        // Unregister Broadcast Receiver
        if (telemetryReceiver != null) {
            unregisterReceiver(telemetryReceiver)
            telemetryReceiver = null
        }
        
        // Stop ringtone if active
        stopRingtoneAlert()
        
        Log.d(TAG, "Companion service destroyed")
    }

    // ----------------------------------------------------
    // WebSocket Setup
    // ----------------------------------------------------
    private fun initializeSocket() {
        val prefs = getSharedPreferences("lockit_prefs", Context.MODE_PRIVATE)
        val serverUrl = prefs.getString("server_url", "https://lockit-backend-ipfu.onrender.com") ?: "https://lockit-backend-ipfu.onrender.com"
        val deviceToken = prefs.getString("device_token", null)
        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)

        if (deviceToken.isNullOrEmpty()) {
            Log.w(TAG, "No pairing token found. Service running in idle mode.")
            updateServiceNotification("Awaiting pairing configuration")
            return
        }

        try {
            val opts = IO.Options().apply {
                auth = mapOf(
                    "role" to "device",
                    "deviceId" to deviceId,
                    "token" to deviceToken
                )
                reconnection = true
                reconnectionDelay = 2000
            }

            socket = IO.socket(serverUrl, opts)

            socket?.on(Socket.EVENT_CONNECT) {
                Log.d(TAG, "WebSocket connected successfully")
                updateServiceNotification("Linked to Web Admin")
                sendTelemetry()
            }

            socket?.on(Socket.EVENT_DISCONNECT) {
                Log.d(TAG, "WebSocket disconnected")
                updateServiceNotification("Offline - Reconnecting...")
            }

            socket?.on(Socket.EVENT_CONNECT_ERROR) { args ->
                val err = args.firstOrNull()
                Log.e(TAG, "WS Connect Error: $err")
                updateServiceNotification("Connection Error - Retrying")
            }

            socket?.on("command") { args ->
                val data = args.firstOrNull() as? JSONObject
                if (data != null) {
                    handleRemoteCommand(data)
                }
            }

            socket?.connect()

        } catch (e: URISyntaxException) {
            Log.e(TAG, "Invalid server URL syntax: $serverUrl", e)
            updateServiceNotification("Invalid server address")
        }
    }

    // ----------------------------------------------------
    // Command Router
    // ----------------------------------------------------
    private fun handleRemoteCommand(data: JSONObject) {
        val commandId = data.optString("id")
        val command = data.optString("command")
        val payload = data.optJSONObject("payload")

        Log.d(TAG, "Command received: $command ($commandId)")

        when (command) {
            "lock" -> {
                // Try Accessibility Service lock
                var success = LockAccessibilityService.lockScreen()
                var errorMsg: String? = null

                if (!success) {
                    Log.w(TAG, "Accessibility Lock failed. Trying Device Admin...")
                    // Try Device Admin fallback
                    val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                    val adminComponent = ComponentName(this, DeviceAdminRcvr::class.java)
                    if (dpm.isAdminActive(adminComponent)) {
                        try {
                            dpm.lockNow()
                            success = true
                        } catch (e: Exception) {
                            errorMsg = e.message
                        }
                    } else {
                        errorMsg = "Accessibility Service and Device Admin are not enabled."
                    }
                }

                logLocalAudit("Screen Lock", if (success) "SUCCESS" else "FAILED", errorMsg)
                sendAck(commandId, success, errorMsg)
            }
            "ring" -> {
                val success = triggerRingtoneAlert()
                logLocalAudit("Sound Alert Played", if (success) "SUCCESS" else "FAILED", if (success) null else "Could not play ringtone.")
                sendAck(commandId, success, if (success) null else "Could not play alert ringtone.")
            }
            "notify" -> {
                val message = payload?.optString("message") ?: "Remote Alert Triggered!"
                showUserAlertNotification(message)
                logLocalAudit("Alert Notification Displayed", "SUCCESS", "Message: $message")
                sendAck(commandId, true, null)
            }
            else -> {
                logLocalAudit("Unknown Command: $command", "FAILED", "Error: Unknown command")
                sendAck(commandId, false, "Unknown remote command: $command")
            }
        }
    }

    private fun logLocalAudit(commandName: String, status: String, details: String?) {
        try {
            val prefs = getSharedPreferences("lockit_prefs", Context.MODE_PRIVATE)
            val auditsJson = prefs.getString("local_audits", "[]") ?: "[]"
            val auditList = gson.fromJson(auditsJson, Array<JsonObject>::class.java).toMutableList()
            
            val newAudit = JsonObject().apply {
                addProperty("command", commandName)
                addProperty("timestamp", java.text.DateFormat.getDateTimeInstance().format(java.util.Date()))
                addProperty("status", status)
                addProperty("details", details ?: "")
            }
            
            auditList.add(0, newAudit)
            if (auditList.size > 50) {
                auditList.removeAt(auditList.size - 1)
            }
            
            prefs.edit().putString("local_audits", gson.toJson(auditList)).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving local audit log", e)
        }
    }

    private fun sendAck(commandId: String, success: Boolean, errorMsg: String?) {
        val ack = JSONObject().apply {
            put("commandId", commandId)
            put("status", if (success) "SUCCESS" else "FAILED")
            if (errorMsg != null) {
                put("error", errorMsg)
            }
        }
        socket?.emit("command_ack", ack)
    }

    // ----------------------------------------------------
    // Remote Ring Implementation
    // ----------------------------------------------------
    private fun triggerRingtoneAlert(): Boolean {
        try {
            stopRingtoneAlert() // Stop existing if running
            
            val defaultUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                
            ringtone = RingtoneManager.getRingtone(this, defaultUri)
            
            // Set max volume
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING)
            audioManager.setStreamVolume(AudioManager.STREAM_RING, maxVolume, 0)
            
            ringtone?.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
                
            ringtone?.play()
            
            // Stop playing after 5 seconds
            Handler(Looper.getMainLooper()).postDelayed({
                stopRingtoneAlert()
            }, 5000)
            
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error playing ring alert", e)
            return false
        }
    }

    private fun stopRingtoneAlert() {
        if (ringtone?.isPlaying == true) {
            ringtone?.stop()
        }
        ringtone = null
    }

    // ----------------------------------------------------
    // User alert notification
    // ----------------------------------------------------
    private fun showUserAlertNotification(message: String) {
        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Remote Companion Alert")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .build()

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(ALERT_NOTIFICATION_ID, notification)
    }

    // ----------------------------------------------------
    // Telemetry Sync
    // ----------------------------------------------------
    private fun registerTelemetryReceiver() {
        telemetryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                sendTelemetry()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(telemetryReceiver, filter)
    }

    private fun sendTelemetry() {
        if (socket?.connected() != true) return

        val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { ifilter ->
            registerReceiver(null, ifilter)
        }

        val level: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale.toFloat()).toInt() else 50

        val status: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val screenStatus = if (keyguardManager.isKeyguardLocked) "LOCKED" else "UNLOCKED"

        val telemetry = JSONObject().apply {
            put("batteryLevel", batteryPct)
            put("isCharging", isCharging)
            put("screenStatus", screenStatus)
        }

        socket?.emit("telemetry", telemetry)
        Log.d(TAG, "Sent telemetry: $telemetry")
    }

    // ----------------------------------------------------
    // Notification & Channel Setup
    // ----------------------------------------------------
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Remote Companion Foreground Connection",
                NotificationManager.IMPORTANCE_LOW
            )
            val alertChannel = NotificationChannel(
                ALERT_CHANNEL_ID,
                "Remote Companion Alerts",
                NotificationManager.IMPORTANCE_HIGH
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
            manager.createNotificationChannel(alertChannel)
        }
    }

    private fun buildServiceNotification(contentText: String): Notification {
        val stopIntent = Intent(this, CompanionService::class.java).apply {
            action = "STOP"
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val mainIntent = Intent(this, MainActivity::class.java)
        val mainPendingIntent = PendingIntent.getActivity(
            this, 0, mainIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Remote Companion running")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(mainPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Connection", stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateServiceNotification(text: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, buildServiceNotification(text))
    }
}
