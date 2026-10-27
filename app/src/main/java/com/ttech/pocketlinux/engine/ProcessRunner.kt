package com.ttech.pocketlinux.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** PTY を使わずにコマンドを実行し、出力を1行ずつ受け取る（初期設定・同期など） */
object ProcessRunner {

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
}
