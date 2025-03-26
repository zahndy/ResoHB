/* While this template provides a good starting point for using Wear Compose, you can always
 * take a look at https://github.com/android/wear-os-samples/tree/main/ComposeStarter to find the
 * most up to date changes to the libraries and their usages.
 */

package com.zahndy.resohb.presentation

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyColumnDefaults
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.tooling.preview.devices.WearDevices
import com.zahndy.resohb.R
import com.zahndy.resohb.presentation.theme.Reso_Theme
import com.zahndy.resohb.service.HeartRateService
import kotlinx.coroutines.launch
import java.net.NetworkInterface
import java.util.Collections


class MainActivity : ComponentActivity() {
    companion object {
        const val PREFS_NAME = "HeartRateMonitorPrefs"
        const val SERVER_PORT_KEY = "server_port"
        const val DEFAULT_SERVER_PORT = 9555
    }

    private val _isServiceRunning = mutableStateOf(false)
    val isServiceRunning get() = _isServiceRunning.value

    private val _networkStatus = mutableStateOf("Unknown")
    val networkStatus get() = _networkStatus.value

    private val _connectedClients = mutableStateOf(0)
    val connectedClients get() = _connectedClients.value

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
            startHeartRateService()
        } else {
            // Handle permission denial
            Toast.makeText(this, "Permissions required for heart rate monitoring", Toast.LENGTH_LONG).show()
        }
    }

    private val clientUpdateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let {
                val count = it.getIntExtra("clientCount", 0)
                _connectedClients.value = count
            }
        }
    }

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setTheme(android.R.style.Theme_DeviceDefault)

        // Initialize connectivity manager
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        // Register a network callback to monitor network changes
        registerNetworkCallback()

        // Get initial network status
        updateNetworkStatus()

        // Force stop any existing service without checking
        val serviceIntent = Intent(this, HeartRateService::class.java)
        stopService(serviceIntent)
        _isServiceRunning.value = false

        // Add a delay before proceeding to ensure port is released
        Handler(Looper.getMainLooper()).postDelayed({
            Log.d("MainActivity", "Existing service should be stopped, proceeding with app initialization")
            // Rest of your initialization, if needed
        }, 500)

        // Register broadcast receiver to get client connection updates
        val clientUpdateFilter = IntentFilter("com.zahndy.resohb.CLIENTS_UPDATED")
        registerReceiver(clientUpdateReceiver, clientUpdateFilter, Context.RECEIVER_NOT_EXPORTED)

        setContent {
            // Get saved port from SharedPreferences
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val savedPort = prefs.getInt(SERVER_PORT_KEY, DEFAULT_SERVER_PORT)

            // Get device IP address to display
            val deviceIp = getDeviceIpAddress()

            WearApp(
                deviceIpAddress = deviceIp,
                serverPort = savedPort,
                isServiceRunning = isServiceRunning,
                onToggleService = {
                    if (isServiceRunning) {
                        stopHeartRateService()
                    } else {
                        startHeartRateMonitoring()
                    }
                },
                onPortChange = { newPort ->
                    // Save new port to SharedPreferences
                    try {
                        val port = newPort.toInt()
                        if (port in 1024..65535) {
                            prefs.edit().putInt(SERVER_PORT_KEY, port).apply()
                        }
                    } catch (e: NumberFormatException) {
                        // Invalid port format, ignore
                    }
                },
                networkStatus = networkStatus,
                connectedClients = connectedClients
            )
        }
        requestBatteryOptimizationExemption()
    }

    private fun getDeviceIpAddress(): String {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr.hostAddress?.contains(':') == false && addr.hostAddress != null) {
                        return addr.hostAddress!!
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error getting device IP: ${e.message}", e)
        }
        return "127.0.0.1" // Fallback to localhost
    }


    private fun startHeartRateMonitoring() {
        // First check if service is actually running
        if (isServiceRunning) {
            Log.d("MainActivity", "Service is already running, stopping first")
            stopHeartRateService()

            // Add a state variable to track that we want to restart
            val pendingRestart = true

            // Set up a receiver to know when the service has actually stopped
            val filter = IntentFilter("com.zahndy.resohb.SERVICE_STOPPED")
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (pendingRestart) {
                        requestWifiConnectivity()
                        val permissions = mutableListOf(
                            Manifest.permission.BODY_SENSORS,
                            Manifest.permission.BODY_SENSORS_BACKGROUND,
                            Manifest.permission.FOREGROUND_SERVICE_HEALTH
                        ).apply {
                            // Add notification permission
                            add(Manifest.permission.POST_NOTIFICATIONS)
                        }.toTypedArray()

                        // Check if permissions are already granted
                        if (permissions.all {
                                ContextCompat.checkSelfPermission(this@MainActivity, it) == PackageManager.PERMISSION_GRANTED
                            }) {
                            startHeartRateService()
                        } else {
                            // Request permissions
                            requestPermissionLauncher.launch(permissions)
                        }
                    }

                    unregisterReceiver(this)
                }
            }
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)

        } else {
            // Service isn't running, we can start directly
            requestWifiConnectivity()

            val permissions = mutableListOf(
                Manifest.permission.BODY_SENSORS,
                Manifest.permission.BODY_SENSORS_BACKGROUND,
                Manifest.permission.FOREGROUND_SERVICE_HEALTH
            ).apply {
                // Add notification permission
                add(Manifest.permission.POST_NOTIFICATIONS)
            }.toTypedArray()

            // Check if permissions are already granted
            if (permissions.all {
                    ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
                }) {
                startHeartRateService()
            } else {
                // Request permissions
                requestPermissionLauncher.launch(permissions)
            }
        }
    }

    private fun startHeartRateService() {
        val serviceIntent = Intent(this, HeartRateService::class.java)
        // Get the server port from SharedPreferences
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val serverPort = prefs.getInt(SERVER_PORT_KEY, DEFAULT_SERVER_PORT)

        // Pass the server port to the service
        serviceIntent.putExtra("server_port", serverPort)

        startForegroundService(serviceIntent)

        // Set service state to running
        _isServiceRunning.value = true
    }

    private fun stopHeartRateService() {
        val serviceIntent = Intent(this, HeartRateService::class.java)
        stopService(serviceIntent)
        _isServiceRunning.value = false

        // Release Wi-Fi network
        releaseWifiConnectivity()

        // Send broadcast to signal the service has stopped
        sendBroadcast(Intent("com.zahndy.resohb.SERVICE_STOPPED").setPackage(packageName))
    }

    private fun requestWifiConnectivity() {
        // First release any existing callback to prevent leaks
        releaseWifiConnectivity()

        // Create a new network callback
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                // Wi-Fi network has been acquired, bind it to use this network by default
                connectivityManager?.bindProcessToNetwork(network)
                Log.d("MainActivity", "Wi-Fi network available and bound to process")

                // Update network status
                updateNetworkStatus()

                // After Wi-Fi is available, we can proceed with checking permissions
                // The actual service start happens after permissions check
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                Log.d("MainActivity", "Wi-Fi network lost")

                // Update network status
                updateNetworkStatus()

                // Attempt to reconnect to Wi-Fi when connection is lost
                if (isServiceRunning) {
                    Log.d("MainActivity", "Attempting to reconnect to Wi-Fi...")
                    Handler(Looper.getMainLooper()).postDelayed({
                        try {
                            connectivityManager?.requestNetwork(
                                NetworkRequest.Builder()
                                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                                    .build(),
                                this
                            )
                            Log.d("MainActivity", "Re-requested Wi-Fi network after loss")
                        } catch (e: Exception) {
                            Log.e("MainActivity", "Error re-requesting Wi-Fi network: ${e.message}", e)
                        }
                    }, 1000) // Wait a second before attempting to reconnect
                }
            }
        }

        // Store the callback so we can unregister it later
        networkCallback = callback

        // Request Wi-Fi network
        try {
            connectivityManager?.requestNetwork(
                NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build(),
                callback
            )
            Log.d("MainActivity", "Requested Wi-Fi network")
        } catch (e: Exception) {
            Log.e("MainActivity", "Error requesting Wi-Fi network: ${e.message}", e)
            // Continue with permission check even if Wi-Fi request failed
        }
    }

    private fun releaseWifiConnectivity() {
        try {
            // Unbind from Wi-Fi network
            connectivityManager?.bindProcessToNetwork(null)

            // Unregister the network callback
            networkCallback?.let {
                connectivityManager?.unregisterNetworkCallback(it)
                networkCallback = null
            }

            Log.d("MainActivity", "Released Wi-Fi network")
        } catch (e: Exception) {
            Log.e("MainActivity", "Error releasing Wi-Fi network: ${e.message}", e)
        }
    }

    private fun registerNetworkCallback() {
        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                updateNetworkStatus()
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                updateNetworkStatus()
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                super.onCapabilitiesChanged(network, networkCapabilities)
                updateNetworkStatus()
            }
        }

        // Register the callback for all network types
        val request = NetworkRequest.Builder().build()
        connectivityManager?.registerNetworkCallback(request, networkCallback)
    }

    private fun updateNetworkStatus() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = cm.activeNetwork ?: return
        val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return

        _networkStatus.value = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi   ✓"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "Bluetooth   X"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular   X"
            else -> "Unknown"
        }
    }

    private fun requestBatteryOptimizationExemption() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val packageName = packageName

        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            try {
                val intent = Intent().apply {
                    action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to request battery optimization exemption", e)
            }
        }
    }
    override fun onDestroy() {
        // Make sure to release Wi-Fi when activity is destroyed
        releaseWifiConnectivity()
        // Unregister the client update receiver
        try {
            unregisterReceiver(clientUpdateReceiver)
        } catch (e: Exception) {
            Log.e("MainActivity", "Error unregistering client receiver: ${e.message}", e)
        }
        super.onDestroy()
    }

    // Make sure to unregister callbacks in onPause/onStop to avoid leaks
    override fun onStop() {
        // If the app is being force-closed, make sure to stop the service
        if (isFinishing) {
            stopHeartRateService()
        }
        try {
            unregisterReceiver(clientUpdateReceiver)
        } catch (e: Exception) {
            Log.e("MainActivity", "Error unregistering client receiver: ${e.message}", e)
        }
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        // Register receiver when activity becomes visible
        val clientUpdateFilter = IntentFilter("com.zahndy.resohb.CLIENTS_UPDATED")
        registerReceiver(clientUpdateReceiver, clientUpdateFilter, Context.RECEIVER_NOT_EXPORTED)
    }

}

@Composable
fun WearApp(
    deviceIpAddress: String,
    serverPort: Int,
    isServiceRunning: Boolean,
    onToggleService: () -> Unit,
    onPortChange: (String) -> Unit,
    networkStatus: String = "Unknown",
    connectedClients: Int = 0
) {
    var port by remember { mutableStateOf(serverPort.toString()) }
    val listState = rememberScalingLazyListState()

    val connectionAddress = "ws://$deviceIpAddress:$serverPort"

    val focusRequester = remember { FocusRequester() }
    val coroutineScope = rememberCoroutineScope()

    val haptic = LocalHapticFeedback.current
    // Add timestamp tracking for haptic feedback
    var lastHapticFeedbackTime by remember { mutableStateOf(0L) }

    Reso_Theme {
        Scaffold(
            positionIndicator = {
                PositionIndicator(scalingLazyListState = listState)
            }
        ) {
            ScalingLazyColumn(
                modifier = Modifier.fillMaxSize()
                    .padding(start = 0.dp, top = 0.dp, end = 0.dp, bottom = 2.dp)
                    .onRotaryScrollEvent {
                        coroutineScope.launch {
                            listState.scrollBy(it.verticalScrollPixels)
                            
                            // Add throttling logic for haptic feedback (500ms)
                            val currentTime = System.currentTimeMillis()
                            if (currentTime - lastHapticFeedbackTime > 100L) {
                                haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                                lastHapticFeedbackTime = currentTime
                            }
                        }
                        true
                    }
                    .focusRequester(focusRequester)
                    .focusable(),
                state = listState, 
                flingBehavior = ScalingLazyColumnDefaults.snapFlingBehavior(state = listState),
                contentPadding = PaddingValues(start = 0.dp, top = 0.dp, end = 0.dp, bottom = 2.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                item {
                    Text(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 0.dp),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colors.primary,
                        text = "Resonite HR Server"
                    )
                }

                item {
                    Spacer(modifier = Modifier.height(8.dp))
                }

                item {
                    Button(
                        modifier = Modifier.width(140.dp),
                        onClick = onToggleService,
                    ) {
                        Text(
                            text = when {
                                isServiceRunning -> "Stop Server"
                                else -> "Start Server"
                            }
                        )
                    }
                }

                if (isServiceRunning) {
                    item {
                        Spacer(
                            modifier = Modifier.height(2.dp)
                        )
                    }
                    item {
                        Text(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 0.dp, vertical = 0.dp),
                            textAlign = TextAlign.Center,
                            fontSize = 14.sp,
                            color = MaterialTheme.colors.secondary,
                            text = "Client should connect to:"
                        )
                    }
                    item {
                        Text(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 0.dp),
                            textAlign = TextAlign.Center,
                            fontSize = 13.sp,
                            color = MaterialTheme.colors.primary,
                            text = connectionAddress
                        )
                    }
                    item {
                        Spacer(
                            modifier = Modifier.height(2.dp)
                        )
                    }
                    item {
                        Text(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 0.dp),
                            textAlign = TextAlign.Center,
                            fontSize = 12.sp,
                            color = MaterialTheme.colors.secondary,
                            text = "Clients: $connectedClients" //show the current amount of connected clients
                        )
                    }
                    item {
                        Spacer(
                            modifier = Modifier.height(10.dp)
                        )
                    }
                }

                item {
                    Text(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 0.dp),
                        textAlign = TextAlign.Center,
                        fontSize = 10.sp,
                        color = MaterialTheme.colors.secondary,
                        text = "Server Port:"
                    )
                }

                item {
                    OutlinedTextField (
                        value = port,
                        onValueChange = {
                            port = it
                            onPortChange(it)
                        },
                        modifier = Modifier
                            .width(110.dp)
                            .height(50.dp)
                            .padding(start = 0.dp, top = 0.dp, end = 0.dp, bottom = 0.dp),
                        shape = RoundedCornerShape(25.dp),
                        textStyle = TextStyle(textAlign = TextAlign.Center,color = MaterialTheme.colors.primaryVariant, fontSize = 19.sp),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }
                item {
                    Spacer(
                        modifier = Modifier.height(4.dp)
                    )
                }

                    item {
                        Text(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 0.dp, vertical = 0.dp),
                            textAlign = TextAlign.Center,
                            fontSize = 10.sp,
                            color = MaterialTheme.colors.secondary,
                            text = "Current Watch Network:"
                        )
                    }

                    item {
                        Text(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 0.dp),
                            textAlign = TextAlign.Center,
                            fontSize = 14.sp,
                            color = MaterialTheme.colors.primary,
                            text = networkStatus
                        )
                    }
                    item {
                        Spacer(
                            modifier = Modifier.height(10.dp)
                        )
                    }
                
            }
        }
    }
    LaunchedEffect(Unit){
        focusRequester.requestFocus()
    }
}

@Composable
fun Greeting(greetingName: String) {
    Text(
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
        color = MaterialTheme.colors.primary,
        text = stringResource(R.string.hello_world, greetingName)
    )
}

@Preview(device = WearDevices.SMALL_ROUND, showSystemUi = true,apiLevel = 34)
@Composable
fun DefaultPreview() {
    WearApp(
        deviceIpAddress = "ws://localhost:1234",
        serverPort = 9333,
        isServiceRunning = false,
        onToggleService = {},
        onPortChange = {},
        networkStatus = "Wi-Fi",
        connectedClients = 0
    )
}