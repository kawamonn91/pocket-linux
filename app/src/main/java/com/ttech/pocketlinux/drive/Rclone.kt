package com.ttech.pocketlinux.drive

import com.ttech.pocketlinux.engine.Paths
import com.ttech.pocketlinux.vm.AgentChannel
import com.ttech.pocketlinux.vm.Guest
import com.ttech.pocketlinux.vm.GuestListener
import com.ttech.pocketlinux.vm.GuestOutput
import com.ttech.pocketlinux.vm.OpenRequest
import com.ttech.pocketlinux.vm.QemuLauncher
import com.ttech.pocketlinux.vm.VmManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException

/**
 * VM の中の rclone（system.erofs に入っている）を動かす。設定は VM のデータ用ディスクに置く。
 * Google のログインは rclone の承認済みの仕組みを使う（同意画面には「rclone」と出る）。
 */
class Rclone(private val paths: Paths, private val vm: VmManager, private val guest: Guest) {

    /** ログインしたかの目印。設定は VM の中にあるので、これを見れば VM を起動しなくて済む */
    private val loggedInMarker = File(paths.rcloneDir, "logged-in")

    val isLoggedIn: Boolean get() = loggedInMarker.isFile || paths.legacyRcloneConfig.isFile

    private fun request(args: List<String>) = OpenRequest(
        op = "exec",
        argv = listOf(RCLONE, "--config", GUEST_CONFIG, "--cache-dir", "$GUEST_DIR/cache") + args,
        cwd = GUEST_DIR,
    )

    /** 1行ずつ受け取りながら動かす（同期など）。終了コードを返す */
    suspend fun run(args: List<String>, onLine: (String) -> Unit): Int = guest.run(request(args), onLine)

    /** 標準出力と標準エラーを分けて受け取る（JSON を読むコマンド用） */
    suspend fun capture(args: List<String>): GuestOutput = guest.capture(request(args))

    /**
     * Google でログインする。同意画面の URL を [openUrl] でブラウザに開き、同意が終わると
     * rclone が受け取ったトークンを設定ファイルに書く。トークンはログに出さない。
     *
     * ブラウザが開くのは端末の 127.0.0.1:53682 で、rclone は VM の中の 127.0.0.1 で待ち受ける。
     * ログインの間だけ、QEMU のポート転送とエージェントの proxy でつなぐ。
     */
    suspend fun login(openUrl: (String) -> Unit, log: (String) -> Unit) {
        val channel = vm.channel()
        runCatching { vm.qmp.removeForward(LOCALHOST, AUTH_PORT) }
        vm.qmp.addForward(LOCALHOST, AUTH_PORT)
        val proxy: AgentChannel.GuestSession = channel.open(
            OpenRequest(op = "proxy", listen = "${QemuLauncher.GUEST_ADDRESS}:$AUTH_PORT", target = "$LOCALHOST:$AUTH_PORT"),
            object : GuestListener {},
        )
        try {
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
            importConfig("[$REMOTE]\ntype = drive\nscope = drive\ntoken = $token\n")
        } finally {
            runCatching { proxy.close() }
            runCatching { vm.qmp.removeForward(LOCALHOST, AUTH_PORT) }
        }
    }

    /** 設定を VM に書いて、ログインした状態にする */
    suspend fun importConfig(text: String) {
        guest.writeFile(null, GUEST_CONFIG, text, mode = "600".toInt(8))
        paths.rcloneDir.mkdirs()
        loggedInMarker.writeText("")
    }

    suspend fun logout() {
        loggedInMarker.delete()
        paths.legacyRcloneConfig.delete()
        guest.remove(null, GUEST_CONFIG)
    }

    /** Drive のフォルダの中のフォルダ名（[path] は Drive の中のパス。"" はマイドライブの直下） */
    suspend fun listFolders(path: String): List<String> {
        val result = capture(listOf("lsjson", remote(path), "--dirs-only", "--no-modtime", "--no-mimetype"))
        if (result.exit != 0) {
            throw IOException(result.failureReason())
        }
        return parseFolderNames(result.stdout)
    }

    companion object {
        const val REMOTE = "gdrive"
        private const val RCLONE = "/usr/bin/rclone"

        /** 同期フォルダの VM の中の場所（コンテナの /mnt/drive） */
        const val GUEST_DRIVE_DIR = "/data/drive"
        /** 設定と bisync の作業フォルダの、VM の中の場所 */
        const val GUEST_DIR = "/data/system/rclone"
        private const val GUEST_CONFIG = "$GUEST_DIR/rclone.conf"
        const val GUEST_EXCLUDES = "$GUEST_DIR/excludes.txt"

        private const val LOCALHOST = "127.0.0.1"
        private const val AUTH_PORT = 53682
        private val AUTH_URL = Regex("""http://127\.0\.0\.1:53682/auth\?state=[A-Za-z0-9_\-]+""")
        private const val TOKEN_BEGIN = "--->"
        private const val TOKEN_END = "<---End paste"
        private val json = Json { ignoreUnknownKeys = true }

        fun remote(path: String): String = "$REMOTE:" + path.trim('/')

        fun guestLocalDir(localName: String): String = "$GUEST_DRIVE_DIR/$localName"

        fun guestWorkDir(id: String): String = "$GUEST_DIR/bisync/$id"

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
         * Drive のフォルダと VM の中のフォルダを双方向に同期する `rclone bisync` の引数。
         * 両方で変わったファイルは新しいほうを残し、古いほうは番号を付けて残す。
         * リンクは中身をたどって送る。
         */
        fun bisyncArgs(remotePath: String, localDir: String, workDir: String, excludeFile: String, resync: Boolean): List<String> =
            buildList {
                add("bisync"); add(remote(remotePath)); add(localDir)
                add("--workdir"); add(workDir)
                add("--exclude-from"); add(excludeFile)
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
