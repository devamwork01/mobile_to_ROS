package com.sensorstream.stream

import android.content.Context

/**
 * Process-level singleton [StreamEngine]. The engine outlives the Activity /
 * ViewModel so streaming continues across configuration changes, backgrounding
 * and task changes — its lifecycle is driven by [com.sensorstream.service.StreamingService],
 * while the UI merely observes [StreamEngine.state] and reads live values.
 */
object StreamHolder {
    @Volatile
    private var instance: StreamEngine? = null

    fun engine(context: Context): StreamEngine =
        instance ?: synchronized(this) {
            instance ?: StreamEngine(context.applicationContext).also { instance = it }
        }
}
