package com.ttech.pocketlinux.bridge

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.ttech.pocketlinux.Notifications
import com.ttech.pocketlinux.R
import java.io.File

/**
 * コンテナ内の `pocket-open URL`（xdg-open・BROWSER）を受けて、Android のブラウザで開く。
 * コンテナは /run/pocket-linux/open に `*.url` を置き、ここでそれを監視する。
 *
 * 画面が裏にあるときは Android の制限で別アプリを開けないため、通知から開けるようにする。
 */
class UrlBridge(
    private val context: Context,
    private val dir: File,
    private val isForeground: () -> Boolean,
) {
    private val main = Handler(Looper.getMainLooper())
    private var observer: FileObserver? = null

    fun start() {
        if (observer != null) return
        dir.mkdirs()
        // 監視を始める前に置かれていたものも処理する
        dir.listFiles()?.filter { it.name.endsWith(".url") }?.forEach { handle(it) }
        // File を受け取るコンストラクタは API 29 から。minSdk 26 のためパス文字列版を使う
        @Suppress("DEPRECATION")
        observer = object : FileObserver(dir.path, MOVED_TO or CLOSE_WRITE) {
            override fun onEvent(event: Int, path: String?) {
                if (path == null || !path.endsWith(".url")) return
                handle(File(dir, path))
            }
        }.also { it.startWatching() }
    }

    fun stop() {
        observer?.stopWatching()
        observer = null
    }

    private fun handle(file: File) {
        val url = runCatching { file.readText().trim().lineSequence().firstOrNull()?.trim() }.getOrNull()
        file.delete()
        if (url.isNullOrEmpty() || !isAllowed(url)) {
            Log.w(TAG, "開けない URL を無視しました: $url")
            return
        }
        main.post { open(url) }
    }

    private fun open(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (isForeground()) {
            runCatching { context.startActivity(intent) }.onSuccess { return }
        }
        val pending = PendingIntent.getActivity(
            context, url.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, Notifications.CHANNEL_EVENTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("ブラウザで開く")
            .setContentText(url)
            .setStyle(NotificationCompat.BigTextStyle().bigText(url))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(url.hashCode(), notification)
    }

    companion object {
        private const val TAG = "UrlBridge"

        /** ブラウザで開いてよいのは http(s) だけ（intent: などで他のアプリを勝手に動かさせない） */
        fun isAllowed(url: String): Boolean {
            val scheme = url.substringBefore(':', "").lowercase()
            return (scheme == "http" || scheme == "https") && url.length < 8192 && url.none { it.isISOControl() }
        }
    }
}
