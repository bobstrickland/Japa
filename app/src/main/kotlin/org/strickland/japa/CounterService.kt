package org.strickland.japa

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.MediaPlayer.OnCompletionListener
import android.media.SoundPool
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.PowerManager.WakeLock
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.Volatile
import kotlin.math.abs

//import android.util.Log;
/**
 * Foreground service that:
 * - Holds a PARTIAL_WAKE_LOCK so the CPU keeps running with screen off.
 * - Maintains an active MediaSession with STATE_PLAYING so the OS routes physical
 * volume button presses to STREAM_MUSIC (instead of STREAM_RING) when the screen
 * is off. VOLUME_CHANGED_ACTION is then reliably broadcast for every press.
 * - Listens for VOLUME_CHANGED_ACTION to count beads when screen is off. After each
 * bead the stream is reset to its midpoint so it never hits a boundary.
 * - When the screen is ON and the Activity is bound, the Activity intercepts volume
 * key events directly via dispatchKeyEvent (returning true so the volume never
 * actually changes), and the receiver is not involved in that mode.
 * - Provides vibration, sound, or no feedback on each bead press.
 */
class CounterService : Service() {
    // Auto-counting is the only background work, and only one loop may ever run, so a
    // single thread is enough — a pool would just make a stray second loop possible.
    private val executorService: ExecutorService = Executors.newSingleThreadExecutor()

    /**
     * Identity of the current auto-count loop. A loop that wakes from its pause and finds
     * a newer generation here has been superseded by a later startAutoCounting() and exits.
     */
    private val autoGeneration = AtomicInteger()

    private var screenReceiver: ScreenReceiver? = null

    // ── State ─────────────────────────────────────────────────────────────────
    @Volatile
    private var isResettingVolume = false

    @Volatile
    private var lastBeadTimeMs: Long = 0
    var currentBead: Int = 0
        private set
    var currentRound: Int = 1
        private set
    var totalBeads: Int = 108
    var totalRounds: Int = 16
    // These three are written on the main thread and read by the auto-count loop, so they
    // must be volatile for that loop to observe a stop promptly.
    @Volatile
    var isComplete: Boolean = false
        private set

    @Volatile
    var isRunning: Boolean = true
        private set

    @Volatile
    var isAutoCounting: Boolean = false
        private set

    // ── Binder ────────────────────────────────────────────────────────────────
    private val binder: IBinder = LocalBinder()

    inner class LocalBinder : Binder() {
        val service: CounterService
            get() = this@CounterService
    }

    // ── Callback to bound Activity ────────────────────────────────────────────
    @Volatile
    private var callback: CounterCallback? = null

    // ── Hardware / OS resources ───────────────────────────────────────────────
    private var wakeLock: WakeLock? = null
    private val wakeHandler = Handler(Looper.getMainLooper())
    private val releaseWakeLockOnIdle = Runnable {
        if (wakeLock != null && wakeLock!!.isHeld()) wakeLock!!.release()
    }
    private var mediaSession: MediaSession? = null
    private var vibrator: Vibrator? = null
    private var soundPool: SoundPool? = null
    private var beadSoundId = -1
    private var roundSoundId = -1
    private var beadSoundDuration: Long = -1
    private var roundSoundDuration: Long = -1

    fun getBeadSoundDuration(): Long {
        val type: String = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(
                PREF_FEEDBACK,
                FEEDBACK_VIBRATION
            )!!
        if (FEEDBACK_SOUND == type) {
            return beadSoundDuration
        } else {
            return -1
        }
    }

    /**
     * Detects volume button presses when the screen is off.
     * 
     * 
     * The active MediaSession (STATE_PLAYING) causes Android to route physical
     * volume button presses to STREAM_MUSIC, which fires this broadcast. After each
     * bead the stream is reset to its midpoint so subsequent presses always have
     * headroom to generate a change — and therefore a broadcast.
     * 
     * 
     * isResettingVolume prevents the broadcast fired by the midpoint reset from
     * being counted as a second bead press.
     */
    private val volumeReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
//            Log.d("CounterService","VolumeButton");

            if (!isRunning || isComplete || isAutoCounting) return
            if ("android.media.VOLUME_CHANGED_ACTION" != intent.getAction()) return
            // Skip the broadcast triggered by our own midpoint reset
            if (isResettingVolume) {
                isResettingVolume = false
                return
            }

            val streamType = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1)
            val newVol = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", -1)
            val prevVol = intent.getIntExtra("android.media.EXTRA_PREV_VOLUME_STREAM_VALUE", -1)

            // Single-step change = deliberate button press.
            // Cooldown spans the full sound duration so a second press during playback
            // is ignored, matching the behaviour of the blocking sleep it replaces.
            if (newVol >= 0 && abs(newVol - prevVol) == 1) { //  && prevVol >= 0
                val now = System.currentTimeMillis()
                val cooldownMs =
                    if (getBeadSoundDuration() > 0) getBeadSoundDuration() + 300 else 300
                //                Log.d("CounterService","cooldownMs:"+cooldownMs);
                if (now - lastBeadTimeMs < cooldownMs) return
                lastBeadTimeMs = now
                countBead()
                resetVolumeToPreviousValue(streamType, prevVol)
                //                if (getBeadSoundDuration() > 0) {
//                    Log.d("CounterService","in first part of if");
//                    // Defer volume reset until the sound finishes — postDelayed returns
//                    // immediately so onReceive() is never blocked.
//                    long resetDelayMs = getBeadSoundDuration() > 0 ? getBeadSoundDuration() + 300 : 0;
//                    wakeHandler.postDelayed(
//                            () -> resetVolumeToPreviousValue(streamType, prevVol),
//                            resetDelayMs);
//                } else {
//                    Log.d("CounterService","in second part of if");
//                    resetVolumeToPreviousValue(streamType, prevVol);
//                }
            }
        }
    }

    // ── Service lifecycle ─────────────────────────────────────────────────────
    override fun onCreate() {
        super.onCreate()
        startMyOwnForeground()
        loadPreferences()
        acquireWakeLock()
        initMediaSession()
        initVibrator()
        initSoundPool()
        registerVolumeReceiver()
    }


    private fun startMyOwnForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(
                CHANNEL_ID,
                "Service Channel",
                NotificationManager.IMPORTANCE_NONE
            )
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(chan)

            val notificationBuilder: NotificationCompat.Builder =
                NotificationCompat.Builder(this, CHANNEL_ID)
            val notification = notificationBuilder.setOngoing(true)
                .setContentTitle("App is running in background")
                .setSmallIcon(R.drawable.ic_notification)
                .setPriority(NotificationManager.IMPORTANCE_MIN) // consider NotificationCompat.PRIORITY_HIGH
                .setCategory(Notification.CATEGORY_SERVICE)
                .build()

            // This fulfills the promise to the OS
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        // Stop the auto-count loop and retire its generation, so a loop still sleeping when
        // the service goes away does not count another bead against a released SoundPool.
        stopAutoCounting()
        autoGeneration.incrementAndGet()
        executorService.shutdownNow()
        wakeHandler.removeCallbacks(releaseWakeLockOnIdle)
        try {
            unregisterReceiver(volumeReceiver)
        } catch (ignored: Exception) {
        }
        if (mediaSession != null) {
            mediaSession!!.setActive(false)
            mediaSession!!.release()
            mediaSession = null
        }
        if (wakeLock != null && wakeLock!!.isHeld()) wakeLock!!.release()
        if (soundPool != null) {
            soundPool!!.release()
            soundPool = null
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────
    fun setCallback(cb: CounterCallback?) {
        this.callback = cb
    }

    fun startStopAutoCounting() {
        if (this.isAutoCounting) {
            stopAutoCounting()
        } else {
            startAutoCounting()
        }
    }

    fun stopAutoCounting() {
        this.isAutoCounting = false
    }

    fun startAutoCounting() {
        this.isAutoCounting = true
        // A loop sleeping between beads cannot see a stop that is followed by a restart —
        // it wakes to find the flag true again and keeps counting alongside the new loop,
        // advancing beads at double speed. Each loop therefore claims a generation and
        // exits once a later start has taken it over. SystemClock.sleep() ignores
        // interrupts, so cancelling the task itself would not stop a sleeping loop.
        val generation = autoGeneration.incrementAndGet()
        executorService.execute(Runnable {
            while (this.isAutoCounting && generation == autoGeneration.get()) {
                val roundComplete = countBead()
                if (roundComplete) {
                    stopAutoCounting()
                    break
                }
                // Pace beads on the background thread; use sound duration when available
                val pauseMs = getBeadSoundDuration() + 500
                SystemClock.sleep(pauseMs)
            }
            Handler(Looper.getMainLooper()).post(Runnable { this.notifyCallback() })
        })
    }

    /**
     * Advance the bead count by one. Thread-safe (called from UI thread via Activity
     * or from the main-thread BroadcastReceiver).
     */
    @Synchronized
    fun countBead(): Boolean {
        if (!isRunning) return false
        if (isComplete) return true

        resetWakeLockTimeout()
        currentBead++
        notifyCallback()

        val roundComplete = (currentBead >= totalBeads)
        deliverFeedback(roundComplete)

        if (roundComplete) {
            if (currentRound >= totalRounds) {
                isComplete = true
            } else {
                currentRound++
                currentBead = 0
            }
        }

        saveState()
        notifyCallback()
        return roundComplete
    }

    /** Reset to the beginning of round 1.  */
    @Synchronized
    fun reset() {
        currentBead = 0
        currentRound = 1
        isComplete = false
        resetWakeLockTimeout()
        saveState()
        notifyCallback()
    }

    /**
     * Reload bead/round totals from SharedPreferences and reset the counter.
     * Called by MainActivity when returning from SettingsActivity.
     */
    @Synchronized
    fun reloadPreferences() {
        loadPreferences()
        val index = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getInt(PREF_MANTRA_INDEX, 0)
        updateBeadSound(getMantraBeadSound(index))
        //reset();
    }

    // ── Private helpers ───────────────────────────────────────────────────────
    private fun loadPreferences() {
        val p = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        totalBeads = p.getInt(PREF_TOTAL_BEADS, 108).coerceAtLeast(1)
        totalRounds = p.getInt(PREF_TOTAL_ROUNDS, 16).coerceAtLeast(1)

        val savedDate: String = p.getString(PREF_SAVED_DATE, "")!!
        val today: String = todayString()
        if (today == savedDate) {
            // Same day — restore saved position, clamped: the totals may have been lowered
            // in Settings since it was saved.
            currentBead = p.getInt(PREF_CURRENT_BEAD, 0).coerceIn(0, totalBeads)
            currentRound = p.getInt(PREF_CURRENT_ROUND, 1).coerceIn(1, totalRounds)
        } else {
            // New day — start fresh
            currentBead = 0
            currentRound = 1
        }

        // A restored position can sit exactly at the end of a round: a finished session stops
        // at totalBeads, and lowering the totals in Settings clamps onto it. Fold it the way
        // countBead() would, so the next press never counts bead 109 of 108.
        if (currentBead >= totalBeads) {
            if (currentRound >= totalRounds) {
                isComplete = true
            } else {
                currentRound++
                currentBead = 0
                isComplete = false
            }
        } else {
            isComplete = false
        }
    }

    fun stopCounting() {
        isRunning = false
    }

    fun startCounting() {
        isRunning = true
    }

    @Synchronized
    fun saveState() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putInt(PREF_CURRENT_BEAD, currentBead)
            .putInt(PREF_CURRENT_ROUND, currentRound)
            .putString(PREF_SAVED_DATE, todayString())
            .apply()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "JapaCounter:WakeLock")
        wakeLock!!.acquire(5 * 60 * 1000L /*5 minutes*/)
        scheduleWakeLockRelease()
    }

    /** Restart the 5-minute idle countdown, re-acquiring the lock if it lapsed.  */
    private fun resetWakeLockTimeout() {
        if (wakeLock != null && !wakeLock!!.isHeld()) wakeLock!!.acquire(5 * 60 * 1000L /*5 minutes*/)
        scheduleWakeLockRelease()
    }

    private fun scheduleWakeLockRelease() {
        wakeHandler.removeCallbacks(releaseWakeLockOnIdle)
        wakeHandler.postDelayed(releaseWakeLockOnIdle, WAKE_IDLE_MS)
    }

    /**
     * An active MediaSession in STATE_PLAYING causes AudioService to route physical
     * volume button presses to STREAM_MUSIC rather than STREAM_RING. STREAM_RING is
     * often silenced or at its maximum, so without this, pressing a volume key may
     * not change any stream value and VOLUME_CHANGED_ACTION is never broadcast.
     */
    private fun initMediaSession() {
        mediaSession = MediaSession(this, "JapaCounter")
        mediaSession!!.setPlaybackState(
            PlaybackState.Builder()
                .setState(
                    PlaybackState.STATE_PLAYING,
                    PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1.0f
                )
                .build()
        )
        mediaSession!!.setActive(true)
    }

    private fun resetVolumeToPreviousValue(streamType: Int, prevVol: Int) {
        if (streamType < 0) return
        val am = getSystemService(AUDIO_SERVICE) as AudioManager?
        if (am == null) return
        isResettingVolume = true
        am.setStreamVolume(streamType, prevVol, 0 /* silent — no UI */)
    }

    private fun registerVolumeReceiver() {
        val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(volumeReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(volumeReceiver, filter)
        }
    }

    private fun initVibrator() {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager?)?.defaultVibrator
        } else {
            // The pre-S way of reaching the vibrator, kept for the API 26-30 devices this
            // build still supports. Suppressed on the branch rather than the function so a
            // future deprecation in the S path above is still reported.
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator?
        }
    }

    private fun initSoundPool() {
        val index = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getInt(PREF_MANTRA_INDEX, 0)
        updateSoundPool(getMantraBeadSound(index), null)
    }

    fun getMantraBeadSound(index: Int): String? {
        val mantras = getResources().obtainTypedArray(R.array.mantra_array)
        var sound: String? = "harekrishna"
        if (index >= 0 && index < mantras.length()) {
            val subId = mantras.getResourceId(index, 0)
            if (subId != 0) {
                val sub = getResources().getStringArray(subId)
                if (sub.size > 1) sound = sub[1]
            }
        }
        mantras.recycle()
        return sound
    }

    fun updateSoundPool(beadSound: String?, roundSound: String?) {
        if (soundPool == null) {
            soundPool = SoundPool.Builder().setMaxStreams(2).build()
        }
        val pool = soundPool!!

        // load() adds a sample, it never replaces the one this id used to point at, so the
        // old sample has to be released explicitly. Without this the pool accumulates a copy
        // per load, and every settings change loads the bead sound twice more — once from
        // SettingsActivity, once from reloadPreferences() when MainActivity resumes.
        if (beadSoundId > 0) pool.unload(beadSoundId)
        if (roundSoundId > 0) pool.unload(roundSoundId)
        beadSoundId = -1
        roundSoundId = -1

        if (beadSound != null) {
            val beadRes = getResources().getIdentifier(beadSound, "raw", getPackageName())
            // load() reports failure as 0, which is not a playable id either.
            if (beadRes != 0) beadSoundId = pool.load(this, beadRes, 1)
            if (beadSoundId > 0) {
                beadSoundDuration = getSoundDuration(beadRes)
            } else {
                beadSoundDuration = -1
            }
        }
        if (roundSound != null) {
            val roundRes = getResources().getIdentifier(roundSound, "raw", getPackageName())
            if (roundRes != 0) roundSoundId = pool.load(this, roundRes, 1)
            if (roundSoundId > 0) {
                roundSoundDuration = getSoundDuration(roundRes)
            } else {
                roundSoundDuration = -1
            }
        }
    }

    fun updateBeadSound(beadSound: String?) {
        updateSoundPool(beadSound, null)
    }

    private fun deliverFeedback(roundComplete: Boolean) {
        var type: String = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(
                PREF_FEEDBACK,
                FEEDBACK_VIBRATION
            )!!
        when (type) {
            FEEDBACK_VIBRATION -> vibrateFor(false)
            FEEDBACK_SOUND -> playBeadSound()
        }
        if (roundComplete) {
            type = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getString(
                    PREF_FEEDBACK_ROUND,
                    FEEDBACK_VIBRATION
                )!!
            when (type) {
                FEEDBACK_VIBRATION -> vibrateFor(roundComplete)
                FEEDBACK_SOUND -> playRoundSound()
            }
        }
    }

    private fun vibrateFor(roundComplete: Boolean) {
        if (vibrator == null || !vibrator!!.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val effect = if (roundComplete // Double-pulse: short tap followed by a longer thud
            )
                VibrationEffect.createWaveform(
                    longArrayOf(0, 60, 60, 140),
                    -1
                ) // Single pulse — createOneShot applies device-tuned DEFAULT_AMPLITUDE
            else
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
            vibrator!!.vibrate(effect)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val effect = if (roundComplete // Double-pulse: short tap followed by a longer thud
            )
                VibrationEffect.createWaveform(
                    longArrayOf(0, 60, 60, 140),
                    -1
                ) // Single pulse — createOneShot applies device-tuned DEFAULT_AMPLITUDE
            else
                VibrationEffect.createOneShot(55, VibrationEffect.DEFAULT_AMPLITUDE)
            vibrator!!.vibrate(effect)
        } else {
            // Pre-O devices have no VibrationEffect, so the untyped duration call is the
            // only option here. Scoped to this branch so the two effect paths above stay
            // covered by the compiler.
            @Suppress("DEPRECATION")
            vibrator!!.vibrate((if (roundComplete) 140 else 55).toLong())
        }
    }

    private fun playRoundSound() {
        val player = MediaPlayer.create(this, Settings.System.DEFAULT_NOTIFICATION_URI)
        if (player == null) return
        player.setOnCompletionListener(OnCompletionListener { mp: MediaPlayer? -> mp!!.release() })
        player.start()
    }

    @Synchronized
    private fun playBeadSound() {
        if (soundPool == null) return
        val speedPref =
            50 + getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getInt(PREF_MANTRA_SPEED, 0)
                .coerceIn(-50, 50)
        val rate = 0.5f + (speedPref / 100.0f)
        if (beadSoundId > 0) {
            soundPool!!.play(beadSoundId, 1f, 1f, 0, 0, rate)
            // Never sleep here — this can be called on the main thread (volume key / receiver).
            // Auto-counting pacing is handled by the background loop in startAutoCounting().
        }
    }

    private fun getSoundDuration(rawId: Int): Long {
        val player = MediaPlayer.create(this, rawId)
        if (player == null) return -1
        var duration = player.getDuration()
        player.release()
        val speedPref =
            50 + getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getInt(PREF_MANTRA_SPEED, 0)
                .coerceIn(-50, 50)
        if (speedPref != 50) {
            val rate = 0.5f + (speedPref / 100.0f)
            duration = (duration / rate).toInt()
        }
        return duration.toLong()
    }

    private fun notifyCallback() {
        if (callback != null) {
            callback!!.onCountUpdated(
                currentBead,
                currentRound,
                totalBeads,
                totalRounds,
                isComplete
            )
        }
    }

    fun setScreenReceiver(screenReceiver: ScreenReceiver?) {
        this.screenReceiver = screenReceiver
    }

    companion object {
        // ── Wake-lock idle timeout ────────────────────────────────────────────────
        private val WAKE_IDLE_MS = 5 * 60 * 1000L // 5 minutes

        // ── SharedPreferences keys ────────────────────────────────────────────────
        const val PREFS_NAME: String = "JapaPrefs"
        const val PREF_TOTAL_BEADS: String = "totalBeads"
        const val PREF_TOTAL_ROUNDS: String = "totalRounds"
        const val PREF_FEEDBACK: String = "feedback"
        const val PREF_FEEDBACK_ROUND: String = "feedback_round"
        const val PREF_SETTINGS_CHANGED: String = "settingsChanged"
        const val PREF_CURRENT_BEAD: String = "currentBead"
        const val PREF_CURRENT_ROUND: String = "currentRound"
        const val PREF_SAVED_DATE: String = "savedDate"
        const val PREF_MANTRA_INDEX: String = "mantaIndex"
        const val PREF_MANTRA_TEXT: String = "mantaText"
        const val PREF_MANTRA_SPEED: String = "mantaSpeed" // 0–100, default 50 = 1.0x rate

        /** Position within text_size_labels / text_size_values; see [TextScale].  */
        const val PREF_TEXT_SIZE: String = "textSize"

        const val FEEDBACK_VIBRATION: String = "vibration"
        const val FEEDBACK_SOUND: String = "sound"
        const val FEEDBACK_NONE: String = "none"

        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "MyChannelId"
        private fun todayString(): String {
            return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        }
    }
}
