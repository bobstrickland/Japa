package org.strickland.japa;

import android.content.SharedPreferences;
import android.content.res.TypedArray;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;

public class PrayerActivity extends AppCompatActivity {

    private static final String PREF_PRAYER_INDEX   = "prayerIndex";
    private static final float  SWIPE_THRESHOLD     = 100f;
    private static final float  SWIPE_VEL_THRESHOLD = 100f;

    private ImageView      bgImage;
    private TextView       tvPrayerText;
    private Spinner        spinnerPrayer;
    private MaterialButton btnPray;

    private String[] prayerNames;
    private String[] prayerAudio;
    private String[] prayerImages;
    private String[] prayerTexts;

    private String      activeAudio;
    private MediaPlayer mediaPlayer;
    private GestureDetector gestureDetector;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_prayer);

        bgImage       = findViewById(R.id.bg_image_prayer);
        tvPrayerText  = findViewById(R.id.tv_prayer_text);
        spinnerPrayer = findViewById(R.id.spinner_prayer);
        btnPray       = findViewById(R.id.btn_pray);

        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
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
                    finish();
                    return true;
                }
                return false;
            }
        });

        loadPrayerArrays();

        SharedPreferences prefs = getSharedPreferences(CounterService.PREFS_NAME, MODE_PRIVATE);
        int savedIndex = prefs.getInt(PREF_PRAYER_INDEX, 0);
        applyPrayerSelection(savedIndex);
        spinnerPrayer.setSelection(savedIndex);

        spinnerPrayer.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                applyPrayerSelection(position);
                getSharedPreferences(CounterService.PREFS_NAME, MODE_PRIVATE)
                        .edit().putInt(PREF_PRAYER_INDEX, position).apply();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        btnPray.setOnClickListener(v -> playPrayer());
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        gestureDetector.onTouchEvent(event);
        return super.dispatchTouchEvent(event);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        releaseMediaPlayer();
    }

    private void loadPrayerArrays() {
        TypedArray prayers = getResources().obtainTypedArray(R.array.prayer_array);
        int count = prayers.length();
        prayerNames  = new String[count];
        prayerAudio  = new String[count];
        prayerImages = new String[count];
        prayerTexts  = new String[count];
        for (int i = 0; i < count; i++) {
            int subId = prayers.getResourceId(i, 0);
            if (subId != 0) {
                String[] sub = getResources().getStringArray(subId);
                prayerNames[i]  = sub.length > 0 ? sub[0] : "";
                prayerAudio[i]  = sub.length > 1 ? sub[1] : "";
                prayerImages[i] = sub.length > 2 ? sub[2] : "";
                prayerTexts[i]  = sub.length > 3 ? sub[3] : "";
            }
        }
        prayers.recycle();

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, prayerNames);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerPrayer.setAdapter(adapter);
    }

    private void applyPrayerSelection(int position) {
        if (prayerNames == null || position < 0 || position >= prayerNames.length) return;

        int resId = getResources().getIdentifier(prayerImages[position], "drawable", getPackageName());
        if (resId != 0) bgImage.setImageResource(resId);

        tvPrayerText.setText(prayerTexts[position]);
        activeAudio = prayerAudio[position];
    }

    private void playPrayer() {
        if (activeAudio == null) return;
        int resId = getResources().getIdentifier(activeAudio, "raw", getPackageName());
        if (resId == 0) return;
        releaseMediaPlayer();
        mediaPlayer = MediaPlayer.create(this, resId);
        if (mediaPlayer != null) {
            mediaPlayer.setOnCompletionListener(mp -> releaseMediaPlayer());
            mediaPlayer.start();
        }
    }

    private void releaseMediaPlayer() {
        if (mediaPlayer != null) {
            if (mediaPlayer.isPlaying()) mediaPlayer.stop();
            mediaPlayer.release();
            mediaPlayer = null;
        }
    }
}
