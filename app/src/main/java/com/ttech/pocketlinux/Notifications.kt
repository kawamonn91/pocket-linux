package com.ttech.pocketlinux

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

object Notifications {
    /** 常駐中の表示（音なし） */
    const val CHANNEL_RUNNING = "running"
    /** ブラウザで開く・同期の失敗など、知らせたいこと */
    const val CHANNEL_EVENTS = "events"

    const val ID_RUNNING = 1
    const val ID_SYNC_ERROR = 2

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_RUNNING, "実行中のコンテナ", NotificationManager.IMPORTANCE_LOW).apply {
                description = "ターミナル・同期・IDE のサーバーが動いている間に表示します"
                setShowBadge(false)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_EVENTS, "お知らせ", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "ブラウザで開く依頼や、同期の失敗をお知らせします"
            }
        )
    }
}
