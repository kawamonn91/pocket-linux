package com.ttech.pocketlinux.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.ttech.pocketlinux.AppGraph
import com.ttech.pocketlinux.MainActivity
import com.ttech.pocketlinux.Notifications
import com.ttech.pocketlinux.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * コンテナのプロセスを裏でも動かし続けるための常駐サービス。
 * ターミナル・作成中の処理・同期・IDE のサーバーのどれかが動いている間だけ起動する。
 *
 * Android は前面サービスの無いアプリのプロセス（とその子プロセス）を、裏に回ると凍結・終了するため。
 */
class ContainerService : Service() {

    private val graph get() = AppGraph.instance
    private var watcher: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundCompat(buildNotification(0, 0, false))
        graph.urlBridge.start()
        watcher = graph.scope.launch {
            combine(
                graph.sessions.sessions,
                graph.sessions.revision,
                graph.tasks.tasks,
                graph.settings.values,
                graph.activity.state,
            ) { _, _, _, settings, activity -> settings.keepAwake to activity }
                .collect { (keepAwake, activity) ->
                    val sessions = graph.sessions.runningCount()
                    val tasks = graph.tasks.runningCount
                    if (sessions == 0 && tasks == 0 && !activity.anyRunning) {
                        stopSelf()
                        return@collect
                    }
                    updateWakeLock(keepAwake)
                    val manager = getSystemService(android.app.NotificationManager::class.java)
                    manager.notify(Notifications.ID_RUNNING, buildNotification(sessions, tasks, keepAwake, activity.summary))
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_ALL -> {
                graph.sessions.closeAll()
                graph.activity.stopAll()
                stopSelf()
            }
            ACTION_TOGGLE_WAKE_LOCK -> graph.settings.update { it.copy(keepAwake = !it.keepAwake) }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        watcher?.cancel()
        updateWakeLock(false)
        graph.urlBridge.stop()
        super.onDestroy()
    }

    private fun updateWakeLock(enabled: Boolean) {
        if (enabled && wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PocketLinux::running")
                .apply { setReferenceCounted(false); acquire() }
        } else if (!enabled) {
            wakeLock?.takeIf { it.isHeld }?.release()
            wakeLock = null
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        ServiceCompat.startForeground(
            this,
            Notifications.ID_RUNNING,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
    }

    private fun buildNotification(sessions: Int, tasks: Int, keepAwake: Boolean, extra: String = ""): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ContainerService::class.java).setAction(ACTION_STOP_ALL), PendingIntent.FLAG_IMMUTABLE,
        )
        val wake = PendingIntent.getService(
            this, 2, Intent(this, ContainerService::class.java).setAction(ACTION_TOGGLE_WAKE_LOCK), PendingIntent.FLAG_IMMUTABLE,
        )
        val parts = buildList {
            if (sessions > 0) add("ターミナル $sessions")
            if (tasks > 0) add("処理中 $tasks")
            if (extra.isNotEmpty()) add(extra)
        }
        return NotificationCompat.Builder(this, Notifications.CHANNEL_RUNNING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Pocket Linux を実行中")
            .setContentText(parts.joinToString("・").ifEmpty { "準備中" })
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, if (keepAwake) "スリープを許可" else "スリープさせない", wake)
            .addAction(0, "すべて終了", stop)
            .build()
    }

    companion object {
        private const val ACTION_STOP_ALL = "com.ttech.pocketlinux.STOP_ALL"
        private const val ACTION_TOGGLE_WAKE_LOCK = "com.ttech.pocketlinux.TOGGLE_WAKE_LOCK"

        /** セッションや処理を始めるときに呼ぶ（既に動いていれば何もしない） */
        fun ensureRunning(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ContainerService::class.java))
        }
    }
}
