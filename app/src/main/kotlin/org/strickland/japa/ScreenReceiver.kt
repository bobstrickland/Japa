package org.strickland.japa

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.Date

class ScreenReceiver : BroadcastReceiver() {
    private var counterService: CounterService? = null

    var isScreenOn: Boolean = true
    private var lastTouched = Date()

    override fun onReceive(context: Context?, intent: Intent) {
        if (Intent.ACTION_SCREEN_OFF == intent.getAction()) {
            Log.d("ScreenReceiver", "Screen is OFF")
            this.isScreenOn = false
            turnOnCounter()
        } else if (Intent.ACTION_SCREEN_ON == intent.getAction()) {
            Log.d("ScreenReceiver", "Screen is ON")
            this.isScreenOn = true
        }
    }

    fun touch() {
        lastTouched = Date()
    }

    fun setCounterService(counterService: CounterService?) {
        this.counterService = counterService
    }

    fun turnOnCounter() {
        if (counterService != null) {
            if (Date().getTime() - lastTouched.getTime() < 1000) {
                counterService!!.startCounting()
            }
        }
    }
}
