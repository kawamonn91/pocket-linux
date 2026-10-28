package com.ttech.pocketlinux.terminal

import android.content.Context
import android.util.Log
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.ttech.pocketlinux.engine.ContainerConfig
import com.ttech.pocketlinux.engine.ContainerManager
import com.ttech.pocketlinux.engine.ContainerStore
import com.ttech.pocketlinux.engine.LaunchCommand
import com.ttech.pocketlinux.engine.ProotLauncher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 1つのタブ（コンテナの中のシェル1つ） */
class TermSession(
    val id: Int,
    val containerId: String,
    val containerName: String,
    val asRoot: Boolean,
    val kind: Kind,
    val terminal: TerminalSession,
) {
    enum class Kind { SHELL, AUTOSTART }

    @Volatile
    var finished: Boolean = false
        internal set

    val title: String
        get() {
            val base = when (kind) {
                Kind.AUTOSTART -> "自動実行"
                Kind.SHELL -> if (asRoot) "$containerName (root)" else containerName
            }
            val running = terminal.title?.takeIf { it.isNotBlank() }
            return when {
                finished -> "$base（終了）"
                running != null && kind == Kind.SHELL -> running
                else -> base
            }
        }
}

/**
 * 開いているセッションを持つ。画面（Activity）が作り直されても、常駐サービスがある間は残る。
 */
class SessionManager(
    private val context: Context,
    private val store: ContainerStore,
    private val launcher: ProotLauncher,
    private val manager: ContainerManager,
) : TerminalSessionClient {

    /** 画面側への通知（表示中のセッションの描画更新など） */
    interface Listener {
        fun onTextChanged(session: TerminalSession)
        fun onSessionFinished(session: TerminalSession)
        fun onBell(session: TerminalSession)
    }

    private val _sessions = MutableStateFlow<List<TermSession>>(emptyList())
    val sessions: StateFlow<List<TermSession>> = _sessions.asStateFlow()

    /** タイトルの変化など、一覧の表示を更新したいときに増える */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    var listener: Listener? = null
    private var nextId = 1

    fun openShell(containerId: String, asRoot: Boolean = false): TermSession? {
        val config = store.load(containerId) ?: return null
        val isFirst = _sessions.value.none { it.containerId == containerId && !it.finished }
        manager.beforeLaunch(config)
        val session = create(config, asRoot, TermSession.Kind.SHELL, launcher.login(config, asRoot, manager.options()))
        if (isFirst) startAutostart(config)
        return session
    }

    /** コンテナの「起動時の自動実行」を、裏のタブで動かす */
    private fun startAutostart(config: ContainerConfig) {
        if (!config.autostartEnabled || config.autostartCommand.isBlank()) return
        if (_sessions.value.any { it.containerId == config.id && it.kind == TermSession.Kind.AUTOSTART && !it.finished }) return
        val command = launcher.exec(config, config.autostartCommand, asRoot = false, manager.options())
        val session = create(config, false, TermSession.Kind.AUTOSTART, command)
        // 画面に出していなくてもプロセスを始める（大きさは表示したときに合わせ直される）
        session.terminal.updateSize(100, 30, 12, 24)
    }

    private fun create(config: ContainerConfig, asRoot: Boolean, kind: TermSession.Kind, command: LaunchCommand): TermSession {
        val terminal = TerminalSession(
            command.executable,
            command.cwd,
            command.argv.toTypedArray(),
            command.env.toTypedArray(),
            TRANSCRIPT_ROWS,
            this,
        )
        val session = TermSession(nextId++, config.id, config.name, asRoot, kind, terminal)
        _sessions.update { it + session }
        return session
    }

    fun find(id: Int): TermSession? = _sessions.value.firstOrNull { it.id == id }

    fun close(id: Int) {
        val session = find(id) ?: return
        session.terminal.finishIfRunning()
        _sessions.update { list -> list.filterNot { it.id == id } }
    }

    fun closeContainer(containerId: String) {
        _sessions.value.filter { it.containerId == containerId }.forEach { close(it.id) }
    }

    fun closeAll() {
        _sessions.value.forEach { it.terminal.finishIfRunning() }
        _sessions.value = emptyList()
    }

    fun runningCount(): Int = _sessions.value.count { !it.finished }

    private fun bySession(session: TerminalSession) = _sessions.value.firstOrNull { it.terminal === session }

    // ---------------------------------------------------- TerminalSessionClient

    override fun onTextChanged(changedSession: TerminalSession) {
        listener?.onTextChanged(changedSession)
    }

    override fun onTitleChanged(changedSession: TerminalSession) {
        _revision.update { it + 1 }
    }

    override fun onSessionFinished(finishedSession: TerminalSession) {
        bySession(finishedSession)?.finished = true
        _revision.update { it + 1 }
        listener?.onSessionFinished(finishedSession)
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
        if (text.isNullOrEmpty()) return
        Clipboard.copy(context, text)
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val text = Clipboard.read(context) ?: return
        session?.emulator?.paste(text)
    }

    override fun onBell(session: TerminalSession) {
        listener?.onBell(session)
    }

    override fun onColorsChanged(session: TerminalSession) {
        listener?.onTextChanged(session)
    }

    override fun onTerminalCursorStateChange(state: Boolean) = Unit

    override fun getTerminalCursorStyle(): Int? = null

    override fun logError(tag: String?, message: String?) { Log.e(tag ?: TAG, message.orEmpty()) }
    override fun logWarn(tag: String?, message: String?) { Log.w(tag ?: TAG, message.orEmpty()) }
    override fun logInfo(tag: String?, message: String?) { Log.i(tag ?: TAG, message.orEmpty()) }
    override fun logDebug(tag: String?, message: String?) { Log.d(tag ?: TAG, message.orEmpty()) }
    override fun logVerbose(tag: String?, message: String?) { Log.v(tag ?: TAG, message.orEmpty()) }
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) { Log.e(tag ?: TAG, message.orEmpty(), e) }
    override fun logStackTrace(tag: String?, e: Exception?) { Log.e(tag ?: TAG, "", e) }

    companion object {
        private const val TAG = "PocketLinux"
        private const val TRANSCRIPT_ROWS = 5000
    }
}
