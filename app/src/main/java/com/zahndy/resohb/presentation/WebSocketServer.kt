package com.zahndy.resohb.data

import android.content.Context
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
    
    // Add adaptive interval tracking
    private var lastHeartRate = -1
    private var lastBatteryPercentage = -1
    private var lastBatteryCharging = false // Add tracking for last charging state
    private var lastBatteryUpdate = 0L
    private val BATTERY_UPDATE_INTERVAL = 60_000L // Only update battery every minute

    // Start the server
    fun start() {
        if (isRunning) return
        
        try {
            server = InternalWebSocketServer(InetSocketAddress(port))
            // Add these two options to help with socket reuse
            server?.isReuseAddr = true
            server?.isTcpNoDelay = true
            
            // Lower the server connection read timeout when idle
            server?.connectionLostTimeout = 60 // 60 seconds
            
            server?.start()
            isRunning = true
            Log.d(TAG, "WebSocket server started on port $port")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start WebSocket server: ${e.message}", e)
            isRunning = false
        }
    }

    // Stop the server
    fun stop() {
        try {
            // Use a more graceful shutdown with timeout
            server?.stop(1000) // 1000ms timeout
            isRunning = false
            Log.d(TAG, "WebSocket server stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping WebSocket server: ${e.message}", e)
        } finally {
            server = null
        }
    }

    // Check if the server is running
    fun isServerRunning(): Boolean = isRunning

    // Get number of connected clients
    fun getConnectedClientCount(): Int = connectedClients.size

    // Send heart rate to all connected clients
    fun broadcastHeartRate(heartRate: Int) {
        if (!isRunning || connectedClients.isEmpty()) {
            // No need to log - this is expected when no clients are connected
            return
        }

        // Only send heart rate if it changed
        if (heartRate != lastHeartRate) {
            val messageHeartRate = "0|$heartRate"
            sendToAllClients(messageHeartRate)
            lastHeartRate = heartRate
        }

        // Check if it's time to update battery percentage (once per minute)
        val currentTime = System.currentTimeMillis()
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
                client.send(message)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error broadcasting heart rate: ${e.message}", e)
        }
    }

    // Internal WebSocket server implementation
    private inner class InternalWebSocketServer(address: InetSocketAddress) : JavaWebSocketServer(address) {
        override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
            connectedClients.add(conn)
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
            }
        }

        override fun onStart() {
            Log.d(TAG, "WebSocket server started successfully")
        }
    }
}