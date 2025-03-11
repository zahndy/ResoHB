/* While this template provides a good starting point for using Wear Compose, you can always
 * take a look at https://github.com/android/wear-os-samples/tree/main/ComposeStarter to find the
 * most up to date changes to the libraries and their usages.
 */

package com.zahndy.resohb.presentation

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
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
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.tooling.preview.devices.WearDevices
import com.zahndy.resohb.R
import com.zahndy.resohb.presentation.theme.Reso_Theme
import com.zahndy.resohb.service.HeartRateService
import java.net.NetworkInterface
import java.util.Collections


class MainActivity : ComponentActivity() {
    companion object {
        const val PREFS_NAME = "HeartRateMonitorPrefs"
        const val SERVER_URL_KEY = "server_url"
        const val DEFAULT_SERVER_URL = "192.0.0.0:9555"
    }

    private val _isServiceRunning = mutableStateOf(false)
    val isServiceRunning get() = _isServiceRunning.value

    private val _networkStatus = mutableStateOf("Unknown")
    val networkStatus get() = _networkStatus.value

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


        setContent {
            // Get saved URL from SharedPreferences
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val savedUrl = prefs.getString(SERVER_URL_KEY, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL

            WearApp(
                initialServerUrl = savedUrl,
                isServiceRunning = isServiceRunning,
                onToggleService = {
                    if (isServiceRunning) {
                        stopHeartRateService()
                    } else {
                        startHeartRateMonitoring()
                    }
                },
                onUrlChange = { newUrl ->
                    // Save new URL to SharedPreferences
                    prefs.edit().putString(SERVER_URL_KEY, newUrl).apply()
                },
                networkStatus = networkStatus
            )
        }
    }

    private fun startHeartRateMonitoring() {
        // Ensure the service is stopped first to free up the port
        stopHeartRateService()

        // Increase the delay to ensure socket fully closes
        Handler(Looper.getMainLooper()).postDelayed({
            // Request Wi-Fi connectivity first
            requestWifiConnectivity()

            
            val permissions = mutableListOf(
                Manifest.permission.BODY_SENSORS,
                Manifest.permission.BODY_SENSORS_BACKGROUND,
                Manifest.permission.FOREGROUND_SERVICE_HEALTH
            ).apply {
                // Add notification permission for Android 13+
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    add(Manifest.permission.POST_NOTIFICATIONS)
                }
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
        }, 500) // Increased delay from 200 to 500ms
    }
    private fun startHeartRateService() {
        val serviceIntent = Intent(this, HeartRateService::class.java)
        // Get the current server URL from SharedPreferences
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val serverUrl = prefs.getString(SERVER_URL_KEY, DEFAULT_SERVER_URL)

        // Pass the server URL to the service - add ws:// prefix if not present
        val finalUrl = if (serverUrl?.startsWith("ws://") == true || serverUrl?.startsWith("wss://") == true) {
            serverUrl
        } else {
            "ws://${serverUrl ?: DEFAULT_SERVER_URL}"
        }
        serviceIntent.putExtra("server_url", finalUrl)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        // Set service state to running
        _isServiceRunning.value = true
    }

    // Add a method to stop the heart rate service
    private fun stopHeartRateService() {
        val serviceIntent = Intent(this, HeartRateService::class.java)
        stopService(serviceIntent)
        _isServiceRunning.value = false

        // Release Wi-Fi network
        releaseWifiConnectivity()

        // Add a delay before allowing restart to ensure port is released
        Handler(Looper.getMainLooper()).postDelayed({
            // The port should be released now
            Log.d("MainActivity", "Socket port should be released now")
        }, 300)
    }

    private fun requestWifiConnectivity() {
        // Create a network callback
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
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
    }
    override fun onDestroy() {
        // Make sure to release Wi-Fi when activity is destroyed
        releaseWifiConnectivity()
        super.onDestroy()
    }

    // Make sure to unregister callbacks in onPause/onStop to avoid leaks
    override fun onStop() {
        // If the app is being force-closed, make sure to stop the service
        if (isFinishing) {
            stopHeartRateService()
        }
        super.onStop()
    }
}

@Composable
fun WearApp(
    initialServerUrl: String,
    isServiceRunning: Boolean,
    onToggleService: () -> Unit,
    onUrlChange: (String) -> Unit,
    networkStatus: String = "Unknown"
) {
    var serverUrl by remember { mutableStateOf(initialServerUrl) }
    val listState = rememberScalingLazyListState()

    Reso_Theme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 0.dp)
                .background(MaterialTheme.colors.background),
            contentAlignment = Alignment.Center,
        ) {
            ScalingLazyColumn(
                modifier = Modifier.fillMaxSize()
                    .padding(top = 0.dp),
                state = listState,
                contentPadding = PaddingValues(0.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                item {

                    Text(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 0.dp),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colors.primary,
                        text = "Resonite HR Monitor"
                    )
                }

                item {
                    Spacer(modifier = Modifier.height(8.dp))
                }

                item {
                    Button(
                        modifier = Modifier
                            .width(140.dp),
                        onClick = onToggleService
                    ) {
                        Text(if (isServiceRunning) "Stop" else "Start")
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
                        text = "Enter Server Adress:"
                    )
                }

                item {
                    OutlinedTextField (
                        value = serverUrl,
                        onValueChange = {
                            serverUrl = it
                            onUrlChange(it)
                        },
                        modifier = Modifier
                            .width(180.dp)
                            .fillMaxHeight()
                            .height(50.dp)
                            .padding(start = 0.dp, top = 0.dp, end = 0.dp, bottom = 0.dp),
                        shape = RoundedCornerShape(25.dp),
                        textStyle = TextStyle(textAlign = TextAlign.Center,color = MaterialTheme.colors.primaryVariant, fontSize = 15.sp),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
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

@Preview(device = WearDevices.SMALL_ROUND, showSystemUi = true)
@Composable
fun DefaultPreview() {
    WearApp(
        initialServerUrl = MainActivity.DEFAULT_SERVER_URL,
        isServiceRunning = false,
        onToggleService = {},
        onUrlChange = {},
        networkStatus = "Wi-Fi"
    )
}