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
        val matched = cookies.filter { cookie -> matches(cookie, host, requestPath) }
        if (matched.isEmpty()) return null
        return matched.joinToString("; ") { "${it.name}=${it.value}" }
    }

    /**
     * Cookieを付与するDataSource.Factoryを作る
     * @param cookiesProvider 最新のCookie一覧を返す関数（画質変更でCookieが更新されても追従するため）
     * */
    fun createFactory(context: Context, userAgent: String, cookiesProvider: () -> List<HlsCookie>): DataSource.Factory {
        val appContext = context.applicationContext
        // Set-Cookie で更新される視聴Cookieに追従するため、ブラウザと同じくCookieJarを使う
        val cookieJar = HlsCookieJar(cookiesProvider) { loginCookies(appContext) }
        val okHttpClient = com.kusamaru.standroid.tool.OkHttpClientSingleton.okHttpClient.newBuilder()
            .cookieJar(cookieJar)
            .build()
        val httpFactory = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(okHttpClient)
            .setUserAgent(userAgent)
            // 公式プレイヤー（ブラウザ）と同じく視聴ページ由来のリクエストとして送る
            .setDefaultRequestProperties(mapOf(
                "Origin" to "https://live.nicovideo.jp",
                "Referer" to "https://live.nicovideo.jp/",
            ))
        return DefaultDataSource.Factory(context, httpFactory)
    }

    /** 診断用。最後に送ったCookie名 */
    @Volatile
    var lastSentCookieNames: String = "なし"

    /** 視聴Cookie（streamメッセージ）＋Set-Cookieでの更新＋ログインCookie を扱うCookieJar */
    private class HlsCookieJar(
        private val provider: () -> List<HlsCookie>,
        private val login: () -> List<HlsCookie>,
    ) : okhttp3.CookieJar {
        private var lastProvided: List<HlsCookie>? = null
        private val store = LinkedHashMap<String, HlsCookie>()

        @Synchronized
        override fun loadForRequest(url: okhttp3.HttpUrl): List<okhttp3.Cookie> {
            val provided = provider()
            // 新しいstreamメッセージを受け取ったらその値で上書き
            if (provided !== lastProvided) {
                lastProvided = provided
                provided.forEach { store[key(it.name, it.domain)] = it }
            }
            val matched = (store.values + login()).filter { matches(it, url.host, url.encodedPath) }
            lastSentCookieNames = matched.joinToString { it.name }.ifEmpty { "なし" }
            return matched.mapNotNull { cookie ->
                runCatching {
                    okhttp3.Cookie.Builder()
                        .name(cookie.name)
                        .value(cookie.value)
                        .domain(cookie.domain.trimStart('.').ifEmpty { url.host })
                        .path(cookie.path)
                        .build()
                }.getOrNull()
            }
        }

        @Synchronized
        override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<okhttp3.Cookie>) {
            val now = System.currentTimeMillis()
            cookies.forEach { cookie ->
                val k = key(cookie.name, cookie.domain)
                if (cookie.expiresAt < now) {
                    store.remove(k)
                } else {
                    store[k] = HlsCookie(domain = cookie.domain, path = cookie.path, name = cookie.name, value = cookie.value)
                }
            }
        }

        private fun key(name: String, domain: String) = "$name@${domain.trimStart('.').lowercase()}"
    }

    /** Cookieがこのホスト・パスへ送るものかどうか */
    fun matches(cookie: HlsCookie, host: String?, path: String?): Boolean {
        if (host == null) return false
        val requestPath = path?.ifEmpty { "/" } ?: "/"
        val domain = cookie.domain.trimStart('.').lowercase()
        val hostLower = host.lowercase()
        val domainMatch = domain.isEmpty() || hostLower == domain || hostLower.endsWith(".$domain")
        return domainMatch && requestPath.startsWith(cookie.path)
    }

    /**
     * ログインCookie（user_session）。復号キー（.key）の取得はログイン済みのブラウザと同じく
     * .nicovideo.jp のログインCookieも必要なため付ける（ブラウザ・yt-dlpはCookieJarで自動的に送っている）
     * */
    private fun loginCookies(context: Context): List<HlsCookie> {
        val session = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context).getString("user_session", "")
        if (session.isNullOrEmpty()) return emptyList()
        return listOf(HlsCookie(domain = "nicovideo.jp", path = "/", name = "user_session", value = session))
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
            val current = cause
            if (current is HttpDataSource.InvalidResponseCodeException) {
                lines += "http=${current.responseCode} host=${current.dataSpec.uri.host} path=${current.dataSpec.uri.path?.takeLast(60)}"
                lines += "sentCookie=$lastSentCookieNames"
                val body = runCatching { String(current.responseBody, Charsets.UTF_8).take(200) }.getOrNull()
                if (!body.isNullOrBlank()) lines += "body=$body"
            } else {
                lines += "cause=${current.javaClass.simpleName}: ${current.message?.take(150)}"
            }
            cause = current.cause
            depth++
        }
        lines += "lastCookie=$lastSentCookieNames"
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
