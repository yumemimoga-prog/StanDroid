package com.kusamaru.standroid.nicoapi.nicolive

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import org.json.JSONObject

/**
 * ニコ生のHLS配信用DataSource。
 *
 * 2026年頃から視聴WebSocketの startWatching に `accessRightMethod: single_cookie` を指定し、
 * `stream` メッセージの `data.cookies` をHLS（m3u8・セグメント）リクエストに付けないと再生できなくなった。
 * Cookieはドメイン・パスが一致するリクエストにだけ付与する。
 * */
@OptIn(UnstableApi::class)
object NicoLiveHlsDataSource {

    /** streamメッセージに含まれるCookie */
    data class HlsCookie(
        val domain: String,
        val path: String,
        val name: String,
        val value: String,
    )

    /**
     * streamメッセージから Cookie 一覧を取り出す。無い（古い形式）なら空。
     * @param message 視聴WebSocketのstreamメッセージ
     * */
    fun parseCookies(message: String?): List<HlsCookie> {
        if (message == null) return emptyList()
        val cookies = JSONObject(message).optJSONObject("data")?.optJSONArray("cookies") ?: return emptyList()
        return (0 until cookies.length()).mapNotNull { i ->
            val obj = cookies.optJSONObject(i) ?: return@mapNotNull null
            val name = obj.optString("name")
            if (name.isEmpty()) return@mapNotNull null
            HlsCookie(
                domain = obj.optString("domain"),
                path = obj.optString("path", "/").ifEmpty { "/" },
                name = name,
                value = obj.optString("value"),
            )
        }
    }

    /** 指定したホスト・パスへ送るべきCookieヘッダーを作る。該当なしならnull */
    fun cookieHeaderFor(cookies: List<HlsCookie>, host: String?, path: String?): String? {
        if (host == null) return null
        val requestPath = path?.ifEmpty { "/" } ?: "/"
        val matched = cookies.filter { cookie ->
            val domain = cookie.domain.trimStart('.').lowercase()
            val hostLower = host.lowercase()
            val domainMatch = domain.isEmpty() || hostLower == domain || hostLower.endsWith(".$domain")
            domainMatch && requestPath.startsWith(cookie.path)
        }
        if (matched.isEmpty()) return null
        return matched.joinToString("; ") { "${it.name}=${it.value}" }
    }

    /**
     * Cookieを付与するDataSource.Factoryを作る
     * @param cookiesProvider 最新のCookie一覧を返す関数（画質変更でCookieが更新されても追従するため）
     * */
    fun createFactory(context: Context, userAgent: String, cookiesProvider: () -> List<HlsCookie>): DataSource.Factory {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setAllowCrossProtocolRedirects(true)
        val resolvingFactory = ResolvingDataSource.Factory(httpFactory) { dataSpec ->
            val header = cookieHeaderFor(cookiesProvider(), dataSpec.uri.host, dataSpec.uri.path)
            if (header != null) dataSpec.withAdditionalHeaders(mapOf("Cookie" to header)) else dataSpec
        }
        return DefaultDataSource.Factory(context, resolvingFactory)
    }

    /** 1再生につき1回だけ診断情報をコピーするためのフラグ */
    @Volatile
    private var diagnosticCopied = false

    /** 新しい番組を開いたらリセットする */
    fun resetDiagnostic() {
        diagnosticCopied = false
    }

    /**
     * 再生エラーの診断情報を作る。Cookieの値やトークンは含めない
     * */
    fun buildDiagnostic(error: PlaybackException, html: NicoLiveHTML): String {
        val lines = mutableListOf<String>()
        lines += "StanDroid ニコ生 診断"
        lines += "error=${error.errorCodeName}"
        var cause: Throwable? = error
        var depth = 0
        while (cause != null && depth < 6) {
            if (cause is HttpDataSource.InvalidResponseCodeException) {
                lines += "http=${cause.responseCode} host=${cause.dataSpec.uri.host} path=${cause.dataSpec.uri.path?.takeLast(60)}"
                lines += "sentCookie=${cause.dataSpec.httpRequestHeaders.containsKey("Cookie")}"
                val body = runCatching { String(cause.responseBody, Charsets.UTF_8).take(200) }.getOrNull()
                if (!body.isNullOrBlank()) lines += "body=$body"
            } else {
                lines += "cause=${cause.javaClass.simpleName}: ${cause.message?.take(150)}"
            }
            cause = cause.cause
            depth++
        }
        lines += "stream=${html.lastStreamDiagnostic}"
        return lines.joinToString("\n")
    }

    /** 診断情報をクリップボードへコピーしてToastで知らせる（1再生1回） */
    fun copyDiagnosticOnce(context: Context, error: PlaybackException, html: NicoLiveHTML) {
        if (diagnosticCopied) return
        diagnosticCopied = true
        val text = buildDiagnostic(error, html)
        println(text)
        runCatching {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("StanDroid診断", text))
            Toast.makeText(context, "エラーの詳細をコピーしました。チャットに貼り付けてください", Toast.LENGTH_LONG).show()
        }
    }

}
