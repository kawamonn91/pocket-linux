package com.ttech.pocketlinux.vm

import com.termux.terminal.TerminalBackend
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * ターミナルのタブを、VM の中のプロセス（PTY）につなぐ。
 * アプリ側に子プロセスを作らないので、タブが増えても phantom process killer の対象が増えない。
 */
class VmTerminalBackend(
    private val vm: VmManager,
    private val scope: CoroutineScope,
    private val request: OpenRequest,
) : TerminalBackend {

    @Volatile
    private var session: AgentChannel.GuestSession? = null

    @Volatile
    private var terminal: TerminalSession? = null

    @Volatile
    private var stopped = false

    private var pendingSize: Pair<Int, Int>? = null

    override fun start(columns: Int, rows: Int, terminal: TerminalSession) {
        this.terminal = terminal
        scope.launch {
            try {
                if (!vm.isRunning) terminal.message("Linux を起動しています…\r\n")
                val channel = vm.channel()
                val listener = object : GuestListener {
                    override fun onStdout(data: ByteArray, length: Int) = terminal.onBackendOutput(data, length)

                    override fun onExit(exit: GuestExit) {
                        exit.error?.let { terminal.message("\r\n$it\r\n") }
                        terminal.onBackendExit(if (exit.signal > 0) -exit.signal else exit.code)
                    }
                }
                synchronized(this@VmTerminalBackend) {
                    if (stopped) {
                        terminal.onBackendExit(0)
                        return@launch
                    }
                    val size = pendingSize ?: (columns to rows)
                    session = channel.open(request.copy(pty = true, cols = size.first, rows = size.second), listener)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                terminal.message("\r\n${e.message ?: e.javaClass.simpleName}\r\n".replace("\n", "\r\n").replace("\r\r", "\r"))
                terminal.onBackendExit(1)
            }
        }
    }

    override fun write(data: ByteArray, offset: Int, count: Int) {
        try {
            session?.writeUrgent(data, offset, count)
        } catch (_: IOException) {
            // VM が止まった。タブには onExit で知らせる
        }
    }

    override fun resize(columns: Int, rows: Int) {
        synchronized(this) {
            val current = session
            if (current == null) {
                pendingSize = columns to rows
                return
            }
            runCatching { current.resize(columns, rows) }
        }
    }

    override fun kill() {
        synchronized(this) {
            stopped = true
            val current = session ?: return
            runCatching { current.signal(SIGHUP) }
            // シェルが SIGHUP を無視していても、チャンネルを閉じれば止まる
            runCatching { current.close() }
            // 閉じると終了の知らせは届かないので、ここでタブを終わりにする（書き込み用のスレッドも止まる）
            terminal?.onBackendExit(128 + SIGHUP)
        }
    }

    private fun TerminalSession.message(text: String) {
        val bytes = text.toByteArray()
        onBackendOutput(bytes, bytes.size)
    }

    private companion object {
        const val SIGHUP = 1
    }
}
