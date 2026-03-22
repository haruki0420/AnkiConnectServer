package com.kuni256.ankiconnectserver

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.documentfile.provider.DocumentFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var tvLogs: TextView
    private lateinit var scrollLogs: ScrollView
    private lateinit var tvAnkiFolder: TextView
    private lateinit var tvLogFolder: TextView
    private lateinit var switchServer: Switch

    // 権限ステータス用UI
    private lateinit var tvPermStorage: TextView
    private lateinit var tvPermNotification: TextView
    private lateinit var btnRequestPerms: Button

    private val ankiFolderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) saveFolderUri("media_folder_uri", uri, tvAnkiFolder)
    }

    private val logFolderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) saveFolderUri("debug_folder_uri", uri, tvLogFolder)
    }

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

        tvLogs = findViewById(R.id.tv_logs)
        scrollLogs = findViewById(R.id.scroll_logs)
        tvAnkiFolder = findViewById(R.id.tv_anki_folder)
        tvLogFolder = findViewById(R.id.tv_log_folder)
        switchServer = findViewById(R.id.switch_server)
        tvPermStorage = findViewById(R.id.tv_perm_storage)
        tvPermNotification = findViewById(R.id.tv_perm_notification)
        btnRequestPerms = findViewById(R.id.btn_request_perms)

        // 通知バー被り防止
        val mainLayout = findViewById<View>(R.id.main_layout)
        ViewCompat.setOnApplyWindowInsetsListener(mainLayout) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        loadSavedFolders()

        findViewById<Button>(R.id.btn_anki_folder).setOnClickListener { ankiFolderPicker.launch(null) }
        findViewById<Button>(R.id.btn_log_folder).setOnClickListener { logFolderPicker.launch(null) }

        findViewById<CheckBox>(R.id.cb_dev_mode).setOnCheckedChangeListener { _, isChecked ->
            scrollLogs.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        switchServer.isChecked = AnkiConnectService.isRunning
        updateSwitchText()
        switchServer.setOnCheckedChangeListener { _, isChecked ->
            val intent = Intent(this, AnkiConnectService::class.java)
            if (isChecked) {
                // 通知権限がないとForegroundService起動時にクラッシュする可能性があるためチェック
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    tvLogs.append("[System] 通知権限がないためサーバーを起動できません。\n")
                    switchServer.isChecked = false
                    return@setOnCheckedChangeListener
                }
                ContextCompat.startForegroundService(this, intent)
            } else {
                stopService(intent)
            }
            updateSwitchText()
        }

        btnRequestPerms.setOnClickListener { checkAndRequestPermissions() }
    }

    // ★追加：画面が表示されるたびに権限の状態をチェックしてUIを更新する
    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
    }

    private fun updatePermissionStatus() {
        // ストレージ権限チェック
        val hasStoragePerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

        if (hasStoragePerm) {
            tvPermStorage.text = "ストレージ権限：✅ 許可済み"
            tvPermStorage.setTextColor(android.graphics.Color.parseColor("#4CAF50"))
        } else {
            tvPermStorage.text = "ストレージ権限：❌ 未許可"
            tvPermStorage.setTextColor(android.graphics.Color.parseColor("#E74C3C"))
        }

        // 通知権限チェック
        val hasNotifPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Android 12以下は自動的に許可されている扱い
        }

        if (hasNotifPerm) {
            tvPermNotification.text = "通　知　権　限：✅ 許可済み"
            tvPermNotification.setTextColor(android.graphics.Color.parseColor("#4CAF50"))
        } else {
            tvPermNotification.text = "通　知　権　限：❌ 未許可"
            tvPermNotification.setTextColor(android.graphics.Color.parseColor("#E74C3C"))
        }

        // 両方許可されていればボタンを隠す
        btnRequestPerms.visibility = if (hasStoragePerm && hasNotifPerm) View.GONE else View.VISIBLE
    }

    private fun updateSwitchText() {
        switchServer.text = if (switchServer.isChecked) "サーバー稼働中 (Port: 8765)" else "サーバー停止中"
        switchServer.setTextColor(if (switchServer.isChecked) android.graphics.Color.parseColor("#4CAF50") else android.graphics.Color.parseColor("#E74C3C"))
    }

    private fun saveFolderUri(key: String, uri: Uri, textView: TextView) {
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        getSharedPreferences("AnkiPrefs", Context.MODE_PRIVATE).edit().putString(key, uri.toString()).apply()
        val folderName = DocumentFile.fromTreeUri(this, uri)?.name ?: uri.toString()
        textView.text = folderName
    }

    private fun loadSavedFolders() {
        val prefs = getSharedPreferences("AnkiPrefs", Context.MODE_PRIVATE)
        val ankiUri = prefs.getString("media_folder_uri", null)
        val logUri = prefs.getString("debug_folder_uri", null)
        if (ankiUri != null) tvAnkiFolder.text = DocumentFile.fromTreeUri(this, Uri.parse(ankiUri))?.name ?: "設定済み"
        if (logUri != null) tvLogFolder.text = DocumentFile.fromTreeUri(this, Uri.parse(logUri))?.name ?: "設定済み"
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter("com.kuni256.ANKI_LOG")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(logReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(logReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(logReceiver)
    }

    private fun checkAndRequestPermissions() {
        // 1. ストレージ権限の要求
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.data = Uri.parse("package:$packageName")
                    startActivity(intent)
                } catch (e: Exception) {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    startActivity(intent)
                }
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 100)
            }
        }

        // 2. 通知権限の要求 (Android 13以上)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
    }
}