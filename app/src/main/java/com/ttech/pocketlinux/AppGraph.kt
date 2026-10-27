package com.ttech.pocketlinux

import android.app.Application
import com.ttech.pocketlinux.bridge.UrlBridge
import com.ttech.pocketlinux.engine.BackupManager
import com.ttech.pocketlinux.engine.ContainerManager
import com.ttech.pocketlinux.engine.ContainerStore
import com.ttech.pocketlinux.engine.HostEnvironment
import com.ttech.pocketlinux.engine.Paths
import com.ttech.pocketlinux.engine.ProotLauncher
import com.ttech.pocketlinux.engine.RegistryClient
import com.ttech.pocketlinux.terminal.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.Inet4Address
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
        .dns { hostname ->
            runCatching {
                Dns.SYSTEM.lookup(hostname).sortedBy { if (it is Inet4Address) 0 else 1 }
            }.getOrElse { Dns.SYSTEM.lookup(hostname) }
        }
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    val store = ContainerStore(paths)
    val launcher = ProotLauncher(paths)
    val containers = ContainerManager(paths, store, RegistryClient(http), launcher, host)
    val backups = BackupManager(paths, store)
    val sessions = SessionManager(app, store, launcher, containers)
    val tasks = TaskTracker()
    val activity = BackgroundActivity()

    /** 画面が前面にあるか（裏にあるときはブラウザを直接開けない） */
    @Volatile
    var isForeground = false

    val urlBridge = UrlBridge(app, paths.openUrlDir) { isForeground }

    companion object {
        lateinit var instance: AppGraph
            private set

        fun init(app: Application) {
            if (!::instance.isInitialized) instance = AppGraph(app)
        }
    }
}
