package com.sensorstream

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.sensorstream.ui.StreamScreen
import com.sensorstream.ui.theme.SensorStreamTheme
import com.sensorstream.vm.StreamViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: StreamViewModel by viewModels()

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        requestBatteryExemption()
        // Keep the screen on while streaming: on this class of device, locking the phone
        // backgrounds the app and the OEM throttles delivery (latency ~7ms -> ~150ms). Holding
        // the screen on keeps the app foreground, so the live stream stays low-latency. The
        // flag is cleared when streaming stops, so the screen sleeps normally otherwise.
        lifecycleScope.launch {
            viewModel.engineState.collect { st ->
                if (st.streaming) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        setContent {
            SensorStreamTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    StreamScreen(viewModel)
                }
            }
        }
    }

    /** Ask the OS to exempt the app from Doze / battery optimization so long
     *  screen-off streaming isn't throttled. One-time system dialog until granted. */
    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            runCatching {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName"),
                    )
                )
            }
        }
    }
}
