package com.kuni256.ankiconnectserver

import android.app.*
import android.content.*
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.*
import android.util.Base64
import android.util.Log
import android.webkit.MimeTypeMap
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
import java.io.File
import java.net.BindException

class AnkiConnectService : Service() {
    private var server: CIOApplicationEngine? = null
    private val TAG = "AnkiConnectServer"

    // ★ 復活：UIのスイッチと連動するためのフラグ
    companion object {
        var isRunning = false
    }

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
                    allowMethod(HttpMethod.Post)
                    allowMethod(HttpMethod.Options)
                    allowHeader(HttpHeaders.ContentType)
                    allowHeader("Access-Control-Request-Private-Network")
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

                                val id = upsertAnkiNote(note)

                                val responseJson = Gson().toJson(AnkiConnectResponse(id, null))
                                call.respondText(responseJson, ContentType.Application.Json)
                            }
                        } catch (e: Exception) {
                            sendLog("❌ 受信エラー: ${e.message}")
                            val errJson = Gson().toJson(AnkiConnectResponse(null, e.message))
                            call.respondText(errJson, ContentType.Application.Json)
                        }
                    }
                }
            }
            server?.start(wait = false)
            isRunning = true // ★ ON
            sendLog("🟢 サーバー起動成功: http://127.0.0.1:8765")
        } catch (e: Exception) {
            sendLog("❌ 起動失敗: ${e.message}")
            stopSelf()
        }
    }

    private fun saveMediaFast(name: String, b64: String) {
        val prefs = getSharedPreferences("AnkiPrefs", Context.MODE_PRIVATE)
        val uriStr = prefs.getString("media_folder_uri", null) ?: return
        val rootFolder = DocumentFile.fromTreeUri(this, Uri.parse(uriStr)) ?: return

        // collection.media を自動特定
        val mediaFolder = rootFolder.findFile("collection.media") ?: rootFolder

        try {
            val pureB64 = if (b64.contains(",")) b64.substringAfter(",") else b64
            val decodedData = Base64.decode(pureB64, Base64.DEFAULT)
            val extension = MimeTypeMap.getFileExtensionFromUrl(name).lowercase()
            val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"

            mediaFolder.createFile(mimeType, name)?.let { file ->
                contentResolver.openOutputStream(file.uri)?.use { out ->
                    out.write(decodedData)
                    out.flush()
                }
                sendLog("🖼 画像保存: $name")
            }
        } catch (e: Exception) {
            sendLog("❌ 画像保存エラー: ${e.message}")
        }
    }

    private fun saveDebugJsonOverwrite(json: String) {
        val uriStr = getSharedPreferences("AnkiPrefs", Context.MODE_PRIVATE).getString("debug_folder_uri", null) ?: return
        val folder = DocumentFile.fromTreeUri(this, Uri.parse(uriStr)) ?: return
        try {
            folder.findFile("anki_debug.json")?.delete()
            folder.createFile("application/json", "anki_debug.json")?.let { file ->
                contentResolver.openOutputStream(file.uri)?.use { it.write(json.toByteArray()) }
            }
            sendLog("📝 デバッグJSONを保存しました")
        } catch (e: Exception) {}
    }

    private fun generateCompareKey(html: String): String {
        val imgRegex = Regex("ap_[a-zA-Z0-9]+\\.png")
        val images = imgRegex.findAll(html).map { it.value }.toList().sorted().joinToString(",")
        var text = html.replace(Regex("<[^>]*>"), "")
        text = text.replace(Regex("&[a-zA-Z0-9#]+;"), "")
        text = text.replace(Regex("[\\s　]+"), "")
        return "$text||$images"
    }

    private fun upsertAnkiNote(note: Note?): Long? {
        if (note == null) return null
        return try {
            val api = AddContentApi(this)
            val deckId = api.deckList.entries.find { it.value == "応用情報" }?.key ?: api.addNewDeck("応用情報")
            val modelId = api.modelList.entries.find { it.value == "AP過去問モデル" }?.key ?: run {
                sendLog("❌ エラー: 'AP過去問モデル' が見つかりません。")
                return null
            }

            val fields = api.getFieldList(modelId)
            val data = Array(fields.size) { "" }
            note.fields?.forEach { (k, v) ->
                val i = fields.indexOf(k)
                if (i != -1) data[i] = v
            }

            val tags = note.tags?.toSet() ?: emptySet()
            val firstFieldRaw = data.firstOrNull() ?: ""
            val newCompareKey = generateCompareKey(firstFieldRaw)

            var existingNoteId: Long? = null
            val prefs = getSharedPreferences("AnkiPrefs", Context.MODE_PRIVATE)
            val uriStr = prefs.getString("media_folder_uri", null)

            // --- 物理DBへのアクセス (以前のコードの欠点を解消) ---
            if (uriStr != null) {
                val rootFolder = DocumentFile.fromTreeUri(this, Uri.parse(uriStr))
                val dbFile = rootFolder?.findFile("collection.anki2")

                if (dbFile != null) {
                    sendLog("🔍 データベース照合中...")
                    val tempDb = File(cacheDir, "temp_anki.db")
                    contentResolver.openInputStream(dbFile.uri)?.use { input ->
                        tempDb.outputStream().use { output -> input.copyTo(output) }
                    }

                    val db = SQLiteDatabase.openDatabase(tempDb.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
                    val cursor = db.rawQuery("SELECT id, flds FROM notes WHERE mid = ?", arrayOf(modelId.toString()))

                    var checkedCount = 0
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(0)
                        val flds = cursor.getString(1)
                        val existingFirstFieldRaw = flds.substringBefore("\u001F")
                        val existingCompareKey = generateCompareKey(existingFirstFieldRaw)

                        if (checkedCount == 0) {
                            sendLog("📝 [診断] 新規キー(先頭15字): ${newCompareKey.take(15)}...")
                            sendLog("📝 [診断] 既存キー(先頭15字): ${existingCompareKey.take(15)}...")
                        }

                        if (existingCompareKey == newCompareKey) {
                            existingNoteId = id
                            break
                        }
                        checkedCount++
                    }
                    cursor.close(); db.close(); tempDb.delete()
                    sendLog("✅ 照合完了。対象件数: $checkedCount")
                } else {
                    sendLog("⚠️ collection.anki2 が見つかりません。Ankiフォルダ設定を確認してください。")
                }
            }

            if (existingNoteId != null) {
                val values = ContentValues().apply {
                    put("flds", data.joinToString("\u001F"))
                    put("tags", " " + (note.tags?.joinToString(" ") ?: "") + " ")
                }
                val notesUri = Uri.parse("content://com.ichi2.anki.flashcards/notes")
                val updateUri = ContentUris.withAppendedId(notesUri, existingNoteId!!)
                contentResolver.update(updateUri, values, null, null)

                sendLog("🔄 ✅ 既存のカードを上書き更新しました (ID: $existingNoteId)")
                existingNoteId
            } else {
                val newId = api.addNote(modelId, deckId!!, data, tags)
                if (newId != null) {
                    sendLog("✨ ✅ 新規カードとして追加しました (ID: $newId)")
                } else {
                    sendLog("❌ 追加失敗: APIがNULLを返しました。")
                }
                newId
            }
        } catch (e: Exception) {
            sendLog("❌ Upsert失敗: ${e.message}")
            null
        }
    }

    private fun sendLog(msg: String) {
        Log.d(TAG, msg)
        sendBroadcast(Intent("com.kuni256.ANKI_LOG").apply {
            putExtra("log_msg", msg)
            setPackage(packageName)
        })
    }

    override fun onDestroy() {
        isRunning = false // ★ OFF
        sendLog("⏹ サービスを終了しました")
        server?.stop(500, 1000)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null
}