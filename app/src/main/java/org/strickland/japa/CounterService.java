package org.strickland.japa;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.pm.ServiceInfo;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.hardware.display.DisplayManager;
import android.media.AudioManager;
import android.media.SoundPool;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.provider.Settings;
import android.view.Display;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

/**
 * Foreground service that:
 *   - Holds a PARTIAL_WAKE_LOCK so the CPU keeps running with screen off.
 *   - Maintains an active MediaSession with STATE_PLAYING so the OS routes physical
 *     volume button presses to STREAM_MUSIC (instead of STREAM_RING) when the screen
 *     is off. VOLUME_CHANGED_ACTION is then reliably broadcast for every press.
 *   - Listens for VOLUME_CHANGED_ACTION to count beads when screen is off. After each
 *     bead the stream is reset to its midpoint so it never hits a boundary.
 *   - When the screen is ON and the Activity is bound, the Activity intercepts volume
 *     key events directly via dispatchKeyEvent (returning true so the volume never
 *     actually changes), and the receiver is not involved in that mode.
 *   - Provides vibration, sound, or no feedback on each bead press.
 */
public class CounterService extends Service {

    // ── Notification ─────────────────────────────────────────────────────────
    static final String CHANNEL_ID   = "japa_counter_channel";
    static final int    NOTIF_ID     = 1;

    // ── Wake-lock idle timeout ────────────────────────────────────────────────
    private static final long WAKE_IDLE_MS = 5 * 60 * 1000L; // 5 minutes

    // ── SharedPreferences keys ────────────────────────────────────────────────
    static final String PREFS_NAME            = "JapaPrefs";
    static final String PREF_TOTAL_BEADS      = "totalBeads";
    static final String PREF_TOTAL_ROUNDS     = "totalRounds";
    static final String PREF_FEEDBACK         = "feedback";
    static final String PREF_SETTINGS_CHANGED = "settingsChanged";
    static final String PREF_CURRENT_BEAD     = "currentBead";
    static final String PREF_CURRENT_ROUND    = "currentRound";
    static final String PREF_SAVED_DATE       = "savedDate";
    static final String PREF_MANTRA_INDEX     = "mantaIndex";

    static final String FEEDBACK_VIBRATION = "vibration";
    static final String FEEDBACK_SOUND     = "sound";
    static final String FEEDBACK_NONE      = "none";

    // ── State ─────────────────────────────────────────────────────────────────
    private volatile boolean isResettingVolume = false;
    private volatile long    lastBeadTimeMs    = 0;
    private int     currentBead  = 0;
    private int     currentRound = 1;
    private int     totalBeads   = 108;
    private int     totalRounds  = 16;
    private boolean isComplete   = false;
    private boolean isRunning    = true;

    // ── Binder ────────────────────────────────────────────────────────────────
    private final IBinder binder = new LocalBinder();

    public class LocalBinder extends Binder {
        CounterService getService() { return CounterService.this; }
    }

    // ── Callback to bound Activity ────────────────────────────────────────────
    private CounterCallback callback;

    // ── Hardware / OS resources ───────────────────────────────────────────────
    private PowerManager.WakeLock wakeLock;
    private final Handler         wakeHandler           = new Handler(Looper.getMainLooper());
    private final Runnable        releaseWakeLockOnIdle = () -> {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    };
    private MediaSession          mediaSession;
    private Vibrator              vibrator;
    private SoundPool             soundPool;
    private int                   beadSoundId  = -1;
    private int                   roundSoundId = -1;

    /**
     * Detects volume button presses when the screen is off.
     *
     * The active MediaSession (STATE_PLAYING) causes Android to route physical
     * volume button presses to STREAM_MUSIC, which fires this broadcast. After each
     * bead the stream is reset to its midpoint so subsequent presses always have
     * headroom to generate a change — and therefore a broadcast.
     *
     * isResettingVolume prevents the broadcast fired by the midpoint reset from
     * being counted as a second bead press.
     */
    private final BroadcastReceiver volumeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!isRunning || isComplete) return;
            if (!"android.media.VOLUME_CHANGED_ACTION".equals(intent.getAction())) return;

            // Skip the broadcast triggered by our own midpoint reset
            if (isResettingVolume) {
                isResettingVolume = false;
                return;
            }

            int streamType = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE",      -1);
            int newVol     = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE",      -1);
            int prevVol    = intent.getIntExtra("android.media.EXTRA_PREV_VOLUME_STREAM_VALUE",  -1);

            // Single-step change = deliberate button press.
            // Cooldown (300 ms) absorbs the burst of VOLUME_CHANGED_ACTION broadcasts
            // that Android fires when the screen turns off, preventing phantom counts.
            if (newVol >= 0 && prevVol >= 0 && Math.abs(newVol - prevVol) == 1) {
                long now = System.currentTimeMillis();
                if (now - lastBeadTimeMs < 300) return;
                lastBeadTimeMs = now;
                countBead();
                resetVolumeToMidpoint(streamType, newVol);
            }
        }
    };

    // ── Service lifecycle ─────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        loadPreferences();
        createNotificationChannel();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, buildNotification());
        }
        acquireWakeLock();
        initMediaSession();
        initVibrator();
        initSoundPool();
        registerVolumeReceiver();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        wakeHandler.removeCallbacks(releaseWakeLockOnIdle);
        try { unregisterReceiver(volumeReceiver); } catch (Exception ignored) {}
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
            mediaSession = null;
        }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (soundPool != null) { soundPool.release(); soundPool = null; }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void setCallback(CounterCallback cb) {
        this.callback = cb;
    }

    /**
     * Advance the bead count by one. Thread-safe (called from UI thread via Activity
     * or from the main-thread BroadcastReceiver).
     */
    public synchronized void countBead() {
        if (!isRunning || isComplete) return;

        resetWakeLockTimeout();
        currentBead++;

        boolean roundComplete = (currentBead >= totalBeads);
        deliverFeedback(roundComplete);

        if (roundComplete) {
            if (currentRound >= totalRounds) {
                isComplete = true;
            } else {
                currentRound++;
                currentBead = 0;
            }
        }

        saveState();
//        updateNotification();
        notifyCallback();
    }

    /** Reset to the beginning of round 1. */
    public synchronized void reset() {
        currentBead  = 0;
        currentRound = 1;
        isComplete   = false;
        resetWakeLockTimeout();
        saveState();
//        updateNotification();
        notifyCallback();
    }

    /**
     * Reload bead/round totals from SharedPreferences and reset the counter.
     * Called by MainActivity when returning from SettingsActivity.
     */
    public synchronized void reloadPreferences() {
        loadPreferences();
        int index = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getInt(PREF_MANTRA_INDEX, 0);
        updateBeadSound(getMantraBeadSound(index));
        reset();
    }

    public boolean isRunning()      { return isRunning;    }
    public int     getCurrentBead() { return currentBead;  }
    public int     getCurrentRound(){ return currentRound; }
    public int     getTotalBeads()  { return totalBeads;   }
    public int     getTotalRounds() { return totalRounds;  }
    public boolean isComplete()     { return isComplete;   }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void loadPreferences() {
        SharedPreferences p = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        totalBeads  = p.getInt(PREF_TOTAL_BEADS,  108);
        totalRounds = p.getInt(PREF_TOTAL_ROUNDS,  16);

        String savedDate = p.getString(PREF_SAVED_DATE, "");
        String today     = todayString();
        if (today.equals(savedDate)) {
            // Same day — restore saved position
            currentBead  = p.getInt(PREF_CURRENT_BEAD,  0);
            currentRound = p.getInt(PREF_CURRENT_ROUND, 1);
        } else {
            // New day — start fresh
            currentBead  = 0;
            currentRound = 1;
        }
        isComplete = false;
    }

    public synchronized void saveState() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putInt(PREF_CURRENT_BEAD,  currentBead)
                .putInt(PREF_CURRENT_ROUND, currentRound)
                .putString(PREF_SAVED_DATE, todayString())
                .apply();
    }

    private static String todayString() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    private boolean foo() {
        DisplayManager dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        for (Display display : dm.getDisplays()) {
            if (display.getState() == Display.STATE_ON) { //            if (display.getState() != Display.STATE_OFF) {
                boolean f = Settings.canDrawOverlays(this);
                return true;
            }
        }
        return false;
    }

    private void acquireWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "JapaCounter:WakeLock");
        wakeLock.acquire();
        scheduleWakeLockRelease();
    }

    /** Restart the 5-minute idle countdown, re-acquiring the lock if it lapsed. */
    private void resetWakeLockTimeout() {
        if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire();
        scheduleWakeLockRelease();
    }

    private void scheduleWakeLockRelease() {
        wakeHandler.removeCallbacks(releaseWakeLockOnIdle);
        wakeHandler.postDelayed(releaseWakeLockOnIdle, WAKE_IDLE_MS);
    }

    /**
     * An active MediaSession in STATE_PLAYING causes AudioService to route physical
     * volume button presses to STREAM_MUSIC rather than STREAM_RING. STREAM_RING is
     * often silenced or at its maximum, so without this, pressing a volume key may
     * not change any stream value and VOLUME_CHANGED_ACTION is never broadcast.
     */
    private void initMediaSession() {
        mediaSession = new MediaSession(this, "JapaCounter");
        mediaSession.setPlaybackState(new PlaybackState.Builder()
                .setState(PlaybackState.STATE_PLAYING,
                          PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1.0f)
                .build());
        mediaSession.setActive(true);
    }

    private void resetVolumeToMidpoint(int streamType, int currentVol) {
        if (streamType < 0) return;
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        int midpoint = am.getStreamMaxVolume(streamType) / 2;
        if (currentVol == midpoint) return; // no change → no broadcast → don't arm the flag
        isResettingVolume = true;
        am.setStreamVolume(streamType, midpoint, 0 /* silent — no UI */);
    }

    private void registerVolumeReceiver() {
        IntentFilter filter = new IntentFilter("android.media.VOLUME_CHANGED_ACTION");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(volumeReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(volumeReceiver, filter);
        }
    }

    private void initVibrator() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager vm = (VibratorManager) getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            vibrator = (vm != null) ? vm.getDefaultVibrator() : null;
        } else {
            vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        }
    }

    private void initSoundPool() {
        int index = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getInt(PREF_MANTRA_INDEX, 0);
        updateSoundPool(getMantraBeadSound(index), null);
    }

    String getMantraBeadSound(int index) {
        android.content.res.TypedArray mantras = getResources().obtainTypedArray(R.array.mantra_array);
        String sound = "harekrishna";
        if (index >= 0 && index < mantras.length()) {
            int subId = mantras.getResourceId(index, 0);
            if (subId != 0) {
                String[] sub = getResources().getStringArray(subId);
                if (sub.length > 1) sound = sub[1];
            }
        }
        mantras.recycle();
        return sound;
    }

    void updateSoundPool(String beadSound, String roundSound) {
        beadSoundId = -1;
        roundSoundId = -1;
        if (soundPool == null) {
            soundPool = new SoundPool.Builder().setMaxStreams(2).build();
        }
        if (beadSound != null) {
            int beadRes = getResources().getIdentifier(beadSound, "raw", getPackageName());
            if (beadRes != 0) beadSoundId = soundPool.load(this, beadRes, 1);
        }
        if (roundSound != null) {
            int roundRes = getResources().getIdentifier(roundSound, "raw", getPackageName());
            if (roundRes != 0) roundSoundId = soundPool.load(this, roundRes, 1);
        }
    }
    void updateBeadSound(String beadSound) {
        updateSoundPool(beadSound, null);
    }

    private void deliverFeedback(boolean roundComplete) {
        String type = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getString(PREF_FEEDBACK, FEEDBACK_VIBRATION);
        switch (type) {
            case FEEDBACK_VIBRATION: vibrateFor(roundComplete); break;
            case FEEDBACK_SOUND:     playSound(roundComplete);  break;
            default: break;
        }
    }

    private void vibrateFor(boolean roundComplete) {
        if (vibrator == null || !vibrator.hasVibrator()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            VibrationEffect effect = roundComplete
                    // Double-pulse: short tap followed by a longer thud
                    ? VibrationEffect.createWaveform(new long[]{0, 60, 60, 140}, -1)
                    // Single pulse — createOneShot applies device-tuned DEFAULT_AMPLITUDE
                    : VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK);
            vibrator.vibrate(effect);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            VibrationEffect effect = roundComplete
                    // Double-pulse: short tap followed by a longer thud
                    ? VibrationEffect.createWaveform(new long[]{0, 60, 60, 140}, -1)
                    // Single pulse — createOneShot applies device-tuned DEFAULT_AMPLITUDE
                    : VibrationEffect.createOneShot(55, VibrationEffect.DEFAULT_AMPLITUDE);
            vibrator.vibrate(effect);
        } else {
            vibrator.vibrate(roundComplete ? 140 : 55);
        }
    }

    private void playSound(boolean roundComplete) {
        if (soundPool == null) return;
        if (roundComplete && roundSoundId != -1) {
            soundPool.play(roundSoundId, 1f, 1f, 0, 0, 1f);
        } else if (beadSoundId != -1) {
            soundPool.play(beadSoundId, 1f, 1f, 0, 0, 1f);
        }
    }

    private void notifyCallback() {
        if (callback != null) {
            callback.onCountUpdated(currentBead, currentRound, totalBeads, totalRounds, isComplete);
        }
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Japa Counter", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Prayer bead counter running in background");
        channel.setShowBadge(false);
        channel.enableVibration(false);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String body = isComplete
                ? getString(R.string.notif_complete)
                : getString(R.string.notif_progress, currentBead, totalBeads, currentRound, totalRounds);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(body)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

//    private void updateNotification() {
//        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
//        if (nm != null) nm.notify(NOTIF_ID, buildNotification());
//    }
}
