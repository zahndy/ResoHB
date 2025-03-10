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
import com.zahndy.resohb.data.WebSocketServer
import com.zahndy.resohb.presentation.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.milliseconds

class HeartRateService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var heartRateRepository: HeartRateRepository
    private lateinit var webSocketServer: WebSocketServer

    // Default server port (will be overridden by intent extra if provided)
    private var serverPort = 9555

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "HeartRateChannel"
        private const val PERMISSION_REQUEST_CODE = 100
        private const val SERVER_PORT_EXTRA = "server_port"
        const val BODY_SENSORS_PERMISSION = android.Manifest.permission.BODY_SENSORS
    }

    override fun onCreate() {
        super.onCreate()
        heartRateRepository = HeartRateRepository(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Get server port from intent extra if available
        intent?.getIntExtra(SERVER_PORT_EXTRA, serverPort)?.let {
            if (it > 0) {
                serverPort = it
            }
        }

        // Initialize WebSocketServer with port
        webSocketServer = WebSocketServer(applicationContext, serverPort)

        if (!hasRequiredPermissions()) {
            Log.e("HeartRateService", "Missing required permissions")
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = createNotification("Starting heart rate server...")
        startForeground(NOTIFICATION_ID, notification)

        serviceScope.launch {
            // Start WebSocket server
            webSocketServer.start()

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
    
    private var isInPowerSavingMode = false
    private var hasConnectedClients = false

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
                    // Send heart rate via WebSocket server
                    webSocketServer.broadcastHeartRate(heartRate)

                    // Update notification with current heart rate and number of connected clients
                    val clientCount = webSocketServer.getConnectedClientCount()
                    
                    // Only update notification if we have clients
                    if (clientCount > 0) {
                        val clientText = if (clientCount == 1) "1 client" else "$clientCount clients"
                        val notification = createNotification("HR: $heartRate BPM | $clientText connected")
                        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                        notificationManager.notify(NOTIFICATION_ID, notification)
                    }
                    
                    // Periodically check if power saving mode changed
                    updatePowerSavingMode()
                }
        }
    }
    
    // Update power saving mode and adjust sampling rate
    private fun updatePowerSavingMode() {
        // Check if power saving mode is on
        val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val newPowerSavingMode = powerManager.isPowerSaveMode
        val newHasConnectedClients = webSocketServer.getConnectedClientCount() > 0
        
        if (newPowerSavingMode != isInPowerSavingMode || newHasConnectedClients != hasConnectedClients) {
            isInPowerSavingMode = newPowerSavingMode
            hasConnectedClients = newHasConnectedClients
            
            heartRateRepository.updatePowerState(powerSaving = isInPowerSavingMode, activeClients = hasConnectedClients)
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
        try {
            // 1. Stop any websocket server
            Log.d("HeartRateService", "Stopping WebSocketServer")
            webSocketServer.stop()
            
            // 2. Cancel all coroutines
            serviceScope.cancel()
            
            // 3. Make sure to wait for any pending socket operations to complete
            runBlocking {
                // Give coroutines a chance to complete cleanly with timeout
                withTimeout(1000) {
                    serviceScope.coroutineContext[Job]?.children?.forEach { child ->
                        child.join()
                    }
                }
            }
            
            // 4. Release any sensor resources if necessary
            heartRateRepository.releaseResources()
            
            Log.d("HeartRateService", "Service destroyed, all resources released")
        } catch (e: Exception) {
            Log.e("HeartRateService", "Error during service shutdown: ${e.message}", e)
        } finally {
            super.onDestroy()
        }
    }
}