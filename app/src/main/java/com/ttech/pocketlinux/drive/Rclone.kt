package com.ttech.pocketlinux.drive

import com.ttech.pocketlinux.engine.LaunchCommand
import com.ttech.pocketlinux.engine.Paths
import com.ttech.pocketlinux.engine.ProcessRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException
import kotlin.concurrent.thread

/**
 * 同梱の rclone（scripts/build-rclone.sh で作る Linux 版）を、アプリのプロセスから直接動かす（コンテナの外）。
 *
 * Android 上でそのまま動かすため、名前解決は [ConnectProxy]（HTTPS_PROXY）、証明書は Android の
 * 証明書フォルダ（SSL_CERT_DIR）で解決する。Google のログインは rclone の承認済みの仕組みを使う
 * （同意画面には「rclone」と出る）。
 */
class Rclone(private val paths: Paths, private val proxy: ConnectProxy) {

    val available: Boolean get() = paths.rclone.isFile

    val configFile: File get() = File(paths.rcloneDir, "rclone.conf")

    val isLoggedIn: Boolean
        get() = configFile.isFile && configFile.readLines().any { it.trim() == "[$REMOTE]" }

    fun command(args: List<String>): LaunchCommand {
        val tmp = File(paths.cacheDir, "rclone-tmp").apply { mkdirs() }
        return LaunchCommand(
            executable = paths.rclone.path,
            argv = listOf(paths.rclone.path) + args,
            env = listOf(
                "HOME=${paths.rcloneDir.path}",
                "PATH=/system/bin",
                "TMPDIR=${tmp.path}",
                "RCLONE_CONFIG=${configFile.path}",
                "RCLONE_CACHE_DIR=${File(paths.rcloneDir, "cache").path}",
                "HTTPS_PROXY=${proxy.url}",
                "NO_PROXY=127.0.0.1,localhost",
                "SSL_CERT_DIR=$CERT_DIRS",
            ),
            cwd = paths.rcloneDir.path,
        )
    }

    /** 1行ずつ受け取りながら動かす（同期など）。終了コードを返す */
    suspend fun run(args: List<String>, onLine: (String) -> Unit): Int = ProcessRunner.run(command(args), onLine)

    /** 標準出力と標準エラーを分けて受け取る（JSON を読むコマンド用） */
    suspend fun capture(args: List<String>): Output = coroutineScope {
        val process = ProcessRunner.start(command(args), mergeStderr = false)
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
            }
        } finally {
            killer.cancel()
        }
    }

    data class Output(val exit: Int, val stdout: String, val stderr: String)

    /**
     * Google でログインする。同意画面の URL を [openUrl] でブラウザに開き、同意が終わると
     * rclone が受け取ったトークンを設定ファイルに書く。トークンはログに出さない。
     */
    suspend fun login(openUrl: (String) -> Unit, log: (String) -> Unit) {
        val output = StringBuilder()
        var opened = false
        var inToken = false
        val exit = run(listOf("authorize", "drive", "--auth-no-open-browser")) { line ->
            if (!opened) AUTH_URL.find(line)?.let { opened = true; openUrl(it.value) }
            output.appendLine(line)
            when {
                line.contains(TOKEN_BEGIN) -> inToken = true
                line.contains(TOKEN_END) -> inToken = false
                !inToken -> log(line)
            }
        }
        val token = parseToken(output.toString())
            ?: throw IOException("ログインできませんでした（終了コード $exit）")
        writeConfig(token)
    }

    fun logout() {
        configFile.delete()
    }

    /** Drive のフォルダの中のフォルダ名（[path] は Drive の中のパス。"" はマイドライブの直下） */
    suspend fun listFolders(path: String): List<String> {
        val result = capture(listOf("lsjson", remote(path), "--dirs-only", "--no-modtime", "--no-mimetype"))
        if (result.exit != 0) {
            throw IOException(result.stderr.lineSequence().lastOrNull { it.isNotBlank() } ?: "フォルダの一覧を取得できません")
        }
        return parseFolderNames(result.stdout)
    }

    private fun writeConfig(token: String) {
        paths.rcloneDir.mkdirs()
        val tmp = File(configFile.path + ".tmp")
        tmp.writeText("[$REMOTE]\ntype = drive\nscope = drive\ntoken = $token\n")
        if (!tmp.renameTo(configFile)) {
            configFile.delete()
            tmp.renameTo(configFile)
        }
    }

    companion object {
        const val REMOTE = "gdrive"

        /** Android 14 からは証明書が Conscrypt の APEX にある。古い場所も並べる（Go は「:」区切りで全部読む） */
        const val CERT_DIRS = "/apex/com.android.conscrypt/cacerts:/system/etc/security/cacerts"

        private val AUTH_URL = Regex("""http://127\.0\.0\.1:53682/auth\?state=[A-Za-z0-9_\-]+""")
        private const val TOKEN_BEGIN = "--->"
        private const val TOKEN_END = "<---End paste"
        private val json = Json { ignoreUnknownKeys = true }

        fun remote(path: String): String = "$REMOTE:" + path.trim('/')

        /** `rclone authorize` の出力から、貼り付け用のトークン（JSON）を取り出す */
        fun parseToken(output: String): String? {
            val body = output.substringAfter(TOKEN_BEGIN, "").substringBefore(TOKEN_END, "")
            val token = body.trim().takeIf { it.startsWith("{") && it.endsWith("}") } ?: return null
            return token.takeIf { runCatching { json.parseToJsonElement(it).jsonObject["access_token"] }.getOrNull() != null }
        }

        /** `rclone lsjson --dirs-only` の出力からフォルダ名を取り出す（名前順） */
        fun parseFolderNames(stdout: String): List<String> =
            json.parseToJsonElement(stdout).jsonArray
                .map { it.jsonObject }
                .filter { it["IsDir"]?.jsonPrimitive?.booleanOrNull != false }
                .mapNotNull { it["Name"]?.jsonPrimitive?.contentOrNull }
                .sortedWith(String.CASE_INSENSITIVE_ORDER)

        /**
         * Drive のフォルダと端末のフォルダを双方向に同期する `rclone bisync` の引数。
         * 両方で変わったファイルは新しいほうを残し、古いほうは番号を付けて残す。
         * リンクは中身をたどって送る（コンテナ内でハードリンクを作るとリンクになるため）。
         */
        fun bisyncArgs(remotePath: String, localDir: File, workDir: File, excludeFile: File, resync: Boolean): List<String> =
            buildList {
                add("bisync"); add(remote(remotePath)); add(localDir.path)
                add("--workdir"); add(workDir.path)
                add("--exclude-from"); add(excludeFile.path)
                add("--resilient"); add("--recover")
                add("--conflict-resolve"); add("newer")
                add("--conflict-loser"); add("num")
                add("--create-empty-src-dirs")
                add("--copy-links")
                add("--drive-skip-gdocs")
                add("--max-lock"); add("2m")
                add("-v")
                if (resync) { add("--resync"); add("--resync-mode"); add("newer") }
            }
    }
}
