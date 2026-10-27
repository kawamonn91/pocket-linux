package com.ttech.pocketlinux.engine

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * イメージのレイヤー（tar / tar.gz）を rootfs に重ねて展開する。
 *
 * - OCI の whiteout（`.wh.名前` で削除、`.wh..wh..opq` で下のレイヤーの中身を隠す）を反映する
 * - Android ではハードリンクを作れないため、リンク先の内容をコピーする
 * - デバイスファイルは作れないので飛ばす（/dev は実行時に端末のものを見せる）
 * - 所有者は記録しない（proot が root の所有に見せる）。setuid などの権限ビットは残す
 *   （sudo が動くのに必要）。自分が読み書きできなくならないよう、所有者の権限は常に付ける
 */
class LayerExtractor(private val ops: FileOps) {

    data class Result(val files: Int, val skipped: Int)

    /**
     * @param onProgress (読み込んだ圧縮後のバイト数, ファイルの大きさ)
     */
    fun extract(archive: File, gzip: Boolean, root: File, onProgress: (Long, Long) -> Unit = { _, _ -> }): Result {
        root.mkdirs()
        val total = archive.length()
        FileInputStream(archive).use { fileIn ->
            val counting = CountingInputStream(BufferedInputStream(fileIn, 1 shl 16)) { read -> onProgress(read, total) }
            val decompressed: InputStream = if (gzip) GZIPInputStream(counting, 1 shl 16) else counting
            TarArchiveInputStream(decompressed).use { tar -> return extract(tar, root) }
        }
    }

    fun extract(tar: TarArchiveInputStream, root: File): Result {
        val createdInLayer = HashSet<String>()
        val deferredLinks = ArrayList<Triple<File, String, Int>>()
        var files = 0
        var skipped = 0

        while (true) {
            val entry: TarArchiveEntry = tar.nextEntry ?: break
            val name = RootfsPath.normalizeEntryName(entry.name)
            if (name.isNullOrEmpty()) {
                if (name == null) skipped++
                continue
            }
            val parentPath = name.substringBeforeLast('/', "")
            val baseName = name.substringAfterLast('/')

            // whiteout
            if (baseName == OPAQUE_WHITEOUT) {
                val dir = RootfsPath.resolve(root, parentPath, ops, followLast = true)
                dir.listFiles()?.forEach { child ->
                    if (child.path !in createdInLayer) ops.deleteTree(child)
                }
                continue
            }
            if (baseName.startsWith(WHITEOUT_PREFIX)) {
                val dir = RootfsPath.resolve(root, parentPath, ops, followLast = true)
                val victim = File(dir, baseName.removePrefix(WHITEOUT_PREFIX))
                if (ops.existsNoFollow(victim)) ops.deleteTree(victim)
                continue
            }

            val parent = RootfsPath.resolve(root, parentPath, ops, followLast = true)
            if (!parent.isDirectory) {
                if (ops.existsNoFollow(parent)) ops.deleteTree(parent)
                parent.mkdirs()
            }
            val target = File(parent, baseName)
            val mode = entry.mode and 0x0FFF

            try {
                when {
                    entry.isDirectory -> {
                        if (ops.isSymlink(target) || (target.exists() && !target.isDirectory)) ops.deleteTree(target)
                        target.mkdirs()
                        ops.chmod(target, mode or 0b111_000_000)
                    }
                    entry.isSymbolicLink -> {
                        replace(target)
                        ops.symlink(entry.linkName, target)
                    }
                    entry.isLink -> {
                        val source = RootfsPath.normalizeEntryName(entry.linkName)
                        if (source == null) {
                            skipped++
                        } else if (!copyHardLink(root, source, target, mode)) {
                            deferredLinks += Triple(target, source, mode)
                        }
                    }
                    entry.isFile -> {
                        replace(target)
                        target.outputStream().use { out -> tar.copyTo(out, 1 shl 16) }
                        ops.chmod(target, mode or 0b110_000_000)
                        entry.lastModifiedDate?.time?.let { target.setLastModified(it) }
                    }
                    entry.isFIFO -> {
                        replace(target)
                        runCatching { ops.mkfifo(target, mode or 0b110_000_000) }.onFailure { skipped++ }
                    }
                    else -> skipped++ // キャラクタ/ブロックデバイスなど
                }
                createdInLayer += target.path
                files++
            } catch (e: IOException) {
                throw IOException("展開できません: $name (${e.message})", e)
            } catch (e: Exception) {
                // Os の失敗（ErrnoException）など。1ファイルの失敗でコンテナ全体を諦めない
                skipped++
            }
        }

        for ((target, source, mode) in deferredLinks) {
            if (!copyHardLink(root, source, target, mode)) skipped++
        }
        return Result(files, skipped)
    }

    /** 既にある同名のものを、新しいエントリで置き換えられるように消す（フォルダは中身ごと） */
    private fun replace(target: File) {
        if (ops.existsNoFollow(target)) ops.deleteTree(target)
    }

    private fun copyHardLink(root: File, sourcePath: String, target: File, mode: Int): Boolean {
        val source = RootfsPath.resolve(root, sourcePath, ops, followLast = false)
        if (ops.isSymlink(source)) {
            replace(target)
            ops.symlink(ops.readlink(source), target)
            return true
        }
        if (!source.isFile) return false
        if (source.canonicalPath == target.canonicalPath) return true
        replace(target)
        source.copyTo(target, overwrite = true)
        // ハードリンクのエントリも元のファイルと同じ mode を持っている
        ops.chmod(target, mode or 0b110_000_000)
        target.setLastModified(source.lastModified())
        return true
    }

    private class CountingInputStream(input: InputStream, private val onRead: (Long) -> Unit) : FilterInputStream(input) {
        private var count = 0L
        private var lastReport = 0L

        override fun read(): Int {
            val b = super.read()
            if (b >= 0) advance(1)
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) advance(n.toLong())
            return n
        }

        private fun advance(n: Long) {
            count += n
            if (count - lastReport >= 512 * 1024) {
                lastReport = count
                onRead(count)
            }
        }
    }

    companion object {
        const val WHITEOUT_PREFIX = ".wh."
        const val OPAQUE_WHITEOUT = ".wh..wh..opq"
    }
}
