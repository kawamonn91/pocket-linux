package com.ttech.pocketlinux.vm

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.ttech.pocketlinux.engine.Paths
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.zip.GZIPInputStream
import kotlin.concurrent.thread

/** VM を起動できなかったなど、利用者に見せる失敗 */
class VmException(message: String) : IOException(message)

/**
 * VM（QEMU の子プロセス 1 つ）の起動と停止。使う側は [channel] を呼ぶだけで、動いていなければ起動する。
 */
class VmManager(
    private val paths: Paths,
    private val options: () -> Options,
    private val onEvent: (String, JsonObject) -> Unit,
    /** 起動の前に呼ぶ。カーネルなどを files/vm/images に用意する */
    private val prepareImages: () -> Unit,
    /** VM を起動する前に呼ぶ（常駐サービスを動かす） */
    private val keepAlive: () -> Unit = {},
) {
    data class Options(
        val memoryMb: Int,
        val cpus: Int,
        val diskBytes: Long,
        val dnsServers: List<String>,
        val storage: String?,
    )

    enum class State { STOPPED, STARTING, RUNNING, STOPPING }

    data class Status(val state: State = State.STOPPED, val error: String? = null, val bootMillis: Long = 0)

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    private val mutex = Mutex()

    @Volatile
    private var process: Process? = null

    @Volatile
    private var current: AgentChannel? = null

    val isRunning: Boolean get() = current?.closed == false

    val qmp: Qmp get() = Qmp(paths.vmQmpSocket)

    /** 動いている VM との接続を返す。動いていなければ起動して、準備ができるまで待つ */
    suspend fun channel(): AgentChannel {
        current?.takeIf { !it.closed }?.let { return it }
        return mutex.withLock {
            current?.takeIf { !it.closed }?.let { return@withLock it }
            start()
        }
    }

    /** 動いているときだけ接続を返す（止まっていれば起動しない） */
    fun channelIfRunning(): AgentChannel? = current?.takeIf { !it.closed }

    /** VM を止める。ファイルシステムを閉じるところまで待ち、応答が無ければ強制的に止める */
    suspend fun shutdown() = mutex.withLock {
        val running = process ?: return@withLock
        _status.value = _status.value.copy(state = State.STOPPING)
        // これ以降の channel() は、ここが終わるのを待ってから起動し直す
        val stopping = current
        current = null
        withContext(Dispatchers.IO) {
            stopping?.takeIf { !it.closed }?.let { channel ->
                val done = CompletableDeferred<Unit>()
                runCatching {
                    channel.open(OpenRequest(op = "shutdown"), object : GuestListener {
                        override fun onExit(exit: GuestExit) {
                            done.complete(Unit)
                        }
                    })
                    withTimeoutOrNull(SHUTDOWN_REPLY_TIMEOUT_MS) { done.await() }
                }
            }
            var waited = 0L
            while (running.isAlive && waited < SHUTDOWN_TIMEOUT_MS) {
                delay(100)
                waited += 100
            }
            if (running.isAlive) {
                Log.w(TAG, "VM が止まらないので強制的に止めます")
                running.destroyForcibly()
            }
            // 監視のスレッドが片付けるのを待たずに、次の起動に進めるように
            running.waitFor()
            stopping?.close()
            if (process === running) process = null
            _status.value = Status(State.STOPPED)
        }
    }

    /** 今すぐ止める（アプリの終了時など） */
    fun kill() {
        process?.destroyForcibly()
    }

    /** 起動ログ（カーネルとエージェントの出力、QEMU の出力） */
    fun consoleLog(): String = buildString {
        for (file in listOf(paths.vmConsoleLog, paths.vmQemuLog)) {
            if (file.isFile && file.length() > 0) {
                append("== ").append(file.name).append('\n')
                append(runCatching { file.readText().takeLast(MAX_LOG_CHARS) }.getOrDefault(""))
                append('\n')
            }
        }
    }

    // ---------------------------------------------------------------- 起動

    private suspend fun start(): AgentChannel = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        _status.value = Status(State.STARTING)
        runCatching { keepAlive() }
        try {
            val opts = options()
            prepareImages()
            prepareDisk(opts.diskBytes)
            killStale()
            paths.vmAgentSocket.delete()
            paths.vmQmpSocket.delete()

            val config = VmConfig(
                qemu = paths.qemu.path,
                kernel = paths.kernel.path,
                system = paths.systemImage.path,
                data = paths.vmData.path,
                agentSocket = paths.vmAgentSocket.path,
                qmpSocket = paths.vmQmpSocket.path,
                consoleLog = paths.vmConsoleLog.path,
                pidFile = paths.vmPidFile.path,
                memoryMb = opts.memoryMb,
                cpus = opts.cpus,
                storage = opts.storage,
            )
            val qemu = ProcessBuilder(QemuLauncher.argv(config))
                .directory(paths.vmDir)
                .redirectErrorStream(true)
                .redirectOutput(paths.vmQemuLog)
                .apply {
                    environment().clear()
                    environment()["PATH"] = "/system/bin"
                    environment()["TMPDIR"] = paths.cacheDir.path
                    environment()["HOME"] = paths.vmDir.path
                }
                .start()
            process = qemu
            runCatching { qemu.outputStream.close() }

            val socket = connect(qemu)
            val ready = CompletableDeferred<JsonObject>()
            val channel = AgentChannel(
                socket.inputStream,
                socket.outputStream,
                onEvent = { type, body ->
                    if (type == "ready") ready.complete(body) else onEvent(type, body)
                },
                onClosed = { cause ->
                    ready.completeExceptionally(IOException("VM との接続が切れました", cause))
                    runCatching { socket.close() }
                },
            )
            channel.start()
            channel.hello(System.currentTimeMillis(), opts.dnsServers)

            val hello = try {
                withTimeout(BOOT_TIMEOUT_MS) { ready.await() }
            } catch (e: Exception) {
                qemu.destroyForcibly()
                throw VmException("Linux を起動できませんでした（${e.message}）\n${tail()}")
            }
            val version = hello["version"]?.jsonPrimitive?.content
            if (version != AGENT_VERSION) {
                qemu.destroyForcibly()
                throw VmException("VM の中身の版が合いません（$version）。アプリを入れ直してください")
            }
            if (hello["data"]?.jsonPrimitive?.booleanOrNull != true) {
                qemu.destroyForcibly()
                throw VmException("データ用のディスクを開けませんでした\n${tail()}")
            }

            current = channel
            val elapsed = System.currentTimeMillis() - started
            Log.i(TAG, "VM を起動しました（$elapsed ms）")
            _status.value = Status(State.RUNNING, bootMillis = elapsed)
            watch(qemu, channel)
            channel
        } catch (e: Throwable) {
            process?.takeIf { it.isAlive }?.destroyForcibly()
            process = null
            _status.value = Status(State.STOPPED, error = e.message ?: e.javaClass.simpleName)
            throw e
        }
    }

    private fun watch(qemu: Process, channel: AgentChannel) {
        thread(name = "VmWatcher", isDaemon = true) {
            val exit = qemu.waitFor()
            Log.i(TAG, "QEMU が終わりました（終了コード $exit）")
            channel.close()
            if (process === qemu) {
                process = null
                current = null
                val wasStopping = _status.value.state == State.STOPPING
                _status.value = Status(
                    State.STOPPED,
                    error = if (wasStopping || exit == 0) null else "VM が止まりました（終了コード $exit）",
                )
            }
        }
    }

    private suspend fun connect(qemu: Process): LocalSocket {
        val deadline = System.currentTimeMillis() + CONNECT_TIMEOUT_MS
        while (true) {
            if (!qemu.isAlive) throw VmException("QEMU が起動できませんでした（終了コード ${qemu.exitValue()}）\n${tail()}")
            if (paths.vmAgentSocket.exists()) {
                val socket = LocalSocket()
                try {
                    socket.connect(LocalSocketAddress(paths.vmAgentSocket.path, LocalSocketAddress.Namespace.FILESYSTEM))
                    return socket
                } catch (_: IOException) {
                    runCatching { socket.close() }
                }
            }
            if (System.currentTimeMillis() > deadline) {
                qemu.destroyForcibly()
                throw VmException("QEMU に接続できませんでした\n${tail()}")
            }
            delay(50)
        }
    }

    private fun tail(): String = consoleLog().takeLast(2000)

    /** 前に動かした QEMU が残っていたら止める（アプリだけが落ちたとき）。残っているとディスクの鍵が取れない */
    private fun killStale() {
        val pid = runCatching { paths.vmPidFile.readText().trim().toInt() }.getOrNull() ?: return
        val cmdline = runCatching { File("/proc/$pid/cmdline").readText() }.getOrNull() ?: return
        if (!cmdline.contains(paths.qemu.name)) return
        Log.w(TAG, "残っていた QEMU（$pid）を止めます")
        runCatching { Os.kill(pid, OsConstants.SIGKILL) }
        var waited = 0
        while (File("/proc/$pid").exists() && waited < 3000) {
            Thread.sleep(50)
            waited += 50
        }
    }

    /**
     * データ用のディスクを用意する。初回はひな形（空の ext4）を疎ファイルとして書き出す。
     * ファイルが設定より小さければ大きくする（ゲストが起動時にファイルシステムを広げる）。
     */
    private fun prepareDisk(diskBytes: Long) {
        paths.vmDir.mkdirs()
        val disk = paths.vmData
        if (!disk.isFile) {
            val tmp = File(disk.path + ".tmp")
            tmp.delete()
            RandomAccessFile(tmp, "rw").use { out ->
                GZIPInputStream(paths.dataTemplate.inputStream().buffered(1 shl 16)).use { input ->
                    writeSparse(input, out)
                }
            }
            if (!tmp.renameTo(disk)) throw VmException("データ用のディスクを作れませんでした")
        }
        if (disk.length() < diskBytes) RandomAccessFile(disk, "rw").use { it.setLength(diskBytes) }
    }

    companion object {
        private const val TAG = "VmManager"

        /** エージェントの版（vm/agent/main.go の Version）。組み合わせが変わったら一緒に上げる */
        const val AGENT_VERSION = "1"

        private const val CONNECT_TIMEOUT_MS = 20_000L
        private const val BOOT_TIMEOUT_MS = 180_000L
        private const val SHUTDOWN_REPLY_TIMEOUT_MS = 5_000L
        private const val SHUTDOWN_TIMEOUT_MS = 15_000L
        private const val MAX_LOG_CHARS = 64 * 1024

        /** 0 だけのブロックは書かずに飛ばす（疎ファイルにして、使った分だけ容量を使う） */
        fun writeSparse(input: java.io.InputStream, out: RandomAccessFile) {
            val buffer = ByteArray(1 shl 16)
            var position = 0L
            while (true) {
                var filled = 0
                while (filled < buffer.size) {
                    val n = input.read(buffer, filled, buffer.size - filled)
                    if (n < 0) break
                    filled += n
                }
                if (filled == 0) break
                if (!isZero(buffer, filled)) {
                    out.seek(position)
                    out.write(buffer, 0, filled)
                }
                position += filled
                if (filled < buffer.size) break
            }
            out.setLength(position)
        }

        private fun isZero(buffer: ByteArray, length: Int): Boolean {
            for (i in 0 until length) if (buffer[i] != 0.toByte()) return false
            return true
        }
    }
}
