package com.zahndy.resohb.data

import android.content.Context
import androidx.health.services.client.HealthServices
import androidx.health.services.client.MeasureCallback
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DeltaDataType
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataTypeAvailability
import androidx.health.services.client.getCapabilities
import androidx.health.services.client.unregisterMeasureCallback
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.runBlocking

class HeartRateRepository(
    context: Context
) {
    private val healthClient = HealthServices.getClient(context)
    private val measureClient = healthClient.measureClient
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Track power mode to adjust sample rate
    private var isPowerSavingMode = false
    private var isConnected = false
    private var currentNetworkType = "Unknown"
    private var currentCallback: MeasureCallback? = null

    // Set a default sample rate that can be adjusted
    private var currentSampleRate = 1000L // milliseconds

    /**
     * Returns the current sample rate in milliseconds
     */
    fun getCurrentSampleRate(): Long {
        return currentSampleRate
    }

    // Properly implemented as a suspend function that can be called from a coroutine
    suspend fun hasHeartRateCapability(): Boolean {
        return try {
            val capabilities = measureClient.getCapabilities()
            android.util.Log.d("HeartRateRepository", "Checking heart rate capability")
            android.util.Log.d("HeartRateRepository", "Capabilities class: ${capabilities::class.java.name}")

            try {
                // Try to register for heart rate updates directly with a temporary callback
                measureClient.registerMeasureCallback(DataType.HEART_RATE_BPM, object : MeasureCallback {
                    override fun onAvailabilityChanged(dataType: DeltaDataType<*, *>, availability: Availability) {}
                    override fun onDataReceived(data: DataPointContainer) {}
                })

                // If we got here, heart rate is supported - immediately unregister
                measureClient.unregisterMeasureCallback(DataType.HEART_RATE_BPM, object : MeasureCallback {
                    override fun onAvailabilityChanged(dataType: DeltaDataType<*, *>, availability: Availability) {}
                    override fun onDataReceived(data: DataPointContainer) {}
                })
                android.util.Log.d("HeartRateRepository", "Heart rate is supported")
                true
            } catch (e: Exception) {
                android.util.Log.e("HeartRateRepository", "Heart rate not supported", e)
                false
            }
        } catch (e: Exception) {
            android.util.Log.e("HeartRateRepository", "Failed to get capabilities", e)
            false
        }
    }

    /**
     * Sets the power and client state to adjust sampling behavior
     */
    fun updatePowerState(powerSaving: Boolean, activeConnection: Boolean, networkType: String = "Unknown") {
        if (isPowerSavingMode != powerSaving || isConnected != activeConnection || currentNetworkType != networkType) {
            isPowerSavingMode = powerSaving
            isConnected = activeConnection
            currentNetworkType = networkType

            // Adjust sample rate based on state
            currentSampleRate = when {
                !activeConnection -> 10000L // No clients: very slow sampling (10s)
                powerSaving && !networkType.contains("Wi-Fi") -> 3000L // Power saving on cellular: slower (3s)
                powerSaving && networkType.contains("Wi-Fi") -> 2000L // Power saving on WiFi: moderate (2s)
                !networkType.contains("Wi-Fi") -> 1500L // Normal mode on cellular (1.5s)
                else -> 1000L // Best case: normal mode on WiFi (1s)
            }

            android.util.Log.d("HeartRateRepository",
                "Updated state - saving: $powerSaving, Connection: $activeConnection, " +
                        "network: $networkType, sample rate: $currentSampleRate ms")
        }
    }

    private var lastHeartRate = -1
    private val heartRateThreshold = 1 // Only report changes of 3+ BPM

    fun heartRateFlow(): Flow<Int> = callbackFlow {
        val callback = object : MeasureCallback {
            override fun onAvailabilityChanged(dataType: DeltaDataType<*, *>, availability: Availability) {
                when (availability) {
                    is DataTypeAvailability -> {
                        android.util.Log.d("HeartRateRepository", "Heart rate sensor available")
                    }
                    else -> {
                        android.util.Log.d("HeartRateRepository", "Heart rate sensor not available: $availability")
                    }
                }
            }

            override fun onDataReceived(data: DataPointContainer) {
                val heartRateBpm = data.getData(DataType.HEART_RATE_BPM)
                heartRateBpm.forEach {
                    val newRate = it.value.toInt()
                    // Only send if the difference is significant or it's the first reading
                    if (lastHeartRate == -1 || Math.abs(newRate - lastHeartRate) >= heartRateThreshold) {
                        lastHeartRate = newRate
                        trySend(newRate)
                    }
                }
            }
        }.also { currentCallback = it }

        repositoryScope.launch {
            try {
                measureClient.registerMeasureCallback(
                    DataType.HEART_RATE_BPM,
                    callback
                )
                android.util.Log.d("HeartRateRepository", "Registered without custom sample rate")
            } catch (e: Exception) {
                android.util.Log.e("HeartRateRepository", "Failed to register callback", e)
                this@callbackFlow.close(e)
            }
        }

        awaitClose {
            repositoryScope.launch {
                try {
                    currentCallback?.let { callback ->
                        measureClient.unregisterMeasureCallback(DataType.HEART_RATE_BPM, callback)
                        currentCallback = null
                        android.util.Log.d("HeartRateRepository", "Unregistered heart rate callback")
                    }
                } catch (e: Exception) {
                    android.util.Log.e("HeartRateRepository", "Error unregistering callback", e)
                }
            }
        }
    }
        // Apply power optimizations to the flow - distinct values, sample based on power state, and conflate
        .distinctUntilChanged()
        .sample(getCurrentSampleRate().milliseconds)
        .conflate()

    // New method for releasing resources
    fun releaseResources() {
        try {
            runBlocking {
                currentCallback?.let { callback ->
                    measureClient.unregisterMeasureCallback(DataType.HEART_RATE_BPM, callback)
                    currentCallback = null
                }
            }
            repositoryScope.cancel()
            android.util.Log.d("HeartRateRepository", "Resources released")
        } catch (e: Exception) {
            android.util.Log.e("HeartRateRepository", "Error releasing resources: ${e.message}")
        }
    }
}