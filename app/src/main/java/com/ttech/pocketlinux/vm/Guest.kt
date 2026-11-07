package com.ttech.pocketlinux.vm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.coroutines.resume

/** ゲストのコマンドの結果 */
data class GuestOutput(val exit: Int, val stdout: String, val stderr: String, val error: String? = null) {
    /** 失敗した理由（error → 標準エラーの最後の行 → 終了コード） */
    fun failureReason(): String = Guest.failureDetail(error, stderr) ?: "終了コード $exit"
}

/**
 * VM の中で動かす処理をコルーチンから使う入口。呼ぶと VM が動いていなければ起動する。
 * コルーチンを取り消すと、ゲストのプロセスも止まる。
 */
class Guest(private val vm: VmManager) {

    /** VM が動いていなければ起動して、準備ができるまで待つ */
    suspend fun ensureRunning() {
        vm.channel()
    }

    /** コマンドを動かし、出力（標準出力と標準エラー）を 1 行ずつ受け取る。終了コードを返す */
    suspend fun run(request: OpenRequest, onLine: (String) -> Unit): Int {
        val out = LineSplitter(onLine)
        val err = LineSplitter(onLine)
        val exit = call(request, stdout = out, stderr = err)
        out.finish()
        err.finish()
        exit.error?.let(onLine)
        return exit.code
    }

    /** コマンドを動かし、標準出力と標準エラーを分けて受け取る */
    suspend fun capture(request: OpenRequest, stdin: InputStream? = null): GuestOutput {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val exit = call(request, stdin = stdin, stdout = out, stderr = err)
        return GuestOutput(exit.code, out.toString("UTF-8"), err.toString("UTF-8"), exit.error)
    }

    /**
     * チャンネルを開いて終わるまで待つ。[stdin] があれば流し込んでから閉じる。無ければすぐ閉じる
     * （標準入力を待つコマンドが止まらないように）。[stdout] に書けなくなったら、ゲストのプロセスを止めて例外にする
     * （書き出しに失敗したバックアップを、成功したことにしないため）。
     */
    suspend fun call(
        request: OpenRequest,
        stdin: InputStream? = null,
        stdout: OutputStream? = null,
        stderr: OutputStream? = null,
    ): GuestExit {
        val channel = vm.channel()
        val writeFailure = AtomicReference<IOException>()
        val exit = withContext(Dispatchers.IO) {
            ensureActive()
            suspendCancellableCoroutine { continuation ->
                lateinit var session: AgentChannel.GuestSession
                // 終わりの知らせは読み取りのスレッドと標準入力のスレッドの両方から来ることがある
                val resumed = AtomicBoolean(false)
                fun finish(exit: GuestExit) {
                    if (resumed.compareAndSet(false, true)) continuation.resume(exit)
                }
                val listener = object : GuestListener {
                    private fun deliver(target: OutputStream?, data: ByteArray, length: Int) {
                        if (target == null || writeFailure.get() != null) return
                        try {
                            target.write(data, 0, length)
                        } catch (e: IOException) {
                            writeFailure.set(e)
                            session.close()
                            finish(GuestExit(1, error = e.message))
                        }
                    }

                    override fun onStdout(data: ByteArray, length: Int) = deliver(stdout, data, length)

                    override fun onStderr(data: ByteArray, length: Int) = deliver(stderr, data, length)

                    override fun onExit(exit: GuestExit) = finish(exit)
                }
                session = channel.open(request, listener)
                continuation.invokeOnCancellation { session.close() }
                thread(name = "GuestStdin", isDaemon = true) {
                    try {
                        if (stdin != null) {
                            val buffer = ByteArray(AgentChannel.MAX_PAYLOAD)
                            while (continuation.isActive) {
                                val n = stdin.read(buffer)
                                if (n < 0) break
                                if (n > 0) session.write(buffer, 0, n)
                            }
                        }
                        session.closeStdin()
                    } catch (_: IOException) {
                        // 読めない・接続が切れた: ゲストの標準入力は閉じられないので、止める
                        session.close()
                        finish(GuestExit(1, error = "入力を送れませんでした"))
                    }
                }
            }
        }
        writeFailure.get()?.let { throw it }
        return exit
    }

    /** 成功しなければ [GuestException] にする */
    suspend fun require(request: OpenRequest, stdin: InputStream? = null, stdout: OutputStream? = null) {
        val err = ByteArrayOutputStream()
        val exit = call(request, stdin, stdout, err)
        if (exit.code != 0) throw GuestException(failureMessage(request.op, exit, err.toString("UTF-8")))
    }

    // ---------------------------------------------------------------- ファイル

    /** ファイルを読む。無ければ null */
    suspend fun readFile(container: String?, path: String): String? {
        val out = ByteArrayOutputStream()
        val exit = call(OpenRequest(op = "get", container = container, path = path), stdout = out)
        return if (exit.code == 0) out.toString("UTF-8") else null
    }

    suspend fun writeFile(container: String?, path: String, content: String, mode: Int = "644".toInt(8)) {
        require(
            OpenRequest(op = "put", container = container, path = path, mode = mode),
            stdin = content.byteInputStream(),
        )
    }

    /** フォルダを作る。[mode] を付けると、すでにあるフォルダもその権限にする */
    suspend fun mkdir(container: String?, path: String, mode: Int? = null) {
        require(OpenRequest(op = "mkdir", container = container, path = path, mode = mode))
    }

    /** VM のルートの [path] の下を、どのコンテナのユーザーからも読み書きできる権限にする */
    suspend fun share(path: String) {
        require(OpenRequest(op = "share", path = path))
    }

    suspend fun remove(container: String?, path: String) {
        require(OpenRequest(op = "rm", container = container, path = path))
    }

    /** tar をゲストに展開する。無ければ作る */
    suspend fun untar(container: String?, tar: InputStream, path: String = "/") {
        require(OpenRequest(op = "untar", container = container, path = path), stdin = tar)
    }

    /** ゲストの [path] の下を tar にして書き出す。[exclude] のフォルダは空にして入れる */
    suspend fun tar(container: String?, path: String, exclude: List<String>, output: OutputStream) {
        require(OpenRequest(op = "tar", container = container, path = path, exclude = exclude), stdout = output)
    }

    // ---------------------------------------------------------------- コンテナと VM

    suspend fun stopContainer(id: String) {
        require(OpenRequest(op = "stop", container = id))
    }

    suspend fun deleteContainer(id: String) {
        require(OpenRequest(op = "delete", container = id))
    }

    /** コンテナが使っているディスクの量 */
    suspend fun diskUsage(id: String): Long {
        val output = capture(OpenRequest(op = "du", container = id))
        if (output.exit != 0) return 0
        return json.parseToJsonElement(output.stdout).jsonObject["bytes"]?.jsonPrimitive?.longOrNull ?: 0
    }

    data class DiskFree(val total: Long, val free: Long, val used: Long)

    suspend fun diskFree(): DiskFree {
        val output = capture(OpenRequest(op = "df"))
        if (output.exit != 0) throw GuestException(failureMessage("df", GuestExit(output.exit, error = output.error), output.stderr))
        val body = json.parseToJsonElement(output.stdout).jsonObject
        fun value(key: String) = body[key]?.jsonPrimitive?.longOrNull ?: 0
        return DiskFree(value("total"), value("free"), value("used"))
    }

    /** ネットワークが変わったときに、VM と動いているコンテナの DNS を書き直す（VM が止まっていれば何もしない） */
    fun updateDns(servers: List<String>) {
        vm.channelIfRunning()?.let { channel ->
            runCatching { channel.open(OpenRequest(op = "dns", servers = servers), object : GuestListener {}) }
        }
    }

    /** スリープ明けに時計を合わせる（VM が止まっていれば何もしない） */
    fun updateClock(timeMillis: Long) {
        vm.channelIfRunning()?.let { channel ->
            runCatching { channel.open(OpenRequest(op = "settime", time = timeMillis), object : GuestListener {}) }
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun failureDetail(error: String?, stderr: String): String? =
            error ?: stderr.lineSequence().lastOrNull { it.isNotBlank() }?.trim()

        fun failureMessage(op: String, exit: GuestExit, stderr: String): String {
            val detail = failureDetail(exit.error, stderr)
            return if (detail.isNullOrEmpty()) "$op に失敗しました（終了コード ${exit.code}）" else "$op に失敗しました: $detail"
        }
    }
}

class GuestException(message: String) : IOException(message)

/** バイト列を行に分ける */
class LineSplitter(private val onLine: (String) -> Unit) : OutputStream() {
    private val buffer = ByteArrayOutputStream()

    override fun write(b: Int) {
        if (b == '\n'.code) flushLine() else buffer.write(b)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        for (i in off until off + len) write(b[i].toInt())
    }

    private fun flushLine() {
        onLine(buffer.toString("UTF-8").removeSuffix("\r"))
        buffer.reset()
    }

    fun finish() {
        if (buffer.size() > 0) flushLine()
    }
}
