package org.strickland.japa;

import android.Manifest;

import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.content.res.TypedArray;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;

import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.snackbar.Snackbar;
import com.google.android.play.core.appupdate.AppUpdateManager;
import com.google.android.play.core.appupdate.AppUpdateManagerFactory;
import com.google.android.play.core.appupdate.AppUpdateOptions;
import com.google.android.play.core.install.InstallStateUpdatedListener;
import com.google.android.play.core.install.model.AppUpdateType;
import com.google.android.play.core.install.model.InstallStatus;
import com.google.android.play.core.install.model.UpdateAvailability;

import com.google.android.material.button.MaterialButton;

public class MainActivity extends AppCompatActivity implements CounterCallback {

    static WeakReference<MainActivity> instance;

    private static final int UPDATE_REQUEST_CODE = 100;

    private String VERSION_NAME;
    private int VERSION_CODE;
    private AppUpdateManager appUpdateManager;
    private final InstallStateUpdatedListener installStateListener = state -> {
        if (state.installStatus() == InstallStatus.DOWNLOADED) {
            showUpdateReadySnackbar();
        }
    };

    private CounterService counterService;
    private boolean        isBound = false;

    // Views
    private ImageView        bgImage;
    private BeadNecklaceView progressBead;
    private TextView tvBeadCurrent;
    private TextView tvBeadOf;
    private TextView tvRound;
    private TextView mantraText;
    private LinearLayout roundDotsLayout;
    private MaterialButton btnReset;
    private MaterialButton btnAuto;
    private MaterialButton btnExit;
    private ImageButton    btnSettings;
    private ImageButton    btnInfo;
    private ScreenReceiver  screenReceiver;
    private boolean         autoEnabled = true;
    private GestureDetector swipeDetector;

    // ── Notification permission (Android 13+) ─────────────────────────────────
    private final ActivityResultLauncher<String> notifPermLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                // Nothing extra needed — the service notification will appear once granted
            });

    // ── Service connection ────────────────────────────────────────────────────
    private final ServiceConnection serviceConn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            CounterService.LocalBinder binder = (CounterService.LocalBinder) service;
            counterService = binder.getService();
            counterService.setCallback(MainActivity.this);
            isBound = true;
            screenReceiver.setCounterService(counterService);
            refreshUI();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            isBound        = false;
            counterService = null;
            screenReceiver.setCounterService(null);
        }
    };

    // ── Activity lifecycle ────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Keep screen on while the app is visible so the user can see progress
        //TODO: do I need to keep this?

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        bgImage        = findViewById(R.id.bg_image);
        progressBead   = findViewById(R.id.progress_bead);
        tvBeadCurrent  = findViewById(R.id.tv_bead_current);
        tvBeadOf       = findViewById(R.id.tv_bead_of);
        tvRound        = findViewById(R.id.tv_round);
        mantraText = findViewById(R.id.mantraText);
        roundDotsLayout= findViewById(R.id.round_dots);
        btnReset       = findViewById(R.id.btn_reset);
        btnAuto        = findViewById(R.id.btn_auto);
        btnExit        = findViewById(R.id.btn_exit);
        btnSettings    = findViewById(R.id.btn_settings);
        btnInfo        = findViewById(R.id.btn_info);

        btnReset.setOnClickListener(v -> {
            if (isBound) {
                counterService.reset();
                Toast.makeText(this, R.string.reset_toast, Toast.LENGTH_SHORT).show();
            }
        });

        btnAuto.setOnClickListener(v -> {
            if (isBound) {
                if (autoEnabled) {
                    if (counterService.isAutoCounting()) {
                        Toast.makeText(this, R.string.auto_stopping, Toast.LENGTH_SHORT).show();
                        btnAuto.setText(R.string.auto_start);
                    } else {
                        Toast.makeText(this, R.string.auto_enabled, Toast.LENGTH_SHORT).show();
                        btnAuto.setText(R.string.auto_stop);
                    }
                    counterService.startStopAutoCounting();
                } else {
                    Toast.makeText(this, R.string.auto_disabled, Toast.LENGTH_SHORT).show();
                }
            }
        });

        btnExit.setOnClickListener(v -> exitApp());

        btnSettings.setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));

        btnInfo.setOnClickListener(v ->
                startActivity(new Intent(this, InfoActivity.class)));

        instance = new WeakReference<>(this);

        swipeDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            private static final float SWIPE_THRESHOLD     = 100f;
            private static final float SWIPE_VEL_THRESHOLD = 100f;

            @Override
            public boolean onDown(MotionEvent e) { return true; }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vX, float vY) {
                if (e1 == null || e2 == null) return false;
                float dX = e2.getX() - e1.getX();
                float dY = e2.getY() - e1.getY();
                if (Math.abs(dX) > Math.abs(dY)
                        && Math.abs(dX) > SWIPE_THRESHOLD
                        && Math.abs(vX) > SWIPE_VEL_THRESHOLD) {
                    startActivity(new Intent(MainActivity.this, PrayerActivity.class));
                    return true;
                }
                return false;
            }
        });

        applyMantraBackground();
        requestNotificationPermissionIfNeeded();
        ensureServiceRunning();

        // 1. Initialize the receiver
        screenReceiver = new ScreenReceiver();
        // 2. Create a filter for the screen actions
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        // 3. Register the receiver
        registerReceiver(screenReceiver, filter);

        try {
            PackageInfo pInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            VERSION_NAME = pInfo.versionName;
            VERSION_CODE = pInfo.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }
        checkWhatIsNew();
        appUpdateManager = AppUpdateManagerFactory.create(this);
        checkForUpdate();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (counterService != null) {
            counterService.startCounting();
        }
        bindService(new Intent(this, CounterService.class),
                serviceConn, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (counterService != null) {
            counterService.startCounting();
        }
        // If the user changed settings, reload them into the service
        if (isBound) {
            SharedPreferences p = getSharedPreferences(CounterService.PREFS_NAME, MODE_PRIVATE);
            if (p.getBoolean(CounterService.PREF_SETTINGS_CHANGED, false)) {
                p.edit().putBoolean(CounterService.PREF_SETTINGS_CHANGED, false).apply();
                counterService.reloadPreferences();
                applyMantraBackground();
            }
        }
        appUpdateManager.registerListener(installStateListener);
        // Prompt to complete if an update was already downloaded (e.g. app was backgrounded)
        appUpdateManager.getAppUpdateInfo().addOnSuccessListener(info -> {
            if (info.installStatus() == InstallStatus.DOWNLOADED) {
                showUpdateReadySnackbar();
            }
        });
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (counterService != null) {
            counterService.stopCounting();
        }
    }
    @Override
    protected void onPause() {
        super.onPause();
        if (counterService != null) {
            if (screenReceiver != null) {
                screenReceiver.touch();
            }
            counterService.stopCounting();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        appUpdateManager.unregisterListener(installStateListener);
    }
    @SuppressWarnings("StatementWithEmptyBody")
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == UPDATE_REQUEST_CODE && resultCode != RESULT_OK) {
            // Update was cancelled or failed — silently ignore
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        swipeDetector.onTouchEvent(event);
        return super.dispatchTouchEvent(event);
    }

    /**
     * Intercept volume key events when the Activity is in the foreground.
     * Returning true consumes the event so the system never processes it —
     * meaning the actual device volume does NOT change in this mode.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && isBound && counterService.isRunning() && !counterService.isAutoCounting()) {
                counterService.countBead();
                return true; // consume — volume unchanged
            }
            if (event.getAction() == KeyEvent.ACTION_UP) {
                return true; // also consume the UP event to prevent any system handling
            }
        }
        return super.dispatchKeyEvent(event);
    }

    // ── CounterCallback ───────────────────────────────────────────────────────

    @Override
    public void onCountUpdated(int currentBead, int currentRound,
                               int totalBeads, int totalRounds, boolean isComplete) {
        // This may be called from a background thread via the volume broadcast receiver
        runOnUiThread(() -> updateUI(currentBead, currentRound, totalBeads, totalRounds, isComplete));
        if (counterService != null) {
            if (counterService.isAutoCounting()) {
                btnAuto.setText(R.string.auto_stop);
            } else {
                btnAuto.setText(R.string.auto_start);
            }
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    void applyMantraBackground() {
        SharedPreferences p = getSharedPreferences(CounterService.PREFS_NAME, MODE_PRIVATE);
         /*

        pickerBeads.setValue( p.getInt(CounterService.PREF_TOTAL_BEADS,  108));
        pickerRounds.setValue(p.getInt(CounterService.PREF_TOTAL_ROUNDS,  16));
          */
        if (counterService != null)  {
            counterService.setTotalBeads(p.getInt(CounterService.PREF_TOTAL_BEADS,  108));
            counterService.setTotalRounds(p.getInt(CounterService.PREF_TOTAL_ROUNDS,  16));
        }
        refreshUI();


        int index = p.getInt(CounterService.PREF_MANTRA_INDEX, 0);
        TypedArray mantras = getResources().obtainTypedArray(R.array.mantra_array);
        if (index >= 0 && index < mantras.length()) {
            int subId = mantras.getResourceId(index, 0);
            if (subId != 0) {
                String[] sub = getResources().getStringArray(subId);
                if (sub.length > 2) {
                    int resId = getResources().getIdentifier(sub[2], "drawable", getPackageName());
                    if (resId != 0) bgImage.setImageResource(resId);
                }
                int textIndex = p.getInt(CounterService.PREF_MANTRA_TEXT, 0);

                //boolean hindi = true;
                if (textIndex == 1 && sub.length > 4) {
                    mantraText.setText(sub[4]);
                } else if (sub.length > 3) {
                    mantraText.setText(sub[3]);
                }
            }
        }
        mantras.recycle();
        if (counterService != null) {
            counterService.stopAutoCounting();
        }
        String feedback = p.getString(CounterService.PREF_FEEDBACK, CounterService.FEEDBACK_VIBRATION);
        if (CounterService.FEEDBACK_SOUND.equals(feedback)) {
            autoEnabled = true;
            btnAuto.setAlpha(1f);
        } else {
            autoEnabled = false;
            btnAuto.setAlpha(.3f);
        }
        btnAuto.setText(R.string.auto_start);
    }

    private void ensureServiceRunning() {
        Intent intent = new Intent(this, CounterService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            }
        }
    }

    private void refreshUI() {
        if (!isBound || counterService == null) return;
        updateUI(
                counterService.getCurrentBead(),
                counterService.getCurrentRound(),
                counterService.getTotalBeads(),
                counterService.getTotalRounds(),
                counterService.isComplete()
        );
    }

    private void updateUI(int currentBead, int currentRound,
                          int totalBeads, int totalRounds, boolean isComplete) {
        // Circular bead necklace
        progressBead.setBeads(totalBeads, currentBead);
        // Central counter text
        tvBeadCurrent.setText(String.valueOf(currentBead));
        tvBeadOf.setText(getString(R.string.of_total, totalBeads));
        // Round text
        tvRound.setText(getString(R.string.round_label, currentRound, totalRounds));
        // Round progress dots (shown when ≤ 24 rounds)
        buildRoundDots(currentRound, totalRounds);
    }

    @SuppressWarnings("StatementWithEmptyBody")
    private void checkForUpdate() {
        appUpdateManager.getAppUpdateInfo().addOnSuccessListener(info -> {
            if (info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE
                    && info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)) {
                //noinspection CatchMayIgnoreException
                try {
                    //appUpdateManager.startUpdateFlowForResult(info, AppUpdateType.FLEXIBLE, this, UPDATE_REQUEST_CODE);
                    appUpdateManager.startUpdateFlowForResult(info, this, AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build(),UPDATE_REQUEST_CODE);
                } catch (IntentSender.SendIntentException e) {
                }
            } else {
            }
        });
    }

    private void showUpdateReadySnackbar() {
        Snackbar.make(
                findViewById(android.R.id.content),
                "Update downloaded. Restart to apply.",
                Snackbar.LENGTH_INDEFINITE
        ).setAction("Restart", v -> appUpdateManager.completeUpdate()).show();
    }

    /**
     * Save state, stop the foreground service (disconnects volume buttons), and finish.
     */
    private void exitApp() {
        Toast.makeText(this, "exitApp", Toast.LENGTH_SHORT).show();
        instance = null;
        if (isBound) {
            counterService.saveState();
            counterService.setCallback(null);
            unbindService(serviceConn);
            isBound = false;
        }
        stopService(new Intent(this, CounterService.class));
        //finish();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            finishAndRemoveTask(); // Closes activity and removes from Recents list
        } else {
            finishAffinity(); // Closes all activities in the task
        }
    }

    /**
     * Dynamically build a row of filled/empty circle indicators for the round count.
     * For more than 24 rounds the dots layout is hidden to avoid overflow.
     */
    private void buildRoundDots(int currentRound, int totalRounds) {
        roundDotsLayout.removeAllViews();
        if (totalRounds > 24) {
            roundDotsLayout.setVisibility(View.GONE);
            return;
        }
        roundDotsLayout.setVisibility(View.VISIBLE);

        int dotSizePx = (int) (12 * getResources().getDisplayMetrics().density);
        int gapPx     = (int) (6  * getResources().getDisplayMetrics().density);

        for (int i = 1; i <= totalRounds; i++) {
            View dot = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dotSizePx, dotSizePx);
            lp.setMargins(gapPx / 2, 0, gapPx / 2, 0);
            dot.setLayoutParams(lp);
            dot.setBackground(ContextCompat.getDrawable(this,
                    i < currentRound ? R.drawable.dot_complete
                            : i == currentRound ? R.drawable.dot_current
                            : R.drawable.dot_pending));
            roundDotsLayout.addView(dot);
        }
    }

    private void checkWhatIsNew() {
        SharedPreferences prefs = getSharedPreferences("app_prefs", MODE_PRIVATE);
        int lastVersionCode = prefs.getInt("last_version_code", -1);
        int currentVersionCode = VERSION_CODE;
        if (currentVersionCode > lastVersionCode) { //
            showWhatsNewDialog();
            prefs.edit().putInt("last_version_code", currentVersionCode).apply();
        }
    }

    private void showWhatsNewDialog() {
        String message = "";
        AssetManager assetManager = getAssets();
        try (InputStream is = assetManager.open("whatsnew.txt");
             BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
            StringBuilder content = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append("\n");
            }
            message = content.toString();
        } catch (IOException e) {
            e.printStackTrace();
        }
        //noinspection ConstantValue
        if (message != null && !message.trim().isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("What's New in v" + VERSION_NAME)
                    .setMessage(message)
                    .setPositiveButton("OK!", (dialog, which) -> dialog.dismiss())
                    .setCancelable(false)
                    .show();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // IMPORTANT: Unregister to avoid memory leaks
        if (screenReceiver != null) {
            unregisterReceiver(screenReceiver);
        }
    }
}
