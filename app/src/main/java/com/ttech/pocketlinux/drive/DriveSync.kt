package com.ttech.pocketlinux.drive

import com.ttech.pocketlinux.BackgroundActivity
import com.ttech.pocketlinux.engine.Paths
import com.ttech.pocketlinux.vm.AgentChannel
import com.ttech.pocketlinux.vm.Guest
import com.ttech.pocketlinux.vm.GuestListener
import com.ttech.pocketlinux.vm.OpenRequest
import com.ttech.pocketlinux.vm.VmManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Google ドライブのフォルダと、VM の中のフォルダ（/data/drive/<名前>。コンテナの ~/drive/<名前>）を双方向に自動で同期する。
 * PC の「Google ドライブ for desktop」に当たる。作業フォルダはゲストの ext4 にあり、rclone の bisync もゲストで動く。
 *
 * - 端末側の変更は、書き込みが落ち着いてから [LOCAL_DEBOUNCE_MS] 後に送る（変更が起きるのは VM が動いている間だけ）
 * - Drive 側の変更は、VM が動いている間は [REMOTE_INTERVAL_MS] おきに取り込む
 * - アプリを開いたときも取り込む（同期する組があれば、そのために VM を起動する）
 * - 同じ組の同期は重ねない（同期中に来た依頼は、終わったあとの1回にまとめる）
 */
class DriveSync(
    private val paths: Paths,
    private val rclone: Rclone,
    private val vm: VmManager,
    private val guest: Guest,
    private val scope: CoroutineScope,
    private val activity: BackgroundActivity,
    private val isOnline: () -> Boolean,
    /** 1.x のデータをまだ移していない間は同期しない（移すまで作業フォルダが VM の中に無い） */
    private val migrationPending: () -> Boolean,
    /** 同期の間は常駐サービスを動かす（裏にいて起動できないときは何もしない） */
    private val keepAlive: () -> Unit,
) {
    data class PairState(val pair: SyncPair, val running: Boolean = false)

    data class State(
        val loggedIn: Boolean = false,
        /** このビルドに Google のログインの設定が入っているか */
        val loginConfigured: Boolean = true,
        val loggingIn: Boolean = false,
        /** 最後のログインが失敗した理由（成功したら消す） */
        val loginError: String? = null,
        val pairs: List<PairState> = emptyList(),
        val excludes: String = "",
        val log: List<String> = emptyList(),
    )

    private val store = SyncPairStore(File(paths.rcloneDir, "pairs.json"))
    private val excludesFile = File(paths.rcloneDir, "excludes.txt")
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val workers = ConcurrentHashMap<String, Channel<Unit>>()
    private val workerJobs = ConcurrentHashMap<String, Job>()
    private val watchers = ConcurrentHashMap<String, AgentChannel.GuestSession>()
    private val debounces = ConcurrentHashMap<String, Job>()
    /** 同期の途中で端末側に変更があった組（終わったらもう1回確かめる） */
    private val changedDuringSync = ConcurrentHashMap.newKeySet<String>()
    private val running = AtomicInteger()
    private var lastSyncAllAt = 0L
    private var started = false

    @Synchronized
    fun start() {
        if (started) return
        started = true
        if (!excludesFile.isFile) writeExcludes(Excludes.DEFAULT)
        _state.update {
            it.copy(
                loggedIn = rclone.isLoggedIn,
                loginConfigured = rclone.configured,
                pairs = store.load().map { pair -> PairState(pair) },
                excludes = excludesFile.readText(),
            )
        }
        state.value.pairs.forEach { startWorker(it.pair) }
        activity.onStopAll { stopRunning() }
        scope.launch {
            while (true) {
                delay(REMOTE_INTERVAL_MS)
                if (vm.isRunning && isOnline()) requestSyncAll()
            }
        }
    }

    /** VM が動き始めたとき: 変更の見張りを始めて、止まっていた間の Drive 側の変更を取り込む */
    fun onVmRunning() {
        restartWatchers()
        requestSyncAll()
        scope.launch { repairPermissionsOnce() }
    }

    /**
     * 2.0.0 は同期フォルダを root の権限で作っていて、コンテナのユーザーが編集できなかった。
     * その分を 1 回だけ直す（これから作るものは最初から編集できる）
     */
    private suspend fun repairPermissionsOnce() {
        val marker = File(paths.rcloneDir, "drive-permissions-repaired")
        if (marker.isFile) return
        runCatching { guest.share(Rclone.GUEST_DRIVE_DIR) }
            .onSuccess {
                paths.rcloneDir.mkdirs()
                marker.writeText("")
            }
            .onFailure { log("同期フォルダの権限を直せませんでした: ${it.message}") }
    }

    /** VM が止まったとき: 見張りはチャンネルごと消えている */
    fun onVmStopped() {
        watchers.clear()
    }

    /** 1.x のデータを移したとき: 同期の記録は移していないので、両方の中身を合わせ直す */
    fun onMigrated() {
        _state.update { it.copy(loggedIn = rclone.isLoggedIn) }
        state.value.pairs.forEach { changePair(it.pair.id) { pair -> pair.copy(needsResync = true, lastError = null) } }
        onVmRunning()
    }

    // ------------------------------------------------------------ ログイン

    /**
     * Google にログインする。ブラウザで同意している間もアプリ（と rclone の受け口）が止められないよう、
     * 常駐サービスを動かしておく。もう一度呼ぶと、前のログインは止めてやり直す。
     */
    fun login(openUrl: (String) -> Unit) {
        loginJob?.cancel()
        loginJob = scope.launch {
            _state.update { it.copy(loggingIn = true, loginError = null) }
            activity.update { it.copy(loggingIn = true) }
            runCatching { keepAlive() }
            try {
                withTimeout(LOGIN_TIMEOUT_MS) { rclone.login(openUrl) { log("ログイン: $it") } }
                _state.update { it.copy(loggedIn = rclone.isLoggedIn) }
                requestSyncAll()
            } catch (e: TimeoutCancellationException) {
                _state.update { it.copy(loginError = "時間内に同意が終わらなかったため、ログインをやめました") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loginError = e.message ?: "ログインできませんでした") }
            } finally {
                activity.update { it.copy(loggingIn = false) }
                _state.update { it.copy(loggingIn = false, loggedIn = rclone.isLoggedIn) }
            }
        }
    }

    private var loginJob: Job? = null

    fun logout() {
        stopRunning()
        _state.update { it.copy(loggedIn = false) }
        scope.launch {
            runCatching { rclone.logout() }.onFailure { log("ログアウト: ${it.message}") }
        }
    }

    suspend fun listFolders(path: String): List<String> = rclone.listFolders(path)

    // ------------------------------------------------------------ 同期する組

    /** 組を足して、すぐに最初の同期（両方の中身を合わせる）を始める */
    fun addPair(remotePath: String, localName: String): SyncPair {
        val name = localName.trim()
        if (remotePath.trim('/').isEmpty()) throw IOException("マイドライブ全体ではなく、フォルダを選んでください")
        if (!Excludes.isValidLocalName(name)) throw IOException("フォルダ名に使えない文字があります")
        val current = state.value.pairs.map { it.pair }
        if (current.any { it.localName == name }) throw IOException("「$name」はもう使われています")
        if (current.any { it.remotePath == remotePath.trim('/') }) throw IOException("このフォルダはもう同期しています")
        val pair = SyncPair(
            id = UUID.randomUUID().toString().substring(0, 8),
            remotePath = remotePath.trim('/'),
            localName = name,
            createdAt = System.currentTimeMillis(),
        )
        updatePairs { it + PairState(pair) }
        startWorker(pair)
        requestSync(pair.id)
        return pair
    }

    /** 同期をやめる。[deleteLocal] なら端末側のフォルダも消す（Drive 側はそのまま） */
    fun removePair(id: String, deleteLocal: Boolean) {
        val pair = findPair(id) ?: return
        stopWatcher(id)
        debounces.remove(id)?.cancel()
        workerJobs.remove(id)?.cancel()
        workers.remove(id)?.close()
        updatePairs { list -> list.filterNot { it.pair.id == id } }
        scope.launch {
            runCatching {
                guest.remove(null, Rclone.guestWorkDir(id))
                if (deleteLocal) guest.remove(null, Rclone.guestLocalDir(pair.localName))
            }.onFailure { log("${pair.localName}: 消せませんでした（${it.message}）") }
        }
    }

    /** 最初の同期からやり直す（同期の記録が壊れたときなど。両方の中身を合わせ直す） */
    fun resync(id: String) {
        changePair(id) { it.copy(needsResync = true, lastError = null) }
        requestSync(id)
    }

    fun requestSync(id: String) {
        workers[id]?.trySend(Unit)
    }

    fun requestSyncAll() {
        if (!state.value.loggedIn) return
        lastSyncAllAt = System.currentTimeMillis()
        state.value.pairs.forEach { requestSync(it.pair.id) }
    }

    /** 画面を開いたとき。しばらく取り込んでいなければ Drive 側の変更を取り込む */
    fun onForeground() {
        if (System.currentTimeMillis() - lastSyncAllAt > FOREGROUND_MIN_INTERVAL_MS) requestSyncAll()
    }

    // ------------------------------------------------------------ 同期しないもの

    /** 同期しないものを変える。条件が変わると bisync は最初からやり直す必要があるので、全部の組をやり直す */
    fun saveExcludes(text: String) {
        writeExcludes(text.trimEnd() + "\n")
        _state.update { it.copy(excludes = excludesFile.readText()) }
        restartWatchers()
        state.value.pairs.forEach { resync(it.pair.id) }
    }

    private fun writeExcludes(text: String) {
        paths.rcloneDir.mkdirs()
        excludesFile.writeText(text)
    }

    // ------------------------------------------------------------ 内部

    private fun startWorker(pair: SyncPair) {
        val channel = Channel<Unit>(Channel.CONFLATED)
        workers[pair.id] = channel
        workerJobs[pair.id] = scope.launch {
            for (request in channel) {
                runSync(pair.id)
                if (changedDuringSync.remove(pair.id)) scheduleLocalSync(pair.id)
            }
        }
    }

    private fun restartWatchers() {
        state.value.pairs.forEach { pairState ->
            stopWatcher(pairState.pair.id)
            startWatcher(pairState.pair)
        }
    }

    /** VM の中のフォルダの変更を見張る。フォルダがまだ無ければ（最初の同期の前）、同期のあとで見張り始める */
    private fun startWatcher(pair: SyncPair) {
        val channel = vm.channelIfRunning() ?: return
        val skipped = Excludes.skippedDirNames(state.value.excludes)
        val session = runCatching {
            channel.open(
                OpenRequest(op = "watch", path = Rclone.guestLocalDir(pair.localName), exclude = skipped.toList()),
                object : GuestListener {
                    override fun onStdout(data: ByteArray, length: Int) {
                        if (isRunning(pair.id)) changedDuringSync += pair.id else scheduleLocalSync(pair.id)
                    }
                },
            )
        }.getOrNull() ?: return
        watchers[pair.id] = session
    }

    private fun stopWatcher(id: String) {
        watchers.remove(id)?.let { runCatching { it.close() } }
    }

    private fun scheduleLocalSync(id: String) {
        debounces.put(id, scope.launch {
            delay(LOCAL_DEBOUNCE_MS)
            requestSync(id)
        })?.cancel()
    }

    private suspend fun runSync(id: String) {
        val pair = findPair(id) ?: return
        if (!state.value.loggedIn) return
        if (migrationPending()) {
            changePair(id) { it.copy(lastError = "前の版のデータを移すまで同期できません（トップ画面のお知らせから移せます）") }
            return
        }
        setRunning(id, true)
        try {
            val resync = pair.needsResync
            var mustResync = false
            val lastLines = ArrayDeque<String>()
            val localDir = Rclone.guestLocalDir(pair.localName)
            val workDir = Rclone.guestWorkDir(id)
            // コンテナのユーザーも書けるようにする（中身は rclone が umask 0 で作る）
            guest.mkdir(null, localDir, mode = "777".toInt(8))
            guest.mkdir(null, workDir)
            guest.writeFile(null, Rclone.GUEST_EXCLUDES, state.value.excludes)
            // 最初の同期の前はフォルダが無く、見張りを始められなかった
            if (!watchers.containsKey(id)) startWatcher(pair)
            log("${pair.localName}: 同期を始めます" + if (resync) "（最初から合わせる）" else "")
            val args = Rclone.bisyncArgs(pair.remotePath, localDir, workDir, Rclone.GUEST_EXCLUDES, resync)
            val exit = rclone.run(args) { line ->
                if (line.contains("--resync")) mustResync = true
                lastLines.addLast(line)
                if (lastLines.size > 3) lastLines.removeFirst()
                if (line.contains("ERROR") || line.contains("NOTICE") || line.contains("INFO  :") && line.contains(": Copied")) {
                    log("${pair.localName}: ${line.substringAfter(" : ", line).trim()}")
                }
            }
            changePair(id) {
                when {
                    exit == 0 -> it.copy(needsResync = false, lastSyncAt = System.currentTimeMillis(), lastError = null)
                    mustResync && !resync -> it.copy(needsResync = true, lastError = "同期の記録が合わなくなりました。「最初からやり直す」で合わせ直してください")
                    else -> it.copy(lastError = lastLines.lastOrNull { l -> l.isNotBlank() }?.trim() ?: "同期に失敗しました（終了コード $exit）")
                }
            }
            log("${pair.localName}: " + if (exit == 0) "同期しました" else "同期に失敗しました（終了コード $exit）")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            changePair(id) { it.copy(lastError = e.message ?: e.javaClass.simpleName) }
        } finally {
            setRunning(id, false)
        }
    }

    private fun stopRunning() {
        // 動いている同期を止める（次の依頼からは普通に動く）
        workerJobs.keys.toList().forEach { id ->
            val pair = findPair(id) ?: return@forEach
            workerJobs.remove(id)?.cancel()
            workers.remove(id)?.close()
            stopWatcher(id)
            startWorker(pair)
        }
    }

    private fun isRunning(id: String) = state.value.pairs.any { it.pair.id == id && it.running }

    private fun setRunning(id: String, value: Boolean) {
        updatePairs(save = false) { list -> list.map { if (it.pair.id == id) it.copy(running = value) else it } }
        val count = if (value) running.incrementAndGet() else running.decrementAndGet()
        activity.update { it.copy(syncing = count > 0) }
        if (value) runCatching { keepAlive() }
    }

    private fun findPair(id: String) = state.value.pairs.firstOrNull { it.pair.id == id }?.pair

    private fun changePair(id: String, change: (SyncPair) -> SyncPair) =
        updatePairs { list -> list.map { if (it.pair.id == id) it.copy(pair = change(it.pair)) else it } }

    @Synchronized
    private fun updatePairs(save: Boolean = true, change: (List<PairState>) -> List<PairState>) {
        _state.update { it.copy(pairs = change(it.pairs)) }
        if (save) store.save(state.value.pairs.map { it.pair })
    }

    private fun log(line: String) {
        _state.update { it.copy(log = (it.log + line).takeLast(MAX_LOG_LINES)) }
    }

    companion object {
        const val LOCAL_DEBOUNCE_MS = 5_000L
        const val REMOTE_INTERVAL_MS = 3 * 60_000L
        private const val FOREGROUND_MIN_INTERVAL_MS = 60_000L
        private const val LOGIN_TIMEOUT_MS = 5 * 60_000L
        private const val MAX_LOG_LINES = 300
    }
}
