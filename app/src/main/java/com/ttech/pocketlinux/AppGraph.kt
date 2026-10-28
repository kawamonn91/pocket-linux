package com.ttech.pocketlinux

import android.app.Application
import com.ttech.pocketlinux.bridge.UrlBridge
import com.ttech.pocketlinux.drive.ConnectProxy
import com.ttech.pocketlinux.drive.DriveSync
import com.ttech.pocketlinux.drive.Rclone
import com.ttech.pocketlinux.engine.BackupManager
import com.ttech.pocketlinux.engine.ContainerManager
import com.ttech.pocketlinux.engine.ContainerStore
import com.ttech.pocketlinux.engine.HostEnvironment
import com.ttech.pocketlinux.engine.Paths
import com.ttech.pocketlinux.engine.ProotLauncher
import com.ttech.pocketlinux.engine.RegistryClient
import com.ttech.pocketlinux.ide.IdeServer
import com.ttech.pocketlinux.service.ContainerService
import com.ttech.pocketlinux.terminal.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * アプリ全体で1つだけ持つ部品。画面と常駐サービスの両方から使う。
 */
class AppGraph(val app: Application) {
    /** 画面に縛られない処理（作成・同期など）を動かす */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val paths = Paths(app).also { it.ensureBaseDirs() }
    val settings = AppSettings(app)
    val host = HostEnvironment(app)
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    val store = ContainerStore(paths)
    val launcher = ProotLauncher(paths)
    val containers = ContainerManager(paths, store, RegistryClient(http), launcher, host)
    val backups = BackupManager(paths, store)
    val sessions = SessionManager(app, store, launcher, containers)
    val tasks = TaskTracker()

    /** Google ドライブとの同期に使う rclone（名前解決は端末内のプロキシ経由） */
    val rclone = Rclone(paths, ConnectProxy())
    val activity = BackgroundActivity()

    /** 画面が前面にあるか（裏にあるときはブラウザを直接開けない） */
    @Volatile
    var isForeground = false

    val urlBridge = UrlBridge(app, paths.openUrlDir) { isForeground }

    /** Google ドライブとの双方向の自動同期（PocketLinuxApp で start する） */
    val drive = DriveSync(paths, rclone, scope, activity, host::isOnline) { ContainerService.ensureRunning(app) }

    /** IDE の窓口（pocket-dev のサーバー）。設定に合わせて applyIdeSettings で動かす・止める */
    val ide = IdeServer(paths, app.assets, launcher, containers, scope, activity) { ContainerService.ensureRunning(app) }
    private var appliedIde: List<Any?>? = null

    /** IDE の設定を反映する。動かす設定で、使えるコンテナがあれば起動し、設定が変わっていれば起動し直す */
    @Synchronized
    fun applyIdeSettings() {
        val values = settings.current
        val container = store.all.value.filter { it.setupDone }.let { list ->
            list.firstOrNull { it.id == values.ideContainerId } ?: list.firstOrNull()
        }
        if (!values.ideEnabled || container == null) {
            appliedIde = null
            ide.stop()
            return
        }
        val wanted = listOf(container.id, values.idePort, values.ideToken, values.ideAllowLan)
        if (wanted == appliedIde && ide.state.value.running) return
        appliedIde = wanted
        ide.start(container, values.idePort, values.ideToken, values.ideAllowLan)
    }

    companion object {
        lateinit var instance: AppGraph
            private set

        fun init(app: Application) {
            if (!::instance.isInitialized) instance = AppGraph(app)
        }
    }
}
