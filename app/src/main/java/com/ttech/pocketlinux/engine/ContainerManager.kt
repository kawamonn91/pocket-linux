package com.ttech.pocketlinux.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** コンテナの作成・初期設定・削除 */
class ContainerManager(
    private val paths: Paths,
    private val store: ContainerStore,
    private val registry: RegistryClient,
    private val launcher: ProotLauncher,
    private val host: HostEnvironment,
    private val ops: FileOps = AndroidFileOps,
) {
    private val extractor = LayerExtractor(ops)
    private val setup = RootfsSetup(ops)

    /** 作成の進み具合（label: 何をしているか、fraction: 0〜1。分からなければ null） */
    fun interface ProgressListener {
        fun onProgress(label: String, fraction: Float?)
    }

    suspend fun create(
        imageInput: String,
        name: String,
        installTools: Boolean,
        progress: ProgressListener,
        log: (String) -> Unit,
    ): ContainerConfig = withContext(Dispatchers.IO) {
        paths.ensureBaseDirs()
        val ref = ImageRef.parse(imageInput)
        progress.onProgress("イメージの情報を取得しています", null)
        val resolved = registry.resolve(ref, host.platform())
        log("${ref.displayName}（${resolved.layers.size} レイヤー、${formatBytes(resolved.layers.sumOf { it.size })}）")

        val id = store.newId()
        try {
            val count = resolved.layers.size
            resolved.layers.forEachIndexed { index, layer ->
                registry.downloadBlob(ref, layer, blobFile(layer)) { done, total ->
                    progress.onProgress("ダウンロード中（${index + 1}/$count）", if (total > 0) done.toFloat() / total else null)
                }
            }
            val rootfs = paths.rootfs(id)
            resolved.layers.forEachIndexed { index, layer ->
                val result = extractor.extract(blobFile(layer), layer.isGzip, rootfs) { done, total ->
                    progress.onProgress("展開中（${index + 1}/$count）", if (total > 0) done.toFloat() / total else null)
                }
                if (result.skipped > 0) log("レイヤー ${index + 1}: ${result.skipped} 件のデバイスファイルなどを飛ばしました")
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
            runSetup(config, installTools, progress, log)
        } catch (e: Throwable) {
            runCatching { store.delete(id) }
            throw e
        }
    }

    /**
     * 初期設定（sudo のインストールと一般ユーザーの作成）。作成時のほか、失敗したときにやり直すのにも使う。
     * sudo を入れられなかった場合は、root でログインするコンテナにする。
     */
    suspend fun runSetup(
        config: ContainerConfig,
        installTools: Boolean,
        progress: ProgressListener,
        log: (String) -> Unit,
    ): ContainerConfig = withContext(Dispatchers.IO) {
        val rootfs = paths.rootfs(config.id)
        val base = config.copy(user = ContainerConfig.DEFAULT_USER, home = "/home/${ContainerConfig.DEFAULT_USER}")
        setup.prepareBase(rootfs, base, host.dnsServers())

        progress.onProgress(if (installTools) "sudo とよく使うツールを入れています" else "sudo を入れています", null)
        val command = launcher.exec(base, RootfsSetup.packageInstallScript(installTools), asRoot = true, options())
        val exit = ProcessRunner.run(command, log)
        val sudoOk = exit == 0 && listOf("usr/bin/sudo", "bin/sudo").any { File(rootfs, it).exists() }

        val updated = if (sudoOk) {
            setup.createUser(rootfs, base, host.groupIds())
            base.copy(setupDone = true, sudoInstalled = true)
        } else {
            log("sudo を入れられませんでした（終了コード $exit）。このコンテナは root でログインします。")
            log("ネットワークを確認してから、コンテナのメニューの「初期設定をやり直す」で再試行できます。")
            base.copy(user = null, home = "/root", setupDone = true, sudoInstalled = false)
        }
        store.save(updated)
        updated
    }

    fun options(): LaunchOptions = LaunchOptions(
        bindStorage = host.hasStorageAccess(),
        fakeProc = FakeProc.prepare(paths.fakeProcDir),
    )

    /** コンテナを開く前に、その時点の DNS を書き込む */
    fun beforeLaunch(config: ContainerConfig) {
        runCatching { setup.writeResolvConf(paths.rootfs(config.id), host.dnsServers()) }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) { store.delete(id) }

    /** pull したレイヤーのキャッシュの合計サイズ */
    fun cacheSize(): Long = paths.blobsDir.listFiles().orEmpty().sumOf { it.length() }

    fun clearCache() {
        paths.blobsDir.listFiles().orEmpty().forEach { it.delete() }
    }

    /** コンテナの使用量（リンクはたどらない） */
    suspend fun diskUsage(id: String): Long = withContext(Dispatchers.IO) { sizeOf(paths.containerDir(id)) }

    private fun sizeOf(file: File): Long {
        if (ops.isSymlink(file)) return 0
        if (file.isFile) return file.length()
        return file.listFiles().orEmpty().sumOf { sizeOf(it) }
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
