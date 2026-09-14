package com.sensorstream.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.sensorstream.stream.Selection
import com.sensorstream.stream.StreamHolder

/**
 * Foreground service that owns the streaming lifecycle. Started via [start] with
 * the connection target + selected sensors; promotes itself to a foreground
 * (dataSync) service with an ongoing notification so the OS keeps the process
 * alive while streaming, then drives the singleton [StreamHolder] engine.
 */
class StreamingService : Service() {

    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val host = intent.getStringExtra(EXTRA_HOST)
                val port = intent.getIntExtra(EXTRA_PORT, 0)
                val handles = intent.getIntArrayExtra(EXTRA_HANDLES) ?: IntArray(0)
                val periods = intent.getIntArrayExtra(EXTRA_PERIODS) ?: IntArray(0)
                if (host == null || handles.isEmpty()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                val selections = handles.indices.map { Selection(handles[it], periods.getOrElse(it) { 0 }) }
                startForegroundNotification(host, port)
                acquireLocks()
                val prefs = getSharedPreferences("sensorstream", Context.MODE_PRIVATE)
                val maxBytes = prefs.getLong("rec_max_bytes", 150L * 1024 * 1024)
                val maxAgeMs = prefs.getLong("rec_max_age_ms", 20L * 60 * 1000)
                val engine = StreamHolder.engine(this)
                engine.configureRecording(filesDir, maxBytes, maxAgeMs)
                engine.start(host, port, selections)
            }
            ACTION_STOP -> {
                StreamHolder.engine(this).stop()
                releaseLocks()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        // Redeliver the last START intent if the service is restarted after a kill.
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        StreamHolder.engine(this).stop()
        releaseLocks()
    }

    /** Keep Wi-Fi and the CPU awake while streaming so screen-off / Doze / battery
     *  saver don't drop the link. Released on stop / destroy. */
    private fun acquireLocks() {
        if (wifiLock == null) {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wifi.createWifiLock(mode, "sensorstream:wifi").apply { setReferenceCounted(false) }
        }
        if (wifiLock?.isHeld == false) wifiLock?.acquire()

        if (wakeLock == null) {
            val power = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sensorstream:cpu")
                .apply { setReferenceCounted(false) }
        }
        if (wakeLock?.isHeld == false) wakeLock?.acquire()
    }

    private fun releaseLocks() {
        if (wifiLock?.isHeld == true) wifiLock?.release()
        if (wakeLock?.isHeld == true) wakeLock?.release()
    }

    private fun startForegroundNotification(host: String, port: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Sensor streaming", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Streaming sensors")
            .setContentText("→ $host:$port")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    companion object {
        const val NOTIF_ID = 1
        const val CHANNEL_ID = "streaming"
        const val ACTION_START = "com.sensorstream.action.START"
        const val ACTION_STOP = "com.sensorstream.action.STOP"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_HANDLES = "handles"
        const val EXTRA_PERIODS = "periods"

        fun start(context: Context, host: String, port: Int, selections: List<Selection>) {
            val intent = Intent(context, StreamingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_HOST, host)
                .putExtra(EXTRA_PORT, port)
                .putExtra(EXTRA_HANDLES, selections.map { it.handle }.toIntArray())
                .putExtra(EXTRA_PERIODS, selections.map { it.periodUs }.toIntArray())
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, StreamingService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
