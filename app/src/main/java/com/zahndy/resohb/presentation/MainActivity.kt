/* While this template provides a good starting point for using Wear Compose, you can always
 * take a look at https://github.com/android/wear-os-samples/tree/main/ComposeStarter to find the
 * most up to date changes to the libraries and their usages.
 */

package com.zahndy.resohb.presentation

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.TextField
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
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.tooling.preview.devices.WearDevices
import com.zahndy.resohb.R
import com.zahndy.resohb.presentation.theme.Reso_Theme
import com.zahndy.resohb.service.HeartRateService


class MainActivity : ComponentActivity() {
    companion object {
        const val PREFS_NAME = "HeartRateMonitorPrefs"
        const val SERVER_URL_KEY = "server_url"
        const val DEFAULT_SERVER_URL = "192.0.0.0:9555"
    }

    private val _isServiceRunning = mutableStateOf(false)
    val isServiceRunning get() = _isServiceRunning.value

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

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setTheme(android.R.style.Theme_DeviceDefault)

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
                }
            )
        }
    }

    private fun startHeartRateMonitoring() {
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
    }

    private fun startHeartRateService() {
        // Existing implementation
        val serviceIntent = Intent(this, HeartRateService::class.java)
        // Get the current server URL from SharedPreferences
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val serverUrl = prefs.getString(SERVER_URL_KEY, DEFAULT_SERVER_URL)

        // Pass the server URL to the service - add http:// prefix if not present
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
    }
}

@Composable
fun WearApp(
    initialServerUrl: String,
    isServiceRunning: Boolean,
    onToggleService: () -> Unit,
    onUrlChange: (String) -> Unit
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
                    TextField(
                        value = serverUrl,
                        onValueChange = {
                            serverUrl = it
                            onUrlChange(it)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight()
                            .height(47.dp)
                            .padding(start = 0.dp, top = 0.dp, end = 0.dp, bottom = 0.dp),
                        shape = RoundedCornerShape(23.dp),
                        textStyle = TextStyle(textAlign = TextAlign.Center),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
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
        onUrlChange = {}
    )
}