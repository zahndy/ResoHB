package com.zahndy.resohb.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.zahndy.resohb.R
import com.zahndy.resohb.data.HeartRateRepository
import com.zahndy.resohb.data.WebSocketClient
import com.zahndy.resohb.presentation.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.milliseconds

class HeartRateService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var heartRateRepository: HeartRateRepository
    private lateinit var webSocketClient: WebSocketClient

    // Default server URL (will be overridden by intent extra if provided)
    private var serverUrl = "ws://192.168.1.100:8080/heartrate" // Use HTTP protocol for WebSocket

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "HeartRateChannel"
        private const val PERMISSION_REQUEST_CODE = 100
        private const val SERVER_URL_EXTRA = "server_url"
        const val BODY_SENSORS_PERMISSION = android.Manifest.permission.BODY_SENSORS
    }

    override fun onCreate() {
        super.onCreate()
        heartRateRepository = HeartRateRepository(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Get server URL from intent extra
        intent?.getStringExtra(SERVER_URL_EXTRA)?.let {
            if (it.isNotEmpty()) {
                // Make sure we use ws:// prefix
                serverUrl = if (it.startsWith("ws://") || it.startsWith("wss://")) {
                    it
                } else {
                    "ws://$it"
                }
                Log.d("HeartRateService", "Using server URL: $serverUrl")
            }
        }
        
        // Initialize WebSocketClient with server URL - it will format the URL correctly
        webSocketClient = WebSocketClient(serverUrl,applicationContext)

        if (!hasRequiredPermissions()) {
            Log.e("HeartRateService", "Missing required permissions")
            stopSelf()
            return START_NOT_STICKY
        }


        val notification = createNotification("Monitoring heart rate...")
        startForeground(NOTIFICATION_ID, notification)

        serviceScope.launch {
            webSocketClient.connect()

            // Add detailed logging to diagnose the heart rate capability
            val hasCapability = heartRateRepository.hasHeartRateCapability()
            Log.d("HeartRateService", "Heart rate capability check result: $hasCapability")

            if (hasCapability) {
                Log.d("HeartRateService", "Starting heart rate collection")
                collectHeartRate()
            } else {
                Log.e("HeartRateService", "Device does not have heart rate capability")
                stopSelf()
            }
        }

        return START_STICKY
    }

    private fun hasRequiredPermissions(): Boolean {
        // Check both notification permissions and body sensors permissions
        val hasBodySensorsPermission = ContextCompat.checkSelfPermission(
            this,
            BODY_SENSORS_PERMISSION
        ) == PackageManager.PERMISSION_GRANTED

        var hasNotificationPermission = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasNotificationPermission = ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        }

        // Log permission status to help with debugging
        Log.d("HeartRateService", "Body sensors permission: $hasBodySensorsPermission")
        Log.d("HeartRateService", "Notification permission: $hasNotificationPermission")

        return hasBodySensorsPermission && hasNotificationPermission
    }
    private var lastNotificationUpdate = 0L
    private val NOTIFICATION_UPDATE_INTERVAL = 2000L // 2 seconds
    private var isInPowerSavingMode = false
    private var isConnected = false
    private var currentNetworkType = "Unknown"

    private fun collectHeartRate() {
        serviceScope.launch {
            // Check if we should be in power saving mode
            updatePowerSavingMode()

            heartRateRepository.heartRateFlow()
                // Only process distinct heart rate values
                .distinctUntilChanged()
                // Sample the flow based on our sample rate
                .sample(heartRateRepository.getCurrentSampleRate().milliseconds)
                // Use conflate to drop intermediary values if processing is slow
                .conflate()
                .catch { e ->
                    // Handle errors
                    stopSelf()
                }
                .collect { heartRate ->
                    // Send heart rate over WebSocket
                    webSocketClient.sendHeartRate(heartRate)

                    // Update notification less frequently to save resources
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastNotificationUpdate > NOTIFICATION_UPDATE_INTERVAL) {
                        val isConnected = webSocketClient.isConnectionActive

                        // Only update notification if connected
                        if (isConnected) {
                            val notification = createNotification("HR: $heartRate BPM | Connected")
                            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                            notificationManager.notify(NOTIFICATION_ID, notification)
                            lastNotificationUpdate = currentTime
                        }
                    }

                    // Periodically check if power saving mode changed
                    updatePowerSavingMode()
                }
        }
    }
    private fun updatePowerSavingMode(forceLowPower: Boolean = false) {
        // Check if power saving mode is on
        val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val newPowerSavingMode = forceLowPower || powerManager.isPowerSaveMode
        val newisConnected = webSocketClient.isConnectionActive

        // Get current network type
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val activeNetwork = cm.activeNetworkInfo
        val networkType = when {
            activeNetwork?.type == android.net.ConnectivityManager.TYPE_WIFI -> "Wi-Fi"
            activeNetwork?.type == android.net.ConnectivityManager.TYPE_MOBILE -> "Cellular"
            else -> "Unknown"
        }

        if (newPowerSavingMode != isInPowerSavingMode ||
            newisConnected != isConnected ||
            networkType != currentNetworkType) {

            isInPowerSavingMode = newPowerSavingMode
            isConnected = newisConnected
            currentNetworkType = networkType

            heartRateRepository.updatePowerState(
                powerSaving = isInPowerSavingMode,
                activeConnection = isConnected,
                networkType = currentNetworkType
            )
        }
    }
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Heart Rate Monitoring",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Channel for heart rate monitoring service"
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(contentText: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Heart Rate Monitor")
            .setContentText(contentText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.launch {
            try {
                // 1. Stop any websocket connection
                Log.d("HeartRateService", "Stopping WebSocket connection")
                webSocketClient.disconnect()

                // 2. Cancel all coroutines and wait for them to complete
                withTimeout(2000) { // 2 second timeout
                    serviceScope.coroutineContext[Job]?.children?.forEach { child ->
                        child.cancelAndJoin()
                    }
                }

                // 3. Release any sensor resources if necessary
                heartRateRepository.releaseResources()

                Log.d("HeartRateService", "Service destroyed, all resources released")
            } catch (e: Exception) {
                Log.e("HeartRateService", "Error during service shutdown: ${e.message}", e)
            } finally {
                // 4. Cancel the service scope itself
                serviceScope.cancel()
                
                withContext(Dispatchers.Main) {
                    super.onDestroy()
                }
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Clean up when the app is removed from recent tasks
        stopSelf()
    }

    // Handle low memory conditions
    override fun onLowMemory() {
        super.onLowMemory()
        // Reduce sample rate and clear any buffers
        updatePowerSavingMode(forceLowPower = true)
    }
}