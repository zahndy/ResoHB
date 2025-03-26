package com.zahndy.resohb.presentation

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.util.Log
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArraySet
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer as JavaWebSocketServer


class WebSocketServer(
    private val context: Context,
    private val port: Int = 9555
) {
    private val TAG = "WebSocketServer"
    private var server: InternalWebSocketServer? = null
    private var isRunning = false
    private val connectedClients = CopyOnWriteArraySet<WebSocket>()
    private val batteryManager by lazy {
        context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    }

    private var lastHeartRate = -1
    private var lastBatteryPercentage = -1
    private var lastBatteryCharging = false // tracking for last charging state
    private var lastBatteryUpdate = 0L
    private val BATTERY_UPDATE_INTERVAL = 60_000L // Only update battery every minute

    private var clientCount = 0

    // Interface for callbacks
    interface WebSocketServerCallback {
        fun onServerStarted(port: Int)
        fun onServerError(errorMessage: String)
        fun onClientConnected(count: Int)
        fun onClientDisconnected(count: Int)
    }

    private var callback: WebSocketServerCallback? = null

    fun setCallback(callback: WebSocketServerCallback?) {
        this.callback = callback
    }

    // Start the server
    fun start() {
        if (isRunning) return
        
        try {
            // Add explicit socket options to help with address reuse
            server = InternalWebSocketServer(InetSocketAddress(port)).apply {
                isReuseAddr = true
                isTcpNoDelay = true
                connectionLostTimeout = 60 // 60 seconds timeout
            }
            
            server?.start()
            isRunning = true
            callback?.onServerStarted(port)
            Log.d(TAG, "WebSocket server started on port $port")
        } catch (e: Exception) {
            val errorMsg = "Failed to start WebSocket server: ${e.message}"
            Log.e(TAG, errorMsg, e)
            isRunning = false
            server = null
            callback?.onServerError(errorMsg)
        }
    }

    // Stop the server
    fun stop() {
        try {
            // First mark server as not running to prevent new operations
            isRunning = false
            
            // Clear clients first to prevent them trying to reconnect during shutdown
            val clientsToClose = ArrayList(connectedClients)
            connectedClients.clear()
            
            // Close client connections with a reason
            for (client in clientsToClose) {
                try {
                    client.close(1001, "Server shutting down")
                } catch (e: Exception) {
                    Log.e(TAG, "Error closing client: ${e.message}")
                }
            }
            
            // Now stop the server with a timeout
            server?.stop(1500)
            server = null
            
            Log.d(TAG, "WebSocket server stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping WebSocket server: ${e.message}", e)
        } finally {
            // Ensure server reference is cleared
            server = null
        }
    }

    // Check if the server is running
    fun isServerRunning(): Boolean = isRunning

    // Get number of connected clients
    fun getConnectedClientCount(): Int = connectedClients.size

    private var heartRateBuffer = mutableListOf<Int>()
    private var lastBroadcastTime = 0L
    private val BROADCAST_INTERVAL = 1000L // Broadcast at most every 1000ms

    // Send heart rate to all connected clients
    fun broadcastHeartRate(heartRate: Int) {
        if (!isRunning) return
        
        heartRateBuffer.add(heartRate)
        val currentTime = System.currentTimeMillis()
        
        // Only broadcast if we have connected clients and enough time has passed
        // and if the heart rate has changed
        if (connectedClients.isNotEmpty() && 
            (currentTime - lastBroadcastTime > BROADCAST_INTERVAL &&
             Math.abs(heartRate - lastHeartRate) > 1)) {
            
            // Use the most recent heart rate
            val latestRate = heartRateBuffer.lastOrNull() ?: heartRate
            heartRateBuffer.clear()
            
            if (latestRate != lastHeartRate) {
                val messageHeartRate = "0|$latestRate"
                sendToAllClients(messageHeartRate)
                lastHeartRate = latestRate
                lastBroadcastTime = currentTime
            }
        }

        // Check if it's time to update battery percentage (once per minute)
        if (currentTime - lastBatteryUpdate >= BATTERY_UPDATE_INTERVAL) {
            val batteryPercentage = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            
            // Only send battery percentage if it changed
            if (batteryPercentage != lastBatteryPercentage) {
                sendToAllClients("1|$batteryPercentage")
                lastBatteryPercentage = batteryPercentage
            }
            
            lastBatteryUpdate = currentTime
        }
        
        // Check charging status on every heart rate update, independent of timer
        val isCharging = batteryManager.isCharging
        if (isCharging != lastBatteryCharging) {
            sendToAllClients("2|$isCharging")
            lastBatteryCharging = isCharging
        }
    }
    
    // Helper method to send messages to all clients
    private fun sendToAllClients(message: String) {
        try {
            for (client in connectedClients) {
                //Log.d(TAG, "Sent Message To Client: $message")
                client.send(message)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error broadcasting heart rate: ${e.message}", e)
        }
    }

    internal fun clientsChanged() {
        clientCount = connectedClients.size
        broadcastClientCount()
    }

    private fun broadcastClientCount() {
        val intent = Intent("com.zahndy.resohb.CLIENTS_UPDATED").apply {
            putExtra("clientCount", clientCount)
            setPackage(context.packageName)
        }
        context.sendBroadcast(intent)
    }

    // Internal WebSocket server implementation
    private inner class InternalWebSocketServer(address: InetSocketAddress) : JavaWebSocketServer(address) {
        override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
            connectedClients.add(conn)
            clientsChanged()
            Log.d(TAG, "New client connected: ${conn.remoteSocketAddress}, total clients: ${connectedClients.size}")
            
            // Send initial values to new client
            if (lastHeartRate >= 0) {
                conn.send("0|$lastHeartRate")
            }
            if (lastBatteryPercentage >= 0) {
                conn.send("1|$lastBatteryPercentage")
            }
            // Also send initial charging status
            conn.send("2|$lastBatteryCharging")
        }

        override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
            connectedClients.remove(conn)
            clientsChanged()
            Log.d(TAG, "Client disconnected. Code: $code, Reason: $reason, Remote: $remote, remaining clients: ${connectedClients.size}")
        }

        override fun onMessage(conn: WebSocket, message: String) {
            Log.d(TAG, "Received message from client: $message")
            // Handle incoming messages if needed
        }

        override fun onError(conn: WebSocket?, ex: Exception) {
            Log.e(TAG, "WebSocket error: ${ex.message}", ex)
            if (conn != null) {
                connectedClients.remove(conn)
                clientsChanged()
            }
        }

        override fun onStart() {
            Log.d(TAG, "WebSocket server started successfully")
        }
    }
}