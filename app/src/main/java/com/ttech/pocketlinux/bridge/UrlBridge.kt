package com.ttech.pocketlinux.bridge

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.ttech.pocketlinux.Notifications
import com.ttech.pocketlinux.R

/**
 * コンテナ内の `pocket-open URL`（xdg-open・BROWSER）を受けて、Android のブラウザで開く。
 * コンテナは /run/pocket-linux/open に `*.url` を置き、VM のエージェントが `open-url` のイベントで知らせてくる。
 *
 * 画面が裏にあるときは Android の制限で別アプリを開けないため、通知から開けるようにする。
 */
class UrlBridge(
    private val context: Context,
    private val isForeground: () -> Boolean,
) {
    private val main = Handler(Looper.getMainLooper())

    /** コンテナからの依頼（内容を信用しない） */
    fun onGuestRequest(url: String) {
        if (!isAllowed(url)) {
            Log.w(TAG, "開けない URL を無視しました: $url")
            return
        }
        openInBrowser(url)
    }

    /** アプリから URL を開く（Google のログインなど）。画面が裏にあるときは通知から開けるようにする */
    fun openInBrowser(url: String) {
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
