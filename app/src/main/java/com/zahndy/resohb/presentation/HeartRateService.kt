package com.zahndy.resohb.presentation

import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.CountDownTimer
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.zahndy.resohb.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout


class HeartRateService : Service(), WebSocketServer.WebSocketServerCallback {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var heartRateRepository: HeartRateRepository
    private lateinit var webSocketServer: WebSocketServer
    
    private val notificationManager by lazy { getSystemService(NOTIFICATION_SERVICE) as NotificationManager }
    private val builder by lazy { NotificationCompat.Builder(this, CHANNEL_ID) }

    // Default server port (will be overridden by intent extra if provided)
    private var serverPort = 9555
    private val timeoutTimer = object : CountDownTimer(1200000, 1000) {
        override fun onTick(millisUntilFinished: Long) {
            val totalSeconds = millisUntilFinished / 1000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            val timeString = String.format("%02d:%02d", minutes, seconds)
            broadcastTimeout(timeString)

            builder.setContentText("0 clients, shutdown in $timeString")
            notificationManager.notify(NOTIFICATION_ID, builder.build())
        }

        override fun onFinish() {
            broadcastTimeout("00:00")
            stopSelf()
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "HeartRateChannel"
        private const val PERMISSION_REQUEST_CODE = 100
        private const val SERVER_PORT_EXTRA = "server_port"
        const val BODY_SENSORS_PERMISSION = android.Manifest.permission.BODY_SENSORS
        const val TIMEOUT_ACTION = "com.zahndy.resohb.TIMEOUT_UPDATED"
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
        webSocketServer.setCallback(this)

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

            // If no clients are connected at start, begin the countdown
            if (webSocketServer.getConnectedClientCount() == 0) {
                timeoutTimer.start()
            }

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

    // WebSocketServerCallback implementation
    override fun onServerStarted(port: Int) {}
    override fun onServerError(errorMessage: String) {}
    override fun onClientConnected(count: Int) {
        timeoutTimer.cancel()
        broadcastTimeout("")
    }
    override fun onClientDisconnected(count: Int) {
        if (count == 0) {
            timeoutTimer.start()
        }
    }

    private fun broadcastTimeout(timeString: String) {
        val intent = Intent(TIMEOUT_ACTION).apply {
            putExtra("timeout", timeString)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun hasRequiredPermissions(): Boolean {
        // Check both notification permissions and body sensors permissions
        val hasBodySensorsPermission = ContextCompat.checkSelfPermission(
            this,
            BODY_SENSORS_PERMISSION
        ) == PackageManager.PERMISSION_GRANTED
        val hasNotificationPermission: Boolean = ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED


        // Log permission status to help with debugging
        Log.d("HeartRateService", "Body sensors permission: $hasBodySensorsPermission")
        Log.d("HeartRateService", "Notification permission: $hasNotificationPermission")

        return hasBodySensorsPermission && hasNotificationPermission
    }

    private var lastNotificationUpdate = 0L
    private var notificationUpdateInterval = 2000L // 2 seconds
    private var isInPowerSavingMode = false
    private var hasConnectedClients = false
    private var currentNetworkType = "Unknown"

    private fun collectHeartRate() {
        serviceScope.launch {
            // Check if we should be in power saving mode
            updatePowerSavingMode()

            heartRateRepository.heartRateFlow()
                // Only process distinct heart rate values
                .distinctUntilChanged()
                // Use conflate to drop intermediary values if processing is slow
                .conflate()
                .catch {
                    // Handle errors
                    stopSelf()
                }
                .collect { heartRate ->
                    // Send heart rate via WebSocket server
                    webSocketServer.broadcastHeartRate(heartRate)

                    // Update notification less frequently to save resources
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastNotificationUpdate > notificationUpdateInterval) {
                        val clientCount = webSocketServer.getConnectedClientCount()

                        if (clientCount > 0) {
                            notificationUpdateInterval = 2000L
                            val clientText = if (clientCount == 1) "1 client" else "$clientCount clients"
                            builder.setContentText("HR: $heartRate BPM | $clientText connected")
                            notificationManager.notify(NOTIFICATION_ID, builder.build())
                            lastNotificationUpdate = currentTime
                        }
                        else {
                            notificationUpdateInterval = 2000L
                        }
                    }
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

        // Get current network type
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = cm.activeNetwork ?: return
        val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return
        val networkType = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "Bluetooth"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
            else -> "Unknown"
        }

        if (newPowerSavingMode != isInPowerSavingMode ||
            newHasConnectedClients != hasConnectedClients ||
            networkType != currentNetworkType) {

            isInPowerSavingMode = newPowerSavingMode
            hasConnectedClients = newHasConnectedClients
            currentNetworkType = networkType

            heartRateRepository.updatePowerState(
                powerSaving = isInPowerSavingMode,
                activeClients = hasConnectedClients,
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
        val intent = Intent(this, MainActivity::class.java).apply {
            // These flags ensure we reuse the existing activity instance
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val activityOptions = ActivityOptions.makeBasic()
        activityOptions.setPendingIntentCreatorBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, flags, activityOptions.toBundle())

         builder
            .setContentTitle("Heart Rate Monitor")
            .setContentText(contentText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        return builder.build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        try {
            timeoutTimer.cancel()
            
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
