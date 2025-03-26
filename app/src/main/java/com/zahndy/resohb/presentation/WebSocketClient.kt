package com.zahndy.resohb.presentation

import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

class WebSocketClient(serverUrl: String) {
    private val TAG = "WebSocketClient"

    private val serverUrl = formatUrl(serverUrl)

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)  // No timeout for reading
        .connectTimeout(10, TimeUnit.SECONDS)   // Add connect timeout
        .build()
    private var webSocket: WebSocket? = null
    private var isConnected = false
    private var reconnectJob: Job? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var reconnectAttempt = 0
    private val maxReconnectDelay = 30_000L // Max delay of 30 seconds
    private val maxReconnectAttempts = 10 // Maximum number of reconnection attempts
    private var isManuallyDisconnected = false

    // Format URL
    private fun formatUrl(url: String): String {
        // First trim any whitespace
        val trimmedUrl = url.trim()

        val formattedUrl = if (trimmedUrl.startsWith("ws://") || trimmedUrl.startsWith("wss://")) {
            trimmedUrl
        } else if (trimmedUrl.startsWith("http://")) {
            trimmedUrl.replace("http://", "ws://")
        } else if (trimmedUrl.startsWith("https://")) {
            trimmedUrl.replace("https://", "wss://")
        } else {
            "ws://$trimmedUrl"
        }

        // Use a different approach to validate the URL
        try {
            // Convert ws:// to http:// just for validation purposes
            val validationUrl = formattedUrl.replace("ws://", "http://").replace("wss://", "https://")
            java.net.URL(validationUrl) // This will throw if the URL structure is invalid
            Log.d(TAG, "Formatted URL from '$url' to '$formattedUrl' - valid: true")
            return formattedUrl
        } catch (e: Exception) {
            Log.e(TAG, "Invalid WebSocket URL: $formattedUrl", e)
            // If URL is invalid, try adding a default port
            val withDefaultPort = if (formattedUrl.contains(":")) {
                formattedUrl
            } else {
                "$formattedUrl:9555"
            }
            Log.d(TAG, "Trying with default port: $withDefaultPort")
            return withDefaultPort
        }
    }

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
                    reconnectAttempt = 0 // Reset reconnect attempts on successful connection
                    Log.d(TAG, "WebSocket connection established to $serverUrl")
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    super.onFailure(webSocket, t, response)
                    isConnected = false
                    Log.e(TAG, "WebSocket failed: ${t.message}", t)

                    // Only attempt to reconnect if not manually disconnected
                    if (!isManuallyDisconnected) {
                        scheduleReconnect()
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    super.onClosed(webSocket, code, reason)
                    isConnected = false
                    Log.d(TAG, "WebSocket closed: $reason")

                    // Only attempt to reconnect if not manually disconnected
                    if (!isManuallyDisconnected) {
                        scheduleReconnect()
                    }
                }

                // Called when a WebSocket message is received
                // override fun onMessage(webSocket: WebSocket, text: String) {
                //super.onMessage(webSocket, text)
                // We could handle server messages here if needed
                //Log.d(TAG, "Message received: $text")
                //}
            })
        } catch (e: Exception) {
            Log.e(TAG, "Exception creating WebSocket: ${e.message}", e)
            // Schedule reconnect after a short delay
            isConnected = false
            scheduleReconnect()
        }
    }

    // Send heart rate data
    fun sendHeartRate(heartRate: Int) {
        // Log connection state
        Log.d(TAG, "sendHeartRate called with connection status: isConnected=$isConnected, webSocket=${webSocket != null}")

        // If not connected, try to reconnect first
        if (!isConnected && !isManuallyDisconnected) {
            Log.d(TAG, "Not connected, attempting to reconnect before sending heart rate")
            scheduleReconnect(immediate = true)

            // Since reconnection is asynchronous, we'll try to send the data anyway
            // If the connection succeeds in the future, the next heart rate will be sent properly
        }

        // Send message if we have a WebSocket even if isConnected flag is false (might be a race condition)
        if (webSocket != null) {
            try {

                val message = "0|$heartRate" //,bat=$batteryPct,bat_charging=$isCharging              BPM=0 BAT=1 bat_charging=3
                val sent = webSocket?.send(message)
                //Log.d(TAG, "Message sent result: $sent, message: $message")
            } catch(e: Exception) {
                Log.e(TAG, "WebSocket exception when sending data: ${e.message}", e)
                // Mark as disconnected to force reconnection on next attempt
                isConnected = false
            }
        } else {
            Log.w(TAG, "Cannot send heart rate - WebSocket is null")
        }
    }

    // Close connection
    fun disconnect() {
        isManuallyDisconnected = true
        cancelReconnect()
        webSocket?.close(1000, "Closing connection")
        webSocket = null
        isConnected = false
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
}