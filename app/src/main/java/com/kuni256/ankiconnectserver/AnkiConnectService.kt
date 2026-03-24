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
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager

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
            // 外部からの接続を許可するため host を 0.0.0.0 に設定
            server = embeddedServer(CIO, port = 8765, host = "0.0.0.0") {
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
                            sendLog("📥 受信: ${rawJson.take(100)}...")
                            saveDebugJsonOverwrite(rawJson)

                            val req = try {
                                Gson().fromJson(rawJson, AnkiConnectRequest::class.java)
                            } catch (e: Exception) {
                                sendLog("❌ JSON解析失敗: ${e.message}")
                                null
                            }

                            if (req == null) {
                                call.respond(HttpStatusCode.BadRequest)
                                return@post
                            }

                            sendLog("🔍 アクション: ${req.action}")

                            if (req.action == "addNote") {
                                val note = req.params?.note
                                if (note == null) {
                                    sendLog("⚠️ 警告: ノートデータが空です (params.note is null)")
                                    val errJson = Gson().toJson(AnkiConnectResponse(null, "params.note is missing"))
                                    call.respondText(errJson, ContentType.Application.Json)
                                } else {
                                    note.picture?.forEach { saveMediaFast(it.filename, it.data) }

                                    // Upsert機能の呼び出し
                                    val id = upsertAnkiNote(note)

                                    if (id != null) {
                                        sendLog("✅ 処理成功: ノートID $id")
                                        val responseJson = Gson().toJson(AnkiConnectResponse(id, null))
                                        call.respondText(responseJson, ContentType.Application.Json)
                                    } else {
                                        sendLog("❌ 処理失敗: IDが取得できませんでした")
                                        val errJson = Gson().toJson(AnkiConnectResponse(null, "Upsert failed. Check app logs."))
                                        call.respondText(errJson, ContentType.Application.Json)
                                    }
                                }
                            } else {
                                sendLog("ℹ️ 未対応のアクションです: ${req.action}")
                                call.respondText(Gson().toJson(AnkiConnectResponse(null, "Unsupported action")), ContentType.Application.Json)
                            }
                        } catch (e: Exception) {
                            sendLog("❌ 通信エラー: ${e.message}")
                            val errJson = Gson().toJson(AnkiConnectResponse(null, e.message))
                            call.respondText(errJson, ContentType.Application.Json)
                        }
                    }
                }
            }
            server?.start(wait = false)
            sendLog("🟢 サーバー起動完了: ポート 8765")

        } catch (e: Exception) {
            val isBindError = e is BindException || e.cause is BindException || e.message?.contains("Address already in use") == true

            if (isBindError) {
                sendLog("❌ ポート8765は既に使用されています。")
                sendLog("⚠️ 他のサーバー(例: Termux版)を停止してから再試行してください。")
            } else {
                sendLog("❌ サーバー起動失敗: ${e.message}")
            }

            Log.e(TAG, "Fatal Server Error", e)
            stopSelf()
        }
    }

    private fun upsertAnkiNote(note: Note?): Long? {
        if (note == null) return null
        return try {
            val api = AddContentApi(this)

            // 1. API権限チェック
            if (ContextCompat.checkSelfPermission(this, "com.ichi2.anki.permission.READ_WRITE_DATABASE") != PackageManager.PERMISSION_GRANTED) {
                sendLog("❌ エラー: AnkiDroid APIの実行権限がありません。AnkiDroidの設定を確認してください。")
                return null
            }

            // 2. デッキ名の動的取得（指定がなければ 'Default'）
            val deckName = note.deckName ?: "Default"
            val deckId = api.deckList.entries.find { it.value == deckName }?.key ?: api.addNewDeck(deckName)
            sendLog("📂 対象デッキ: $deckName (ID: $deckId)")

            // 3. モデル名の動的取得（指定がなければ 'Basic'）
            val modelName = note.modelName ?: "Basic"
            val modelId = api.modelList.entries.find { it.value == modelName }?.key ?: run {
                sendLog("❌ エラー: モデル '$modelName' が AnkiDroid 内に見つかりません。")
                return null
            }
            sendLog("📝 使用モデル: $modelName (ID: $modelId)")

            // 4. フィールドデータの作成と紐付け
            val fields = api.getFieldList(modelId)
            val data = Array(fields.size) { "" }
            var mappedFields = 0
            note.fields?.forEach { (k, v) ->
                val i = fields.indexOf(k)
                if (i != -1) {
                    data[i] = v
                    mappedFields++
                }
            }
            sendLog("🔗 フィールド紐付け: $mappedFields / ${fields.size} 件完了")

            val firstFieldRaw = data.firstOrNull() ?: ""
            if (firstFieldRaw.isBlank()) {
                sendLog("⚠️ 警告: 第1フィールド(ソート用)が空です。カードが追加できない可能性があります。")
            }

            val tags = note.tags?.toSet() ?: emptySet()
            val newCompareKey = generateCompareKey(firstFieldRaw)

            // 5. ContentProvider を使って重複チェック (安全なDBアクセス)
            val notesUri = Uri.parse("content://com.ichi2.anki.flashcards/notes")
            var existingNoteId: Long? = null

            try {
                sendLog("🔍 ContentProvider で重複チェックを開始...")
                contentResolver.query(
                    notesUri,
                    arrayOf("_id", "flds"), // 取得するカラム
                    "mid = ?",              // modelIdで絞り込み
                    arrayOf(modelId.toString()),
                    null
                )?.use { cursor ->
                    val idIdx = cursor.getColumnIndex("_id")
                    val fldsIdx = cursor.getColumnIndex("flds")

                    var checkedCount = 0
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idIdx)
                        val flds = cursor.getString(fldsIdx)
                        // Ankiのデータは \u001F (Unit Separator) で区切られているため、最初の要素を取得
                        val existingFirstField = flds.substringBefore('\u001F')

                        if (generateCompareKey(existingFirstField) == newCompareKey) {
                            existingNoteId = id
                            sendLog("🔄 同一カードを発見しました (ID: $existingNoteId)")
                            break
                        }
                        checkedCount++
                    }
                    sendLog("✅ 照合完了。対象件数: $checkedCount")
                }
            } catch (e: Exception) {
                sendLog("⚠️ DB照合中にエラーが発生しました。照合をスキップします: ${e.message}")
            }

            // 6. Upsert (更新 or 追加) の実行
            if (existingNoteId != null) {
                // 【更新】 既存ノートのIDを指定して上書き
                val values = ContentValues().apply {
                    put("flds", data.joinToString("\u001F"))
                    // Ankiのタグフォーマットは前後にスペースが必要 (例: " tag1 tag2 ")
                    val formattedTags = if (tags.isNotEmpty()) " ${tags.joinToString(" ")} " else ""
                    put("tags", formattedTags)
                }
                val updateUri = ContentUris.withAppendedId(notesUri, existingNoteId!!)
                val count = contentResolver.update(updateUri, values, null, null)

                if (count > 0) {
                    sendLog("✨ 既存カードを更新しました (ID: $existingNoteId)")
                    existingNoteId
                } else {
                    sendLog("❌ 更新に失敗しました")
                    null
                }
            } else {
                // 【追加】 APIを使って新規追加
                sendLog("🚀 新規カード追加を実行中...")
                val newId = api.addNote(modelId, deckId!!, data, tags)
                if (newId != null) {
                    sendLog("✨ 新規カードを追加しました (ID: $newId)")
                } else {
                    sendLog("❌ 追加失敗: APIがnullを返しました。第1フィールドの重複や必須フィールドを確認してください。")
                }
                newId
            }
        } catch (e: Exception) {
            sendLog("💥 Upsert処理中に例外発生: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    // 比較用のヘルパー（HTMLタグやスペースの違いを無視して純粋なテキストで比較する）
    private fun generateCompareKey(html: String): String {
        var text = html.replace(Regex("<[^>]*>"), "") // HTMLタグを除去
        text = text.replace(Regex("&[a-zA-Z0-9#]+;"), "") // HTMLエンティティを除去
        return text.replace(Regex("\\s+"), "") // すべての空白文字を除去
    }

    private fun saveMediaFast(filename: String, b64: String) {
        val uriStr = getSharedPreferences("AnkiPrefs", Context.MODE_PRIVATE).getString("media_folder_uri", null) ?: return
        val folder = DocumentFile.fromTreeUri(this, Uri.parse(uriStr))
        try {
            val decoded = Base64.decode(if (b64.contains(",")) b64.substringAfter(",") else b64, Base64.DEFAULT)
            folder?.createFile("application/octet-stream", filename)?.let { file ->
                contentResolver.openOutputStream(file.uri)?.use { out -> out.write(decoded) }
            }
            sendLog("🖼 画像保存成功: $filename")
        } catch (e: Exception) {
            sendLog("❌ 画像保存エラー: ${e.message}")
            Log.e(TAG, "Media Save Error", e)
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
        } catch (e: Exception) { }
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