package com.ttech.pocketlinux.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.concurrent.thread

/** PTY を使わずにコマンドを実行し、出力を1行ずつ受け取る（初期設定・同期など） */
object ProcessRunner {

    data class Output(val exit: Int, val stdout: String, val stderr: String)

    fun start(command: LaunchCommand, mergeStderr: Boolean = true): Process {
        val builder = ProcessBuilder(command.argv)
            .directory(File(command.cwd))
            .redirectErrorStream(mergeStderr)
        builder.environment().apply {
            clear()
            command.env.forEach { entry ->
                val parts = entry.split('=', limit = 2)
                put(parts[0], parts.getOrElse(1) { "" })
            }
        }
        return builder.start()
    }

    /** 終わるまで待って終了コードを返す。コルーチンが取り消されたらプロセスも止める */
    suspend fun run(command: LaunchCommand, onLine: (String) -> Unit): Int = coroutineScope {
        val process = start(command)
        // 読み込み待ちのまま取り消されても、プロセスを止めれば読み込みが EOF で抜ける
        val killer = launch { try { awaitCancellation() } finally { process.destroyForcibly() } }
        try {
            withContext(Dispatchers.IO) {
                process.outputStream.close()
                process.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) onLine(line)
                }
                process.waitFor()
            }.also { ensureActive() }
        } finally {
            killer.cancel()
        }
    }

    /** 標準出力と標準エラーを分けて受け取る（JSON を読むコマンド用など） */
    suspend fun capture(command: LaunchCommand): Output = coroutineScope {
        val process = start(command, mergeStderr = false)
        val killer = launch { try { awaitCancellation() } finally { process.destroyForcibly() } }
        try {
            withContext(Dispatchers.IO) {
                process.outputStream.close()
                val stderr = StringBuilder()
                val drain = thread(isDaemon = true) { stderr.append(process.errorStream.bufferedReader().readText()) }
                val stdout = process.inputStream.bufferedReader().readText()
                val exit = process.waitFor()
                drain.join()
                Output(exit, stdout, stderr.toString())
            }.also { ensureActive() }
        } finally {
            killer.cancel()
        }
    }
}

