package com.ttech.pocketlinux.engine

import android.system.Os
import android.system.OsConstants
import com.ttech.pocketlinux.drive.HardLinkGuard
import com.ttech.pocketlinux.drive.Rclone
import com.ttech.pocketlinux.vm.Guest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlin.concurrent.thread

/**
 * 1.x（proot）で作ったものを VM へ移す。アプリの更新のあと、利用者がトップ画面のお知らせから実行する
 * （時間がかかり、途中は端末の容量を 2 倍使うので、勝手には始めない）。
 *
 * - コンテナ: 端末の `files/containers/<id>/rootfs` を tar にして VM の `/data/containers/<id>` へ
 * - Drive の同期フォルダ: `files/drive` を VM の `/data/drive` へ（次の同期は両方の中身を合わせ直す）
 * - rclone の設定: `files/rclone/rclone.conf` を VM へ
 *
 * 途中で失敗しても、移し終えていないものは端末側に残るので、やり直せる。
 */
class LegacyMigration(
    private val paths: Paths,
    private val store: ContainerStore,
    private val guest: Guest,
    private val containers: ContainerManager,
    private val rclone: Rclone,
) {
    /** 移すものが残っているか */
    fun pending(): Boolean = legacyContainers().isNotEmpty() || hasLegacyDrive() || paths.legacyRcloneConfig.isFile

    fun legacyContainers(): List<ContainerConfig> = store.list().filter { store.isLegacy(it.id) }

    private fun hasLegacyDrive() = paths.legacyDriveDir.listFiles().orEmpty().isNotEmpty()

    /** 全部を移す。Drive の同期フォルダを移したら true（同期の記録を作り直す必要がある） */
    suspend fun migrateAll(progress: (String) -> Unit, log: (String) -> Unit): Boolean = withContext(Dispatchers.IO) {
        guest.ensureRunning()
        val onSkip = { name: String -> log("読めないので移しませんでした: $name") }
        for (config in legacyContainers()) {
            migrateContainer(config, onSkip) { bytes -> progress("「${config.name}」を移しています（${ContainerManager.formatBytes(bytes)}）") }
            log("「${config.name}」を移しました")
        }
        val driveMoved = hasLegacyDrive()
        if (driveMoved) {
            // コンテナの中身を指すリンクになっているものは、消す前に普通のファイルに戻す
            runCatching { HardLinkGuard.materialize(paths.legacyDriveDir) }
                .onFailure { log("Drive のフォルダのハードリンクを戻せませんでした（${it.message}）") }
            stream(paths.legacyDriveDir, Rclone.GUEST_DRIVE_DIR, onSkip = onSkip) { bytes -> progress("Drive の同期フォルダを移しています（${ContainerManager.formatBytes(bytes)}）") }
            AndroidFileOps.deleteTree(paths.legacyDriveDir)
            log("Drive の同期フォルダを移しました")
        }
        if (paths.legacyRcloneConfig.isFile) {
            rclone.importConfig(paths.legacyRcloneConfig.readText())
            paths.legacyRcloneConfig.delete()
            log("Google のログイン情報を移しました")
        }
        paths.legacyLeftovers.forEach { AndroidFileOps.deleteTree(it) }
        paths.legacyDriveDir.delete()
        driveMoved
    }

    private suspend fun migrateContainer(config: ContainerConfig, onSkip: (String) -> Unit, onProgress: (Long) -> Unit) {
        val rootfs = paths.legacyRootfs(config.id)
        try {
            stream(rootfs, null, config.id, onSkip, onProgress)
            containers.repairLegacy(config)
        } catch (e: Throwable) {
            withContext(NonCancellable) { runCatching { guest.deleteContainer(config.id) } }
            throw e
        }
        AndroidFileOps.deleteTree(rootfs)
        store.refresh()
    }

    /** 端末のフォルダを tar にして、ゲストに展開する */
    private suspend fun stream(
        dir: File,
        guestPath: String?,
        container: String? = null,
        onSkip: (String) -> Unit,
        onProgress: (Long) -> Unit,
    ) {
        val hostRoot = dir.canonicalPath
        val input = PipedInputStream(1 shl 20)
        val output = PipedOutputStream(input)
        var failure: Throwable? = null
        thread(name = "LegacyTar", isDaemon = true) {
            try {
                output.use { writeTar(dir, hostRoot, CountingOutputStream(it, 16L shl 20, onProgress), onSkip) }
            } catch (e: Throwable) {
                failure = e
                runCatching { output.close() }
            }
        }
        guest.untar(container, input, guestPath ?: "/")
        failure?.let { throw IOException("読み込めないファイルがありました: ${it.message}", it) }
    }

    private fun writeTar(dir: File, hostRoot: String, output: OutputStream, onSkip: (String) -> Unit) {
        TarArchiveOutputStream(BufferedOutputStream(output, 1 shl 16)).use { tar ->
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
            walk(tar, dir, "", hostRoot, onSkip)
            tar.finish()
        }
    }

    private fun walk(tar: TarArchiveOutputStream, dir: File, prefix: String, hostRoot: String, onSkip: (String) -> Unit) {
        val children = dir.listFiles() ?: return
        for (child in children.sortedBy { it.name }) {
            val name = prefix + child.name
            val stat = runCatching { Os.lstat(child.path) }.getOrNull() ?: continue
            val mode = stat.st_mode and "7777".toInt(8)
            when {
                OsConstants.S_ISLNK(stat.st_mode) -> {
                    val entry = TarArchiveEntry(name, TarConstants.LF_SYMLINK)
                    entry.linkName = toGuestLink(hostRoot, Os.readlink(child.path))
                    tar.putArchiveEntry(entry)
                    tar.closeArchiveEntry()
                }
                OsConstants.S_ISDIR(stat.st_mode) -> {
                    // 権限が 000 のフォルダは proot が bind の受け口に作ったもの（/mnt/drive など）
                    val dirMode = if (mode == 0) "755".toInt(8) else mode
                    tar.putArchiveEntry(TarArchiveEntry("$name/").apply {
                        this.mode = "40000".toInt(8) or dirMode
                        setModTime(stat.st_mtime * 1000)
                    })
                    tar.closeArchiveEntry()
                    // 中身は端末のものを見せていただけなので移さない
                    if (name !in SKIP_CONTENT_DIRS) walk(tar, child, "$name/", hostRoot, onSkip)
                }
                OsConstants.S_ISREG(stat.st_mode) -> {
                    // 権限が 000 の空ファイルも bind の受け口（/usr/local/bin/rclone など）。残すと邪魔になる
                    if (mode == 0 && stat.st_size == 0L) continue
                    val input = runCatching { child.inputStream() }.getOrElse {
                        onSkip(name)
                        null
                    } ?: continue
                    input.use { stream ->
                        tar.putArchiveEntry(TarArchiveEntry(name).apply {
                            size = stat.st_size
                            this.mode = "100000".toInt(8) or mode
                            setModTime(stat.st_mtime * 1000)
                        })
                        stream.copyTo(tar, 1 shl 16)
                        tar.closeArchiveEntry()
                    }
                }
                else -> Unit // ソケットなどは移さない
            }
        }
    }

    companion object {
        private val SKIP_CONTENT_DIRS = setOf("proc", "sys", "dev", "tmp")

        /**
         * proot の --link2symlink はリンクの中身に端末側の絶対パスを書く。この rootfs の中を指すリンクを、
         * コンテナの中のパスにする。
         */
        fun toGuestLink(rootfs: String, target: String): String =
            if (target.startsWith("$rootfs/")) target.removePrefix(rootfs) else target
    }
}
