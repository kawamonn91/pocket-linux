package com.ttech.pocketlinux

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.ttech.pocketlinux.engine.ContainerConfig
import com.ttech.pocketlinux.engine.ContainerManager
import com.ttech.pocketlinux.service.ContainerService
import com.ttech.pocketlinux.terminal.TerminalController
import com.ttech.pocketlinux.ui.HomeActions
import com.ttech.pocketlinux.ui.HomeScreen
import com.ttech.pocketlinux.ui.Notice
import com.ttech.pocketlinux.ui.PocketLinuxTheme
import com.ttech.pocketlinux.ui.TerminalScreen
import com.ttech.pocketlinux.ui.drive.DriveScreen
import com.ttech.pocketlinux.ui.settings.SettingsScreen
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val graph get() = AppGraph.instance

    private sealed interface Screen {
        data object Home : Screen
        data class Terminal(val sessionId: Int?) : Screen
        data object Drive : Screen
        data object Settings : Screen
    }

    private var screen by mutableStateOf<Screen>(Screen.Home)
    private var noticeVersion by mutableStateOf(0)
    private var pendingBackup: ContainerConfig? = null

    private lateinit var controller: TerminalController

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { noticeVersion++ }

    private val backupTarget =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/gzip")) { uri ->
            val container = pendingBackup ?: return@registerForActivityResult
            pendingBackup = null
            if (uri != null) startBackup(container, uri)
        }

    private val restoreSource =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) startRestore(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        controller = TerminalController(this, terminalCallbacks)
        graph.sessions.listener = controller

        if (intent?.getBooleanExtra(EXTRA_OPEN_TERMINAL, false) == true && graph.sessions.sessions.value.isNotEmpty()) {
            screen = Screen.Terminal(null)
        }

        setContent {
            PocketLinuxTheme { Content() }
        }
    }

    override fun onResume() {
        super.onResume()
        graph.isForeground = true
        noticeVersion++
    }

    override fun onPause() {
        graph.isForeground = false
        super.onPause()
    }

    override fun onDestroy() {
        if (graph.sessions.listener === controller) graph.sessions.listener = null
        super.onDestroy()
    }

    @Composable
    private fun Content() {
        val containers by graph.store.all.collectAsState()
        val sessions by graph.sessions.sessions.collectAsState()
        val revision by graph.sessions.revision.collectAsState()
        val tasks by graph.tasks.tasks.collectAsState()
        val settings by graph.settings.values.collectAsState()
        val notices = remember(noticeVersion) { buildNotices() }

        when (val current = screen) {
            Screen.Home -> HomeScreen(
                containers = containers,
                tasks = tasks,
                sessionCounts = sessions.filter { !it.finished }.groupingBy { it.containerId }.eachCount(),
                notices = notices,
                actions = homeActions,
            )
            is Screen.Terminal -> TerminalScreen(
                sessions = sessions,
                revision = revision,
                currentId = current.sessionId,
                fontSizeSp = settings.fontSizeSp,
                controller = controller,
                onSelect = { screen = Screen.Terminal(it) },
                onClose = { graph.sessions.close(it) },
                onNewTab = { asRoot -> terminalCallbacks.newTab(asRoot) },
                onPaste = { terminalCallbacks.pasteFromClipboard() },
                onBack = { screen = Screen.Home },
            )
            Screen.Drive -> DriveScreen(onBack = { screen = Screen.Home })
            Screen.Settings -> SettingsScreen(onBack = { screen = Screen.Home })
        }
    }

    // ------------------------------------------------------------ トップ画面の操作

    private val homeActions = object : HomeActions {
        override fun createContainer(image: String, name: String, installTools: Boolean) {
            ContainerService.ensureRunning(this@MainActivity)
            graph.tasks.start(graph.scope, "コンテナを作成: $image") {
                val config = graph.containers.create(image, name, installTools, { label, fraction -> progress(label, fraction) }, { log(it) })
                "「${config.name}」を作成しました"
            }
        }

        override fun openShell(containerId: String, asRoot: Boolean) {
            val config = graph.store.load(containerId) ?: return
            if (!config.setupDone) {
                toast("初期設定が終わっていません。メニューの「初期設定をやり直す」を実行してください")
                return
            }
            ContainerService.ensureRunning(this@MainActivity)
            val session = graph.sessions.openShell(containerId, asRoot) ?: return
            screen = Screen.Terminal(session.id)
        }

        override fun rerunSetup(containerId: String) {
            val config = graph.store.load(containerId) ?: return
            ContainerService.ensureRunning(this@MainActivity)
            graph.tasks.start(graph.scope, "初期設定: ${config.name}") {
                val updated = graph.containers.runSetup(config, installTools = false, { label, fraction -> progress(label, fraction) }, { log(it) })
                if (updated.sudoInstalled) "sudo を使えるようになりました" else "sudo を入れられませんでした（ログを確認してください）"
            }
        }

        override fun rename(containerId: String, name: String) {
            graph.store.update(containerId) { it.copy(name = name) }
        }

        override fun saveAutostart(containerId: String, command: String, enabled: Boolean) {
            graph.store.update(containerId) { it.copy(autostartCommand = command, autostartEnabled = enabled && command.isNotBlank()) }
        }

        override fun backup(container: ContainerConfig) {
            pendingBackup = container
            val date = SimpleDateFormat("yyyyMMdd", Locale.JAPAN).format(Date())
            val safeName = container.name.replace(Regex("[^\\p{L}\\p{N}._-]+"), "_")
            backupTarget.launch("$safeName-$date.tar.gz")
        }

        override fun restore() {
            restoreSource.launch(arrayOf("application/gzip", "application/x-gzip", "application/x-tar", "application/octet-stream"))
        }

        override fun delete(containerId: String) {
            graph.sessions.closeContainer(containerId)
            graph.tasks.start(graph.scope, "コンテナを削除") {
                progress("削除しています")
                graph.containers.delete(containerId)
                "削除しました"
            }
        }

        override fun cancelTask(id: Int) = graph.tasks.cancel(id)
        override fun dismissTask(id: Int) = graph.tasks.dismiss(id)
        override fun openTerminals() { screen = Screen.Terminal(null) }
        override fun openDrive() { screen = Screen.Drive }
        override fun openSettings() { screen = Screen.Settings }
    }

    private fun startBackup(container: ContainerConfig, uri: Uri) {
        ContainerService.ensureRunning(this)
        graph.tasks.start(graph.scope, "バックアップ: ${container.name}") {
            val output = contentResolver.openOutputStream(uri) ?: error("保存先を開けません")
            output.use { stream ->
                graph.backups.export(container.id, stream) { count -> progress("書き出し中（$count ファイル）") }
            }
            "書き出しました"
        }
    }

    private fun startRestore(uri: Uri) {
        ContainerService.ensureRunning(this)
        val fallbackName = uri.lastPathSegment?.substringAfterLast('/')?.removeSuffix(".tar.gz") ?: "復元したコンテナ"
        graph.tasks.start(graph.scope, "バックアップから復元") {
            val input = contentResolver.openInputStream(uri) ?: error("ファイルを開けません")
            val config = input.use { stream ->
                graph.backups.import(stream, fallbackName) { read -> progress("読み込み中（${ContainerManager.formatBytes(read)}）") }
            }
            if (!config.setupDone) {
                graph.containers.runSetup(config, installTools = false, { label, fraction -> progress(label, fraction) }, { log(it) })
            }
            "「${config.name}」として復元しました"
        }
    }

    // ------------------------------------------------------------ ターミナルの操作

    private val terminalCallbacks = object : TerminalController.Callbacks {
        private fun currentContainerId(): String? {
            val id = (screen as? Screen.Terminal)?.sessionId
            val sessions = graph.sessions.sessions.value
            return (sessions.firstOrNull { it.id == id } ?: sessions.lastOrNull())?.containerId
                ?: graph.store.all.value.firstOrNull()?.id
        }

        private fun currentIndex(): Int {
            val sessions = graph.sessions.sessions.value
            val id = (screen as? Screen.Terminal)?.sessionId
            return sessions.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: (sessions.size - 1)
        }

        override fun newTab(asRoot: Boolean) {
            val containerId = currentContainerId() ?: return
            homeActions.openShell(containerId, asRoot)
        }

        override fun closeCurrentTab() {
            val sessions = graph.sessions.sessions.value
            val current = sessions.getOrNull(currentIndex()) ?: return
            graph.sessions.close(current.id)
            val rest = graph.sessions.sessions.value
            screen = if (rest.isEmpty()) Screen.Home else Screen.Terminal(rest.getOrNull(currentIndex().coerceAtMost(rest.size - 1))?.id)
        }

        override fun switchTab(offset: Int) {
            val sessions = graph.sessions.sessions.value
            if (sessions.isEmpty()) return
            val index = Math.floorMod(currentIndex() + offset, sessions.size)
            screen = Screen.Terminal(sessions[index].id)
        }

        override fun selectTab(index: Int) {
            graph.sessions.sessions.value.getOrNull(index)?.let { screen = Screen.Terminal(it.id) }
        }

        override fun changeFontSize(delta: Int) = graph.settings.update {
            it.copy(fontSizeSp = (it.fontSizeSp + delta).coerceIn(AppSettings.MIN_FONT, AppSettings.MAX_FONT))
        }

        override fun resetFontSize() = graph.settings.update { it.copy(fontSizeSp = 14) }

        override fun pasteFromClipboard() {
            controller.currentSession?.let { graph.sessions.onPasteTextFromClipboard(it) }
        }

        override fun vibrateOnBell() = graph.settings.current.vibrateOnBell
        override fun enforceCharInput() = graph.settings.current.enforceCharInput
    }

    // ------------------------------------------------------------ お知らせ

    private fun buildNotices(): List<Notice> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            add(Notice(
                id = "notification",
                title = "通知を許可してください",
                body = "裏でコンテナを動かし続けるために、実行中の通知を出します。ブラウザで開く依頼（Claude Code のログインなど）も通知で届きます。",
                actionLabel = "許可する",
                onAction = { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) },
            ))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !ChildProcessGuide.isDismissed(this@MainActivity)) {
            add(Notice(
                id = "phantom",
                title = "子プロセスの制限を外してください（Android 12 以降）",
                body = ChildProcessGuide.message(),
                actionLabel = if (Build.VERSION.SDK_INT >= 34) "開発者向けオプションを開く" else "表示しない",
                onAction = {
                    if (Build.VERSION.SDK_INT >= 34) {
                        runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
                    }
                    ChildProcessGuide.dismiss(this@MainActivity)
                    noticeVersion++
                },
            ))
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    companion object {
        const val EXTRA_OPEN_TERMINAL = "open_terminal"
    }
}
