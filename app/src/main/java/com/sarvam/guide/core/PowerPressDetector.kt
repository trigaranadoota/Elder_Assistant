package com.sarvam.guide.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat

class PowerPressDetector(
    private val context: Context,
    private val onWake: () -> Unit,
) {
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF, Intent.ACTION_SCREEN_ON -> press()
            }
        }
    }

    private var started = false
    private var windowStart = 0L
    private var presses = 0

    @Synchronized
    private fun press() {
        val now = SystemClock.elapsedRealtime()
        if (now - windowStart > WINDOW_MS) {
            presses = 0
            windowStart = now
        }
        presses++
        if (presses >= TRIPLE_PRESS) {
            presses = 0
            windowStart = 0
            onWake()
        }
    }

    fun start() {
        if (started) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        val flags = if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.RECEIVER_EXPORTED
        } else {
            0
        }
        ContextCompat.registerReceiver(context, receiver, filter, flags)
        started = true
    }

    fun stop() {
        if (!started) return
        context.unregisterReceiver(receiver)
        started = false
    }

    companion object {
        private const val TRIPLE_PRESS = 3
        private const val WINDOW_MS = 2600L
    }
}