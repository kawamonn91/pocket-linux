package com.ttech.pocketlinux.engine

import android.system.Os
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * コンテナを tar.gz に書き出す・戻す。
 *
 * 中身は rootfs そのもの（`docker import` などでも使える）で、Pocket Linux の設定は
 * 先頭の `.pocket-linux-container.json` に入れる。
 * ハードリンクの代わりのリンクは、書き出すときにコンテナ内のパスにし、復元するときに戻す（[L2sLinks]）。
 */
class BackupManager(private val paths: Paths, private val store: ContainerStore) {

    suspend fun export(id: String, output: OutputStream, onProgress: (Int) -> Unit) = withContext(Dispatchers.IO) {
        val config = store.load(id) ?: throw ContainerException("コンテナが見つかりません")
        val rootfs = paths.rootfs(id)
        // リンクの中身は proot が使う実体のパス（/data/data/…）で書かれている（ProotLauncher を参照）
        val hostRoot = rootfs.canonicalPath
        var count = 0
        TarArchiveOutputStream(GZIPOutputStream(BufferedOutputStream(output, 1 shl 16), 1 shl 16)).use { tar ->
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)

            val json = store.encode(config).toByteArray()
            tar.putArchiveEntry(TarArchiveEntry(CONFIG_ENTRY).apply { size = json.size.toLong(); mode = "100644".toInt(8) })
            tar.write(json)
            tar.closeArchiveEntry()

            fun walk(dir: File, prefix: String) {
                val children = dir.listFiles() ?: return
                for (child in children.sortedBy { it.name }) {
                    val name = prefix + child.name
                    val stat = runCatching { Os.lstat(child.path) }.getOrNull() ?: continue
                    val mode = stat.st_mode and "7777".toInt(8)
                    when {
                        android.system.OsConstants.S_ISLNK(stat.st_mode) -> {
                            val entry = TarArchiveEntry(name, TarConstants.LF_SYMLINK)
                            entry.linkName = L2sLinks.toGuest(hostRoot, Os.readlink(child.path))
                            tar.putArchiveEntry(entry)
                            tar.closeArchiveEntry()
                        }
                        android.system.OsConstants.S_ISDIR(stat.st_mode) -> {
                            tar.putArchiveEntry(TarArchiveEntry("$name/").apply {
                                this.mode = "40000".toInt(8) or mode
                                setModTime(stat.st_mtime * 1000)
                            })
                            tar.closeArchiveEntry()
                            // 中身は端末のものを見せているだけなので書き出さない
                            if (name !in SKIP_CONTENT_DIRS) walk(child, "$name/")
                        }
                        android.system.OsConstants.S_ISREG(stat.st_mode) -> {
                            val entry = TarArchiveEntry(name).apply {
                                size = stat.st_size
                                this.mode = "100000".toInt(8) or mode
                                setModTime(stat.st_mtime * 1000)
                            }
                            tar.putArchiveEntry(entry)
                            child.inputStream().use { it.copyTo(tar, 1 shl 16) }
                            tar.closeArchiveEntry()
                        }
                        else -> Unit // ソケットなどは書き出さない
                    }
                    if (++count % 500 == 0) onProgress(count)
                }
            }
            walk(rootfs, "")
            tar.finish()
        }
        onProgress(count)
    }

    /**
     * バックアップから新しいコンテナを作る。Pocket Linux 以外で作った rootfs の tar.gz も読める
     * （その場合は root でログインするコンテナになり、初期設定をやり直せる）。
     */
    suspend fun import(input: InputStream, fallbackName: String, onProgress: (Long) -> Unit): ContainerConfig =
        withContext(Dispatchers.IO) {
            val id = store.newId()
            val rootfs = paths.rootfs(id)
            try {
                val counting = CountingInputStream(BufferedInputStream(input, 1 shl 16), 4L shl 20, onProgress)
                val hostRoot = rootfs.apply { mkdirs() }.canonicalPath
                TarArchiveInputStream(GZIPInputStream(counting, 1 shl 16)).use { tar ->
                    LayerExtractor(AndroidFileOps).extract(tar, rootfs) { L2sLinks.toHost(hostRoot, it) }
                }
                val configFile = File(rootfs, CONFIG_ENTRY)
                val restored = configFile.takeIf { it.isFile }?.let { runCatching { store.decode(it.readText()) }.getOrNull() }
                configFile.delete()

                val config = restored?.copy(id = id, name = restored.name + "（復元）", createdAt = System.currentTimeMillis())
                    ?: ContainerConfig(
                        id = id,
                        name = fallbackName,
                        image = "（読み込んだ rootfs）",
                        createdAt = System.currentTimeMillis(),
                        user = null,
                        home = "/root",
                    )
                store.save(config)
                config
            } catch (e: Throwable) {
                runCatching { store.delete(id) }
                throw e
            }
        }

    companion object {
        const val CONFIG_ENTRY = ".pocket-linux-container.json"
        private val SKIP_CONTENT_DIRS = setOf("proc", "sys", "dev", "tmp")
    }
}
