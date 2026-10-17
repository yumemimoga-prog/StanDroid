package com.kusamaru.standroid.tool

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * エラーの全文（例外と発生場所）をクリップボードへコピーする。
 * Toastだと途中で切れて読めないため、貼り付けて共有できるようにする。
 * */
object ErrorReport {

    /** 同じ内容を連続でコピーしない */
    @Volatile
    private var lastText = ""

    fun copy(context: Context?, throwable: Throwable) {
        context ?: return
        val appContext = context.applicationContext
        val frames = throwable.stackTrace
            .filter { it.className.startsWith("com.kusamaru") }
            .take(8)
            .joinToString("\n") { "  at ${it.className.substringAfterLast('.')}.${it.methodName}(${it.fileName}:${it.lineNumber})" }
        val text = "StanDroid エラー詳細\n$throwable\n$frames"
        if (text == lastText) return
        lastText = text
        println(text)
        Handler(Looper.getMainLooper()).post {
            runCatching {
                val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("StanDroidエラー", text))
                Toast.makeText(appContext, "エラーの詳細をコピーしました", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
