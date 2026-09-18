package org.strickland.japa

import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader

class InfoActivity : AppCompatActivity() {
    //    private TextView tvInfoContent;
    //    private TextView appVersionName;
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_info)

        val tvInfoContent = findViewById<TextView>(R.id.tv_info_content)
        val appVersionName = findViewById<TextView>(R.id.appVersionName)
        try {
            val pInfo = getPackageManager().getPackageInfo(getPackageName(), 0)
            val versionName = pInfo.versionName
            //int verCode = pInfo.versionCode;
            appVersionName.setText("v" + versionName)
        } catch (e: PackageManager.NameNotFoundException) {
            e.printStackTrace()
        }
        var text = "nothing here."
        val assetManager = getAssets()
        try {
            assetManager.open("info.md").use { `is` ->
                BufferedReader(InputStreamReader(`is`)).use { reader ->
                    val content = StringBuilder()
                    var line: String?
                    while ((reader.readLine().also { line = it }) != null) {
                        content.append(line).append("\n")
                    }
                    text = content.toString()
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }


        //tvInfoContent.setText(text);

        //Markwon markwon = Markwon.create(this);
        val markwon = Markwon.builder(this)
            .usePlugin(TablePlugin.create(this))
            .build()
        markwon.setMarkdown(tvInfoContent, text)


        val btnClose = findViewById<ImageButton>(R.id.btn_close_info)
        btnClose.setOnClickListener(View.OnClickListener { v: View? -> finish() })
    }
}
