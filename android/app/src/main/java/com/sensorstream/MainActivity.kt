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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.sensorstream.ui.AppScaffold
import com.sensorstream.ui.settings.ThemeMode
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
        // Keep the screen on while streaming: on this class of device, locking the phone
        // backgrounds the app and the OEM throttles delivery (latency ~7ms -> ~150ms). Holding
        // the screen on keeps the app foreground, so the live stream stays low-latency. The
        // flag is cleared when streaming stops, so the screen sleeps normally otherwise.
        lifecycleScope.launch {
            viewModel.engineState.collect { st ->
                if (st.streaming) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                // Ask for the Doze/battery exemption the first time streaming starts — when it
                // actually matters — and only once; Settings offers it again if declined.
                if (st.streaming) maybeAskBatteryExemptionOnce()
            }
        }
        setContent {
            val settings by viewModel.settings.collectAsState()
            val dark = when (settings.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            SensorStreamTheme(dark = dark) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppScaffold(viewModel)
                }
            }
        }
    }

    private fun maybeAskBatteryExemptionOnce() {
        val prefs = getSharedPreferences("sensorstream", Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_ASKED_BATTERY, false)) return
        prefs.edit().putBoolean(KEY_ASKED_BATTERY, true).apply()
        requestBatteryExemption(this)
    }

    companion object {
        private const val KEY_ASKED_BATTERY = "asked_battery_exemption"

        /** Ask the OS to exempt the app from Doze / battery optimization so long screen-off
         *  streaming isn't throttled. No-op if already exempt. */
        @SuppressLint("BatteryLife")
        fun requestBatteryExemption(context: Context) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (pm.isIgnoringBatteryOptimizations(context.packageName)) return
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

}
