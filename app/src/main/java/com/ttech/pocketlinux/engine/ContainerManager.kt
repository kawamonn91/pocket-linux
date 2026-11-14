package com.ttech.pocketlinux.engine

import com.ttech.pocketlinux.vm.Guest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream

/** 初期設定の結果。[skipped] は入れられなかった任意のもの（日本語環境・C・Java・Python） */
data class SetupResult(val config: ContainerConfig, val skipped: List<String>)

/** コンテナの作成・初期設定・削除。中身は VM の中にあり、端末側に残るのは設定とレイヤーのキャッシュだけ */
class ContainerManager(
    private val paths: Paths,
    private val store: ContainerStore,
    private val registry: RegistryClient,
    private val guest: Guest,
    private val host: HostEnvironment,
) {
    private val setup = ContainerSetup(guest)

    /** 作成の進み具合（label: 何をしているか、fraction: 0〜1。分からなければ null） */
    fun interface ProgressListener {
        fun onProgress(label: String, fraction: Float?)
    }

    suspend fun create(
        imageInput: String,
        name: String,
        options: SetupOptions,
        progress: ProgressListener,
        log: (String) -> Unit,
    ): SetupResult = withContext(Dispatchers.IO) {
        paths.ensureBaseDirs()
        val ref = ImageRef.parse(imageInput)
        progress.onProgress("イメージの情報を取得しています", null)
        // VM の中はどの端末でも arm64
        val resolved = registry.resolve(ref, Platform.GUEST)
        log("${ref.displayName}（${resolved.layers.size} レイヤー、${formatBytes(resolved.layers.sumOf { it.size })}）")

        val id = store.newId()
        try {
            val count = resolved.layers.size
            resolved.layers.forEachIndexed { index, layer ->
                registry.downloadBlob(ref, layer, blobFile(layer)) { done, total ->
                    progress.onProgress("ダウンロード中（${index + 1}/$count）", if (total > 0) done.toFloat() / total else null)
                }
            }
            progress.onProgress("Linux を起動しています", null)
            guest.ensureRunning()
            resolved.layers.forEachIndexed { index, layer ->
                val archive = blobFile(layer)
                val total = archive.length()
                FileInputStream(archive).use { fileIn ->
                    val counting = CountingInputStream(BufferedInputStream(fileIn, 1 shl 16), 512L * 1024) { read ->
                        progress.onProgress("展開中（${index + 1}/$count）", if (total > 0) read.toFloat() / total else null)
                    }
                    // gzip は端末側で解く（VM の中で解くより何倍も速い）
                    val tar: InputStream = if (layer.isGzip) GZIPInputStream(counting, 1 shl 16) else counting
                    guest.untar(id, tar)
                }
            }

            val config = ContainerConfig(
                id = id,
                name = name.ifBlank { ref.displayName },
                image = ref.displayName,
                imageDigest = resolved.manifestDigest,
                createdAt = System.currentTimeMillis(),
                env = resolved.config.env,
                workingDir = resolved.config.workingDir,
            )
            store.save(config)
            runSetup(config, options, progress, log)
        } catch (e: Throwable) {
            runCatching { store.delete(id) }
            runCatching { withContext(kotlinx.coroutines.NonCancellable) { guest.deleteContainer(id) } }
            throw e
        }
    }

    /**
     * 初期設定（sudo と選んだもののインストールと、一般ユーザーの作成）。作成時のほか、失敗したときにやり直すのにも使う。
     * sudo を入れられなかった場合は、root でログインするコンテナにする。
     */
    suspend fun runSetup(
        config: ContainerConfig,
        options: SetupOptions,
        progress: ProgressListener,
        log: (String) -> Unit,
    ): SetupResult = withContext(Dispatchers.IO) {
        val base = config.copy(user = ContainerConfig.DEFAULT_USER, home = "/home/${ContainerConfig.DEFAULT_USER}")
        setup.prepareBase(base)

        progress.onProgress("${options.label()} を入れています", null)
        val result = setup.installPackages(base, options, log) { step -> progress.onProgress(step, null) }
        val exit = result.exit

        val updated = if (exit == 0) {
            setup.createUser(base)
            base.copy(setupDone = true, sudoInstalled = true)
        } else {
            log("sudo を入れられませんでした（終了コード $exit）。このコンテナは root でログインします。")
            log("ネットワークを確認してから、コンテナのメニューの「初期設定をやり直す」で再試行できます。")
            base.copy(user = null, home = "/root", setupDone = true, sudoInstalled = false)
        }
        store.save(updated)
        if (result.skipped.isNotEmpty()) {
            log("入れられなかったもの: ${result.skipped.joinToString("・")}（ネットワークや空き容量を確かめて、ターミナルから入れ直せます）")
        }
        SetupResult(updated, result.skipped)
    }

    /** 1.x の中身（移行・古いバックアップの復元）を VM で使えるように直す */
    suspend fun repairLegacy(config: ContainerConfig) = setup.repairLegacy(config)

    /** コンテナを開く前に、その時点の DNS を VM に教える（VM が動いていれば） */
    fun beforeLaunch() {
        guest.updateDns(host.guestDnsServers())
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        val legacy = store.isLegacy(id)
        store.delete(id)
        if (!legacy) guest.deleteContainer(id)
    }

    /** pull したレイヤーのキャッシュの合計サイズ */
    fun cacheSize(): Long = paths.blobsDir.listFiles().orEmpty().sumOf { it.length() }

    fun clearCache() {
        paths.blobsDir.listFiles().orEmpty().forEach { it.delete() }
    }

    private fun blobFile(layer: Layer) = File(paths.blobsDir, layer.digest.removePrefix("sha256:"))

    companion object {
        fun formatBytes(bytes: Long): String = when {
            bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
            bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
            bytes >= 1L shl 10 -> "%.0f KB".format(bytes / (1L shl 10).toDouble())
            else -> "$bytes B"
        }
    }
}

/** 作成・初期設定などで、利用者に見せる失敗 */
class ContainerException(message: String) : IOException(message)
