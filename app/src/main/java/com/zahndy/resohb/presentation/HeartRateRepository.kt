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
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

class HeartRateRepository(
    private val context: Context
) {
    private val healthClient = HealthServices.getClient(context)
    private val measureClient = healthClient.measureClient
    
    // Track power mode to adjust sample rate
    private var isPowerSavingMode = false
    private var hasActiveClients = false
    
    // Set a default sample rate that can be adjusted
    private var currentSampleRate = 500L // milliseconds

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
    fun updatePowerState(powerSaving: Boolean, activeClients: Boolean) {
        if (isPowerSavingMode != powerSaving || hasActiveClients != activeClients) {
            isPowerSavingMode = powerSaving
            hasActiveClients = activeClients
            
            // Adjust sample rate based on state
            currentSampleRate = when {
                !activeClients -> 2000L // If no clients, very slow sampling (2 seconds)
                powerSaving -> 1000L // Power saving but clients connected (1 second)
                else -> 500L // Normal mode with clients (500ms)
            }
            
            android.util.Log.d("HeartRateRepository", 
                "Updated power state - saving: $powerSaving, clients: $activeClients, sample rate: $currentSampleRate ms")
        }
    }

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
                    trySend(it.value.toInt())
                }
            }
        }

        // Use another coroutine to register the callback
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // This is a more power-efficient approach if the API supports it
                // Direct registration without custom sample rate for compatibility
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

        // Clean up when the flow collection ends
        awaitClose {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    measureClient.unregisterMeasureCallback(DataType.HEART_RATE_BPM, callback)
                    android.util.Log.d("HeartRateRepository", "Unregistered heart rate callback")
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
}