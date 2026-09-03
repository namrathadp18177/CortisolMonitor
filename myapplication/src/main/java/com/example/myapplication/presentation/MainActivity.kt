/* While this template provides a good starting point for using Wear Compose, you can always
 * take a look at https://github.com/android/wear-os-samples/tree/main/ComposeStarter to find the
 * most up to date changes to the libraries and their usages.
 */

package com.example.myapplication.presentation

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
import androidx.compose.ui.platform.LocalContext
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.tooling.preview.devices.WearDevices
import androidx.compose.ui.tooling.preview.Preview
import com.example.myapplication.VitalsStreamingService
import com.example.myapplication.presentation.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setTheme(android.R.style.Theme_DeviceDefault)

        setContent {
            WearApp(
                onStart = { requestPermissionsThenStart() },
                onStop = { stopService(Intent(this, VitalsStreamingService::class.java)) }
            )
        }
    }

    private val requiredPermissions: Array<String>
        get() = buildList {
            add(Manifest.permission.BODY_SENSORS)
            add(Manifest.permission.ACTIVITY_RECOGNITION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()

    private lateinit var permissionLauncher: androidx.activity.result.ActivityResultLauncher<Array<String>>

    private fun requestPermissionsThenStart() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            startVitalsService()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startVitalsService() {
        ContextCompat.startForegroundService(this, Intent(this, VitalsStreamingService::class.java))
    }

    init {
        // Registered in init so it's available before onCreate's setContent runs.
        permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { results ->
            if (results.values.all { it }) startVitalsService()
        }
    }
}

@Composable
fun WearApp(onStart: () -> Unit, onStop: () -> Unit) {
    val context = LocalContext.current
    var connected by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("Not streaming") }

    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                connected = intent?.getBooleanExtra("connected", false) ?: false
                val error = intent?.getStringExtra("error")
                statusText = when {
                    error != null -> error
                    connected -> "Streaming HRV · EDA · skin temp"
                    else -> "Not streaming"
                }
            }
        }
        val filter = IntentFilter(VitalsStreamingService.ACTION_STATUS)
        ContextCompat.registerReceiver(context, receiver, filter, RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }

    MyApplicationTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colors.background),
            contentAlignment = Alignment.Center
        ) {
            TimeText()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "CortisolMonitor",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colors.primary
                )
                Text(
                    text = statusText,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colors.onBackground
                )
                Button(onClick = if (connected) onStop else onStart) {
                    Text(if (connected) "Stop" else "Start")
                }
            }
        }
    }
}

@Preview(device = WearDevices.SMALL_ROUND, showSystemUi = true)
@Composable
fun DefaultPreview() {
    WearApp(onStart = {}, onStop = {})
}
