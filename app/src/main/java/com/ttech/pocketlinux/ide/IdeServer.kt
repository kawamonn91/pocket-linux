package com.ttech.pocketlinux.ide

import android.content.res.AssetManager
import com.ttech.pocketlinux.BackgroundActivity
import com.ttech.pocketlinux.engine.ContainerConfig
import com.ttech.pocketlinux.vm.Guest
import com.ttech.pocketlinux.vm.GuestCommands
import com.ttech.pocketlinux.vm.VmManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import java.io.ByteArrayOutputStream

/**
 * IDE の窓口: pocket-dev のサーバー（Python）を、選んだコンテナの中で動かす。
 * 作業フォルダは ~/drive（Drive と同期するフォルダ）なので、pocket-dev の IDE で編集し、
 * ターミナルで実行する、という使い方ができる。
 *
 * サーバーは VM の中で待ち受け、QEMU のポート転送で端末に出す。既定は端末の中（127.0.0.1）からだけ接続でき、
 * 設定で LAN・Tailscale（0.0.0.0）からも許可できる（どちらもトークンが必要）。
 */
class IdeServer(
    private val assets: AssetManager,
    private val vm: VmManager,
    private val guest: Guest,
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
    private var job: Job? = null
    /** 今のポート転送（アドレス, ポート）。VM が止まると消える */
    private var forward: Pair<String, Int>? = null
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
                PocketDevAssets.install(assets, guest)
                replaceForward(if (allowLan) "0.0.0.0" else "127.0.0.1", port)
                val exit = guest.run(GuestCommands.exec(config, startScript(port, token), asRoot = false), ::log)
                if (isCurrent(current) && _state.value.running) {
                    _state.update { it.copy(error = "IDE のサーバーが止まりました（終了コード $exit）。ログを確認してください") }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isCurrent(current)) _state.update { it.copy(error = e.message ?: e.javaClass.simpleName) }
            } finally {
                synchronized(this@IdeServer) {
                    if (current == generation) {
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
        // コルーチンを取り消すと、ゲストのプロセスも止まる。ポート転送は次の起動か VM の停止まで残るが、誰も待ち受けていない
        job?.cancel()
        job = null
    }

    /** 前のポート転送を外して、新しいものを足す（VM が起動し直していれば、前のものはもう無い） */
    private suspend fun replaceForward(address: String, port: Int) {
        guest.ensureRunning()
        forward?.let { (oldAddress, oldPort) -> runCatching { vm.qmp.removeForward(oldAddress, oldPort) } }
        forward = null
        vm.qmp.addForward(address, port)
        forward = address to port
    }

    private fun log(line: String) {
        _state.update { it.copy(log = (it.log + line).takeLast(MAX_LOG_LINES)) }
    }

    companion object {
        private const val MAX_LOG_LINES = 200

        /**
         * コンテナの中で実行するスクリプト。Python が無ければ入れてから、pocket-dev を起動する。
         * VM の中では全部の宛先で待ち受ける（外から届くのはポート転送した分だけ。誰から届くかは転送の側で決まる）。
         * トークンは環境変数で渡す（コマンドの引数に出さない）
         */
        fun startScript(port: Int, token: String): String = """
            |set -e
            |if ! command -v python3 >/dev/null 2>&1; then
            |  echo "== Python を入れています"
            |  if command -v apt-get >/dev/null 2>&1; then sudo -n apt-get update && sudo -n apt-get install -y --no-install-recommends python3
            |  elif command -v apk >/dev/null 2>&1; then sudo -n apk add --no-cache python3
            |  elif command -v dnf >/dev/null 2>&1; then sudo -n dnf install -y python3
            |  else echo "Python を入れられませんでした（パッケージマネージャーが見つかりません）" >&2; exit 3; fi
            |fi
            |cd ${GuestCommands.POCKET_DEV_DIR}
            |export POCKETDEV_TOKEN='$token'
            |export PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1
            |exec python3 -m agent.server --root "${'$'}HOME/drive" --bind 0.0.0.0 --port $port
            |""".trimMargin()
    }
}

/** APK に同梱した pocket-dev（scripts/vendor-pocket-dev.sh で取り込む）を、版が変わったときだけ VM へ送る */
object PocketDevAssets {
    private const val ROOT = "pocket-dev"
    /** コンテナの /opt/pocket-dev に見える */
    private const val GUEST_DIR = "/data/pocket-dev"

    suspend fun install(assets: AssetManager, guest: Guest) {
        val bundled = assets.open("$ROOT/VERSION").bufferedReader().use { it.readText().trim() }
        val installed = guest.readFile(null, "$GUEST_DIR/VERSION")?.trim()
        if (installed == bundled) return
        // フォルダ自体は消さない（動いているコンテナが bind mount している）
        val tar = ByteArrayOutputStream()
        TarArchiveOutputStream(tar).use { out ->
            out.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            addDir(assets, out, ROOT, "")
            out.finish()
        }
        guest.untar(null, tar.toByteArray().inputStream(), GUEST_DIR)
    }

    private fun addDir(assets: AssetManager, out: TarArchiveOutputStream, path: String, prefix: String) {
        for (name in assets.list(path).orEmpty()) {
            val child = "$path/$name"
            if (assets.list(child).orEmpty().isNotEmpty()) {
                addDir(assets, out, child, "$prefix$name/")
                continue
            }
            val bytes = assets.open(child).use { it.readBytes() }
            out.putArchiveEntry(TarArchiveEntry("$prefix$name").apply { size = bytes.size.toLong(); mode = "100644".toInt(8) })
            out.write(bytes)
            out.closeArchiveEntry()
        }
    }
}
