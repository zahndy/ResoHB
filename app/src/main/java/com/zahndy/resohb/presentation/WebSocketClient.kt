package com.zahndy.resohb.data

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class WebSocketClient(private val serverUrl : String, private val context: Context) {
    private val TAG = "WebSocketClient"
    
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)  // No timeout for reading
        .connectTimeout(10, TimeUnit.SECONDS)   // Add connect timeout
        .build()
    private var webSocket: WebSocket? = null
    private var isConnected = false
    val isConnectionActive: Boolean
        get() = isConnected

    private val batteryManager by lazy {
        context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    }
    private var lastHeartRate = -1
    private var lastBatteryPercentage = -1
    private var lastBatteryCharging = false // tracking for last charging state
    private var lastBatteryUpdate = 0L
    private val batteryUpdateInterval = 60_000L // Only update battery every minute
    private var heartRateBuffer = mutableListOf<Int>()
    private var lastBroadcastTime = 0L
    private val messageInterval = 1000L

    private var reconnectJob: Job? = null
    private var reconnectAttempt = 0
    private val maxReconnectDelay = 30_000L // Max delay of 30 seconds
    private val maxReconnectAttempts = 10 // Maximum number of reconnection attempts
    private var isManuallyDisconnected = false

    // Calculate exponential backoff delay
    private fun getReconnectDelay(): Long {
        val delay = when {
            reconnectAttempt <= 0 -> 100L  // First retry almost immediately
            reconnectAttempt == 1 -> 1_000L  // 1 second
            else -> minOf(
                1000L * (1 shl minOf(reconnectAttempt - 1, 5)), // Exponential backoff: 1, 2, 4, 8, 16, 32 seconds
                maxReconnectDelay
            )
        }
        Log.d(TAG, "Reconnect attempt $reconnectAttempt with delay $delay ms")
        return delay
    }

    private val messageQueue = mutableListOf<String>()
    private val queueLock = Object()
    private val clientScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Send heart rate data
    fun sendHeartRate(heartRate: Int) {
        val message = "0|$heartRate"

        if (!isConnected) {
            synchronized(queueLock) {
                // Queue message if disconnected (limit queue size to prevent memory issues)
                if (messageQueue.size < 100) {
                    messageQueue.add(message)
                    Log.d(TAG, "Message queued while disconnected")
                }
            }
            if (!isManuallyDisconnected) {
                scheduleReconnect(immediate = true)
            }
            return
        }

        heartRateBuffer.add(heartRate)
        val currentTime = System.currentTimeMillis()

        // Only send if enough time has passed AND if the heart rate has changed
        if ((currentTime - lastBroadcastTime > messageInterval && abs(heartRate - lastHeartRate) > 1)) {
            // Use the most recent heart rate
            val latestRate = heartRateBuffer.lastOrNull() ?: heartRate
            heartRateBuffer.clear()

            if (latestRate != lastHeartRate) {
                val messageHeartRate = "0|$latestRate"
                sendMessage(messageHeartRate)
                lastHeartRate = latestRate
                lastBroadcastTime = currentTime
            }
        }

        // Check if it's time to update battery percentage (once per minute)
        if (currentTime - lastBatteryUpdate >= batteryUpdateInterval) {
            val batteryPercentage = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

            // Only send battery percentage if it changed
            if (batteryPercentage != lastBatteryPercentage) {
                sendMessage("1|$batteryPercentage")
                lastBatteryPercentage = batteryPercentage
            }

            lastBatteryUpdate = currentTime
        }

        // Check charging status on every heart rate update, independent of timer
        val isCharging = batteryManager.isCharging
        if (isCharging != lastBatteryCharging) {
            sendMessage("2|$isCharging")
            lastBatteryCharging = isCharging
        }
    }

    private fun sendMessage(message: String) {
        webSocket?.let { ws ->
            try {
                val sent = ws.send(message)
                if (!sent) {
                    synchronized(queueLock) {
                        if (messageQueue.size < 100) {
                            messageQueue.add(message)
                        }
                    }
                }
            } catch(e: Exception) {
                Log.e(TAG, "WebSocket exception when sending data: ${e.message}", e)
                isConnected = false
                broadcastClientStatus()
                scheduleReconnect()
            }
        }
    }

    // Initialize connection
    fun connect() {
        // Reset reconnect state
        isManuallyDisconnected = false
        reconnectAttempt = 0
        cancelReconnect()

        // Log the URL we're connecting to
        Log.d(TAG, "Connecting to WebSocket URL: $serverUrl")

        try {
            val request = Request.Builder()
                .url(serverUrl)
                .build()
            
            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    super.onOpen(webSocket, response)
                    isConnected = true
                    broadcastClientStatus()
                    reconnectAttempt = 0 // Reset reconnect attempts on successful connection
                    Log.d(TAG, "WebSocket connection established to $serverUrl")
                   
                    // Send any queued messages
                    synchronized(queueLock) {
                        messageQueue.forEach { message ->
                            sendMessage(message)
                        }
                        messageQueue.clear()
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    super.onFailure(webSocket, t, response)
                    isConnected = false
                    broadcastClientStatus()
                    Log.e(TAG, "WebSocket failed: ${t.message}", t)

                    // Only attempt to reconnect if not manually disconnected
                    if (!isManuallyDisconnected) {
                        scheduleReconnect()
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    super.onClosed(webSocket, code, reason)
                    isConnected = false
                    broadcastClientStatus()
                    Log.d(TAG, "WebSocket closed: $reason")

                    // Only attempt to reconnect if not manually disconnected
                    if (!isManuallyDisconnected) {
                        scheduleReconnect()
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Exception creating WebSocket: ${e.message}", e)
            // Schedule reconnect after a short delay
            isConnected = false
            broadcastClientStatus()
            scheduleReconnect()
        }
    }

    // Close connection
    fun disconnect() {
        isManuallyDisconnected = true
        cancelReconnect()
        synchronized(queueLock) {
            messageQueue.clear()
        }
        webSocket?.close(1000, "Closing connection")
        webSocket = null
        isConnected = false
        broadcastClientStatus()
        clientScope.cancel()
    }

    // Schedule reconnection with exponential backoff
    private fun scheduleReconnect(immediate: Boolean = false) {
        // Don't reconnect if we've exceeded the max attempts
        if (reconnectAttempt >= maxReconnectAttempts && !immediate) {
            Log.e(TAG, "Maximum reconnection attempts reached ($maxReconnectAttempts). Giving up.")
            return
        }

        cancelReconnect() // Cancel any existing reconnection job

        reconnectJob = CoroutineScope(Dispatchers.IO).launch {
            if (!immediate) {
                reconnectAttempt++
                val delay = getReconnectDelay()
                Log.d(TAG, "Scheduling reconnect attempt $reconnectAttempt in ${delay}ms")
                delay(delay)
            } else {
                Log.d(TAG, "Immediate reconnection requested")
            }
            
            // Check if we need to reconnect
            if (!isManuallyDisconnected) {
                Log.d(TAG, "Attempting to reconnect...")
                withContext(Dispatchers.Main) {
                    connect()
                }
            }
        }
    }

    // Cancel any pending reconnection
    private fun cancelReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
    }

    private fun broadcastClientStatus() {
        Log.d(TAG, "Broadcasting client status: isConnected=$isConnected")
        val intent = Intent("com.zahndy.resohb.CLIENTS_UPDATED").apply {
            putExtra("ClientConnected", isConnected)
            setPackage(context.packageName)
        }
        context.sendBroadcast(intent)
    }
}