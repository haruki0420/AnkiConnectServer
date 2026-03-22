package com.kuni256.ankiconnectserver

data class AnkiConnectRequest(val action: String, val version: Int, val params: Params?)
data class Params(val note: Note?)
data class Note(
    val deckName: String?,
    val modelName: String?,
    val fields: Map<String, String>?,
    val tags: List<String>?,
    val picture: List<Picture>?,
    val options: NoteOptions?
)
data class NoteOptions(val allowDuplicate: Boolean, val duplicateScope: String)
data class Picture(val data: String, val filename: String, val fields: List<String>?)
data class AnkiConnectResponse(val result: Any?, val error: String?)