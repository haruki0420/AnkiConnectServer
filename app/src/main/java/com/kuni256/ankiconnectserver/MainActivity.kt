package com.kuni256.ankiconnectserver

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var logView: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var statusView: TextView
    private val ANKI_PERMISSION = "com.ichi2.anki.permission.READ_WRITE_DATABASE"

    private val PICK_MEDIA_FOLDER_CODE = 999
    private val PICK_DEBUG_FOLDER_CODE = 998 // ログ保存用の新しいコード

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val message = intent?.getStringExtra("log_msg") ?: ""
            appendLog(message)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        logView = findViewById(R.id.text_logs)
        scrollView = findViewById(R.id.scroll_view)
        statusView = findViewById(R.id.text_status)

        updatePermissionDisplay()

        findViewById<Button>(R.id.button_start).setOnClickListener {
            if (checkAndRequestPermissions()) startAnkiServer()
        }

        findViewById<Button>(R.id.button_stop).setOnClickListener {
            stopService(Intent(this, AnkiConnectService::class.java))
            appendLog("--- サービスを停止リクエストしました ---")
        }

        findViewById<Button>(R.id.button_select_media).setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            startActivityForResult(intent, PICK_MEDIA_FOLDER_CODE)
            appendLog("★ Ankiの collection.media フォルダを選択してください")
        }

        // ▼ ログ保存先を選択するボタンの処理 ▼
        findViewById<Button>(R.id.button_select_debug).setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            startActivityForResult(intent, PICK_DEBUG_FOLDER_CODE)
            appendLog("★ デバッグログ(JSON)の保存先フォルダを選択してください（Downloadsなど）")
        }

        val filter = IntentFilter("com.kuni256.ANKI_LOG")
        ContextCompat.registerReceiver(this, logReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK && data?.data != null) {
            val uri = data.data!!
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            val prefs = getSharedPreferences("AnkiPrefs", Context.MODE_PRIVATE)

            // 選んだボタンに応じて保存する名前を変える
            if (requestCode == PICK_MEDIA_FOLDER_CODE) {
                prefs.edit().putString("media_folder_uri", uri.toString()).apply()
                appendLog("✅ 画像フォルダのアクセス権限を取得しました！")
            } else if (requestCode == PICK_DEBUG_FOLDER_CODE) {
                prefs.edit().putString("debug_folder_uri", uri.toString()).apply()
                appendLog("✅ ログ保存先のアクセス権限を取得しました！")
            }
            updatePermissionDisplay()
        }
    }

    private fun appendLog(msg: String) {
        runOnUiThread {
            logView.append("${msg}\n")
            scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun updatePermissionDisplay() {
        val hasAnki = ContextCompat.checkSelfPermission(this, ANKI_PERMISSION) == PackageManager.PERMISSION_GRANTED
        val hasNotify = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true

        val prefs = getSharedPreferences("AnkiPrefs", Context.MODE_PRIVATE)
        val hasMediaFolder = prefs.getString("media_folder_uri", null) != null
        val hasDebugFolder = prefs.getString("debug_folder_uri", null) != null

        statusView.text = "Anki権限: ${if(hasAnki) "OK" else "未許可"}\n画像フォルダ: ${if(hasMediaFolder) "設定完了" else "未設定 (必須！)"}\nログ保存先: ${if(hasDebugFolder) "設定完了" else "未設定 (任意)"}"
        statusView.setTextColor(if(hasAnki && hasNotify && hasMediaFolder) 0xFF4CAF50.toInt() else 0xFFFF0000.toInt())
    }

    private fun checkAndRequestPermissions(): Boolean {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, ANKI_PERMISSION) != PackageManager.PERMISSION_GRANTED) needed.add(ANKI_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 100)
            false
        } else true
    }

    private fun startAnkiServer() {
        val intent = Intent(this, AnkiConnectService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        } catch (e: Exception) { appendLog("サービス開始に失敗: ${e.message}") }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(logReceiver)
    }
}