package io.github.makealauncher.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import io.github.makealauncher.core.data.DataSources

/**
 * Feeds Android system data into the engine while the launcher is visible, and stops
 * completely when it isn't.
 *
 * - Time uses the system's once-a-minute broadcast. A seconds timer runs only while some
 *   visible binding actually displays seconds.
 * - Battery uses the system's battery broadcast, which arrives only when the level or
 *   charging state changes.
 */
class AndroidDataSources(private val context: Context, private val data: DataSources) {
    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            data.time.onMinute()
            updateSecondTicker()
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = readBattery(intent)
    }

    private val secondTicker = object : Runnable {
        override fun run() {
            if (!running) return
            data.time.onSecond()
            if (data.time.secondsNeeded) scheduleNextSecond()
        }
    }

    fun start() {
        if (running) return
        running = true
        // These are protected system broadcasts, so no exported/not-exported flag is needed.
        context.registerReceiver(
            timeReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_TIME_TICK)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            },
        )
        context.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let(::readBattery)
        // Catch up on anything that changed while we were stopped.
        data.time.onMinute()
        data.time.onSecond()
        updateSecondTicker()
    }

    fun stop() {
        if (!running) return
        running = false
        handler.removeCallbacks(secondTicker)
        context.unregisterReceiver(timeReceiver)
        context.unregisterReceiver(batteryReceiver)
    }

    /** Starts or stops the seconds timer depending on whether anything shows seconds. */
    fun updateSecondTicker() {
        handler.removeCallbacks(secondTicker)
        if (running && data.time.secondsNeeded) scheduleNextSecond()
    }

    private fun scheduleNextSecond() {
        val now = System.currentTimeMillis()
        handler.postDelayed(secondTicker, 1000 - now % 1000 + 2)
    }

    private fun readBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level >= 0 && scale > 0) data.battery.level.set(level * 100 / scale)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        data.battery.charging.set(status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
    }
}
