package com.ttech.pocketlinux.engine

import com.ttech.pocketlinux.vm.Guest
import com.ttech.pocketlinux.vm.GuestCommands
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * コンテナを tar.gz に書き出す・戻す。
 *
 * 中身は rootfs そのもの（`docker import` などでも使える）で、Pocket Linux の設定は
 * ルートの `.pocket-linux-container.json` に入れる。tar はゲストが作り、gzip は端末側で行う。
 */
class BackupManager(private val store: ContainerStore, private val guest: Guest, private val containers: ContainerManager) {

    suspend fun export(id: String, output: OutputStream, onProgress: (Long) -> Unit) = withContext(Dispatchers.IO) {
        val config = store.load(id) ?: throw ContainerException("コンテナが見つかりません")
        guest.writeFile(id, "/$CONFIG_ENTRY", store.encode(config))
        try {
            GZIPOutputStream(BufferedOutputStream(output, 1 shl 16), 1 shl 16).use { gzip ->
                guest.tar(id, "/", SKIP_CONTENT_DIRS, CountingOutputStream(gzip, 16L shl 20, onProgress))
            }
        } finally {
            withContext(NonCancellable) { runCatching { guest.remove(id, "/$CONFIG_ENTRY") } }
        }
    }

    /**
     * バックアップから新しいコンテナを作る。Pocket Linux 以外で作った rootfs の tar.gz も読める
     * （その場合は root でログインするコンテナになり、初期設定をやり直せる）。
     * 1.x で書き出したものは所有者がすべて root なので、持ち主を直す。
     */
    suspend fun import(input: InputStream, fallbackName: String, onProgress: (Long) -> Unit): ContainerConfig =
        withContext(Dispatchers.IO) {
            val id = store.newId()
            try {
                val counting = CountingInputStream(BufferedInputStream(input, 1 shl 16), 4L shl 20, onProgress)
                guest.untar(id, GZIPInputStream(counting, 1 shl 16))
                val restored = guest.readFile(id, "/$CONFIG_ENTRY")?.let { runCatching { store.decode(it) }.getOrNull() }
                runCatching { guest.remove(id, "/$CONFIG_ENTRY") }

                val config = restored?.copy(id = id, name = restored.name + "（復元）", createdAt = System.currentTimeMillis())
                    ?: ContainerConfig(
                        id = id,
                        name = fallbackName,
                        image = "（読み込んだ rootfs）",
                        createdAt = System.currentTimeMillis(),
                        user = null,
                        home = "/root",
                    )
                if (restored != null && isFromLegacy(config)) containers.repairLegacy(config)
                store.save(config)
                config
            } catch (e: Throwable) {
                runCatching { store.delete(id) }
                withContext(NonCancellable) { runCatching { guest.deleteContainer(id) } }
                throw e
            }
        }

    /** 1.x のコンテナにはハードリンクの代わりのフォルダ（.l2s）がある */
    private suspend fun isFromLegacy(config: ContainerConfig): Boolean =
        guest.capture(GuestCommands.rootScript(config, "test -d /.l2s")).exit == 0

    companion object {
        const val CONFIG_ENTRY = ".pocket-linux-container.json"

        /** フォルダ自体は残して中身を入れない（中身は VM が見せているだけ） */
        val SKIP_CONTENT_DIRS = listOf("/proc", "/sys", "/dev", "/tmp", "/run")
    }
}
