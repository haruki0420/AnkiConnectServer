package com.kuni256.ankiconnectserver

import android.app.*
import android.content.*
import android.net.Uri
import android.os.*
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import com.ichi2.anki.api.AddContentApi
import com.google.gson.Gson
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.plugins.cors.routing.*
import java.net.BindException

class AnkiConnectService : Service() {
    private var server: CIOApplicationEngine? = null
    private val TAG = "AnkiConnectServer"

    override fun onCreate() {
        super.onCreate()
        val chId = "AnkiConnect"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(chId, "AnkiConnect Server", NotificationManager.IMPORTANCE_LOW))
        }
        val notification = NotificationCompat.Builder(this, chId)
            .setContentTitle("AnkiConnect 稼働中")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true).build()
        startForeground(1, notification)

        sendLog("🚀 サービスを初期化中...")
        startKtorServer()
    }

    private fun startKtorServer() {
        try {
            server = embeddedServer(CIO, port = 8765, host = "127.0.0.1") {
                install(CORS) {
                    allowMethod(HttpMethod.Post); allowMethod(HttpMethod.Options)
                    allowHeader(HttpHeaders.ContentType); allowHeader("Access-Control-Request-Private-Network")
                    anyHost()
                }
                routing {
                    options("/") {
                        call.response.header("Access-Control-Allow-Private-Network", "true")
                        call.respond(HttpStatusCode.OK)
                    }
                    post("/") {
                        try {
                            val rawJson = call.receiveText()
                            saveDebugJsonOverwrite(rawJson)

                            val req = Gson().fromJson(rawJson, AnkiConnectRequest::class.java)
                            if (req.action == "addNote") {
                                val note = req.params?.note
                                note?.picture?.forEach { saveMediaFast(it.filename, it.data) }

                                val id = addAnkiNote(note)
                                val responseBody = Gson().toJson(AnkiConnectResponse(id, null))
                                call.respondText(responseBody, ContentType.Application.Json)

                                if (id != null) sendLog("✅ 追加成功: ID $id")
                                else sendLog("❌ 追加失敗 (モデル名等を確認)")
                            }
                        } catch (e: Exception) {
                            val errBody = Gson().toJson(AnkiConnectResponse(null, e.message))
                            call.respondText(errBody, ContentType.Application.Json)
                        }
                    }
                }
            }
            server?.start(wait = false)
            sendLog("🟢 サーバー起動成功: http://127.0.0.1:8765")

        } catch (e: Exception) {
            // ★★★ ポート競合エラーの判定と停止処理 ★★★
            val isBindError = e is BindException || e.cause is BindException || e.message?.contains("Address already in use") == true

            if (isBindError) {
                sendLog("❌ ポート8765は既に使用されています。")
                sendLog("⚠️ 他のAnkiConnectサーバーを停止してから再試行してください。")
            } else {
                sendLog("❌ サーバー起動失敗: ${e.message}")
            }

            // サーバーが動かない場合はサービスを継続する意味がないので、即座に終了させる
            Log.e(TAG, "Fatal Server Error", e)
            stopSelf()
        }
    }

    private fun saveMediaFast(filename: String, b64: String) {
        val uriStr = getSharedPreferences("AnkiPrefs", Context.MODE_PRIVATE).getString("media_folder_uri", null) ?: return
        val folder = DocumentFile.fromTreeUri(this, Uri.parse(uriStr))
        try {
            val decoded = Base64.decode(if (b64.contains(",")) b64.substringAfter(",") else b64, Base64.DEFAULT)
            folder?.createFile("application/octet-stream", filename)?.let { file ->
                contentResolver.openOutputStream(file.uri)?.use { out -> out.write(decoded) }
            }
        } catch (e: Exception) { Log.e(TAG, "Media Save Error", e) }
    }

    private fun saveDebugJsonOverwrite(json: String) {
        val uriStr = getSharedPreferences("AnkiPrefs", Context.MODE_PRIVATE).getString("debug_folder_uri", null) ?: return
        val folder = DocumentFile.fromTreeUri(this, Uri.parse(uriStr)) ?: return
        try {
            folder.findFile("anki_debug.json")?.delete()
            folder.createFile("application/json", "anki_debug.json")?.let { file ->
                contentResolver.openOutputStream(file.uri)?.use { it.write(json.toByteArray()) }
            }
        } catch (e: Exception) { }
    }

    private fun addAnkiNote(note: Note?): Long? {
        if (note == null) return null
        return try {
            val api = AddContentApi(this)
            val deckId = api.deckList.entries.find { it.value == (note.deckName ?: "応用情報") }?.key ?: api.addNewDeck(note.deckName ?: "応用情報")
            val modelId = api.modelList.entries.find { it.value == (note.modelName ?: "AP過去問モデル") }?.key ?: return null
            val fields = api.getFieldList(modelId)
            val data = Array(fields.size) { "" }
            note.fields?.forEach { (k, v) ->
                val i = fields.indexOf(k)
                if (i != -1) data[i] = v
            }
            api.addNote(modelId, deckId!!, data, note.tags?.toSet() ?: emptySet())
        } catch (e: Exception) { null }
    }

    private fun sendLog(msg: String) {
        Log.d(TAG, msg)
        sendBroadcast(Intent("com.kuni256.ANKI_LOG").apply {
            putExtra("log_msg", msg)
            setPackage(packageName)
        })
    }

    override fun onDestroy() {
        sendLog("⏹ サービスを終了しました")
        server?.stop(500, 1000)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null
}