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
import kotlinx.coroutines.launch
import java.lang.reflect.Method
import kotlinx.coroutines.withContext

class HeartRateRepository(
    context: Context
) {
    private val healthClient = HealthServices.getClient(context)
    private val measureClient = healthClient.measureClient

    // Properly implemented as a suspend function that can be called from a coroutine
    suspend fun hasHeartRateCapability(): Boolean {
        return try {
            val capabilities = measureClient.getCapabilities()
            android.util.Log.d("HeartRateRepository", "Checking heart rate capability")
            android.util.Log.d("HeartRateRepository", "Capabilities class: ${capabilities::class.java.name}")

            // Instead of using a non-existent method, we'll try to register for heart rate updates
            // If registration succeeds, heart rate is supported
            try {
                // Try to register for heart rate updates directly
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
  
    fun heartRateFlow(): Flow<Int> = callbackFlow {
        val callback = object : MeasureCallback {
            override fun onAvailabilityChanged(dataType: DeltaDataType<*, *>, availability: Availability) {
                // Handle availability changes if needed
            }

            override fun onDataReceived(data: DataPointContainer) {
                val heartRateBpm = data.getData(DataType.HEART_RATE_BPM)
                heartRateBpm.forEach {
                    // Simply send all heart rate values received
                    trySend(it.value.toInt())
                }
            }
        }

        // Use another coroutine to register the callback
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // If registerMeasureCallback is a suspend function, we need to call it in a coroutine
                measureClient.registerMeasureCallback(DataType.HEART_RATE_BPM, callback)
            } catch (e: Exception) {
                // Handle the error and close the channel if registration fails
                this@callbackFlow.close(e)
            }
        }

        // Clean up when the flow collection ends
        awaitClose {
            CoroutineScope(Dispatchers.IO).launch {
                measureClient.unregisterMeasureCallback(DataType.HEART_RATE_BPM, callback)
            }
        }
    }.distinctUntilChanged()
}