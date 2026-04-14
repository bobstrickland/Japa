package org.strickland.japa;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;

public class MainActivity extends AppCompatActivity implements CounterCallback {

    private CounterService counterService;
    private boolean        isBound = false;

    // Views
    private BeadNecklaceView progressBead;
    private TextView tvBeadCurrent;
    private TextView tvBeadOf;
    private TextView tvRound;
    private LinearLayout roundDotsLayout;
    private MaterialButton btnReset;
    private MaterialButton btnExit;
    private ImageButton    btnSettings;

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
            refreshUI();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            isBound        = false;
            counterService = null;
        }
    };

    // ── Activity lifecycle ────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Keep screen on while the app is visible so the user can see progress
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        progressBead   = findViewById(R.id.progress_bead);
        tvBeadCurrent  = findViewById(R.id.tv_bead_current);
        tvBeadOf       = findViewById(R.id.tv_bead_of);
        tvRound        = findViewById(R.id.tv_round);
        roundDotsLayout= findViewById(R.id.round_dots);
        btnReset       = findViewById(R.id.btn_reset);
        btnExit        = findViewById(R.id.btn_exit);
        btnSettings    = findViewById(R.id.btn_settings);

        btnReset.setOnClickListener(v -> {
            if (isBound) {
                counterService.reset();
                Toast.makeText(this, R.string.reset_toast, Toast.LENGTH_SHORT).show();
            }
        });

        btnExit.setOnClickListener(v -> exitApp());

        btnSettings.setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));

        requestNotificationPermissionIfNeeded();
        ensureServiceRunning();
    }

    @Override
    protected void onStart() {
        super.onStart();
        bindService(new Intent(this, CounterService.class),
                serviceConn, Context.BIND_AUTO_CREATE);
    }

    public void onTaskRemoved(Intent rootIntent) {
        exitApp();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // If the user changed settings, reload them into the service
        if (isBound) {
            SharedPreferences p = getSharedPreferences(CounterService.PREFS_NAME, MODE_PRIVATE);
            if (p.getBoolean(CounterService.PREF_SETTINGS_CHANGED, false)) {
                p.edit().putBoolean(CounterService.PREF_SETTINGS_CHANGED, false).apply();
                counterService.reloadPreferences();
                Toast.makeText(this, R.string.settings_applied, Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (isBound) {
            counterService.setCallback(null);
            unbindService(serviceConn);
            isBound = false;
        }
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
            if (event.getAction() == KeyEvent.ACTION_DOWN && isBound && counterService.isRunning()) {
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
    }

    // ── Private helpers ───────────────────────────────────────────────────────

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

    /**
     * Save state, stop the foreground service (disconnects volume buttons), and finish.
     */
    private void exitApp() {
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
}
