package org.strickland.japa

import android.Manifest
import android.app.ActivityOptions
import android.app.AlertDialog
import android.content.ComponentName
import android.content.DialogInterface
import android.content.Intent
import android.content.IntentFilter
import android.content.IntentSender.SendIntentException
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.GestureDetector
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import com.google.android.gms.tasks.OnSuccessListener
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallState
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import org.strickland.japa.CounterService.LocalBinder
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.lang.ref.WeakReference
import kotlin.math.abs

//import android.util.Log;
class MainActivity : AppCompatActivity(), CounterCallback {
    private var VERSION_NAME: String? = null
    private var VERSION_CODE = 0
    private var appUpdateManager: AppUpdateManager? = null
    private val installStateListener = InstallStateUpdatedListener { state: InstallState? ->
        if (state!!.installStatus() == InstallStatus.DOWNLOADED) {
            showUpdateReadySnackbar()
        }
    }

    private var counterService: CounterService? = null
    private var isBound = false

    // Views
    private var bgImage: ImageView? = null
    private var progressBead: BeadNecklaceView? = null
    private var tvBeadCurrent: TextView? = null
    private var tvBeadOf: TextView? = null
    private var tvRound: TextView? = null
    private var mantraText: TextView? = null
    private var roundDotsLayout: LinearLayout? = null
    private var btnReset: MaterialButton? = null
    private var btnAuto: MaterialButton? = null
    private var btnExit: MaterialButton? = null
    private var btnSettings: ImageButton? = null
    private var btnInfo: ImageButton? = null
    private var screenReceiver: ScreenReceiver? = null
    private var autoEnabled = true
    private var swipeDetector: GestureDetector? = null
    private var lastKeyBeadTimeMs: Long = 0

    // ── Notification permission (Android 13+) ─────────────────────────────────
    private val notifPermLauncher = registerForActivityResult<String?, Boolean?>(
        RequestPermission(),
        ActivityResultCallback { granted: Boolean? -> })

    // ── Service connection ────────────────────────────────────────────────────
    private val serviceConn: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as LocalBinder
            counterService = binder.service
            counterService!!.setCallback(this@MainActivity)
            isBound = true
            screenReceiver!!.setCounterService(counterService)
            refreshUI()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            counterService = null
            screenReceiver!!.setCounterService(null)
        }
    }

    // ── Activity lifecycle ────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen on while the app is visible so the user can see progress
        //TODO: do I need to keep this?
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        bgImage = findViewById<ImageView>(R.id.bg_image)
        progressBead = findViewById<BeadNecklaceView>(R.id.progress_bead)
        tvBeadCurrent = findViewById<TextView>(R.id.tv_bead_current)
        tvBeadOf = findViewById<TextView>(R.id.tv_bead_of)
        tvRound = findViewById<TextView>(R.id.tv_round)
        mantraText = findViewById<TextView>(R.id.mantraText)
        roundDotsLayout = findViewById<LinearLayout>(R.id.round_dots)
        btnReset = findViewById<MaterialButton>(R.id.btn_reset)
        btnAuto = findViewById<MaterialButton>(R.id.btn_auto)
        btnExit = findViewById<MaterialButton>(R.id.btn_exit)
        btnSettings = findViewById<ImageButton>(R.id.btn_settings)
        btnInfo = findViewById<ImageButton>(R.id.btn_info)

        btnReset!!.setOnClickListener(View.OnClickListener { v: View? ->
            if (isBound) {
                counterService!!.reset()
                Toast.makeText(this, R.string.reset_toast, Toast.LENGTH_SHORT).show()
            }
        })

        btnAuto!!.setOnClickListener(View.OnClickListener { v: View? ->
            if (isBound) {
                if (autoEnabled) {
                    if (counterService!!.isAutoCounting) {
                        Toast.makeText(this, R.string.auto_stopping, Toast.LENGTH_SHORT).show()
                        btnAuto!!.setText(R.string.auto_start)
                    } else {
                        Toast.makeText(this, R.string.auto_enabled, Toast.LENGTH_SHORT).show()
                        btnAuto!!.setText(R.string.auto_stop)
                    }
                    counterService!!.startStopAutoCounting()
                } else {
                    Toast.makeText(this, R.string.auto_disabled, Toast.LENGTH_SHORT).show()
                }
            }
        })

        btnExit!!.setOnClickListener(View.OnClickListener { v: View? -> exitApp() })

        btnSettings!!.setOnClickListener(View.OnClickListener { v: View? ->
            startActivity(
                Intent(
                    this,
                    SettingsActivity::class.java
                )
            )
        })

        btnInfo!!.setOnClickListener(View.OnClickListener { v: View? ->
            startActivity(
                Intent(
                    this,
                    InfoActivity::class.java
                )
            )
        })

        instance = WeakReference<MainActivity?>(this)

        swipeDetector = GestureDetector(this, object : SimpleOnGestureListener() {
            private val SWIPE_THRESHOLD = 100f
            private val SWIPE_VEL_THRESHOLD = 100f

            override fun onDown(e: MotionEvent): Boolean {
                return true
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                vX: Float,
                vY: Float
            ): Boolean {
                if (e1 == null) return false
                val dX = e2.getX() - e1.getX()
                val dY = e2.getY() - e1.getY()
                if (abs(dX) > abs(dY) && abs(dX) > SWIPE_THRESHOLD && abs(vX) > SWIPE_VEL_THRESHOLD) {
                    if (e1.getX() > e2.getX()) { // swipe left
                        startActivity(Intent(this@MainActivity, PrayerActivity::class.java))
                        return true
                    } else { // swipe right
                        // Slide the user-prayer screen in from the left so the screens move
                        // with the finger. ActivityOptions works on every API level here;
                        // the matching close animation lives in UserPrayerActivity.
                        startActivity(
                            Intent(this@MainActivity, UserPrayerActivity::class.java),
                            ActivityOptions.makeCustomAnimation(
                                this@MainActivity,
                                R.anim.slide_in_from_left,
                                R.anim.slide_out_to_right
                            ).toBundle()
                        )
                        return true
                    }
                }
                return false
            }
        })

        applyMantraBackground()
        requestNotificationPermissionIfNeeded()
        ensureServiceRunning()

        // 1. Initialize the receiver
        screenReceiver = ScreenReceiver()
        // 2. Create a filter for the screen actions
        val filter = IntentFilter()
        filter.addAction(Intent.ACTION_SCREEN_OFF)
        filter.addAction(Intent.ACTION_SCREEN_ON)
        // 3. Register the receiver
        registerReceiver(screenReceiver, filter)

        try {
            val pInfo = getPackageManager().getPackageInfo(getPackageName(), 0)
            VERSION_NAME = pInfo.versionName
            // longVersionCode is API 28+; the compat call reads it there and falls back to
            // the old int field below that, so no branch of our own is needed. Narrowed to
            // Int because the "last seen" value is stored in prefs as one.
            VERSION_CODE = PackageInfoCompat.getLongVersionCode(pInfo).toInt()
        } catch (e: PackageManager.NameNotFoundException) {
            e.printStackTrace()
        }
        checkWhatIsNew()
        appUpdateManager = AppUpdateManagerFactory.create(this)
        checkForUpdate()
    }

    override fun onStart() {
        super.onStart()
        if (counterService != null) {
            counterService!!.startCounting()
        }
        bindService(
            Intent(this, CounterService::class.java),
            serviceConn, BIND_AUTO_CREATE
        )
    }

    override fun onResume() {
        super.onResume()
        if (counterService != null) {
            counterService!!.startCounting()
        }
        // If the user changed settings, reload them into the service
        if (isBound) {
            val p = getSharedPreferences(CounterService.PREFS_NAME, MODE_PRIVATE)
            if (p.getBoolean(CounterService.PREF_SETTINGS_CHANGED, false)) {
                p.edit().putBoolean(CounterService.PREF_SETTINGS_CHANGED, false).apply()
                counterService!!.reloadPreferences()
                applyMantraBackground()
            }
        }
        applyTextSize()
        appUpdateManager!!.registerListener(installStateListener)
        // Prompt to complete if an update was already downloaded (e.g. app was backgrounded)
        appUpdateManager!!.getAppUpdateInfo()
            .addOnSuccessListener(OnSuccessListener { info: AppUpdateInfo? ->
                if (info!!.installStatus() == InstallStatus.DOWNLOADED) {
                    showUpdateReadySnackbar()
                }
            })
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (counterService != null) {
            counterService!!.stopCounting()
        }
    }

    override fun onPause() {
        super.onPause()
        if (counterService != null) {
            if (screenReceiver != null) {
                screenReceiver!!.touch()
            }
            counterService!!.stopCounting()
        }
    }

    override fun onStop() {
        super.onStop()
        appUpdateManager!!.unregisterListener(installStateListener)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == UPDATE_REQUEST_CODE && resultCode != RESULT_OK) {
            // Update was cancelled or failed — silently ignore
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        swipeDetector!!.onTouchEvent(event)
        return super.dispatchTouchEvent(event)
    }

    /**
     * Intercept volume key events when the Activity is in the foreground.
     * Returning true consumes the event so the system never processes it —
     * meaning the actual device volume does NOT change in this mode.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val service = counterService;
        if (!isBound || service == null || service.isAutoCounting) {
            return super.dispatchKeyEvent(event)
        } else if (event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_UP || event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && isBound && service.isRunning && !service.isAutoCounting) {
                val now = System.currentTimeMillis()
                val beadDuration = service.getBeadSoundDuration()
                val cooldown = if (beadDuration > 0) beadDuration else 300
                if (now - lastKeyBeadTimeMs < cooldown) return true // debounce — sound still playing
                lastKeyBeadTimeMs = now
                service.countBead()
                return true // consume — volume unchanged
            }
            if (event.getAction() == KeyEvent.ACTION_UP) {
                return true // also consume the UP event to prevent any system handling
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ── CounterCallback ───────────────────────────────────────────────────────
    override fun onCountUpdated(
        currentBead: Int, currentRound: Int,
        totalBeads: Int, totalRounds: Int, isComplete: Boolean
    ) {
        // May be called from a background thread — all UI work must go through runOnUiThread.
        runOnUiThread(Runnable {
            updateUI(currentBead, currentRound, totalBeads, totalRounds, isComplete)
            if (counterService != null) {
                btnAuto!!.setText(
                    if (counterService!!.isAutoCounting)
                        R.string.auto_stop
                    else
                        R.string.auto_start
                )
            }
        })
    }

    // ── Private helpers ───────────────────────────────────────────────────────
    /** Re-sizes the mantra to the setting; also called directly when Settings saves.  */
    fun applyTextSize() {
        TextScale.applyTo(mantraText!!)
    }

    fun applyMantraBackground() {
        val p = getSharedPreferences(CounterService.PREFS_NAME, MODE_PRIVATE)
        /*

        pickerBeads.setValue( p.getInt(CounterService.PREF_TOTAL_BEADS,  108));
        pickerRounds.setValue(p.getInt(CounterService.PREF_TOTAL_ROUNDS,  16));
          */
        if (counterService != null) {
            counterService!!.totalBeads = (p.getInt(CounterService.PREF_TOTAL_BEADS, 108))
            counterService!!.totalRounds = (
                p.getInt(
                    CounterService.PREF_TOTAL_ROUNDS,
                    16
                )
            )
        }
        refreshUI()


        val index = p.getInt(CounterService.PREF_MANTRA_INDEX, 0)
        val mantras = getResources().obtainTypedArray(R.array.mantra_array)
        if (index >= 0 && index < mantras.length()) {
            val subId = mantras.getResourceId(index, 0)
            if (subId != 0) {
                val sub = getResources().getStringArray(subId)
                if (sub.size > 2) {
                    val resId = getResources().getIdentifier(sub[2], "drawable", getPackageName())
                    if (resId != 0) bgImage!!.setImageResource(resId)
                }
                val textIndex = p.getInt(CounterService.PREF_MANTRA_TEXT, 0)

                //boolean hindi = true;
                if (textIndex == 1 && sub.size > 4) {
                    mantraText!!.setText(sub[4])
                } else if (sub.size > 3) {
                    mantraText!!.setText(sub[3])
                }
            }
        }
        mantras.recycle()
        if (counterService != null) {
            counterService!!.stopAutoCounting()
        }
        val feedback: String = p.getString(
            CounterService.PREF_FEEDBACK,
            CounterService.FEEDBACK_VIBRATION
        )!!
        if (CounterService.FEEDBACK_SOUND == feedback) {
            autoEnabled = true
            btnAuto!!.setAlpha(1f)
        } else {
            autoEnabled = false
            btnAuto!!.setAlpha(.3f)
        }
        btnAuto!!.setText(R.string.auto_start)
    }

    private fun ensureServiceRunning() {
        val intent = Intent(this, CounterService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun refreshUI() {
        if (!isBound || counterService == null) return
        updateUI(
            counterService!!.currentBead,
            counterService!!.currentRound,
            counterService!!.totalBeads,
            counterService!!.totalRounds,
            counterService!!.isComplete
        )
    }

    private fun updateUI(
        currentBead: Int, currentRound: Int,
        totalBeads: Int, totalRounds: Int, isComplete: Boolean
    ) {
        // Circular bead necklace
        progressBead!!.setBeads(totalBeads, currentBead)
        // Central counter text
        tvBeadCurrent!!.setText(currentBead.toString())
        tvBeadOf!!.setText(getString(R.string.of_total, totalBeads))
        // Round text
        tvRound!!.setText(getString(R.string.round_label, currentRound, totalRounds))
        // Round progress dots (shown when ≤ 24 rounds)
        var allComplete = false
        if (currentBead == totalBeads && currentRound == totalRounds) {
            allComplete = true
        }
        buildRoundDots(currentRound, totalRounds, allComplete)
    }

    private fun checkForUpdate() {
        appUpdateManager!!.getAppUpdateInfo()
            .addOnSuccessListener(OnSuccessListener { info: AppUpdateInfo? ->
                if (info!!.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE
                    && info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)
                ) {
                    try {
                        //appUpdateManager.startUpdateFlowForResult(info, AppUpdateType.FLEXIBLE, this, UPDATE_REQUEST_CODE);
                        appUpdateManager!!.startUpdateFlowForResult(
                            info,
                            this,
                            AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build(),
                            UPDATE_REQUEST_CODE
                        )
                    } catch (e: SendIntentException) {
                    }
                } else {
                }
            })
    }

    private fun showUpdateReadySnackbar() {
        Snackbar.make(
            findViewById<View>(android.R.id.content),
            "Update downloaded. Restart to apply.",
            Snackbar.LENGTH_INDEFINITE
        ).setAction(
            "Restart",
            View.OnClickListener { v: View? -> appUpdateManager!!.completeUpdate() }).show()
    }

    /**
     * Save state, stop the foreground service (disconnects volume buttons), and finish.
     */
    private fun exitApp() {
        Toast.makeText(this, "exitApp", Toast.LENGTH_SHORT).show()
        instance = null
        if (isBound) {
            counterService!!.saveState()
            counterService!!.setCallback(null)
            unbindService(serviceConn)
            isBound = false
        }
        stopService(Intent(this, CounterService::class.java))
        //finish();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            finishAndRemoveTask() // Closes activity and removes from Recents list
        } else {
            finishAffinity() // Closes all activities in the task
        }
    }

    /**
     * Dynamically build a row of filled/empty circle indicators for the round count.
     * For more than 24 rounds the dots layout is hidden to avoid overflow.
     */
    private fun buildRoundDots(currentRound: Int, totalRounds: Int, complete: Boolean) {
        roundDotsLayout!!.removeAllViews()
        if (totalRounds > 24) {
            roundDotsLayout!!.setVisibility(View.GONE)
            return
        }
        roundDotsLayout!!.setVisibility(View.VISIBLE)

        val dotSizePx = (12 * getResources().getDisplayMetrics().density).toInt()
        val gapPx = (6 * getResources().getDisplayMetrics().density).toInt()

        for (i in 1..totalRounds) {
            val dot = View(this)
            val lp = LinearLayout.LayoutParams(dotSizePx, dotSizePx)
            lp.setMargins(gapPx / 2, 0, gapPx / 2, 0)
            dot.setLayoutParams(lp)
            if (complete) {
                dot.setBackground(ContextCompat.getDrawable(this, R.drawable.dot_complete))
            } else {
                dot.setBackground(
                    ContextCompat.getDrawable(
                        this,
                        if (i < currentRound)
                            R.drawable.dot_complete
                        else
                            if (i == currentRound)
                                R.drawable.dot_current
                            else
                                R.drawable.dot_pending
                    )
                )
            }
            roundDotsLayout!!.addView(dot)
        }
    }

    private fun checkWhatIsNew() {
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val lastVersionCode = prefs.getInt("last_version_code", -1)
        val currentVersionCode = VERSION_CODE
        if (currentVersionCode > lastVersionCode) { //
            showWhatsNewDialog()
            prefs.edit().putInt("last_version_code", currentVersionCode).apply()
        }
    }

    private fun showWhatsNewDialog() {
        var message = ""
        val assetManager = getAssets()
        try {
            assetManager.open("whatsnew.txt").use { `is` ->
                BufferedReader(InputStreamReader(`is`)).use { reader ->
                    val content = StringBuilder()
                    var line: String?
                    while ((reader.readLine().also { line = it }) != null) {
                        content.append(line).append("\n")
                    }
                    message = content.toString()
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
        if (message.isNotBlank()) {
            AlertDialog.Builder(this)
                .setTitle("What's New in v" + VERSION_NAME)
                .setMessage(message)
                .setPositiveButton(
                    "OK!",
                    DialogInterface.OnClickListener { dialog: DialogInterface?, which: Int -> dialog!!.dismiss() })
                .setCancelable(false)
                .show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // The service is started (not merely bound), so it outlives this Activity and would
        // otherwise keep a strong reference to it — pushing bead updates into a destroyed
        // view hierarchy for as long as counting runs. Dropping the callback and the static
        // instance here costs nothing: both exist only to update visible UI.
        if (isBound) {
            counterService?.setCallback(null)
        }
        instance = null

        // Not unbinding leaves the framework to tear the connection down itself, which it
        // logs as "Activity has leaked ServiceConnection". Unbinding here is expected to be
        // safe — onDestroy is not part of the screen-off path (that is onPause/onStop, with
        // the binding intact), and a started service survives losing its last client — but
        // counting with the screen off depends on the service reference ScreenReceiver
        // obtains via onServiceConnected, so this is left off until it has been exercised on
        // a device. To test: uncomment, then check that a volume press still counts a bead
        // with the screen off after backgrounding, after a rotation, and after a settings
        // change; watch for the leak warning disappearing from logcat.
        //
        // if (isBound) {
        //     unbindService(serviceConn)
        //     isBound = false
        //     counterService = null
        // }

        // IMPORTANT: Unregister to avoid memory leaks
        if (screenReceiver != null) {
            unregisterReceiver(screenReceiver)
        }
    }

    companion object {
        var instance: WeakReference<MainActivity?>? = null

        private const val UPDATE_REQUEST_CODE = 100
    }
}
