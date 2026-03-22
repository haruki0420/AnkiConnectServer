package com.kuni256.ankiconnectserver

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.documentfile.provider.DocumentFile
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {
    private lateinit var tvLogs: TextView
    private lateinit var scrollLogs: ScrollView
    private lateinit var tvAnkiFolder: TextView
    private lateinit var tvLogFolder: TextView
    private lateinit var switchServer: Switch
    private lateinit var tvPermStorage: TextView
    private lateinit var tvPermNotification: TextView

    // フォルダ選択の結果を受け取るランチャー
    private val ankiPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { saveFolderUri("media_folder_uri", it, tvAnkiFolder) }
    }
    private val logPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { saveFolderUri("debug_folder_uri", it, tvLogFolder) }
    }

    // Service からのログを受信する BroadcastReceiver
    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val msg = intent?.getStringExtra("log_msg") ?: return
            val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            tvLogs.append("[$time] $msg\n")
            scrollLogs.post { scrollLogs.fullScroll(View.FOCUS_DOWN) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // UIパーツの紐付け
        tvLogs = findViewById(R.id.tv_logs); scrollLogs = findViewById(R.id.scroll_logs)
        tvAnkiFolder = findViewById(R.id.tv_anki_folder); tvLogFolder = findViewById(R.id.tv_log_folder)
        switchServer = findViewById(R.id.switch_server)
        tvPermStorage = findViewById(R.id.tv_perm_storage); tvPermNotification = findViewById(R.id.tv_perm_notification)

        // 通知バー被り防止
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main_layout)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        loadSavedFolders()

        // 各種リスナーの設定
        findViewById<Button>(R.id.btn_anki_folder).setOnClickListener { ankiPicker.launch(null) }
        findViewById<Button>(R.id.btn_log_folder).setOnClickListener { logPicker.launch(null) }
        findViewById<CheckBox>(R.id.cb_dev_mode).setOnCheckedChangeListener { _, isChecked ->
            scrollLogs.visibility = if (isChecked) View.VISIBLE else View.GONE
        }
        findViewById<Button>(R.id.btn_request_perms).setOnClickListener { checkAndRequestPermissions() }

        // スイッチの初期状態設定とイベント
        switchServer.isChecked = AnkiConnectService.isRunning
        updateSwitchText()
        switchServer.setOnCheckedChangeListener { _, isChecked ->
            val intent = Intent(this, AnkiConnectService::class.java)
            if (isChecked) ContextCompat.startForegroundService(this, intent) else stopService(intent)
            updateSwitchText()
        }
    }

    private fun saveFolderUri(key: String, uri: Uri, textView: TextView) {
        // 再起動後もアクセス可能にする永続権限の取得
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        getSharedPreferences("AnkiPrefs", Context.MODE_PRIVATE).edit().putString(key, uri.toString()).apply()
        textView.text = DocumentFile.fromTreeUri(this, uri)?.name ?: "選択済み"
    }

    private fun loadSavedFolders() {
        val prefs = getSharedPreferences("AnkiPrefs", Context.MODE_PRIVATE)
        prefs.getString("media_folder_uri", null)?.let { tvAnkiFolder.text = DocumentFile.fromTreeUri(this, Uri.parse(it))?.name ?: "設定済み" }
        prefs.getString("debug_folder_uri", null)?.let { tvLogFolder.text = DocumentFile.fromTreeUri(this, Uri.parse(it))?.name ?: "設定済み" }
    }

    private fun updatePermissionStatus() {
        val storage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        tvPermStorage.text = "ストレージ権限：" + if(storage) "✅ 許可済み" else "❌ 未許可"
        tvPermStorage.setTextColor(if(storage) Color.parseColor("#4CAF50") else Color.parseColor("#E74C3C"))

        val notif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED else true
        tvPermNotification.text = "通　知　権　限：" + if(notif) "✅ 許可済み" else "❌ 未許可"
        tvPermNotification.setTextColor(if(notif) Color.parseColor("#4CAF50") else Color.parseColor("#E74C3C"))
    }

    private fun updateSwitchText() {
        switchServer.text = if (switchServer.isChecked) "サーバー稼働中" else "サーバー停止中"
        switchServer.setTextColor(if (switchServer.isChecked) Color.parseColor("#4CAF50") else Color.parseColor("#E74C3C"))
    }

    override fun onResume() { super.onResume(); updatePermissionStatus() }
    override fun onStart() {
        super.onStart()
        registerReceiver(logReceiver, IntentFilter("com.kuni256.ANKI_LOG"), if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Context.RECEIVER_EXPORTED else 0)
    }
    override fun onStop() { super.onStop(); try { unregisterReceiver(logReceiver) } catch (e: Exception) {} }

    private fun checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
        }
    }
}