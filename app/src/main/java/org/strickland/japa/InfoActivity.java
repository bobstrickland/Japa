package org.strickland.japa;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.os.Bundle;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

public class InfoActivity extends AppCompatActivity {

    private TextView tvInfoContent;
    private TextView appVersionName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_info);

        tvInfoContent = findViewById(R.id.tv_info_content);
        appVersionName = findViewById(R.id.appVersionName);
        try {
            PackageInfo pInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            String versionName = pInfo.versionName;
            //int verCode = pInfo.versionCode;
            appVersionName.setText("v"+versionName);
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }
        String text = "nothing here.";
        AssetManager assetManager = getAssets();
        try (InputStream is = assetManager.open("info.txt");
             BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {

            StringBuilder content = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append("\n");
            }
            text = content.toString();
        } catch (IOException e) {
            e.printStackTrace();
        }
        tvInfoContent.setText(text);
        ImageButton btnClose = findViewById(R.id.btn_close_info);
        btnClose.setOnClickListener(v -> finish());
    }
}
