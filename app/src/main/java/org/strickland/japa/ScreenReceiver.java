package org.strickland.japa;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.util.Date;

public class ScreenReceiver extends BroadcastReceiver {
    private CounterService counterService;

    public boolean screenOn = true;
    private Date lastTouched = new Date();

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
            Log.d("ScreenReceiver", "Screen is OFF");
            screenOn = false;
            turnOnCounter();
        } else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
            Log.d("ScreenReceiver", "Screen is ON");
            screenOn = true;
        }
    }

    public boolean isScreenOn() {
        return screenOn;
    }
    public void touch() {
        lastTouched = new Date();
    }
    public void setCounterService(CounterService counterService) {
        this.counterService = counterService;
    }

    public void turnOnCounter() {
        if (counterService != null) {
            if (new Date().getTime() - lastTouched.getTime() < 1000) {
                counterService.startCounting();
            }
        }
    }



}
