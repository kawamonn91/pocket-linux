package com.ttech.pocketlinux.ide

import android.content.res.AssetManager
import com.ttech.pocketlinux.BackgroundActivity
import com.ttech.pocketlinux.engine.ContainerConfig
import com.ttech.pocketlinux.engine.ContainerManager
import com.ttech.pocketlinux.engine.Paths
import com.ttech.pocketlinux.engine.ProcessRunner
import com.ttech.pocketlinux.engine.ProotLauncher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/**
 * IDE の窓口: pocket-dev のサーバー（Python）を、選んだコンテナの中で動かす。
 * 作業フォルダは ~/drive（Drive と同期するフォルダ）なので、pocket-dev の IDE で編集し、
 * ターミナルで実行する、という使い方ができる。既定は端末の中からだけ接続でき、
 * 設定で LAN・Tailscale からも許可できる（どちらもトークンが必要）。
 */
class IdeServer(
    private val paths: Paths,
    private val assets: AssetManager,
    private val launcher: ProotLauncher,
    private val containers: ContainerManager,
    private val scope: CoroutineScope,
    private val activity: BackgroundActivity,
    /** 動かしている間は常駐サービスを動かす */
    private val keepAlive: () -> Unit,
) {
    data class State(
        val running: Boolean = false,
        val containerName: String? = null,
        val port: Int = 0,
        val allowLan: Boolean = false,
        val log: List<String> = emptyList(),
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var process: Process? = null
    private var job: Job? = null
    /** 何回目の起動か。起動し直したとき、止めた古いサーバーの後片付けが新しい状態を書き換えないように */
    private var generation = 0

    init {
        activity.onStopAll { stop() }
    }

    @Synchronized
    fun start(config: ContainerConfig, port: Int, token: String, allowLan: Boolean) {
        stop()
        val current = ++generation
        _state.value = State(running = true, containerName = config.name, port = port, allowLan = allowLan)
        activity.update { it.copy(ideRunning = true) }
        runCatching { keepAlive() }
        job = scope.launch(Dispatchers.IO) {
            try {
                PocketDevAssets.extract(assets, paths.pocketDevDir)
                containers.beforeLaunch(config)
                val command = launcher.exec(config, startScript(port, token, allowLan), asRoot = false, containers.options())
                val started = ProcessRunner.start(command)
                synchronized(this@IdeServer) {
                    if (current != generation) {
                        started.destroy()
                        return@launch
                    }
                    process = started
                }
                started.outputStream.close()
                started.inputStream.bufferedReader().useLines { lines -> lines.forEach(::log) }
                val exit = started.waitFor()
                if (isCurrent(current) && _state.value.running) {
                    _state.update { it.copy(error = "IDE のサーバーが止まりました（終了コード $exit）。ログを確認してください") }
                }
            } catch (e: Exception) {
                if (isCurrent(current)) _state.update { it.copy(error = e.message ?: e.javaClass.simpleName) }
            } finally {
                synchronized(this@IdeServer) {
                    if (current == generation) {
                        process = null
                        _state.update { it.copy(running = false) }
                        activity.update { it.copy(ideRunning = false) }
                    }
                }
            }
        }
    }

    @Synchronized
    private fun isCurrent(value: Int) = value == generation

    @Synchronized
    fun stop() {
        generation++
        _state.update { it.copy(running = false) }
        activity.update { it.copy(ideRunning = false) }
        // proot は --kill-on-exit で起動しているので、proot を止めればコンテナの中のサーバーも止まる
        process?.destroy()
        process = null
        job?.cancel()
        job = null
    }

    private fun log(line: String) {
        _state.update { it.copy(log = (it.log + line).takeLast(MAX_LOG_LINES)) }
    }

    companion object {
        private const val MAX_LOG_LINES = 200

        /**
         * コンテナの中で実行するスクリプト。Python が無ければ入れてから、pocket-dev を起動する。
         * トークンは環境変数で渡す（コマンドの引数に出さない）
         */
        fun startScript(port: Int, token: String, allowLan: Boolean): String {
            val bind = if (allowLan) "0.0.0.0" else "127.0.0.1"
            return """
                |set -e
                |if ! command -v python3 >/dev/null 2>&1; then
                |  echo "== Python を入れています"
                |  if command -v apt-get >/dev/null 2>&1; then sudo -n apt-get update && sudo -n apt-get install -y --no-install-recommends python3
                |  elif command -v apk >/dev/null 2>&1; then sudo -n apk add --no-cache python3
                |  elif command -v dnf >/dev/null 2>&1; then sudo -n dnf install -y python3
                |  else echo "Python を入れられませんでした（パッケージマネージャーが見つかりません）" >&2; exit 3; fi
                |fi
                |cd ${ProotLauncher.GUEST_POCKET_DEV_DIR}
                |export POCKETDEV_TOKEN='$token'
                |export PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1
                |exec python3 -m agent.server --root "${'$'}HOME/drive" --bind $bind --port $port
                |""".trimMargin()
        }
    }
}

/** APK に同梱した pocket-dev（scripts/vendor-pocket-dev.sh で取り込む）を、版が変わったときだけ展開する */
object PocketDevAssets {
    private const val ROOT = "pocket-dev"

    fun extract(assets: AssetManager, dest: File) {
        val bundled = assets.open("$ROOT/VERSION").bufferedReader().use { it.readText().trim() }
        val installed = File(dest, "VERSION").takeIf { it.isFile }?.readText()?.trim()
        if (installed == bundled) return
        dest.deleteRecursively()
        copyDir(assets, ROOT, dest)
    }

    private fun copyDir(assets: AssetManager, path: String, dest: File) {
        val children = assets.list(path).orEmpty()
        if (children.isEmpty()) {
            dest.parentFile?.mkdirs()
            assets.open(path).use { input -> dest.outputStream().use { input.copyTo(it) } }
            return
        }
        dest.mkdirs()
        children.forEach { copyDir(assets, "$path/$it", File(dest, it)) }
    }
}
