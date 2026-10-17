package com.kusamaru.standroid.nicoapi.nicolive

import com.kusamaru.standroid.tool.OkHttpClientSingleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Request
import okio.BufferedSource
import org.json.JSONObject
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * 新コメントサーバー（NDGR / mpn.live.nicovideo.jp）からコメントを受信するクライアント。
 *
 * 2024年以降、視聴WebSocketは "room" ではなく "messageServer"（data.viewUri）を送ってくる。
 * viewUri?at=now から ChunkedEntry（Protobuf、長さ区切りストリーム）を受け取り、
 * そこに含まれる segment.uri から ChunkedMessage を受け取るとコメントになる。
 *
 * 受け取ったコメントは従来のコメントサーバーと同じ {"chat":{...}} 形式のJSONに変換して渡すので、
 * 既存の表示処理（CommentJSONParse など）をそのまま使える。
 *
 * 仕様の参考：n-air-app/nicolive-comment-protobuf、tsukumijima/NDGRClient
 * */
class NicoLiveNdgrComment {

    private val client = OkHttpClientSingleton.okHttpClient.newBuilder()
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var mainJob: Job? = null
    private val activeCalls = Collections.synchronizedSet(mutableSetOf<Call>())

    /** 同じコメントを二重に流さないためのID記録（新しい順に一定数だけ保持） */
    private val receivedIds = Collections.synchronizedSet(LinkedHashSet<String>())

    /**
     * 接続する
     * @param viewUri messageServer メッセージの data.viewUri
     * @param roomName コメントに付ける部屋名
     * @param onComment (コメントJSON, 部屋名, 過去コメントならtrue)
     * */
    fun connect(viewUri: String, roomName: String, onComment: (String, String, Boolean) -> Unit) {
        mainJob?.cancel()
        mainJob = scope.launch {
            val startedSegments = mutableSetOf<String>()
            var at = "now"
            var isFirst = true
            var errorCount = 0
            while (isActive) {
                var nextAt: Long? = null
                try {
                    val url = viewUri + (if (viewUri.contains("?")) "&" else "?") + "at=$at"
                    streamDelimited(url) { bytes ->
                        val entry = ProtoReader(bytes).fields()
                        entry[1]?.bytes?.let { segment -> // segment
                            val uri = ProtoReader(segment).fields()[3]?.string
                            if (uri != null && startedSegments.add(uri)) {
                                launch { readSegment(uri, roomName, false, onComment) }
                            }
                        }
                        entry[3]?.bytes?.let { previous -> // previous：少し前のコメント
                            val uri = ProtoReader(previous).fields()[3]?.string
                            if (isFirst && uri != null && startedSegments.add(uri)) {
                                launch { readSegment(uri, roomName, true, onComment) }
                            }
                        }
                        entry[4]?.bytes?.let { next -> // next
                            nextAt = ProtoReader(next).fields()[1]?.varint
                        }
                    }
                    errorCount = 0
                } catch (e: Exception) {
                    coroutineContext.ensureActive()
                    e.printStackTrace()
                    errorCount++
                    if (errorCount >= 5) break
                    delay(2000)
                    continue
                }
                isFirst = false
                val next = nextAt ?: break // nextが無ければ放送終了
                at = next.toString()
                // 古いセグメントの記録は捨てる
                if (startedSegments.size > 50) startedSegments.clear()
            }
        }
    }

    /** セグメントからコメントを読む */
    private suspend fun readSegment(uri: String, roomName: String, isHistory: Boolean, onComment: (String, String, Boolean) -> Unit) {
        try {
            streamDelimited(uri) { bytes ->
                val json = chunkedMessageToJSON(bytes) ?: return@streamDelimited
                onComment(json, roomName, isHistory)
            }
        } catch (e: Exception) {
            coroutineContext.ensureActive()
            e.printStackTrace()
        }
    }

    /** ChunkedMessage を従来形式のコメントJSONへ。コメント以外ならnull */
    private fun chunkedMessageToJSON(bytes: ByteArray): String? {
        val chunked = ProtoReader(bytes).fields()
        val meta = chunked[1]?.bytes?.let { ProtoReader(it).fields() } ?: return null
        val message = chunked[2]?.bytes?.let { ProtoReader(it).fields() } ?: return null
        // chat(1) か overflowed_chat(20)
        val chat = (message[1] ?: message[20])?.bytes?.let { ProtoReader(it).fields() } ?: return null

        val id = meta[1]?.string
        if (id != null) {
            if (!receivedIds.add(id)) return null
            if (receivedIds.size > 2000) synchronized(receivedIds) {
                val iterator = receivedIds.iterator()
                repeat(1000) { if (iterator.hasNext()) { iterator.next(); iterator.remove() } }
            }
        }
        val atFields = meta[2]?.bytes?.let { ProtoReader(it).fields() }
        val seconds = atFields?.get(1)?.varint ?: (System.currentTimeMillis() / 1000)
        val nanos = atFields?.get(2)?.varint ?: 0L

        val rawUserId = chat[5]?.varint
        val hashedUserId = chat[6]?.string
        val chatObject = JSONObject().apply {
            put("thread", "")
            put("no", (chat[8]?.varint ?: 0L).toString())
            put("vpos", (chat[3]?.varint?.toInt() ?: 0).toString())
            put("date", seconds.toString())
            put("date_usec", (nanos / 1000).toString())
            put("user_id", rawUserId?.toString() ?: hashedUserId ?: "")
            if (rawUserId == null) put("anonymity", "1")
            if ((chat[4]?.varint ?: 0L) == 1L) put("premium", "1")
            val mail = modifierToMail(chat[7]?.bytes)
            if (mail.isNotEmpty()) put("mail", mail)
            chat[2]?.string?.let { put("name", it) }
            put("content", chat[1]?.string ?: "")
        }
        return JSONObject().put("chat", chatObject).toString()
    }

    /** Modifier を従来の mail（コマンド）文字列へ */
    private fun modifierToMail(bytes: ByteArray?): String {
        if (bytes == null) return ""
        val modifier = ProtoReader(bytes).fields()
        val commands = mutableListOf<String>()
        when (modifier[1]?.varint?.toInt()) {
            1 -> commands += "shita"
            2 -> commands += "ue"
        }
        when (modifier[2]?.varint?.toInt()) {
            1 -> commands += "small"
            2 -> commands += "big"
        }
        modifier[3]?.varint?.toInt()?.let { index ->
            val name = COLOR_NAMES.getOrNull(index)
            if (name != null && name != "white") commands += name
        }
        modifier[4]?.bytes?.let { fullColor ->
            val rgb = ProtoReader(fullColor).fields()
            val r = rgb[1]?.varint?.toInt() ?: 0
            val g = rgb[2]?.varint?.toInt() ?: 0
            val b = rgb[3]?.varint?.toInt() ?: 0
            commands += String.format("#%02x%02x%02x", r, g, b)
        }
        when (modifier[5]?.varint?.toInt()) {
            1 -> commands += "mincho"
            2 -> commands += "gothic"
        }
        if (modifier[6]?.varint?.toInt() == 1) commands += "_live"
        return commands.joinToString(" ")
    }

    /** 長さ区切りのProtobufストリームを読み、1メッセージごとに呼ぶ */
    private suspend fun streamDelimited(url: String, onMessage: (ByteArray) -> Unit) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Stan-Droid;@kusamaru_jp")
            .header("Origin", "https://live.nicovideo.jp")
            .header("Referer", "https://live.nicovideo.jp/")
            .header("Accept", "*/*")
            .build()
        val call = client.newCall(request)
        activeCalls.add(call)
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code} $url")
                val source = response.body?.source() ?: return
                while (true) {
                    coroutineContext.ensureActive()
                    val length = readVarint(source) ?: break
                    val bytes = source.readByteArray(length)
                    onMessage(bytes)
                }
            }
        } finally {
            activeCalls.remove(call)
        }
    }

    /** ストリームから varint を読む。終端ならnull */
    private fun readVarint(source: BufferedSource): Long? {
        if (source.exhausted()) return null
        var result = 0L
        var shift = 0
        while (true) {
            val b = source.readByte().toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            if (shift > 63) throw java.io.IOException("varint too long")
        }
    }

    /** 終了 */
    fun destroy() {
        mainJob?.cancel()
        synchronized(activeCalls) { activeCalls.forEach { it.cancel() } }
        scope.cancel()
    }

    /** 最小限のProtobufデコーダ。同じフィールド番号は最後の値を使う */
    class ProtoReader(private val buf: ByteArray) {
        class Field(val varint: Long? = null, val bytes: ByteArray? = null) {
            val string: String? get() = bytes?.toString(Charsets.UTF_8)
        }

        fun fields(): Map<Int, Field> {
            val result = mutableMapOf<Int, Field>()
            var pos = 0
            fun varint(): Long {
                var value = 0L
                var shift = 0
                while (true) {
                    val b = buf[pos++].toInt() and 0xFF
                    value = value or ((b and 0x7F).toLong() shl shift)
                    if (b and 0x80 == 0) return value
                    shift += 7
                }
            }
            while (pos < buf.size) {
                val tag = varint()
                val number = (tag ushr 3).toInt()
                when ((tag and 7).toInt()) {
                    0 -> result[number] = Field(varint = varint())
                    1 -> pos += 8
                    2 -> {
                        val length = varint().toInt()
                        result[number] = Field(bytes = buf.copyOfRange(pos, pos + length))
                        pos += length
                    }
                    5 -> pos += 4
                    else -> return result // 未対応の型は以降を無視
                }
            }
            return result
        }
    }

    companion object {
        private val COLOR_NAMES = listOf(
            "white", "red", "pink", "orange", "yellow", "green", "cyan", "blue", "purple", "black",
            "white2", "red2", "pink2", "orange2", "yellow2", "green2", "cyan2", "blue2", "purple2", "black2",
        )
    }
}
