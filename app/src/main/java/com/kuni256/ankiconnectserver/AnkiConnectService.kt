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

class AnkiConnectService : Service() {
    private var server: CIOApplicationEngine? = null
    private val TAG = "AnkiConnectServer"

    override fun onCreate() {
        super.onCreate()
        val chId = "AnkiConnect"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(chId, "Server", NotificationManager.IMPORTANCE_LOW))
        }
        startForeground(1, NotificationCompat.Builder(this, chId).setContentTitle("AnkiConnect Running").setSmallIcon(android.R.drawable.ic_dialog_info).build())
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
                                // 画像を保存
                                note?.picture?.forEach { saveMediaStrict(it.filename, it.data) }

                                val id = addAnkiNote(note)
                                // ★重要：手動でJSON文字列に変換して返却することで、エラーを回避
                                val responseJson = Gson().toJson(AnkiConnectResponse(id, null))
                                call.respondText(responseJson, ContentType.Application.Json)
                                sendLog("✅ 追加成功: $id")
                            }
                        } catch (e: Exception) {
                            val errJson = Gson().toJson(AnkiConnectResponse(null, e.message))
                            call.respondText(errJson, ContentType.Application.Json)
                        }
                    }
                }
            }
            server?.start(wait = false)
            sendLog("★ サーバー起動(8765)")
        } catch (e: Exception) {
            if (e.message?.contains("Address already in use") == true) sendLog("❌ ポート重複エラー")
        }
    }

    // 画像フォルダでの「名前検索」を一切せず、直接上書きに近い挙動をさせる
    private fun saveMediaStrict(name: String, b64: String) {
        val uriStr = getSharedPreferences("AnkiPrefs", Context.MODE_PRIVATE).getString("media_folder_uri", null) ?: return
        val folder = DocumentFile.fromTreeUri(this, Uri.parse(uriStr)) ?: return

        // Androidの制限上、巨大フォルダでの検索(findFile)は禁止。
        // 代わりに application/octet-stream で作成することで、OSによるリネームを防ぐ
        folder.createFile("application/octet-stream", name)?.let { file ->
            contentResolver.openOutputStream(file.uri)?.use { out ->
                out.write(Base64.decode(b64, Base64.DEFAULT))
                out.flush()
            }
        }
    }

    // ログフォルダはファイルが少ないため、findFileを使用して「上書き」を実現
    private fun saveDebugJsonOverwrite(json: String) {
        val uriStr = getSharedPreferences("AnkiPrefs", Context.MODE_PRIVATE).getString("debug_folder_uri", null) ?: return
        val folder = DocumentFile.fromTreeUri(this, Uri.parse(uriStr)) ?: return

        // 既存のログを消して上書き
        folder.findFile("anki_debug.json")?.delete()
        folder.createFile("application/json", "anki_debug.json")?.let { file ->
            contentResolver.openOutputStream(file.uri)?.use { it.write(json.toByteArray()) }
        }
    }

    private fun addAnkiNote(note: Note?): Long? {
        if (note == null) return null
        val api = AddContentApi(this)
        val deckId = api.deckList.entries.find { it.value == "応用情報" }?.key ?: api.addNewDeck("応用情報")
        val modelId = api.modelList.entries.find { it.value == "AP過去問モデル" }?.key ?: return null
        val fields = api.getFieldList(modelId)
        val data = Array(fields.size) { "" }
        note.fields?.forEach { (k, v) -> val i = fields.indexOf(k); if (i != -1) data[i] = v }
        return api.addNote(modelId, deckId!!, data, note.tags?.toSet() ?: emptySet())
    }

    private fun sendLog(msg: String) {
        sendBroadcast(Intent("com.kuni256.ANKI_LOG").apply { putExtra("log_msg", msg); setPackage(packageName) })
    }

    override fun onDestroy() { server?.stop(500, 1000); super.onDestroy() }
    override fun onBind(intent: Intent?) = null
}